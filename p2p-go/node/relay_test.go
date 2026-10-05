package node

import (
	"context"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/peerstore"
	"github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/client"
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

func TestRelayOnlyNeedsARelay(t *testing.T) {
	if _, err := NewHost(&Config{IdentitySeed: seed(), RelayOnly: true}, &collectingPeers{}, &collectingStreams{}); err == nil {
		t.Fatal("expected relay-only mode without any relay to be rejected")
	}
}
