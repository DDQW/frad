package node

import (
	"context"
	"crypto/rand"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	dhtpkg "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p/core/protocol"
)

// The listeners are called from libp2p's own goroutines while the test polls them, so both
// guard their slices with a mutex (go test -race flags the unguarded version).
type collectingPeers struct {
	mu    sync.Mutex
	found []string
}

func (c *collectingPeers) OnPeerFound(peerId string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.found = append(c.found, peerId)
}
func (c *collectingPeers) OnDiscoveryError(message string) { fmt.Println("discovery error:", message) }

func (c *collectingPeers) first() (string, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.found) == 0 {
		return "", false
	}
	return c.found[0], true
}

type collectingStreams struct {
	mu       sync.Mutex
	incoming []string
}

func (c *collectingStreams) OnIncomingStream(protocolId string, peerId string, streamHandle string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.incoming = append(c.incoming, streamHandle)
}

func (c *collectingStreams) first() (string, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.incoming) == 0 {
		return "", false
	}
	return c.incoming[0], true
}

func seed() []byte {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	return b
}

// startTestBootstrap mirrors cmd/bootstrap/main.go (a mode-Server DHT node with
// no rendezvous logic of its own) — real app Hosts are always mode-Auto, so a
// realistic two-app-peer discovery test needs a third, bootstrap-shaped party
// to actually store/serve provider records, exactly like production.
func startTestBootstrap(t *testing.T) string {
	t.Helper()
	h, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = h.Close() })

	kadDHT, err := dhtpkg.New(h, dhtpkg.ProtocolPrefix(protocol.ID(ProtocolPrefix)), dhtpkg.Mode(dhtpkg.ModeServer))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = kadDHT.Close() })
	if err := kadDHT.Bootstrap(context.Background()); err != nil {
		t.Fatal(err)
	}

	var addrs string
	for _, addr := range h.Addrs() {
		addrs += fmt.Sprintf("%s/p2p/%s\n", addr, h.ID())
	}
	return addrs
}

func TestTwoHostsRendezvousViaBootstrapAndChat(t *testing.T) {
	bootstrapAddrs := startTestBootstrap(t)

	aPeers := &collectingPeers{}
	aStreams := &collectingStreams{}
	a, err := NewHost(&Config{ListenPort: 0, IdentitySeed: seed(), BootstrapPeers: bootstrapAddrs}, aPeers, aStreams)
	if err != nil {
		t.Fatal(err)
	}
	defer a.Stop()
	if err := a.Start(); err != nil {
		t.Fatal(err)
	}

	bPeers := &collectingPeers{}
	bStreams := &collectingStreams{}
	b, err := NewHost(&Config{ListenPort: 0, IdentitySeed: seed(), BootstrapPeers: bootstrapAddrs}, bPeers, bStreams)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Stop()
	if err := b.Start(); err != nil {
		t.Fatal(err)
	}

	time.Sleep(1 * time.Second) // let both sides finish connecting/identifying with the bootstrap node

	topic := "frad/test/v1/smoke"
	if err := a.StartAdvertising(topic); err != nil {
		t.Fatal(err)
	}
	time.Sleep(1 * time.Second)
	if err := b.FindPeersOnce(topic); err != nil {
		t.Fatal(err)
	}

	deadline := time.After(20 * time.Second)
	var foundPeer string
	for {
		if p, ok := bPeers.first(); ok {
			foundPeer = p
			break
		}
		select {
		case <-deadline:
			t.Fatal("B never found A via DHT rendezvous through the bootstrap node")
		case <-time.After(200 * time.Millisecond):
		}
	}

	stream, err := b.OpenStream(foundPeer, ChatProtocolID)
	if err != nil {
		t.Fatalf("B opening stream to A: %v", err)
	}
	if _, err := stream.Write([]byte("hello")); err != nil {
		t.Fatalf("B writing to stream: %v", err)
	}

	deadline = time.After(5 * time.Second)
	var handle string
	for {
		if h, ok := aStreams.first(); ok {
			handle = h
			break
		}
		select {
		case <-deadline:
			t.Fatal("A never observed the incoming stream from B")
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
