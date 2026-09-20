package node

import (
	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/peer"
)

// relayOptions configures this host to transparently fall back to a relayed
// connection (via libp2p's circuit-relay v2) through one of relays whenever a
// direct dial fails, and to attempt NAT hole-punching first. This is the only
// place relay-vs-direct is decided — Host.OpenStream/OpenStream callers never
// branch on it; the Swarm's own dialer does.
//
// relays is this app's user-configurable bootstrap/relay node list (see
// Config.BootstrapPeers) — with none configured, this host can still make
// direct connections but has no relay fallback and, more fundamentally, no
// way to bootstrap its DHT routing table in the first place (see
// ProtocolPrefix's doc comment).
func relayOptions(relays []peer.AddrInfo) []libp2p.Option {
	opts := []libp2p.Option{
		libp2p.EnableRelay(),
		libp2p.NATPortMap(),
		libp2p.EnableHolePunching(),
	}
	if len(relays) > 0 {
		opts = append(opts, libp2p.EnableAutoRelayWithStaticRelays(relays))
	}
	return opts
}
