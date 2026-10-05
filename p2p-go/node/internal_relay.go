package node

import (
	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/control"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/multiformats/go-multiaddr"
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

// relayOnlyOptions configures a host (see Config.RelayOnly) whose IP address only
// the configured relays ever learn: it listens on nothing, never hole-punches or
// maps ports, holds a reservation on the relays so others can reach it through a
// circuit, and relayOnlyGater refuses every direct dial to anyone but those relays.
// Its DHT runs in client mode (reachability is forced private), so it serves no
// queries either.
func relayOnlyOptions(relays []peer.AddrInfo) []libp2p.Option {
	allowed := make(map[peer.ID]bool, len(relays))
	for _, r := range relays {
		allowed[r.ID] = true
	}
	return []libp2p.Option{
		libp2p.NoListenAddrs,
		libp2p.EnableRelay(),
		libp2p.ForceReachabilityPrivate(),
		libp2p.EnableAutoRelayWithStaticRelays(relays),
		libp2p.ConnectionGater(relayOnlyGater{relays: allowed}),
	}
}

// relayOnlyGater lets a relay-only host connect directly to its relays and to
// everyone else only through a relay circuit.
type relayOnlyGater struct {
	relays map[peer.ID]bool
}

func isCircuit(addr multiaddr.Multiaddr) bool {
	_, err := addr.ValueForProtocol(multiaddr.P_CIRCUIT)
	return err == nil
}

func (g relayOnlyGater) InterceptPeerDial(peer.ID) bool { return true }

func (g relayOnlyGater) InterceptAddrDial(p peer.ID, addr multiaddr.Multiaddr) bool {
	return g.relays[p] || isCircuit(addr)
}

func (g relayOnlyGater) InterceptAccept(addrs network.ConnMultiaddrs) bool {
	return isCircuit(addrs.RemoteMultiaddr())
}

func (g relayOnlyGater) InterceptSecured(_ network.Direction, p peer.ID, addrs network.ConnMultiaddrs) bool {
	return g.relays[p] || isCircuit(addrs.RemoteMultiaddr())
}

func (g relayOnlyGater) InterceptUpgraded(network.Conn) (bool, control.DisconnectReason) {
	return true, 0
}
