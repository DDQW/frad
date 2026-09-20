// Package node exposes a minimal wide-range (go-libp2p) peer-discovery and
// byte-stream API, built as an Android .aar via `gomobile bind`. Every
// exported type/method here is restricted to what gomobile bind supports:
// bool, numeric types, string, []byte, and exported struct/interface types
// built from those — no generics, channels, maps, or []string may cross the
// binding boundary, so async results go through the callback interfaces in
// callbacks.go and bootstrap/relay lists cross as newline-delimited strings.
package node

import (
	"bytes"
	"context"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/peerstore"
	"github.com/libp2p/go-libp2p/core/protocol"
	drouting "github.com/libp2p/go-libp2p/p2p/discovery/routing"
	"github.com/multiformats/go-multiaddr"
)

// ProtocolPrefix scopes this app's Kademlia DHT to its own isolated namespace
// (protocol id becomes "/frad/kad/1.0.0"), so it never joins or pollutes the
// public IPFS DHT. Consequence: this DHT has zero peers until something
// connects to a FRAD-specific bootstrap node — see cmd/bootstrap.
const ProtocolPrefix = "/frad"

const (
	ChatProtocolID     = "/frad/chat/1.0.0"
	TransferProtocolID = "/frad/transfer/1.0.0"
)

// advertiseRetryInterval is used when a DHT Advertise call fails outright
// (e.g. no peers yet), instead of trusting its (meaningless in that case) TTL.
const advertiseRetryInterval = 30 * time.Second

// Config is populated by Kotlin before calling NewHost.
type Config struct {
	// ListenPort; 0 lets the OS assign an ephemeral port.
	ListenPort int
	// IdentitySeed is exactly 32 bytes, used to deterministically derive this
	// host's libp2p Ed25519 identity for the lifetime of one browsing session.
	// It is deliberately NOT derived from the app's long-term chat identity —
	// see WideRangeNode.kt for why (rotating discovery id vs. stable chat id,
	// same separation BLE's rotating session id already gives M1).
	IdentitySeed []byte
	// BootstrapPeers is a newline-delimited list of multiaddrs, each including
	// a trailing "/p2p/<peerid>" — the user-configurable bootstrap/relay node
	// list (see README "Downloads"/"Project status" for why this can't ship
	// with real defaults baked in).
	BootstrapPeers string
}

// Host wraps a libp2p host + a Kademlia DHT scoped to ProtocolPrefix. Direct
// vs. relayed dialing is never special-cased here: the underlying libp2p Swarm
// dials whatever addresses (including relay circuit addresses) the DHT lookup
// populated into the Peerstore, using the AutoRelay/hole-punching behavior
// configured once in relayOptions (internal_relay.go).
type Host struct {
	ctx    context.Context
	cancel context.CancelFunc

	h   host.Host
	dht *dht.IpfsDHT

	peersListener   PeerFoundListener
	streamsListener IncomingStreamListener

	mu             sync.Mutex
	pendingStreams map[string]network.Stream
	nextHandle     int64
}

// NewHost constructs (but does not yet bootstrap) a wide-range libp2p host.
// Call Start to begin DHT bootstrap.
func NewHost(cfg *Config, peers PeerFoundListener, streams IncomingStreamListener) (*Host, error) {
	if len(cfg.IdentitySeed) != 32 {
		return nil, fmt.Errorf("IdentitySeed must be exactly 32 bytes, got %d", len(cfg.IdentitySeed))
	}
	priv, _, err := crypto.GenerateEd25519Key(bytes.NewReader(cfg.IdentitySeed))
	if err != nil {
		return nil, fmt.Errorf("generating libp2p identity: %w", err)
	}

	relays, err := parseAddrInfos(cfg.BootstrapPeers)
	if err != nil {
		return nil, err
	}

	listenAddrs := []string{
		fmt.Sprintf("/ip4/0.0.0.0/tcp/%d", cfg.ListenPort),
		fmt.Sprintf("/ip4/0.0.0.0/udp/%d/quic-v1", cfg.ListenPort),
	}

	opts := append([]libp2p.Option{
		libp2p.Identity(priv),
		libp2p.ListenAddrStrings(listenAddrs...),
	}, relayOptions(relays)...)

	h, err := libp2p.New(opts...)
	if err != nil {
		return nil, fmt.Errorf("creating libp2p host: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	kadDHT, err := dht.New(h, dht.ProtocolPrefix(protocol.ID(ProtocolPrefix)), dht.Mode(dht.ModeAuto))
	if err != nil {
		_ = h.Close()
		cancel()
		return nil, fmt.Errorf("creating DHT: %w", err)
	}

	result := &Host{
		ctx: ctx, cancel: cancel,
		h: h, dht: kadDHT,
		peersListener: peers, streamsListener: streams,
		pendingStreams: make(map[string]network.Stream),
	}

	h.SetStreamHandler(protocol.ID(ChatProtocolID), result.handleIncomingStream(ChatProtocolID))
	h.SetStreamHandler(protocol.ID(TransferProtocolID), result.handleIncomingStream(TransferProtocolID))

	// Eager connect to the configured bootstrap/relay peers so DHT bootstrap
	// below has a head start; dialing also happens lazily on demand, so a
	// failure here is not fatal.
	for _, ai := range relays {
		_ = h.Connect(ctx, ai)
	}

	return result, nil
}

// Start begins Kademlia DHT bootstrap/maintenance. Safe to call once.
func (n *Host) Start() error {
	return n.dht.Bootstrap(n.ctx)
}

// Stop tears down the DHT and libp2p host. The Host is unusable afterwards.
func (n *Host) Stop() error {
	n.cancel()
	if n.dht != nil {
		_ = n.dht.Close()
	}
	return n.h.Close()
}

func (n *Host) LocalPeerId() string {
	return n.h.ID().String()
}

// StartAdvertising periodically re-Provides rendezvousTopic on the DHT for as
// long as the Host is running, so other peers' FindPeersOnce calls can find
// this one. Errors are reported via PeerFoundListener.OnDiscoveryError rather
// than returned, since advertising itself runs on a background loop.
func (n *Host) StartAdvertising(rendezvousTopic string) error {
	discovery := drouting.NewRoutingDiscovery(n.dht)
	go func() {
		for {
			ttl, err := discovery.Advertise(n.ctx, rendezvousTopic)
			if err != nil {
				if n.peersListener != nil {
					n.peersListener.OnDiscoveryError(err.Error())
				}
				ttl = advertiseRetryInterval
			}
			select {
			case <-n.ctx.Done():
				return
			case <-time.After(ttl):
			}
		}
	}()
	return nil
}

// FindPeersOnce runs one DHT lookup round for rendezvousTopic; each peer found
// (excluding this host itself) is reported asynchronously via
// PeerFoundListener.OnPeerFound, possibly zero to many times, until the lookup
// is exhausted.
func (n *Host) FindPeersOnce(rendezvousTopic string) error {
	discovery := drouting.NewRoutingDiscovery(n.dht)
	peerChan, err := discovery.FindPeers(n.ctx, rendezvousTopic)
	if err != nil {
		if n.peersListener != nil {
			n.peersListener.OnDiscoveryError(err.Error())
		}
		return err
	}
	go func() {
		for p := range peerChan {
			if p.ID == n.h.ID() {
				continue
			}
			n.h.Peerstore().AddAddrs(p.ID, p.Addrs, peerstore.TempAddrTTL)
			if n.peersListener != nil {
				n.peersListener.OnPeerFound(p.ID.String())
			}
		}
	}()
	return nil
}

// OpenStream dials peerId and opens a stream on protocolId. Whether the dial
// ends up direct or relayed is entirely the Swarm's own decision based on the
// addresses already in the Peerstore (see the Host doc comment) — Kotlin
// never needs to know which one happened.
func (n *Host) OpenStream(peerId string, protocolId string) (*Stream, error) {
	pid, err := peer.Decode(peerId)
	if err != nil {
		return nil, fmt.Errorf("invalid peer id %q: %w", peerId, err)
	}
	s, err := n.h.NewStream(n.ctx, pid, protocol.ID(protocolId))
	if err != nil {
		return nil, fmt.Errorf("opening stream to %s: %w", peerId, err)
	}
	return &Stream{s: s}, nil
}

// AcceptStream retrieves a stream previously announced via
// IncomingStreamListener.OnIncomingStream and removes it from the pending set
// — each handle can only be accepted once.
func (n *Host) AcceptStream(streamHandle string) (*Stream, error) {
	n.mu.Lock()
	defer n.mu.Unlock()
	s, ok := n.pendingStreams[streamHandle]
	if !ok {
		return nil, fmt.Errorf("no pending stream for handle %s", streamHandle)
	}
	delete(n.pendingStreams, streamHandle)
	return &Stream{s: s}, nil
}

func (n *Host) handleIncomingStream(protocolId string) network.StreamHandler {
	return func(s network.Stream) {
		n.mu.Lock()
		n.nextHandle++
		handle := fmt.Sprintf("%d", n.nextHandle)
		n.pendingStreams[handle] = s
		n.mu.Unlock()

		if n.streamsListener != nil {
			n.streamsListener.OnIncomingStream(protocolId, s.Conn().RemotePeer().String(), handle)
		}
	}
}

// parseAddrInfos parses a newline-delimited list of "/ip4/.../p2p/<id>"-style
// multiaddrs (blank lines ignored) into peer.AddrInfo, grouping multiple
// addresses for the same peer id together.
func parseAddrInfos(newlineDelimited string) ([]peer.AddrInfo, error) {
	var addrs []multiaddr.Multiaddr
	for _, line := range strings.Split(newlineDelimited, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		ma, err := multiaddr.NewMultiaddr(line)
		if err != nil {
			return nil, fmt.Errorf("invalid bootstrap multiaddr %q: %w", line, err)
		}
		addrs = append(addrs, ma)
	}
	if len(addrs) == 0 {
		return nil, nil
	}
	infos, err := peer.AddrInfosFromP2pAddrs(addrs...)
	if err != nil {
		return nil, fmt.Errorf("parsing bootstrap peer addrs (each needs a trailing /p2p/<peerid>): %w", err)
	}
	return infos, nil
}
