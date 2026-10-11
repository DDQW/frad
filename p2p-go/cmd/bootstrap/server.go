package main

import (
	"context"
	"errors"
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
	// federationRecheck is how long a -peer node's last verification stands.
	// The reconnect loop runs every minute, but sightings are only persisted
	// hourly (lastSeenPersistGranularity), so dialing back every one of its
	// addresses each time would be wasted on both ends.
	federationRecheck = lastSeenPersistGranularity
)

// Why recordIfServer didn't vouch for a peer.
var (
	errNotServer   = errors.New("doesn't run FRAD's DHT in server mode")
	errNoAddrs     = errors.New("can't be reached at any listable address")
	errNetworkFull = errors.New("its network already has its share of known servers")
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
	s.store.addCandidates(fromCurated, "-peer", cfg.federation)

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
		if p.ID != s.h.ID() {
			wg.Go(func() { s.checkFederationPeer(ctx, p) })
		}
	}
	wg.Wait()
	s.persist()
}

func (s *server) checkFederationPeer(ctx context.Context, p peer.AddrInfo) {
	ctx, cancel := context.WithTimeout(ctx, nodeCheckTimeout)
	defer cancel()
	if s.h.Network().Connectedness(p.ID) != network.Connected {
		if err := s.h.Connect(ctx, p); err != nil {
			s.cfg.logf("federation peer %s unreachable: %v", p.ID, err)
			return
		}
	}
	if s.store.seenSince(p.ID, time.Now().Add(-federationRecheck)) {
		return
	}
	if _, err := s.recordIfServer(ctx, p.ID); err != nil {
		s.cfg.logf("federation peer %s isn't listed: %v", p.ID, err)
	}
}

// gossipOnce checks a random handful of known and candidate nodes, swapping
// node lists with each one that turns out to be a FRAD server, then persists.
func (s *server) gossipOnce(ctx context.Context) {
	s.queueConnectedServers()
	mine := s.sharedNodes()
	var wg sync.WaitGroup
	for _, target := range s.store.pick(gossipFanout) {
		wg.Go(func() { s.checkNode(ctx, target, mine) })
	}
	wg.Wait()
	s.store.prune(time.Now())
	s.persist()
}

// checkNode connects to info and, if it is a FRAD server, records it as known
// (at the addresses it has been proven reachable at) and takes what it shares
// as new candidates. Lists from anything else aren't worth hearing.
func (s *server) checkNode(ctx context.Context, info peer.AddrInfo, mine []peer.AddrInfo) {
	ctx, cancel := context.WithTimeout(ctx, nodeCheckTimeout)
	defer cancel()
	if err := s.h.Connect(ctx, info); err != nil {
		s.store.markFailed(info.ID)
		return
	}
	addrs, err := s.recordIfServer(ctx, info.ID)
	if err != nil {
		s.store.markFailed(info.ID)
		return
	}
	theirs, err := p2pnode.ExchangeNodeList(ctx, s.h, info.ID, mine, s.cfg.allowPrivate)
	if err != nil {
		s.cfg.logf("node list from %s: %v", info.ID, err)
		return
	}
	s.store.addCandidates(fromServers, p2pnode.NetworkKey(addrs[0]), theirs)
}

// recordIfServer marks a connected peer as a known node if it is a FRAD
// server, at the addresses it has been proven reachable at
// (p2pnode.VerifyNodeAddrs) and the diversity cap admits, and returns those.
func (s *server) recordIfServer(ctx context.Context, id peer.ID) ([]ma.Multiaddr, error) {
	// Connect returns at once for a peer already connected - e.g. through the
	// DHT - possibly before identify has told us what it runs.
	p2pnode.AwaitSelfReport(ctx, s.h, id)
	if !p2pnode.IsFradServer(s.h, id) {
		return nil, errNotServer
	}
	verified := p2pnode.VerifyNodeAddrs(ctx, s.h, id, s.cfg.allowPrivate)
	if len(verified) == 0 {
		return nil, errNoAddrs
	}
	addrs := s.store.markAlive(id, verified, time.Now())
	if len(addrs) == 0 {
		return nil, errNetworkFull
	}
	return addrs, nil
}

// queueConnectedServers queues every connected FRAD server this node doesn't
// know yet as a candidate. The DHT keeps connections to plenty of other
// servers - including ones nobody has gossiped about yet - so this is a
// second, independent way for servers to find each other. A connection alone
// proves nothing about where a server can be reached, though, so they get the
// same check as any candidate, starting from the addresses they report for
// themselves. And a server is only queued with those found through other
// servers (fromServers) if this node dialed it itself; one that only ever
// dialed in is just someone talking to this node, from anywhere, and queues
// with the lists anyone can push (fromPushed) - otherwise connecting in from a
// handful of networks would be enough to crowd verified servers' candidates
// out.
func (s *server) queueConnectedServers() {
	type queue struct {
		src candidateSource
		via string
	}
	queued := make(map[queue][]peer.AddrInfo)
	for _, p := range s.h.Network().Peers() {
		if s.store.isKnown(p) || !p2pnode.IsFradServer(s.h, p) {
			continue
		}
		addrs := p2pnode.SelfReportedNodeAddrs(s.h, p, s.cfg.allowPrivate)
		conns := s.h.Network().ConnsToPeer(p)
		if len(addrs) == 0 || len(conns) == 0 {
			continue
		}
		q := queue{src: fromPushed, via: p2pnode.NetworkKey(conns[0].RemoteMultiaddr())}
		for _, c := range conns {
			if c.Stat().Direction == network.DirOutbound {
				q = queue{src: fromServers, via: p2pnode.NetworkKey(c.RemoteMultiaddr())}
				break
			}
		}
		queued[q] = append(queued[q], peer.AddrInfo{ID: p, Addrs: addrs})
	}
	for q, infos := range queued {
		s.store.addCandidates(q.src, q.via, infos)
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
// node vouches for them. Lists from servers already verified rank above
// anybody's (see candidateSource).
func (s *server) learned(from network.Conn, infos []peer.AddrInfo) {
	src := fromPushed
	if s.store.isKnown(from.RemotePeer()) {
		src = fromServers
	}
	s.store.addCandidates(src, p2pnode.NetworkKey(from.RemoteMultiaddr()), infos)
}

func (s *server) dhtBootstrapPeers() []peer.AddrInfo {
	return append(slices.Clone(s.cfg.federation), s.store.alive()...)
}
