package main

import (
	"cmp"
	"context"
	"crypto/rand"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
	ma "github.com/multiformats/go-multiaddr"

	p2pnode "frad.local/p2p-go/node"
)

// fakeServer is a loopback FRAD server as far as the crawler can tell: a
// mode-Server /frad DHT plus the node-list protocol, serving whatever list
// the test sets, after delay. With fradDHT false it is just some libp2p node
// that speaks the node-list protocol - which must not get listed.
type fakeServer struct {
	h     host.Host
	mu    sync.Mutex
	list  []peer.AddrInfo
	delay time.Duration
}

func startFakeServer(t *testing.T, fradDHT bool, opts ...libp2p.Option) *fakeServer {
	t.Helper()
	h, err := libp2p.New(append([]libp2p.Option{libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0")}, opts...)...)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = h.Close() })
	if fradDHT {
		kad, err := dht.New(h, dht.ProtocolPrefix(protocol.ID(p2pnode.ProtocolPrefix)), dht.Mode(dht.ModeServer))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = kad.Close() })
	}
	s := &fakeServer{h: h}
	h.SetStreamHandler(p2pnode.NodesProtocolID, p2pnode.NodeListHandler(s.serve, nil, true))
	return s
}

func (s *fakeServer) serve() []peer.AddrInfo {
	s.mu.Lock()
	list, delay := s.list, s.delay
	s.mu.Unlock()
	time.Sleep(delay)
	return list
}

func (s *fakeServer) knows(others ...*fakeServer) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.list = nil
	for _, o := range others {
		s.list = append(s.list, o.info())
	}
}

// info is where the server really is - which, for one making false claims
// about itself, isn't all of h.Addrs().
func (s *fakeServer) info() peer.AddrInfo {
	listening := slices.DeleteFunc(s.h.Network().ListenAddresses(), func(a ma.Multiaddr) bool {
		return p2pnode.CheckNodeAddr(a, true) != nil // the relay transport's /p2p-circuit
	})
	return peer.AddrInfo{ID: s.h.ID(), Addrs: listening}
}

func testConfig(t *testing.T) crawlConfig {
	return crawlConfig{
		maxContacts: 200, maxLevels: 10, nodeTimeout: 10 * time.Second, timeout: time.Minute,
		parallelism: 4, allowPrivate: true, logf: t.Logf,
	}
}

func writeFile(t *testing.T, dir, name, content string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func readFile(t *testing.T, dir, name string) string {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(dir, name))
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func runCrawl(t *testing.T, dir string, cfg crawlConfig) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	if err := run(ctx, dir, cfg); err != nil {
		t.Fatal(err)
	}
}

// TestCrawlListsServersSeenInThreeRuns: from a list naming only A, the crawl
// finds B and C through the servers' own lists, lists all three once they
// have been seen in three consecutive runs - never the impostor D - keeps the
// output byte-identical while nothing changes, and delists a server after it
// misses two runs in a row.
func TestCrawlListsServersSeenInThreeRuns(t *testing.T) {
	a, b, c := startFakeServer(t, true), startFakeServer(t, true), startFakeServer(t, true)
	impostor := startFakeServer(t, false)
	a.knows(b)
	b.knows(a, c, impostor)
	impostor.knows(impostor)

	dir := t.TempDir()
	writeFile(t, dir, nodesFile, nodesHeader+p2pnode.FormatNodeList([]peer.AddrInfo{a.info()}))
	cfg := testConfig(t)

	for run := 1; run < qualifyingStreak; run++ {
		runCrawl(t, dir, cfg)
		if body := strings.TrimPrefix(readFile(t, dir, nodesFile), nodesHeader); body != "" {
			t.Fatalf("run %d listed nodes before they qualified:\n%s", run, body)
		}
	}
	runCrawl(t, dir, cfg)
	listed := readFile(t, dir, nodesFile)
	for _, s := range []*fakeServer{a, b, c} {
		if !strings.Contains(listed, s.h.ID().String()) {
			t.Errorf("%s missing from:\n%s", s.h.ID(), listed)
		}
	}
	if strings.Contains(listed, impostor.h.ID().String()) || strings.Contains(readFile(t, dir, stateFile), impostor.h.ID().String()) {
		t.Fatal("a node without FRAD's DHT was listed or tracked")
	}

	stateBefore := readFile(t, dir, stateFile)
	runCrawl(t, dir, cfg)
	if readFile(t, dir, nodesFile) != listed || readFile(t, dir, stateFile) != stateBefore {
		t.Fatal("an unchanged network changed the output")
	}

	_ = c.h.Close()
	runCrawl(t, dir, cfg)
	if !strings.Contains(readFile(t, dir, nodesFile), c.h.ID().String()) {
		t.Fatal("one missed run delisted C")
	}
	runCrawl(t, dir, cfg)
	if strings.Contains(readFile(t, dir, nodesFile), c.h.ID().String()) {
		t.Fatal("C is still listed after missing two runs")
	}
}

func TestCrawlHonoursBudgetBansAndPins(t *testing.T) {
	a, b, c, d := startFakeServer(t, true), startFakeServer(t, true), startFakeServer(t, true), startFakeServer(t, true)
	pinned := startFakeServer(t, true)
	a.knows(b, c)
	c.knows(d)

	dir := t.TempDir()
	writeFile(t, dir, nodesFile, p2pnode.FormatNodeList([]peer.AddrInfo{a.info()}))
	writeFile(t, dir, bannedFile, "# no B\n"+b.h.ID().String()+"\n")
	writeFile(t, dir, pinnedFile, p2pnode.FormatNodeList([]peer.AddrInfo{pinned.info()}))
	cfg := testConfig(t)
	cfg.maxContacts = 3 // pinned, A, then only C from A's list: D is over budget

	runCrawl(t, dir, cfg)
	state := readFile(t, dir, stateFile)
	for _, s := range []*fakeServer{pinned, a, c} {
		if !strings.Contains(state, s.h.ID().String()) {
			t.Errorf("%s wasn't crawled", s.h.ID())
		}
	}
	if strings.Contains(state, b.h.ID().String()) {
		t.Error("banned B was crawled")
	}
	if strings.Contains(state, d.h.ID().String()) {
		t.Error("D was crawled despite the contact budget")
	}
	if !strings.Contains(readFile(t, dir, nodesFile), pinned.h.ID().String()) {
		t.Error("the pinned node must be listed from the first run on")
	}
}

// TestCrawlWithNoInputs is the very first run in a fresh repository: no
// network access needed, and it writes the header-only list.
func TestCrawlWithNoInputs(t *testing.T) {
	dir := t.TempDir()
	runCrawl(t, dir, testConfig(t))
	if got := readFile(t, dir, nodesFile); got != nodesHeader {
		t.Fatalf("got:\n%s", got)
	}
	if got := readFile(t, dir, stateFile); got != "{\n  \"version\": 1,\n  \"nodes\": {}\n}\n" {
		t.Fatalf("got state:\n%s", got)
	}
}

// TestCrawlListsOnlyProvenAddrs: a real server that also claims addresses it
// doesn't answer on - in the real world, ones in other networks, to pass the
// diversity caps as several servers - is listed at its real address only.
func TestCrawlListsOnlyProvenAddrs(t *testing.T) {
	claims := []ma.Multiaddr{ma.StringCast("/ip4/127.0.0.1/tcp/1"), ma.StringCast("/ip4/127.0.0.2/tcp/1"), ma.StringCast("/ip4/127.0.0.3/tcp/1")}
	liar := startFakeServer(t, true, libp2p.AddrsFactory(func(real []ma.Multiaddr) []ma.Multiaddr {
		return slices.Concat(claims, real)
	}))
	dir := t.TempDir()
	writeFile(t, dir, nodesFile, p2pnode.FormatNodeList([]peer.AddrInfo{liar.info()}))
	for run := 0; run < qualifyingStreak; run++ {
		runCrawl(t, dir, testConfig(t))
	}
	if got, want := readFile(t, dir, nodesFile), renderNodeList([]peer.AddrInfo{liar.info()}); got != want {
		t.Fatalf("listed:\n%s\nwant only the address the server answers on:\n%s", got, want)
	}
}

// TestCrawlStopsAtTimeout: past cfg.timeout no new contact - and no new
// level - starts, what was found is still written, and the nodes the crawl
// didn't get to keep their state.
func TestCrawlStopsAtTimeout(t *testing.T) {
	seeds := []*fakeServer{startFakeServer(t, true), startFakeServer(t, true), startFakeServer(t, true)}
	var listed []peer.AddrInfo
	for _, s := range seeds {
		listed = append(listed, s.info())
	}
	dir := t.TempDir()
	writeFile(t, dir, nodesFile, p2pnode.FormatNodeList(listed))
	runCrawl(t, dir, testConfig(t)) // all three tracked, streak 1

	// Now each answers slowly, and they all know a server nobody has met.
	newcomer := startFakeServer(t, true)
	for _, s := range seeds {
		s.knows(newcomer)
		s.mu.Lock()
		s.delay = 300 * time.Millisecond
		s.mu.Unlock()
	}
	cfg := testConfig(t)
	cfg.parallelism = 1
	cfg.timeout = 100 * time.Millisecond
	runCrawl(t, dir, cfg) // one seed, then time is up

	state, err := loadState(filepath.Join(dir, stateFile))
	if err != nil {
		t.Fatal(err)
	}
	var streaks []int
	for _, s := range seeds {
		streaks = append(streaks, state.Nodes[s.h.ID().String()].Streak)
	}
	slices.Sort(streaks)
	if fmt.Sprint(streaks) != "[1 1 2]" {
		t.Fatalf("seed streaks %v, want one contacted (2) and two untouched (1)", streaks)
	}
	if state.Nodes[newcomer.h.ID().String()] != nil {
		t.Fatal("a new level started after the timeout")
	}
}

// TestCrawlSharesBudgetBetweenNetworks: a live server listing a flood of
// made-up peers that sort first by id - unreachable, each on a network of its
// own - can't spend the contact budget that is left once the seeds are done:
// the newcomer an honest server names still gets its turn.
func TestCrawlSharesBudgetBetweenNetworks(t *testing.T) {
	var keys []crypto.PrivKey
	for i := 0; i < 21; i++ {
		priv, _, err := crypto.GenerateEd25519Key(rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		keys = append(keys, priv)
	}
	idOf := func(priv crypto.PrivKey) peer.ID {
		id, err := peer.IDFromPrivateKey(priv)
		if err != nil {
			t.Fatal(err)
		}
		return id
	}
	slices.SortFunc(keys, func(a, b crypto.PrivKey) int { return cmp.Compare(idOf(a), idOf(b)) })
	newcomer := startFakeServer(t, true, libp2p.Identity(keys[len(keys)-1])) // the highest id
	var flood []peer.AddrInfo
	for i, priv := range keys[:len(keys)-1] {
		flood = append(flood, peer.AddrInfo{ID: idOf(priv), Addrs: []ma.Multiaddr{ma.StringCast(fmt.Sprintf("/ip4/127.0.0.%d/tcp/1", i+2))}})
	}

	liar, honest := startFakeServer(t, true), startFakeServer(t, true)
	liar.mu.Lock()
	liar.list = flood
	liar.mu.Unlock()
	honest.knows(newcomer)

	dir := t.TempDir()
	writeFile(t, dir, nodesFile, p2pnode.FormatNodeList([]peer.AddrInfo{liar.info(), honest.info()}))
	cfg := testConfig(t)
	cfg.maxContacts = 4 // the two seeds, then two of the 21 nodes they name
	runCrawl(t, dir, cfg)
	if !strings.Contains(readFile(t, dir, stateFile), newcomer.h.ID().String()) {
		t.Fatal("the flood took the whole budget: the newcomer wasn't contacted")
	}
}

func TestCrawlHonoursMaxLevels(t *testing.T) {
	a, b, c := startFakeServer(t, true), startFakeServer(t, true), startFakeServer(t, true)
	a.knows(b)
	b.knows(c)
	dir := t.TempDir()
	writeFile(t, dir, nodesFile, p2pnode.FormatNodeList([]peer.AddrInfo{a.info()}))
	cfg := testConfig(t)
	cfg.maxLevels = 2
	runCrawl(t, dir, cfg)
	state := readFile(t, dir, stateFile)
	if !strings.Contains(state, b.h.ID().String()) || strings.Contains(state, c.h.ID().String()) {
		t.Fatalf("want A and B crawled, C beyond the level limit:\n%s", state)
	}
}
