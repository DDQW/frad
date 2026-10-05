package main

import (
	"crypto/rand"
	"fmt"
	mrand "math/rand/v2"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/peer"
	ma "github.com/multiformats/go-multiaddr"
)

func randomID(t testing.TB) peer.ID {
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

func node(t testing.TB, addrs ...string) peer.AddrInfo {
	info := peer.AddrInfo{ID: randomID(t)}
	for _, a := range addrs {
		info.Addrs = append(info.Addrs, ma.StringCast(a))
	}
	return info
}

func alive(info peer.AddrInfo) observation { return observation{alive: true, addrs: info.Addrs} }

func TestStateUpdateTransitions(t *testing.T) {
	s := newCrawlState()
	a := node(t, "/ip4/93.184.216.1/tcp/4001")
	b := node(t, "/ip4/198.51.100.1/tcp/4001")
	never := node(t, "/ip4/203.0.113.1/tcp/4001")
	streak := func(info peer.AddrInfo) int {
		if ns := s.Nodes[info.ID.String()]; ns != nil {
			return ns.Streak
		}
		return -1 // untracked
	}

	for run := 1; run <= 4; run++ {
		s.update(map[peer.ID]observation{a.ID: alive(a), b.ID: alive(b), never.ID: {}})
		if want := min(run, qualifyingStreak); streak(a) != want || streak(b) != want {
			t.Fatalf("run %d: streaks %d/%d, want %d (saturating)", run, streak(a), streak(b), want)
		}
	}
	if streak(never) != -1 {
		t.Fatal("a node never seen alive must not be tracked")
	}

	// One missed check: a qualified node keeps its place...
	s.update(map[peer.ID]observation{a.ID: {}})
	if streak(a) != qualifyingStreak || !s.Nodes[a.ID.String()].Missed {
		t.Fatal("a listed node must survive one missed check")
	}
	// ...and recovers fully when it answers again.
	s.update(map[peer.ID]observation{a.ID: alive(a)})
	if streak(a) != qualifyingStreak || s.Nodes[a.ID.String()].Missed {
		t.Fatal("answering again must clear the miss")
	}
	// Two in a row: forgotten.
	s.update(map[peer.ID]observation{a.ID: {}})
	s.update(map[peer.ID]observation{a.ID: {}})
	if streak(a) != -1 {
		t.Fatal("two missed checks in a row must forget the node")
	}

	// A node not yet qualified must be seen in consecutive runs.
	c := node(t, "/ip4/93.184.217.1/tcp/4001")
	s.update(map[peer.ID]observation{c.ID: alive(c)})
	s.update(map[peer.ID]observation{c.ID: alive(c)})
	s.update(map[peer.ID]observation{c.ID: {}})
	if streak(c) != -1 {
		t.Fatal("a miss before qualifying must reset the node")
	}

	// Nodes not contacted in a run (budget exhausted) keep their state.
	before := *s.Nodes[b.ID.String()]
	s.update(map[peer.ID]observation{})
	if after := *s.Nodes[b.ID.String()]; after.Streak != before.Streak || after.Missed != before.Missed {
		t.Fatal("an uncontacted node's state changed")
	}
}

func TestStateIsStableWhenNothingChanges(t *testing.T) {
	s := newCrawlState()
	a := node(t, "/ip4/93.184.216.1/udp/4001/quic-v1", "/ip4/93.184.216.1/tcp/4001")
	var snapshots []string
	for run := 0; run < 5; run++ {
		s.update(map[peer.ID]observation{a.ID: alive(a)})
		data, err := s.marshal()
		if err != nil {
			t.Fatal(err)
		}
		snapshots = append(snapshots, string(data))
	}
	if snapshots[2] != snapshots[3] || snapshots[3] != snapshots[4] {
		t.Fatalf("state keeps changing once a node is qualified:\n%s\n%s", snapshots[2], snapshots[3])
	}
	reloaded, err := parseState([]byte(snapshots[4]))
	if err != nil {
		t.Fatal(err)
	}
	if again, _ := reloaded.marshal(); string(again) != snapshots[4] {
		t.Fatal("state doesn't round-trip through JSON")
	}
}

func TestStateTrimKeepsMostEstablished(t *testing.T) {
	s := newCrawlState()
	veteran := node(t, "/ip4/93.184.216.1/tcp/4001")
	s.update(map[peer.ID]observation{veteran.ID: alive(veteran)})
	s.update(map[peer.ID]observation{veteran.ID: alive(veteran)})
	results := map[peer.ID]observation{}
	for i := 0; i < maxTrackedNodes+10; i++ {
		n := node(t, fmt.Sprintf("/ip4/93.%d.%d.1/tcp/4001", 1+i/200, i%200))
		results[n.ID] = alive(n)
	}
	s.update(results)
	if len(s.Nodes) != maxTrackedNodes || s.Nodes[veteran.ID.String()] == nil {
		t.Fatalf("tracking %d nodes (veteran kept: %v), want %d", len(s.Nodes), s.Nodes[veteran.ID.String()] != nil, maxTrackedNodes)
	}
}

func TestSelectListedCapsAndPriorities(t *testing.T) {
	sameNet := []peer.AddrInfo{
		node(t, "/ip4/93.184.216.1/tcp/4001"), node(t, "/ip4/93.184.216.2/tcp/4001"), node(t, "/ip4/93.184.216.3/tcp/4001"),
	}
	listed := selectListed(sameNet, nil, nil, banList{}, maxListedEntries)
	if len(listed) != 2 {
		t.Fatalf("listed %d nodes from one /24, want 2", len(listed))
	}

	// Pinned entries are always listed and take their network's share first.
	pinned := []peer.AddrInfo{
		node(t, "/ip4/198.51.100.1/tcp/4001"), node(t, "/ip4/198.51.100.2/tcp/4001"), node(t, "/ip4/198.51.100.3/tcp/4001"),
	}
	rival := node(t, "/ip4/198.51.100.9/tcp/4001")
	listed = selectListed([]peer.AddrInfo{rival}, pinned, nil, banList{}, maxListedEntries)
	if ids := peer.AddrInfosToIDs(listed); len(ids) != 3 || slices.Contains(ids, rival.ID) {
		t.Fatalf("got %v, want exactly the three pinned entries", ids)
	}

	// Incumbents beat newcomers when space runs out.
	var qualified []peer.AddrInfo
	for i := 0; i < 5; i++ {
		qualified = append(qualified, node(t, fmt.Sprintf("/ip4/93.%d.0.1/tcp/4001", i+1)))
	}
	sortByID(qualified)
	incumbent := qualified[4] // last by id: would lose without incumbency
	listed = selectListed(qualified, nil, []peer.AddrInfo{incumbent}, banList{}, 2)
	if ids := peer.AddrInfosToIDs(listed); len(ids) != 2 || ids[0] != incumbent.ID || ids[1] != qualified[0].ID {
		t.Fatalf("got %v, want the incumbent first, then the lowest id", ids)
	}

	// The size limit counts addresses.
	var many []peer.AddrInfo
	for i := 0; i < 80; i++ {
		many = append(many, node(t, fmt.Sprintf("/ip4/93.%d.%d.1/tcp/4001", 100+i/200, i), fmt.Sprintf("/ip4/93.%d.%d.1/udp/4001/quic-v1", 100+i/200, i)))
	}
	if n := len(strings.Fields(renderBody(selectListed(many, nil, nil, banList{}, maxListedEntries)))); n != maxListedEntries {
		t.Fatalf("listed %d addresses, want %d", n, maxListedEntries)
	}
}

func renderBody(infos []peer.AddrInfo) string {
	return strings.TrimPrefix(renderNodeList(infos), nodesHeader)
}

func TestSelectListedExcludesBanned(t *testing.T) {
	byID := node(t, "/ip4/93.184.216.1/tcp/4001")
	byAddr := node(t, "/ip4/198.51.100.7/tcp/4001", "/ip6/2001:db8::7/tcp/4001")
	byFullAddr := node(t, "/ip4/203.0.113.7/tcp/4001")
	pinnedButBanned := node(t, "/ip4/93.184.217.1/tcp/4001")
	fine := node(t, "/ip4/93.184.218.1/tcp/4001")

	path := filepath.Join(t.TempDir(), bannedFile)
	content := strings.Join([]string{
		"# banned",
		byID.ID.String(),
		"/ip4/198.51.100.7", // every port on that IP
		"/ip4/203.0.113.7/tcp/4001/p2p/" + byFullAddr.ID.String(),
		pinnedButBanned.ID.String(),
	}, "\n")
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	banned, err := readBanned(path)
	if err != nil {
		t.Fatal(err)
	}

	listed := selectListed([]peer.AddrInfo{byID, byAddr, byFullAddr, fine}, []peer.AddrInfo{pinnedButBanned}, nil, banned, maxListedEntries)
	got := renderBody(listed)
	want := "/ip4/93.184.218.1/tcp/4001/p2p/" + fine.ID.String() + "\n" +
		"/ip6/2001:db8::7/tcp/4001/p2p/" + byAddr.ID.String() + "\n"
	if got != want {
		t.Fatalf("listed:\n%s\nwant:\n%s", got, want)
	}
}

func TestSelectionIsDeterministic(t *testing.T) {
	s := newCrawlState()
	results := map[peer.ID]observation{}
	var current []peer.AddrInfo
	for i := 0; i < 60; i++ {
		n := node(t, fmt.Sprintf("/ip4/93.184.%d.1/tcp/4001", i%40), fmt.Sprintf("/ip6/2a01:4f8:%x::1/udp/4001/quic-v1", i))
		results[n.ID] = alive(n)
		if i%3 == 0 {
			current = append(current, n)
		}
	}
	for run := 0; run < qualifyingStreak; run++ {
		s.update(results)
	}
	want := renderNodeList(selectListed(s.qualified(false), nil, current, banList{}, maxListedEntries))
	for i := 0; i < 10; i++ {
		shuffled := slices.Clone(current)
		mrand.Shuffle(len(shuffled), func(i, j int) { shuffled[i], shuffled[j] = shuffled[j], shuffled[i] })
		if got := renderNodeList(selectListed(s.qualified(false), nil, shuffled, banList{}, maxListedEntries)); got != want {
			t.Fatal("the same inputs produced a different nodes.txt")
		}
	}
}

func TestReadPinnedAndBannedRejectTypos(t *testing.T) {
	dir := t.TempDir()
	write := func(name, content string) string {
		path := filepath.Join(dir, name)
		if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
		return path
	}
	id := randomID(t).String()

	if pinned, err := readPinned(write("p1", "# ok\n/dns4/node.example.org/tcp/4001/p2p/"+id+"\n"), false); err != nil || len(pinned) != 1 {
		t.Fatalf("valid pinned file: %v %v", pinned, err)
	}
	for _, bad := range []string{
		"/dns4/node.example.org/tcp/4001",              // no peer id
		"/ip4/192.168.1.1/tcp/4001/p2p/" + id,          // private
		"/ip4/93.184.216.1/tcp/4001/p2p/" + id + " xx", // trailing junk
	} {
		if _, err := readPinned(write("p2", bad), false); err == nil {
			t.Errorf("pinned %q: expected an error", bad)
		}
	}
	if _, err := readBanned(write("b1", "certainly not an id")); err == nil {
		t.Error("expected an error for a malformed banned.txt line")
	}
	if pinned, err := readPinned(filepath.Join(dir, "absent"), false); err != nil || pinned != nil {
		t.Error("a missing pinned.txt must mean no pinned entries")
	}
}

func TestSeedOrder(t *testing.T) {
	p, c, tr := node(t, "/ip4/93.184.216.1/tcp/4001"), node(t, "/ip4/93.184.216.2/tcp/4001"), node(t, "/ip4/93.184.216.3/tcp/4001")
	// The same tracked node again under a second address, plus the current
	// node seen at a new address.
	moved := peer.AddrInfo{ID: c.ID, Addrs: []ma.Multiaddr{ma.StringCast("/ip4/93.184.216.9/tcp/4001")}}
	seeds := seedOrder([]peer.AddrInfo{p}, []peer.AddrInfo{c}, []peer.AddrInfo{tr, moved})

	first := []peer.AddrInfo{p, c}
	sortByID(first)
	if ids := peer.AddrInfosToIDs(seeds); len(ids) != 3 || ids[0] != first[0].ID || ids[1] != first[1].ID || ids[2] != tr.ID {
		t.Fatalf("seed order %v, want pinned+current by id, then tracked", ids)
	}
	for _, s := range seeds {
		if s.ID == c.ID && len(s.Addrs) != 2 {
			t.Fatalf("duplicate entries weren't merged: %v", s)
		}
	}
}

func TestRenderNodeList(t *testing.T) {
	a := node(t, "/ip4/93.184.216.1/tcp/4001", "/dns4/a.example.org/tcp/4001")
	got := renderNodeList([]peer.AddrInfo{a})
	want := nodesHeader +
		"/dns4/a.example.org/tcp/4001/p2p/" + a.ID.String() + "\n" +
		"/ip4/93.184.216.1/tcp/4001/p2p/" + a.ID.String() + "\n"
	if got != want {
		t.Fatalf("got:\n%s\nwant:\n%s", got, want)
	}
	for _, line := range strings.Split(nodesHeader, "\n") {
		if line != "" && !strings.HasPrefix(line, "#") {
			t.Fatalf("header line %q isn't a comment", line)
		}
	}
}

func TestParseState(t *testing.T) {
	s, err := parseState(nil)
	if err != nil || s.Version != 1 || s.Nodes == nil {
		t.Fatalf("empty state: %+v %v", s, err)
	}
	s, err = parseState([]byte(`{"version":1,"nodes":{"x":null}}`))
	if err != nil || len(s.Nodes) != 0 {
		t.Fatalf("null entries must be dropped: %+v %v", s, err)
	}
	if _, err := parseState([]byte("{")); err == nil {
		t.Fatal("expected an error for broken JSON")
	}
	// A hand-edited entry with a private address is ignored, not listed.
	id := randomID(t).String()
	s, _ = parseState([]byte(`{"version":1,"nodes":{"` + id + `":{"addrs":["/ip4/10.0.0.1/tcp/4001"],"streak":3}}}`))
	if q := s.qualified(false); len(q) != 0 {
		t.Fatalf("private address from the state file listed: %v", q)
	}
}
