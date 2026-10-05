package main

import (
	"context"
	"fmt"
	"net/http"
	"slices"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
	"github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/relay"
	ma "github.com/multiformats/go-multiaddr"

	p2pnode "frad.local/p2p-go/node"
)

const (
	// gossipFanout is how many nodes one gossip round checks. Small and
	// frequent rather than all at once: the network converges within a few
	// rounds either way, and no node gets a burst of dials from everyone.
	gossipFanout = 8
	// nodeCheckTimeout bounds connecting to one node plus the list exchange.
	nodeCheckTimeout = 20 * time.Second
	// federationTag protects connections to -peer nodes from the connection
	// manager's trimming, so federation doesn't silently erode under load.
	federationTag = "frad-federation"
)

type config struct {
	listenAddrs  []string
	keyFile      string
	nodesFile    string
	officialURLs []string
	federation   []peer.AddrInfo
	announce     []ma.Multiaddr
	relayOpts    []relay.Option
	allowPrivate bool
	timing       timing
	logf         func(format string, args ...any)
}

type timing struct {
	firstGossip       time.Duration // after start, once -peer connections had a moment
	gossipInterval    time.Duration
	officialInterval  time.Duration
	reconnectInterval time.Duration
	nodeTTL           time.Duration // known nodes unseen for longer are dropped
}

var defaultTiming = timing{
	firstGossip:       30 * time.Second,
	gossipInterval:    10 * time.Minute,
	officialInterval:  6 * time.Hour,
	reconnectInterval: time.Minute,
	nodeTTL:           7 * 24 * time.Hour,
}

type server struct {
	cfg          config
	h            host.Host
	dht          *dht.IpfsDHT
	store        *nodeStore
	http         *http.Client
	stopTracking context.CancelFunc // ends RecordSelfReportedAddrs
}

func newServer(cfg config, priv crypto.PrivKey) (*server, error) {
	opts := []libp2p.Option{
		libp2p.Identity(priv),
		libp2p.ListenAddrStrings(cfg.listenAddrs...),
		// Runs the circuit-relay v2 SERVICE (this node relays for others),
		// unlike node.Host's client-only libp2p.EnableRelay().
		libp2p.EnableRelayService(cfg.relayOpts...),
		// libp2p only switches the relay service on once AutoNAT has confirmed this node
		// is publicly reachable - which needs enough other peers to dial back, something
		// FRAD's small isolated network may never have. This binary is meant to run on a
		// public address (see the README), so assert that up front instead.
		libp2p.ForceReachabilityPublic(),
		// Node lists are untrusted input; never let one make this node dial into
		// its own LAN or cloud-internal network.
		libp2p.ConnectionGater(p2pnode.PublicDialGater(cfg.allowPrivate)),
	}
	if len(cfg.announce) > 0 {
		// Behind 1:1 NAT (most cloud VMs) the detected addresses are private
		// ones nobody can dial; advertise the operator's public ones instead -
		// in identify, the relay's reservation answers and the node list alike.
		announce := cfg.announce
		opts = append(opts, libp2p.AddrsFactory(func([]ma.Multiaddr) []ma.Multiaddr {
			return slices.Clone(announce)
		}))
	}
	h, err := libp2p.New(opts...)
	if err != nil {
		return nil, fmt.Errorf("host error: %w", err)
	}
	// Before anything connects: what other servers say about themselves is
	// the only source of their addresses this node will vouch for.
	trackCtx, stopTracking := context.WithCancel(context.Background())
	if err := p2pnode.RecordSelfReportedAddrs(trackCtx, h); err != nil {
		stopTracking()
		_ = h.Close()
		return nil, fmt.Errorf("host error: %w", err)
	}

	s := &server{
		cfg:          cfg,
		h:            h,
		store:        newNodeStore(cfg.nodesFile, h.ID(), cfg.timing.nodeTTL, cfg.allowPrivate),
		http:         &http.Client{Timeout: officialListTimeout},
		stopTracking: stopTracking,
	}
	if err := s.store.load(time.Now()); err != nil {
		// A damaged list is no reason to stay down; it refills from the network.
		cfg.logf("ignoring unreadable nodes file %s: %v", cfg.nodesFile, err)
	}
	s.store.addCandidates(cfg.federation)

	s.dht, err = dht.New(h,
		dht.ProtocolPrefix(protocol.ID(p2pnode.ProtocolPrefix)),
		dht.Mode(dht.ModeServer),
		// Re-read whenever the routing table runs low: the -peer nodes plus every
		// server verified since, so this node rejoins the one shared /frad DHT
		// instead of starting an island of its own.
		dht.BootstrapPeersFunc(s.dhtBootstrapPeers),
	)
	if err != nil {
		stopTracking()
		_ = h.Close()
		return nil, fmt.Errorf("dht error: %w", err)
	}
	h.SetStreamHandler(protocol.ID(p2pnode.NodesProtocolID),
		p2pnode.NodeListHandler(s.sharedNodes, s.learned, cfg.allowPrivate))
	return s, nil
}

func (s *server) close() {
	if err := s.store.save(); err != nil {
		s.cfg.logf("saving %s: %v", s.cfg.nodesFile, err)
	}
	s.stopTracking()
	_ = s.dht.Close()
	_ = s.h.Close()
}

// run serves until ctx is cancelled.
func (s *server) run(ctx context.Context) {
	if err := s.dht.Bootstrap(ctx); err != nil {
		s.cfg.logf("dht bootstrap: %v", err)
	}
	for _, p := range s.cfg.federation {
		s.h.ConnManager().Protect(p.ID, federationTag)
	}
	t := s.cfg.timing
	var wg sync.WaitGroup
	wg.Go(func() { every(ctx, 0, t.reconnectInterval, s.reconnectFederation) })
	if len(s.cfg.officialURLs) > 0 {
		wg.Go(func() { every(ctx, 0, t.officialInterval, s.refreshOfficialList) })
	}
	wg.Go(func() { every(ctx, t.firstGossip, t.gossipInterval, s.gossipOnce) })
	wg.Wait()
}

// every runs fn after first, then every interval, until ctx is done.
func every(ctx context.Context, first, interval time.Duration, fn func(context.Context)) {
	timer := time.NewTimer(first)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
		}
		fn(ctx)
		timer.Reset(interval)
	}
}

func (s *server) printReady() {
	fmt.Println("FRAD bootstrap/relay node ready. Share these multiaddrs with users for their bootstrap-node list:")
	for _, addr := range s.h.Addrs() {
		fmt.Printf("  %s/p2p/%s\n", addr, s.h.ID())
	}
	if len(s.ownAddrs()) == 0 {
		fmt.Println("WARNING: none of these addresses is publicly routable, so this node won't list itself")
		fmt.Println("in node lists. Behind NAT (e.g. a cloud VM), pass its public address with -announce.")
	}
}

// reconnectFederation dials every -peer node that isn't connected right now,
// and records each connected one as a sighting - whether this loop or the DHT
// (which bootstraps from the same peers) made the connection.
func (s *server) reconnectFederation(ctx context.Context) {
	var wg sync.WaitGroup
	for _, p := range s.cfg.federation {
		if p.ID == s.h.ID() {
			continue
		}
		wg.Go(func() {
			if s.h.Network().Connectedness(p.ID) != network.Connected {
				cctx, cancel := context.WithTimeout(ctx, nodeCheckTimeout)
				defer cancel()
				if err := s.h.Connect(cctx, p); err != nil {
					s.cfg.logf("federation peer %s unreachable: %v", p.ID, err)
					return
				}
			}
			if !s.recordIfServer(ctx, p.ID) {
				s.cfg.logf("federation peer %s doesn't look like a FRAD bootstrap node", p.ID)
			}
		})
	}
	wg.Wait()
	s.persist()
}

// gossipOnce checks a random handful of known and candidate nodes, swapping
// node lists with each one that turns out to be a FRAD server, then persists.
func (s *server) gossipOnce(ctx context.Context) {
	s.recordConnectedServers(ctx)
	mine := s.sharedNodes()
	var wg sync.WaitGroup
	for _, target := range s.store.pick(gossipFanout) {
		wg.Go(func() { s.checkNode(ctx, target, mine) })
	}
	wg.Wait()
	s.store.prune(time.Now())
	s.persist()
}

// checkNode connects to info and, if identify shows a FRAD server, records it
// as known (at the addresses it reports for itself) and takes what it shares as
// new candidates. Lists from anything else aren't worth hearing.
func (s *server) checkNode(ctx context.Context, info peer.AddrInfo, mine []peer.AddrInfo) {
	ctx, cancel := context.WithTimeout(ctx, nodeCheckTimeout)
	defer cancel()
	if err := s.h.Connect(ctx, info); err != nil || !s.recordIfServer(ctx, info.ID) {
		s.store.markFailed(info.ID)
		return
	}
	theirs, err := p2pnode.ExchangeNodeList(ctx, s.h, info.ID, mine, s.cfg.allowPrivate)
	if err != nil {
		s.cfg.logf("node list from %s: %v", info.ID, err)
		return
	}
	s.store.addCandidates(theirs)
}

// recordIfServer marks a connected peer as a known node if it is a FRAD
// server with at least one address worth listing.
func (s *server) recordIfServer(ctx context.Context, id peer.ID) bool {
	if !p2pnode.IsFradServer(s.h, id) {
		return false
	}
	addrs := p2pnode.AwaitVerifiedNodeAddrs(ctx, s.h, id, s.cfg.allowPrivate)
	if len(addrs) == 0 {
		return false
	}
	s.store.markAlive(id, addrs, time.Now())
	return true
}

// recordConnectedServers counts every live connection to a FRAD server as a
// sighting. The DHT keeps connections to plenty of other servers - including
// ones nobody has gossiped about yet - so this is a second, independent way
// for servers to find each other, and keeps busy servers listed without
// needing a gossip pick.
func (s *server) recordConnectedServers(ctx context.Context) {
	for _, p := range s.h.Network().Peers() {
		s.recordIfServer(ctx, p)
	}
}

func (s *server) persist() {
	if err := s.store.save(); err != nil {
		s.cfg.logf("saving %s: %v", s.cfg.nodesFile, err)
	}
}

// sharedNodes is what this node answers the node-list protocol with: its own
// addresses first, then the servers it has verified, freshest first.
func (s *server) sharedNodes() []peer.AddrInfo {
	own := []peer.AddrInfo{{ID: s.h.ID(), Addrs: s.ownAddrs()}}
	return p2pnode.MergeNodeLists(p2pnode.MaxNodeListEntries, own, s.store.alive())
}

// ownAddrs are this node's addresses that are worth listing (publicly routable
// unless -allow-private-addrs).
func (s *server) ownAddrs() []ma.Multiaddr {
	var addrs []ma.Multiaddr
	for _, addr := range s.h.Addrs() {
		if p2pnode.CheckNodeAddr(addr, s.cfg.allowPrivate) == nil {
			addrs = append(addrs, addr)
		}
	}
	return addrs
}

// learned takes a list pushed by whoever opened a node-list stream - an app or
// another server - as candidates; they still have to pass a check before this
// node vouches for them.
func (s *server) learned(_ peer.ID, infos []peer.AddrInfo) {
	s.store.addCandidates(infos)
}

func (s *server) dhtBootstrapPeers() []peer.AddrInfo {
	return append(slices.Clone(s.cfg.federation), s.store.alive()...)
}
