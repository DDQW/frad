package main

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"

	p2pnode "frad.local/p2p-go/node"
)

// fakeServer is a loopback FRAD server as far as the crawler can tell: a
// mode-Server /frad DHT plus the node-list protocol, serving whatever list
// the test sets. With fradDHT false it is just some libp2p node that speaks
// the node-list protocol - which must not get listed.
type fakeServer struct {
	h    host.Host
	mu   sync.Mutex
	list []peer.AddrInfo
}

func startFakeServer(t *testing.T, fradDHT bool) *fakeServer {
	t.Helper()
	h, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
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
	defer s.mu.Unlock()
	return s.list
}

func (s *fakeServer) knows(others ...*fakeServer) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.list = nil
	for _, o := range others {
		s.list = append(s.list, o.info())
	}
}

func (s *fakeServer) info() peer.AddrInfo { return peer.AddrInfo{ID: s.h.ID(), Addrs: s.h.Addrs()} }

func testConfig(t *testing.T) crawlConfig {
	return crawlConfig{maxContacts: 200, nodeTimeout: 10 * time.Second, parallelism: 4, allowPrivate: true, logf: t.Logf}
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
