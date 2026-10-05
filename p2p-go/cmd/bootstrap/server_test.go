package main

import (
	"context"
	"crypto/rand"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"slices"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	ma "github.com/multiformats/go-multiaddr"

	p2pnode "frad.local/p2p-go/node"
)

// newTestServer starts a bootstrap server on loopback. Private addresses are
// allowed (everything here is 127.0.0.1) and the background loops don't run
// unless a test calls run itself, so each test drives rounds explicitly.
func newTestServer(t *testing.T, mutate func(*config)) *server {
	t.Helper()
	priv, _, err := crypto.GenerateEd25519Key(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	cfg := config{
		listenAddrs:  []string{"/ip4/127.0.0.1/tcp/0"},
		nodesFile:    filepath.Join(t.TempDir(), "nodes.txt"),
		allowPrivate: true,
		timing:       defaultTiming,
		logf:         t.Logf,
	}
	if mutate != nil {
		mutate(&cfg)
	}
	s, err := newServer(cfg, priv)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.close)
	return s
}

func infoOf(s *server) peer.AddrInfo {
	return peer.AddrInfo{ID: s.h.ID(), Addrs: s.h.Addrs()}
}

func testContext(t *testing.T) context.Context {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	t.Cleanup(cancel)
	return ctx
}

// TestGossipSpreadsServersAcrossFederation: A only knows B (-peer), B has
// verified C; a couple of gossip rounds later A has verified C as well, and
// persisted both.
func TestGossipSpreadsServersAcrossFederation(t *testing.T) {
	ctx := testContext(t)
	b := newTestServer(t, nil)
	c := newTestServer(t, nil)
	a := newTestServer(t, func(cfg *config) { cfg.federation = []peer.AddrInfo{infoOf(b)} })

	b.checkNode(ctx, infoOf(c), b.sharedNodes())
	if !b.store.isKnown(c.h.ID()) {
		t.Fatal("B didn't verify C")
	}

	a.reconnectFederation(ctx)
	if a.h.Network().Connectedness(b.h.ID()) != network.Connected || !a.store.isKnown(b.h.ID()) {
		t.Fatal("A didn't connect to and verify its federation peer B")
	}

	a.gossipOnce(ctx) // hears about C from B (or meets it through the DHT already)
	if !a.store.isCandidate(c.h.ID()) && !a.store.isKnown(c.h.ID()) {
		t.Fatal("A didn't learn C from B's node list")
	}
	a.gossipOnce(ctx) // checks C itself
	if !a.store.isKnown(c.h.ID()) {
		t.Fatal("A didn't verify C")
	}
	// B heard about A from A's own list, but only as a candidate until it checks.
	if !b.store.isCandidate(a.h.ID()) || b.store.isKnown(a.h.ID()) {
		t.Fatal("B should hold A as an unverified candidate")
	}

	reloaded := newNodeStore(a.cfg.nodesFile, a.h.ID(), defaultTiming.nodeTTL, true)
	if err := reloaded.load(time.Now()); err != nil {
		t.Fatal(err)
	}
	if !reloaded.isKnown(b.h.ID()) || !reloaded.isKnown(c.h.ID()) {
		t.Fatalf("persisted nodes file is missing B or C: %v", reloaded.alive())
	}
}

// TestConnectedServersBecomeKnown: a server met any other way - here a plain
// connection, in practice the DHT - is picked up by the next gossip round.
func TestConnectedServersBecomeKnown(t *testing.T) {
	ctx := testContext(t)
	a := newTestServer(t, nil)
	b := newTestServer(t, nil)
	if err := b.h.Connect(ctx, infoOf(a)); err != nil {
		t.Fatal(err)
	}
	// Connect only waits for B's identify of A; A identifies B on its own time.
	waitFor(t, ctx, "A to identify B", func() bool {
		return p2pnode.IsFradServer(a.h, b.h.ID()) && len(p2pnode.VerifiedNodeAddrs(a.h, b.h.ID(), true)) > 0
	})
	a.gossipOnce(ctx)
	if !a.store.isKnown(b.h.ID()) {
		t.Fatal("A didn't record the FRAD server connected to it")
	}
}

func TestGossipOnlyVerifiesFradServers(t *testing.T) {
	ctx := testContext(t)
	a := newTestServer(t, nil)

	// A libp2p node that isn't a FRAD server (no /frad/kad DHT), even though
	// it speaks the node-list protocol and pushes itself as a server.
	impostor, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatal(err)
	}
	defer impostor.Close()
	impostorInfo := peer.AddrInfo{ID: impostor.ID(), Addrs: impostor.Addrs()}
	impostor.SetStreamHandler(p2pnode.NodesProtocolID, p2pnode.NodeListHandler(
		func() []peer.AddrInfo { return []peer.AddrInfo{impostorInfo} }, nil, true))
	unreachable := peer.AddrInfo{ID: newTestServer(t, nil).h.ID(), Addrs: []ma.Multiaddr{ma.StringCast("/ip4/127.0.0.1/tcp/1")}}

	a.store.addCandidates([]peer.AddrInfo{impostorInfo, unreachable})
	a.gossipOnce(ctx)
	for _, id := range []peer.ID{impostor.ID(), unreachable.ID} {
		if a.store.isKnown(id) || a.store.isCandidate(id) {
			t.Errorf("%s should have been checked and dropped", id)
		}
	}
}

func TestServerAnswersWithItselfAndKnownNodes(t *testing.T) {
	ctx := testContext(t)
	a := newTestServer(t, nil)
	b := newTestServer(t, nil)
	a.checkNode(ctx, infoOf(b), a.sharedNodes())

	app, err := libp2p.New(libp2p.NoListenAddrs)
	if err != nil {
		t.Fatal(err)
	}
	defer app.Close()
	if err := app.Connect(ctx, infoOf(a)); err != nil {
		t.Fatal(err)
	}
	pushed := []peer.AddrInfo{{ID: newTestServer(t, nil).h.ID(), Addrs: []ma.Multiaddr{ma.StringCast("/ip4/127.0.0.1/tcp/2")}}}
	theirs, err := p2pnode.ExchangeNodeList(ctx, app, a.h.ID(), pushed, true)
	if err != nil {
		t.Fatal(err)
	}
	if ids := peer.AddrInfosToIDs(theirs); len(ids) != 2 || ids[0] != a.h.ID() || ids[1] != b.h.ID() {
		t.Fatalf("got %v, want A itself followed by B", theirs)
	}
	if !a.store.isCandidate(pushed[0].ID) || a.store.isKnown(pushed[0].ID) {
		t.Fatal("a pushed entry must become a candidate, not a known node")
	}
}

func TestAnnounceReplacesDetectedAddrs(t *testing.T) {
	announce := []ma.Multiaddr{
		ma.StringCast("/ip4/203.0.113.9/tcp/4001"),
		ma.StringCast("/ip6/2001:db8::9/udp/4001/quic-v1"),
	}
	s := newTestServer(t, func(cfg *config) {
		cfg.announce = announce
		cfg.allowPrivate = false
	})
	if got := s.h.Addrs(); !sameAddrs(got, announce) {
		t.Fatalf("advertised %v, want %v", got, announce)
	}
	if own := s.sharedNodes()[0]; own.ID != s.h.ID() || !sameAddrs(own.Addrs, announce) {
		t.Fatalf("node list starts with %v, want this node at its announced addresses", own)
	}
}

func sameAddrs(a, b []ma.Multiaddr) bool {
	if len(a) != len(b) {
		return false
	}
	for _, addr := range a {
		if !ma.Contains(b, addr) {
			return false
		}
	}
	return true
}

func TestUnannouncedPrivateNodeDoesNotListItself(t *testing.T) {
	s := newTestServer(t, func(cfg *config) { cfg.allowPrivate = false })
	if got := s.sharedNodes(); len(got) != 0 {
		t.Fatalf("a node with only loopback addresses listed %v", got)
	}
}

// TestFederatedServersShareOneDHT checks -peer actually joins the DHTs: after
// federating, each server has the other in its /frad routing table.
func TestFederatedServersShareOneDHT(t *testing.T) {
	ctx := testContext(t)
	b := newTestServer(t, nil)
	a := newTestServer(t, func(cfg *config) { cfg.federation = []peer.AddrInfo{infoOf(b)} })
	if err := a.dht.Bootstrap(ctx); err != nil {
		t.Fatal(err)
	}
	a.reconnectFederation(ctx)
	waitFor(t, ctx, "A and B in each other's routing table", func() bool {
		return a.dht.RoutingTable().Find(b.h.ID()) != "" && b.dht.RoutingTable().Find(a.h.ID()) != ""
	})
}

// TestRunLoops wires everything together with short intervals: the official
// list (served over HTTP) and -peer both feed the store without any manual
// rounds, and run returns once its context ends.
func TestRunLoops(t *testing.T) {
	b := newTestServer(t, nil)
	c := newTestServer(t, nil)
	official := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("# official list\n" + p2pnode.FormatNodeList([]peer.AddrInfo{infoOf(c)})))
	}))
	defer official.Close()
	a := newTestServer(t, func(cfg *config) {
		cfg.federation = []peer.AddrInfo{infoOf(b)}
		cfg.officialURLs = []string{official.URL}
		cfg.timing = timing{
			firstGossip:       10 * time.Millisecond,
			gossipInterval:    50 * time.Millisecond,
			officialInterval:  time.Hour,
			reconnectInterval: 50 * time.Millisecond,
			nodeTTL:           time.Hour,
		}
	})

	ctx, cancel := context.WithCancel(testContext(t))
	done := make(chan struct{})
	go func() {
		a.run(ctx)
		close(done)
	}()
	waitFor(t, testContext(t), "A to verify B (via -peer) and C (via the official list)", func() bool {
		return a.store.isKnown(b.h.ID()) && a.store.isKnown(c.h.ID())
	})
	cancel()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("run didn't return after its context ended")
	}
}

func waitFor(t *testing.T, ctx context.Context, what string, cond func() bool) {
	t.Helper()
	for !cond() {
		select {
		case <-ctx.Done():
			t.Fatalf("timed out waiting for %s", what)
		case <-time.After(50 * time.Millisecond):
		}
	}
}

func TestParseFlags(t *testing.T) {
	id := newTestServer(t, nil).h.ID().String()
	cfg, err := parseFlags([]string{
		"-identity-key-file", "/var/lib/frad/id.key",
		"-peer", "/ip4/203.0.113.1/tcp/4001/p2p/" + id,
		"-peer", "/ip6/2001:db8::1/udp/4001/quic-v1/p2p/" + id,
		"-announce", "/ip4/203.0.113.2/tcp/4001",
	})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.nodesFile != "/var/lib/frad/nodes.txt" {
		t.Errorf("nodes file %q, want it next to the identity key", cfg.nodesFile)
	}
	if len(cfg.federation) != 1 || len(cfg.federation[0].Addrs) != 2 {
		t.Errorf("federation %v, want one peer with both addresses", cfg.federation)
	}
	if len(cfg.announce) != 1 || len(cfg.officialURLs) != 2 || len(cfg.listenAddrs) != 4 {
		t.Errorf("announce %v, official %v, listen %v", cfg.announce, cfg.officialURLs, cfg.listenAddrs)
	}
	if !slices.Contains(cfg.listenAddrs, "/ip6/::/udp/4001/quic-v1") {
		t.Errorf("no dual-stack listener in %v", cfg.listenAddrs)
	}

	if cfg, err := parseFlags([]string{"-official-list-url", ""}); err != nil || len(cfg.officialURLs) != 0 {
		t.Errorf("an empty -official-list-url must disable it: %v %v", cfg.officialURLs, err)
	}
	for _, bad := range [][]string{
		{"-peer", "/ip4/203.0.113.1/tcp/4001"},
		{"-peer", "nonsense"},
		{"-announce", "/ip4/203.0.113.2/tcp/4001/p2p/" + id},
	} {
		if _, err := parseFlags(bad); err == nil {
			t.Errorf("%v: expected an error", bad)
		}
	}
}
