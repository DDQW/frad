package node

import (
	"context"
	"slices"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/peerstore"
	"github.com/libp2p/go-libp2p/core/protocol"
	"github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/client"
	"github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/proto"
	"github.com/multiformats/go-multiaddr"
)

// TestOpenStreamOverRelayedConnection proves a chat can start when the only path
// between two peers is a circuit-relay v2 hop (both behind NAT, hole punching
// failed): B knows A solely by a relay circuit address, so the connection it gets
// is "limited", which libp2p refuses to open streams on unless OpenStream opts in.
func TestOpenStreamOverRelayedConnection(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Same shape as cmd/bootstrap: relay service with libp2p's default (limited) resources.
	relayHost, err := libp2p.New(
		libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"),
		libp2p.EnableRelayService(),
		libp2p.ForceReachabilityPublic(),
	)
	if err != nil {
		t.Fatal(err)
	}
	defer relayHost.Close()
	relayInfo := peer.AddrInfo{ID: relayHost.ID(), Addrs: relayHost.Addrs()}

	aStreams := &collectingStreams{}
	a, err := NewHost(&Config{IdentitySeed: seed()}, &collectingPeers{}, aStreams)
	if err != nil {
		t.Fatal(err)
	}
	defer a.Stop()
	b, err := NewHost(&Config{IdentitySeed: seed()}, &collectingPeers{}, &collectingStreams{})
	if err != nil {
		t.Fatal(err)
	}
	defer b.Stop()

	if err := a.h.Connect(ctx, relayInfo); err != nil {
		t.Fatalf("A connecting to relay: %v", err)
	}
	// The relay service starts asynchronously once the host sees itself as public.
	var reserveErr error
	for i := 0; i < 50; i++ {
		if _, reserveErr = client.Reserve(ctx, a.h, relayInfo); reserveErr == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if reserveErr != nil {
		t.Fatalf("A reserving a relay slot: %v", reserveErr)
	}

	circuit, err := multiaddr.NewMultiaddr("/p2p/" + relayHost.ID().String() + "/p2p-circuit")
	if err != nil {
		t.Fatal(err)
	}
	for _, addr := range relayHost.Addrs() {
		b.h.Peerstore().AddAddr(a.h.ID(), addr.Encapsulate(circuit), peerstore.PermanentAddrTTL)
	}

	stream, err := b.OpenStream(a.h.ID().String(), ChatProtocolID)
	if err != nil {
		t.Fatalf("B opening a stream to A through the relay: %v", err)
	}
	if !stream.s.Conn().Stat().Limited {
		t.Fatal("expected the stream to run over the limited (relayed) connection")
	}
	if _, err := stream.Write([]byte("hello")); err != nil {
		t.Fatalf("B writing: %v", err)
	}

	deadline := time.After(10 * time.Second)
	var handle string
	for {
		if h, ok := aStreams.first(); ok {
			handle = h
			break
		}
		select {
		case <-deadline:
			t.Fatal("A never saw the relayed stream")
		case <-time.After(100 * time.Millisecond):
		}
	}
	incoming, err := a.AcceptStream(handle)
	if err != nil {
		t.Fatal(err)
	}
	buf, err := incoming.Read(5)
	if err != nil {
		t.Fatal(err)
	}
	if string(buf) != "hello" {
		t.Fatalf("expected 'hello', got %q", string(buf))
	}
}

// TestPendingStreamsAreBounded checks incoming streams the app never accepts are
// capped at maxPendingStreams and can be dropped, instead of piling up forever.
func TestPendingStreamsAreBounded(t *testing.T) {
	aStreams := &collectingStreams{}
	a, err := NewHost(&Config{IdentitySeed: seed()}, &collectingPeers{}, aStreams)
	if err != nil {
		t.Fatal(err)
	}
	defer a.Stop()
	b, err := NewHost(&Config{IdentitySeed: seed()}, &collectingPeers{}, &collectingStreams{})
	if err != nil {
		t.Fatal(err)
	}
	defer b.Stop()
	b.h.Peerstore().AddAddrs(a.h.ID(), a.h.Addrs(), peerstore.PermanentAddrTTL)

	for i := 0; i < maxPendingStreams+5; i++ {
		s, err := b.OpenStream(a.h.ID().String(), ChatProtocolID)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = s.Write([]byte("x")) // lazy protocol negotiation: A only sees the stream once data flows
	}

	deadline := time.After(10 * time.Second)
	for {
		a.mu.Lock()
		n := len(a.pendingStreams)
		a.mu.Unlock()
		aStreams.mu.Lock()
		announced := len(aStreams.incoming)
		aStreams.mu.Unlock()
		if announced >= maxPendingStreams {
			if n > maxPendingStreams {
				t.Fatalf("%d pending streams held, cap is %d", n, maxPendingStreams)
			}
			break
		}
		select {
		case <-deadline:
			t.Fatalf("only %d streams announced", announced)
		case <-time.After(100 * time.Millisecond):
		}
	}

	a.dropPendingStream("1")
	a.mu.Lock()
	_, stillThere := a.pendingStreams["1"]
	a.mu.Unlock()
	if stillThere {
		t.Fatal("dropPendingStream left the stream pending")
	}
}

// TestRelayOnlyHostNeverConnectsDirectly checks Config.RelayOnly: even though B
// knows A's direct addresses, every connection between them runs through the
// relay, so neither side learns the other's IP address.
func TestRelayOnlyHostNeverConnectsDirectly(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	relayHost, err := libp2p.New(
		libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"),
		libp2p.EnableRelayService(),
		libp2p.ForceReachabilityPublic(),
	)
	if err != nil {
		t.Fatal(err)
	}
	defer relayHost.Close()
	relayInfo := peer.AddrInfo{ID: relayHost.ID(), Addrs: relayHost.Addrs()}
	var relayAddrs string
	for _, addr := range relayHost.Addrs() {
		relayAddrs += addr.String() + "/p2p/" + relayHost.ID().String() + "\n"
	}

	aStreams := &collectingStreams{}
	a, err := NewHost(&Config{IdentitySeed: seed()}, &collectingPeers{}, aStreams)
	if err != nil {
		t.Fatal(err)
	}
	defer a.Stop()
	b, err := NewHost(&Config{IdentitySeed: seed(), BootstrapPeers: relayAddrs, RelayOnly: true}, &collectingPeers{}, &collectingStreams{})
	if err != nil {
		t.Fatal(err)
	}
	defer b.Stop()

	// The relay transport's virtual /p2p-circuit listener is the only one allowed.
	for _, addr := range b.h.Network().ListenAddresses() {
		if !isCircuit(addr) {
			t.Fatalf("relay-only host is listening on %s", addr)
		}
	}

	if err := a.h.Connect(ctx, relayInfo); err != nil {
		t.Fatal(err)
	}
	var reserveErr error
	for i := 0; i < 50; i++ {
		if _, reserveErr = client.Reserve(ctx, a.h, relayInfo); reserveErr == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if reserveErr != nil {
		t.Fatalf("A reserving a relay slot: %v", reserveErr)
	}

	// B knows A's direct addresses too - the gater must refuse to use them.
	b.h.Peerstore().AddAddrs(a.h.ID(), a.h.Addrs(), peerstore.PermanentAddrTTL)
	if err := b.h.Connect(ctx, peer.AddrInfo{ID: a.h.ID(), Addrs: a.h.Addrs()}); err == nil {
		t.Fatal("relay-only host connected to a peer directly")
	}
	circuit, _ := multiaddr.NewMultiaddr("/p2p/" + relayHost.ID().String() + "/p2p-circuit")
	for _, addr := range relayHost.Addrs() {
		b.h.Peerstore().AddAddr(a.h.ID(), addr.Encapsulate(circuit), peerstore.PermanentAddrTTL)
	}

	stream, err := b.OpenStream(a.h.ID().String(), ChatProtocolID)
	if err != nil {
		t.Fatalf("relay-only B opening a stream to A: %v", err)
	}
	if _, err := stream.Write([]byte("hi")); err != nil {
		t.Fatal(err)
	}
	time.Sleep(500 * time.Millisecond) // give any (forbidden) direct upgrade a chance to show up

	for _, c := range b.h.Network().ConnsToPeer(a.h.ID()) {
		if !isCircuit(c.RemoteMultiaddr()) {
			t.Fatalf("B has a direct connection to A: %s", c.RemoteMultiaddr())
		}
	}
	for _, c := range a.h.Network().ConnsToPeer(b.h.ID()) {
		if !isCircuit(c.RemoteMultiaddr()) {
			t.Fatalf("A sees B directly at %s", c.RemoteMultiaddr())
		}
	}
}

// TestDefaultModeHostReservesRelaySlot checks a default-mode host books a slot
// on its bootstrap/relay node by itself (nobody calls client.Reserve here), so
// a third party that knows it only by a circuit address can reach it. Before
// relayOptions forced reachability to private, AutoRelay waited for an AutoNAT
// verdict that never came on a network this small, and this test failed.
func TestDefaultModeHostReservesRelaySlot(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()

	relayHost, err := libp2p.New(
		libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"),
		libp2p.EnableRelayService(),
		libp2p.ForceReachabilityPublic(),
	)
	if err != nil {
		t.Fatal(err)
	}
	defer relayHost.Close()
	// AutoRelay drops a candidate that doesn't (yet) speak the hop protocol, so
	// only start the app host once the relay service is up - as it long has been by
	// the time a phone connects to a real bootstrap node.
	waitFor(t, ctx, "the relay service to start", func() bool {
		return slices.Contains(relayHost.Mux().Protocols(), protocol.ID(proto.ProtoIDv2Hop))
	})

	aStreams := &collectingStreams{}
	a, err := NewHost(&Config{IdentitySeed: seed(), BootstrapPeers: p2pAddrs(relayHost)}, &collectingPeers{}, aStreams)
	if err != nil {
		t.Fatal(err)
	}
	defer a.Stop()
	if err := a.Start(); err != nil {
		t.Fatal(err)
	}
	waitFor(t, ctx, "A to reserve a relay slot", func() bool {
		info := relayHost.ConnManager().GetTagInfo(a.h.ID())
		return info != nil && info.Tags["relay-reservation"] > 0
	})

	c, err := libp2p.New(libp2p.NoListenAddrs, libp2p.EnableRelay())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	circuit := multiaddr.StringCast("/p2p/" + relayHost.ID().String() + "/p2p-circuit")
	var viaRelay []multiaddr.Multiaddr
	for _, addr := range relayHost.Addrs() {
		viaRelay = append(viaRelay, addr.Encapsulate(circuit))
	}
	limitedCtx := network.WithAllowLimitedConn(ctx, "test")
	if err := c.Connect(limitedCtx, peer.AddrInfo{ID: a.h.ID(), Addrs: viaRelay}); err != nil {
		t.Fatalf("C reaching A through its relay reservation: %v", err)
	}
	s, err := c.NewStream(limitedCtx, a.h.ID(), ChatProtocolID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.Write([]byte("hi")); err != nil {
		t.Fatal(err)
	}
	waitFor(t, ctx, "A to see C's stream", func() bool {
		_, ok := aStreams.first()
		return ok
	})
}

func waitFor(t *testing.T, ctx context.Context, what string, cond func() bool) {
	t.Helper()
	for !cond() {
		select {
		case <-ctx.Done():
			t.Fatalf("timed out waiting for %s", what)
		case <-time.After(100 * time.Millisecond):
		}
	}
}

func TestRelayOnlyNeedsARelay(t *testing.T) {
	if _, err := NewHost(&Config{IdentitySeed: seed(), RelayOnly: true}, &collectingPeers{}, &collectingStreams{}); err == nil {
		t.Fatal("expected relay-only mode without any relay to be rejected")
	}
}
