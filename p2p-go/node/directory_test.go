package node

import (
	"context"
	"crypto/rand"
	"fmt"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	dhtpkg "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/peerstore"
	"github.com/libp2p/go-libp2p/core/protocol"
	ma "github.com/multiformats/go-multiaddr"
)

func newPeerID(t testing.TB) peer.ID {
	t.Helper()
	priv, _, err := crypto.GenerateEd25519Key(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	id, err := peer.IDFromPrivateKey(priv)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

// nodeListOf builds a node list (one peer per address) from transport addresses.
func nodeListOf(t testing.TB, addrs ...string) []peer.AddrInfo {
	t.Helper()
	var infos []peer.AddrInfo
	for _, a := range addrs {
		infos = append(infos, peer.AddrInfo{ID: newPeerID(t), Addrs: []ma.Multiaddr{ma.StringCast(a)}})
	}
	return infos
}

func countLines(text string) int {
	return len(strings.Fields(text))
}

func TestParseNodeListValidation(t *testing.T) {
	id := newPeerID(t).String()
	cases := []struct {
		addr          string
		public, local bool // accepted without / with allowPrivate
	}{
		{"/ip4/93.184.216.34/tcp/4001", true, true},
		{"/ip4/93.184.216.34/udp/4001/quic-v1", true, true},
		{"/ip6/2a01:4f8::1/tcp/4001", true, true},
		{"/ip6/2a01:4f8::1/udp/4001/quic-v1", true, true},
		{"/dns4/node.example.org/tcp/4001", true, true},
		{"/dns6/node.example.org/udp/4001/quic-v1", true, true},
		{"/dns/Node.Example.org./tcp/4001", true, true},
		{"/ip4/127.0.0.1/tcp/4001", false, true},
		{"/ip6/::1/tcp/4001", false, true},
		{"/ip4/10.1.2.3/tcp/4001", false, true},
		{"/ip4/172.16.0.9/tcp/4001", false, true},
		{"/ip4/192.168.1.1/tcp/4001", false, true},
		{"/ip4/100.64.0.1/tcp/4001", false, true},
		{"/ip4/169.254.1.1/tcp/4001", false, true},
		{"/ip6/fd00::1/tcp/4001", false, true},
		{"/ip6/fe80::1/tcp/4001", false, true},
		{"/ip6/64:ff9b::808:808/tcp/4001", false, true},
		{"/ip6/::ffff:10.0.0.1/tcp/4001", false, true},
		{"/dns4/localhost/tcp/4001", false, true},
		{"/dns4/printer.local/tcp/4001", false, true},
		{"/dns4/intranet/tcp/4001", false, true},
		{"/ip4/0.0.0.0/tcp/4001", false, false},
		{"/ip6/::/tcp/4001", false, false},
		{"/ip4/224.0.0.1/tcp/4001", false, false},
		{"/ip4/255.255.255.255/tcp/4001", false, false},
		{"/dns4/10.0.0.1/tcp/4001", false, false},
		{"/dns4/bad_name.example.org/tcp/4001", false, false},
		{"/dnsaddr/example.org", false, false},
		{"/ip4/93.184.216.34/tcp/0", false, false},
		{"/ip4/93.184.216.34/tcp/4001/ws", false, false},
		{"/ip4/93.184.216.34/udp/4001", false, false},
		{"/ip4/93.184.216.34/udp/4001/quic-v1/webtransport", false, false},
		{"/ip4/93.184.216.34/tcp/4001/p2p/" + newPeerID(t).String() + "/p2p-circuit", false, false},
	}
	for _, c := range cases {
		line := c.addr + "/p2p/" + id
		if got := len(ParseNodeList(line, false)) == 1; got != c.public {
			t.Errorf("%s: accepted=%v from the internet, want %v", c.addr, got, c.public)
		}
		if got := len(ParseNodeList(line, true)) == 1; got != c.local {
			t.Errorf("%s: accepted=%v with allowPrivate, want %v", c.addr, got, c.local)
		}
	}

	for _, junk := range []string{
		"", "   ", "# a comment /ip4/93.184.216.34/tcp/4001/p2p/" + id,
		"not a multiaddr", "/ip4/93.184.216.34/tcp/4001",
		"/ip4/93.184.216.34/tcp/4001/p2p/" + id + strings.Repeat("x", maxNodeLineBytes),
	} {
		if got := ParseNodeList(junk, true); len(got) != 0 {
			t.Errorf("%q: parsed as %v, want nothing", junk, got)
		}
	}
}

func TestParseNodeListGroupsByPeerAndDedupes(t *testing.T) {
	a, b := newPeerID(t), newPeerID(t)
	text := strings.Join([]string{
		"# header",
		"/ip4/93.184.216.34/tcp/4001/p2p/" + a.String(),
		"/ip4/93.184.216.34/tcp/4001/p2p/" + a.String(), // duplicate
		"",
		"/ip4/198.51.100.7/tcp/4001/p2p/" + b.String(),
		"/ip4/93.184.216.34/udp/4001/quic-v1/p2p/" + a.String(),
		"/ip6/2a01:4f8::1/tcp/4001/p2p/" + a.String(),
		"/ip6/2a01:4f8::1/udp/4001/quic-v1/p2p/" + a.String(),
		"/dns4/a.example.org/tcp/4001/p2p/" + a.String(), // fifth address of a: over maxAddrsPerNode
	}, "\n")

	infos := ParseNodeList(text, false)
	if len(infos) != 2 || infos[0].ID != a || infos[1].ID != b {
		t.Fatalf("want [a b] in first-seen order, got %v", infos)
	}
	if len(infos[0].Addrs) != maxAddrsPerNode {
		t.Fatalf("a has %d addrs, want %d", len(infos[0].Addrs), maxAddrsPerNode)
	}

	formatted := FormatNodeList(infos)
	if countLines(formatted) != maxAddrsPerNode+1 {
		t.Fatalf("formatted list has the wrong number of lines:\n%s", formatted)
	}
	if again := ParseNodeList(formatted, false); FormatNodeList(again) != formatted {
		t.Fatal("FormatNodeList output doesn't round-trip through ParseNodeList")
	}
}

func TestMergeNodeListsCapsEachNetwork(t *testing.T) {
	sameV4 := nodeListOf(t, "/ip4/93.184.216.1/tcp/4001", "/ip4/93.184.216.2/tcp/4001", "/ip4/93.184.216.3/tcp/4001")
	sameV6 := nodeListOf(t, "/ip6/2a01:4f8:1::1/tcp/4001", "/ip6/2a01:4f8:1:ffff::2/tcp/4001", "/ip6/2a01:4f8:1:1::3/tcp/4001")
	sameDomain := nodeListOf(t, "/dns4/a.example.org/tcp/4001", "/dns4/b.c.example.org/tcp/4001", "/dns4/EXAMPLE.org/tcp/4001")
	elsewhere := nodeListOf(t, "/ip4/93.184.217.1/tcp/4001", "/ip6/2a01:4f8:2::1/tcp/4001", "/dns4/node.example.net/tcp/4001")

	merged := MergeNodeLists(100, sameV4, sameV6, sameDomain, elsewhere)
	want := []peer.ID{
		sameV4[0].ID, sameV4[1].ID,
		sameV6[0].ID, sameV6[1].ID,
		sameDomain[0].ID, sameDomain[1].ID,
		elsewhere[0].ID, elsewhere[1].ID, elsewhere[2].ID,
	}
	if got := peer.AddrInfosToIDs(merged); fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("merged peers:\n got %v\nwant %v", got, want)
	}
}

func TestMergeNodeListsKeepsOnePeersAddrsTogether(t *testing.T) {
	a, b, c := newPeerID(t), newPeerID(t), newPeerID(t)
	tcp := ma.StringCast("/ip4/93.184.216.1/tcp/4001")
	quic := ma.StringCast("/ip4/93.184.216.1/udp/4001/quic-v1")
	merged := MergeNodeLists(100,
		[]peer.AddrInfo{{ID: a, Addrs: []ma.Multiaddr{tcp}}},
		[]peer.AddrInfo{{ID: b, Addrs: []ma.Multiaddr{ma.StringCast("/ip4/93.184.216.2/tcp/4001")}}},
		// a's second transport in the same /24 doesn't count as a third peer...
		[]peer.AddrInfo{{ID: a, Addrs: []ma.Multiaddr{quic, tcp}}},
		// ...but c is one.
		[]peer.AddrInfo{{ID: c, Addrs: []ma.Multiaddr{ma.StringCast("/ip4/93.184.216.3/tcp/4001")}}},
	)
	if len(merged) != 2 || merged[0].ID != a || len(merged[0].Addrs) != 2 || merged[1].ID != b {
		t.Fatalf("got %v", merged)
	}
}

func TestMergeNodeListsLimitsTotalEntries(t *testing.T) {
	var many []peer.AddrInfo
	for i := 0; i < 100; i++ {
		many = append(many, nodeListOf(t, fmt.Sprintf("/ip4/93.0.%d.1/tcp/4001", i))...)
	}
	merged := MergeNodeLists(MaxNodeListEntries, many)
	if n := countLines(FormatNodeList(merged)); n != MaxNodeListEntries {
		t.Fatalf("merged list has %d entries, want %d", n, MaxNodeListEntries)
	}
	if merged[0].ID != many[0].ID {
		t.Fatal("earlier entries must win")
	}
}

func TestValidateNodeList(t *testing.T) {
	id := newPeerID(t).String()
	in := strings.Join([]string{
		"# comment",
		"/ip4/93.184.216.34/tcp/4001/p2p/" + id,
		"/ip4/127.0.0.1/tcp/4001/p2p/" + id,
		"/ip4/93.184.216.34/tcp/4001/p2p/" + id,
		"garbage",
		" /dns4/node.example.org/tcp/4001/p2p/" + id + " ",
	}, "\n")
	want := "/ip4/93.184.216.34/tcp/4001/p2p/" + id + "\n/dns4/node.example.org/tcp/4001/p2p/" + id + "\n"
	if got := ValidateNodeList(in); got != want {
		t.Fatalf("got:\n%s\nwant:\n%s", got, want)
	}
}

func TestNodeListWireLimits(t *testing.T) {
	var many []peer.AddrInfo
	for i := 0; i < 100; i++ {
		many = append(many, nodeListOf(t, fmt.Sprintf("/ip4/93.184.%d.1/tcp/4001", i))...)
	}
	encoded := encodeNodeList(many)
	if n := countLines(string(encoded)); n != MaxNodeListEntries {
		t.Fatalf("encoded %d entries, want the %d-entry cap", n, MaxNodeListEntries)
	}
	if _, err := readNodeList(strings.NewReader(string(encoded)), false); err != nil {
		t.Fatalf("a list at the limit must be accepted: %v", err)
	}

	tooMany := FormatNodeList(many[:MaxNodeListEntries+1])
	if _, err := readNodeList(strings.NewReader(tooMany), false); err == nil {
		t.Fatal("a list over the entry limit must be a protocol error")
	}
	tooBig := "# " + strings.Repeat("x", maxNodeListBytes) + "\n"
	if _, err := readNodeList(strings.NewReader(tooBig), false); err == nil {
		t.Fatal("a list over the byte limit must be a protocol error")
	}
}

func newTestHost(t *testing.T, cfg *Config) *Host {
	t.Helper()
	cfg.IdentitySeed = seed()
	cfg.allowPrivateNodeAddrs = true
	h, err := NewHost(cfg, &collectingPeers{}, &collectingStreams{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = h.Stop() })
	return h
}

// p2pAddrs renders h's addresses as a node list, or bootstrap-peer list.
func p2pAddrs(h host.Host) string {
	return FormatNodeList([]peer.AddrInfo{{ID: h.ID(), Addrs: h.Addrs()}})
}

func TestHostsExchangeNodeLists(t *testing.T) {
	a := newTestHost(t, &Config{})
	b := newTestHost(t, &Config{})
	b.h.Peerstore().AddAddrs(a.h.ID(), a.h.Addrs(), peerstore.PermanentAddrTTL)

	aKnows := FormatNodeList(nodeListOf(t, "/ip4/93.184.216.1/tcp/4001", "/dns4/node.example.org/udp/4001/quic-v1"))
	bKnows := FormatNodeList(nodeListOf(t, "/ip6/2a01:4f8::7/tcp/4001"))
	a.SetKnownNodes(aKnows + "garbage\n")
	b.SetKnownNodes(bKnows)

	got, err := b.ExchangeNodesWithPeer(a.LocalPeerId())
	if err != nil {
		t.Fatal(err)
	}
	if got != aKnows {
		t.Fatalf("B learned:\n%s\nwant A's known nodes:\n%s", got, aKnows)
	}
	if learned := a.TakeLearnedNodes(); learned != bKnows {
		t.Fatalf("A learned:\n%s\nwant B's known nodes:\n%s", learned, bKnows)
	}
	if again := a.TakeLearnedNodes(); again != "" {
		t.Fatalf("TakeLearnedNodes must clear what it returned, still had:\n%s", again)
	}
}

// startNodeListServer runs a bare libp2p host that serves list over
// NodesProtocolID and records what it is sent.
func startNodeListServer(t *testing.T, list []peer.AddrInfo) (host.Host, func() []peer.AddrInfo) {
	t.Helper()
	h, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = h.Close() })
	var mu sync.Mutex
	var received []peer.AddrInfo
	h.SetStreamHandler(NodesProtocolID, NodeListHandler(
		func() []peer.AddrInfo { return list },
		func(_ network.Conn, infos []peer.AddrInfo) {
			mu.Lock()
			defer mu.Unlock()
			received = append(received, infos...)
		},
		true,
	))
	return h, func() []peer.AddrInfo {
		mu.Lock()
		defer mu.Unlock()
		return received
	}
}

func TestExchangeNodesWithBootstrapPeers(t *testing.T) {
	serverList := nodeListOf(t, "/ip4/93.184.216.1/tcp/4001", "/ip4/198.51.100.1/tcp/4001")
	server, received := startNodeListServer(t, serverList)
	unreachable := "/ip4/127.0.0.1/tcp/1/p2p/" + newPeerID(t).String() + "\n"

	app := newTestHost(t, &Config{BootstrapPeers: unreachable + p2pAddrs(server)})
	got, err := app.ExchangeNodesWithBootstrapPeers()
	if err != nil {
		t.Fatalf("one reachable bootstrap peer must be enough: %v", err)
	}
	if want := FormatNodeList(serverList); got != want {
		t.Fatalf("learned:\n%s\nwant:\n%s", got, want)
	}
	// The app shares its bootstrap peers in return, so the server hears about
	// every server the app knows - itself included, which a server simply skips.
	if ids := peer.AddrInfosToIDs(received()); len(ids) != 2 || ids[1] != server.ID() {
		t.Fatalf("server received %v, want the app's two bootstrap peers", received())
	}

	lonely := newTestHost(t, &Config{BootstrapPeers: unreachable})
	if _, err := lonely.ExchangeNodesWithBootstrapPeers(); err == nil {
		t.Fatal("expected an error when no bootstrap peer answers")
	}
	none := newTestHost(t, &Config{})
	if _, err := none.ExchangeNodesWithBootstrapPeers(); err == nil {
		t.Fatal("expected an error without bootstrap peers")
	}
}

func TestBootstrapPeersSharedOnlyIfPublic(t *testing.T) {
	lan, public := newPeerID(t).String(), newPeerID(t).String()
	configured, err := parseAddrInfos(strings.Join([]string{
		"/ip4/192.168.1.5/tcp/4001/p2p/" + lan,
		"/ip4/192.168.1.5/tcp/4001/p2p/" + public,
		"/ip4/93.184.216.34/tcp/4001/p2p/" + public,
	}, "\n"))
	if err != nil {
		t.Fatal(err)
	}
	if got, want := FormatNodeList(shareableNodes(configured, false)), "/ip4/93.184.216.34/tcp/4001/p2p/"+public+"\n"; got != want {
		t.Fatalf("shared:\n%s\nwant only the public bootstrap address:\n%s", got, want)
	}
	if got := shareableNodes(configured, true); len(got) != 2 || len(got[1].Addrs) != 2 {
		t.Fatalf("with allowPrivate everything is shareable, got %v", got)
	}
}

// TestBootstrapPeersSkipCommentsAndTypos: the official nodes.txt (with its
// '#' header) works as a bootstrap list as is, and one bad line doesn't cost
// the rest of the list - only a list without a single usable entry fails.
func TestBootstrapPeersSkipCommentsAndTypos(t *testing.T) {
	a, b := newPeerID(t).String(), newPeerID(t).String()
	infos, err := parseAddrInfos(strings.Join([]string{
		"# FRAD official node list - see README.md next to this file.",
		"#",
		"/ip4/93.184.216.34/tcp/4001/p2p/" + a,
		"/ip4/93.184.216.34/tcp/4001/p2p/" + a, // duplicate
		"/ip4/93.184.216.34/tcp/4001",          // no peer id
		"not a multiaddr",
		"  /ip4/198.51.100.7/udp/4001/quic-v1/p2p/" + b + "  ",
	}, "\n"))
	if err != nil {
		t.Fatal(err)
	}
	if got := FormatNodeList(infos); got != "/ip4/93.184.216.34/tcp/4001/p2p/"+a+"\n/ip4/198.51.100.7/udp/4001/quic-v1/p2p/"+b+"\n" {
		t.Fatalf("parsed:\n%s", got)
	}
	if infos, err := parseAddrInfos("# only a comment\n\n"); err != nil || len(infos) != 0 {
		t.Fatalf("a list of comments is just empty: %v %v", infos, err)
	}
	if _, err := parseAddrInfos("# header\nnot a multiaddr\n/ip4/93.184.216.34/tcp/4001"); err == nil {
		t.Fatal("expected an error for a list without one usable entry")
	}

	// End to end: a host starts from the official list's format.
	server, _ := startNodeListServer(t, nil)
	app := newTestHost(t, &Config{BootstrapPeers: "# FRAD official node list\n" + p2pAddrs(server)})
	if _, err := app.ExchangeNodesWithBootstrapPeers(); err != nil {
		t.Fatalf("bootstrap peer from a commented list unusable: %v", err)
	}
}

func TestNodeListHandlerRejectsOversizedLists(t *testing.T) {
	server, received := startNodeListServer(t, nodeListOf(t, "/ip4/93.184.216.1/tcp/4001"))
	client, err := libp2p.New(libp2p.NoListenAddrs)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := client.Connect(ctx, peer.AddrInfo{ID: server.ID(), Addrs: server.Addrs()}); err != nil {
		t.Fatal(err)
	}

	var tooMany []peer.AddrInfo
	for i := 0; i <= MaxNodeListEntries; i++ {
		tooMany = append(tooMany, nodeListOf(t, fmt.Sprintf("/ip4/93.184.%d.1/tcp/4001", i))...)
	}
	s, err := client.NewStream(ctx, server.ID(), NodesProtocolID)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = s.Write([]byte(FormatNodeList(tooMany)))
	_ = s.CloseWrite()
	if _, err := readNodeList(s, true); err == nil {
		t.Fatal("expected the server to reset the stream instead of answering")
	}
	if got := received(); len(got) != 0 {
		t.Fatalf("server used %d entries from an over-limit list", len(got))
	}

	// A well-formed exchange on the same connection still works.
	theirs, err := ExchangeNodeList(ctx, client, server.ID(), tooMany[:3], true)
	if err != nil || len(theirs) != 1 || len(received()) != 3 {
		t.Fatalf("follow-up exchange: theirs=%v received=%v err=%v", theirs, received(), err)
	}
}

// TestNodeListHandlerLimitsEachPeer: a peer holding its share of the
// handler's slots with streams that never finish can't take any more - and
// can't lock anyone else out either.
func TestNodeListHandlerLimitsEachPeer(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	limiter := newExchangeLimiter()
	server, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	list := nodeListOf(t, "/ip4/93.184.216.1/tcp/4001")
	server.SetStreamHandler(NodesProtocolID, nodeListHandler(func() []peer.AddrInfo { return list }, nil, true, limiter))
	serverInfo := peer.AddrInfo{ID: server.ID(), Addrs: server.Addrs()}

	staller, err := libp2p.New(libp2p.NoListenAddrs)
	if err != nil {
		t.Fatal(err)
	}
	defer staller.Close()
	if err := staller.Connect(ctx, serverInfo); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < maxExchangesPerPeer; i++ {
		s, err := staller.NewStream(ctx, server.ID(), NodesProtocolID)
		if err != nil {
			t.Fatal(err)
		}
		defer s.Reset()
		// Protocol negotiation is lazy: the handler only runs once something
		// is written. Half a line, and the list never ends.
		if _, err := s.Write([]byte("/ip4/")); err != nil {
			t.Fatal(err)
		}
	}
	waitFor(t, ctx, "the staller's streams to hold their slots", func() bool {
		return limiter.inUse(staller.ID()) == maxExchangesPerPeer
	})
	if _, err := ExchangeNodeList(ctx, staller, server.ID(), nil, true); err == nil {
		t.Fatal("a peer got more than its share of exchange slots")
	}

	other, err := libp2p.New(libp2p.NoListenAddrs)
	if err != nil {
		t.Fatal(err)
	}
	defer other.Close()
	if err := other.Connect(ctx, serverInfo); err != nil {
		t.Fatal(err)
	}
	if theirs, err := ExchangeNodeList(ctx, other, server.ID(), nil, true); err != nil || len(theirs) != 1 {
		t.Fatalf("another peer was locked out: %v %v", theirs, err)
	}
}

func TestExchangeLimiter(t *testing.T) {
	l := newExchangeLimiter()
	a, b := newPeerID(t), newPeerID(t)
	var held []exchangeSource
	for i := 0; i < maxExchangesPerPeer; i++ {
		held = append(held, exchangeSource{peer: a, network: "net-a"})
	}
	for _, src := range held {
		if !l.acquire(src) {
			t.Fatal("refused a slot within the per-peer limit")
		}
	}
	if l.acquire(exchangeSource{peer: a, network: "net-x"}) {
		t.Fatal("a peer exceeded maxExchangesPerPeer")
	}
	// Other peers on a's network share its per-network quota.
	for i := maxExchangesPerPeer; i < maxExchangesPerNetwork; i++ {
		src := exchangeSource{peer: newPeerID(t), network: "net-a"}
		if !l.acquire(src) {
			t.Fatal("refused a slot within the per-network limit")
		}
		held = append(held, src)
	}
	if l.acquire(exchangeSource{peer: b, network: "net-a"}) {
		t.Fatal("a network exceeded maxExchangesPerNetwork")
	}
	for i := len(held); i < maxConcurrentNodeExchanges; i++ {
		src := exchangeSource{peer: newPeerID(t), network: fmt.Sprintf("net-%d", i)}
		if !l.acquire(src) {
			t.Fatal("refused a slot within the overall limit")
		}
		held = append(held, src)
	}
	if l.acquire(exchangeSource{peer: b, network: "net-b"}) {
		t.Fatal("exceeded maxConcurrentNodeExchanges")
	}
	for _, src := range held {
		l.release(src)
	}
	if l.total != 0 || len(l.byPeer) != 0 || len(l.byNetwork) != 0 {
		t.Fatalf("slots leaked: %+v", l)
	}
}

func TestIsFradServer(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	serverHost, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatal(err)
	}
	defer serverHost.Close()
	kad, err := dhtpkg.New(serverHost, dhtpkg.ProtocolPrefix(protocol.ID(ProtocolPrefix)), dhtpkg.Mode(dhtpkg.ModeServer))
	if err != nil {
		t.Fatal(err)
	}
	defer kad.Close()
	app := newTestHost(t, &Config{})

	client, err := libp2p.New(libp2p.NoListenAddrs)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	for _, c := range []struct {
		name string
		info peer.AddrInfo
		want bool
	}{
		{"mode-Server DHT node", peer.AddrInfo{ID: serverHost.ID(), Addrs: serverHost.Addrs()}, true},
		{"app host (DHT client)", peer.AddrInfo{ID: app.h.ID(), Addrs: app.h.Addrs()}, false},
	} {
		if err := client.Connect(ctx, c.info); err != nil {
			t.Fatal(err)
		}
		if got := IsFradServer(client, c.info.ID); got != c.want {
			t.Errorf("%s: IsFradServer=%v, want %v", c.name, got, c.want)
		}
	}
}

// TestVerifyNodeAddrsProvesEachAddress checks a server is listed only at
// addresses it has been reached at: not where a list that pointed at it says
// it is, and not wherever it claims to be either - only its claims that
// survive a dial-back count.
func TestVerifyNodeAddrsProvesEachAddress(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	// Claimed next to its real addresses; nothing listens there.
	claimed := []ma.Multiaddr{ma.StringCast("/ip4/127.0.0.1/tcp/1"), ma.StringCast("/ip4/127.0.0.2/tcp/1")}
	server, err := libp2p.New(
		libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0", "/ip4/127.0.0.1/udp/0/quic-v1"),
		libp2p.AddrsFactory(func(detected []ma.Multiaddr) []ma.Multiaddr { return slices.Concat(claimed, detected) }),
	)
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	listening := slices.DeleteFunc(server.Network().ListenAddresses(), func(a ma.Multiaddr) bool {
		return CheckNodeAddr(a, true) != nil // the relay transport's /p2p-circuit
	})
	observer, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatal(err)
	}
	defer observer.Close()
	if err := RecordSelfReportedAddrs(ctx, observer); err != nil {
		t.Fatal(err)
	}

	// The server connects to the observer, so the observer never dialed it...
	if err := server.Connect(ctx, peer.AddrInfo{ID: observer.ID(), Addrs: observer.Addrs()}); err != nil {
		t.Fatal(err)
	}
	// ...and then a hostile list claims a different address for it.
	planted := ma.StringCast("/ip4/127.0.0.3/tcp/4001")
	if err := observer.Connect(ctx, peer.AddrInfo{ID: server.ID(), Addrs: []ma.Multiaddr{planted}}); err != nil {
		t.Fatal(err)
	}

	if got := VerifyNodeAddrs(ctx, observer, server.ID(), true); !sameAddrSet(got, listening) {
		t.Fatalf("verified %v, want only the addresses the server listens on, %v", got, listening)
	}
	if got := VerifyNodeAddrs(ctx, observer, server.ID(), false); len(got) != 0 {
		t.Fatalf("loopback addresses must not verify for the internet, got %v", got)
	}
	if got := SelfReportedNodeAddrs(observer, server.ID(), true); len(got) != len(claimed)+len(listening) {
		t.Fatalf("self-reported %v, want every claim (verified or not)", got)
	}

	// Without the recorder there is nothing to dial back, but an address this
	// host dialed itself still counts.
	dialer, err := libp2p.New(libp2p.NoListenAddrs)
	if err != nil {
		t.Fatal(err)
	}
	defer dialer.Close()
	tcpOnly := listening[:1]
	if err := dialer.Connect(ctx, peer.AddrInfo{ID: server.ID(), Addrs: tcpOnly}); err != nil {
		t.Fatal(err)
	}
	quick, cancelQuick := context.WithTimeout(ctx, 100*time.Millisecond)
	defer cancelQuick()
	if got := VerifyNodeAddrs(quick, dialer, server.ID(), true); !sameAddrSet(got, tcpOnly) {
		t.Fatalf("verified %v, want the dialed %v", got, tcpOnly)
	}
}

// TestVerifyNodeAddrsResolvesClaimedNames: a server that announces itself
// only by DNS name (-announce /dns4/...) and has only ever connected in - as a
// server does to the -peer it federates with - is still verified: its name
// is resolved and dialed back, and it is the IP that answered that gets
// listed, never the name, so the diversity caps see the network it is on.
func TestVerifyNodeAddrsResolvesClaimedNames(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	server, err := libp2p.New(
		libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"),
		libp2p.AddrsFactory(func(detected []ma.Multiaddr) []ma.Multiaddr {
			var named []ma.Multiaddr
			for _, a := range detected {
				if len(a) > 1 && a[0].Code() == ma.P_IP4 {
					named = append(named, ma.StringCast("/dns4/localhost").Encapsulate(a[1:]))
				}
			}
			return named
		}),
	)
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	listening := slices.DeleteFunc(server.Network().ListenAddresses(), func(a ma.Multiaddr) bool {
		return CheckNodeAddr(a, true) != nil // the relay transport's /p2p-circuit
	})
	observer, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatal(err)
	}
	defer observer.Close()
	if err := RecordSelfReportedAddrs(ctx, observer); err != nil {
		t.Fatal(err)
	}
	if err := server.Connect(ctx, peer.AddrInfo{ID: observer.ID(), Addrs: observer.Addrs()}); err != nil {
		t.Fatal(err)
	}
	if !AwaitSelfReport(ctx, observer, server.ID()) {
		t.Fatal("no identify report from the server")
	}
	if got := SelfReportedNodeAddrs(observer, server.ID(), true); len(got) != 1 || got[0].String() != "/dns4/localhost"+strings.TrimPrefix(listening[0].String(), "/ip4/127.0.0.1") {
		t.Fatalf("the server reported %v, want only its DNS name", got)
	}

	if got := VerifyNodeAddrs(ctx, observer, server.ID(), true); !sameAddrSet(got, listening) {
		t.Fatalf("verified %v, want the IP address the name led to, %v", got, listening)
	}
}

// TestDialBackChecksIdentity: an address where some other node answers
// doesn't verify - the handshake must authenticate the claimed peer id.
func TestDialBackChecksIdentity(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	other, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatal(err)
	}
	defer other.Close()
	dialer, err := libp2p.New(libp2p.NoListenAddrs)
	if err != nil {
		t.Fatal(err)
	}
	defer dialer.Close()
	if !dialBack(ctx, dialer, other.ID(), other.Addrs()[0]) {
		t.Fatal("dial-back to a node's real address failed")
	}
	if dialBack(ctx, dialer, newPeerID(t), other.Addrs()[0]) {
		t.Fatal("an address verified for a peer id that doesn't live there")
	}
}

func sameAddrSet(a, b []ma.Multiaddr) bool {
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

func TestPublicDialGater(t *testing.T) {
	g := PublicDialGater(false)
	for addr, want := range map[string]bool{
		"/ip4/93.184.216.34/tcp/4001":          true,
		"/ip6/2a01:4f8::1/udp/4001/quic-v1":    true,
		"/ip4/127.0.0.1/tcp/4001":              false,
		"/ip4/10.0.0.1/tcp/4001":               false,
		"/ip4/100.64.1.1/tcp/4001":             false,
		"/ip6/fd00::1/tcp/4001":                false,
		"/ip6/::ffff:192.168.1.1/tcp/4001":     false,
		"/ip4/93.184.216.34/tcp/1/p2p-circuit": true,
	} {
		if got := g.InterceptAddrDial("", ma.StringCast(addr)); got != want {
			t.Errorf("%s: allowed=%v, want %v", addr, got, want)
		}
	}
	if !PublicDialGater(true).InterceptAddrDial("", ma.StringCast("/ip4/127.0.0.1/tcp/4001")) {
		t.Error("allowPrivate must allow loopback")
	}

	// And end to end: a gated host can't be talked into dialing loopback.
	target, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	gated, err := libp2p.New(libp2p.NoListenAddrs, libp2p.ConnectionGater(g))
	if err != nil {
		t.Fatal(err)
	}
	defer gated.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := gated.Connect(ctx, peer.AddrInfo{ID: target.ID(), Addrs: target.Addrs()}); err == nil {
		t.Fatal("gated host dialed a loopback address")
	}
}
