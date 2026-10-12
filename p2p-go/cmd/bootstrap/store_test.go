package main

import (
	"crypto/rand"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

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

func addrs(s ...string) []ma.Multiaddr {
	var out []ma.Multiaddr
	for _, a := range s {
		out = append(out, ma.StringCast(a))
	}
	return out
}

func TestStoreSaveLoadAndExpiry(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nodes.txt")
	self, fresh, stale := randomID(t), randomID(t), randomID(t)
	now := time.Date(2026, 10, 5, 12, 0, 0, 0, time.UTC)

	st := newNodeStore(path, self, 7*24*time.Hour, false)
	st.markAlive(fresh, addrs("/ip4/93.184.216.1/tcp/4001", "/ip4/93.184.216.1/udp/4001/quic-v1"), now.Add(-time.Hour))
	st.markAlive(stale, addrs("/ip4/198.51.100.1/tcp/4001"), now.Add(-8*24*time.Hour))
	st.markAlive(self, addrs("/ip4/203.0.113.1/tcp/4001"), now) // never lists itself
	if err := st.save(); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	if !strings.HasPrefix(text, "# ") || strings.Count(text, fresh.String()) != 2 || strings.Contains(text, self.String()) {
		t.Fatalf("unexpected nodes file:\n%s", text)
	}

	// Add junk a human might have left behind; loading must shrug it off.
	text += "garbage line\n/ip4/10.0.0.1/tcp/4001/p2p/" + randomID(t).String() + " " + now.Format(time.RFC3339) + "\n"
	if err := os.WriteFile(path, []byte(text), 0o644); err != nil {
		t.Fatal(err)
	}
	loaded := newNodeStore(path, self, 7*24*time.Hour, false)
	if err := loaded.load(now); err != nil {
		t.Fatal(err)
	}
	alive := loaded.alive()
	if len(alive) != 1 || alive[0].ID != fresh || len(alive[0].Addrs) != 2 {
		t.Fatalf("loaded %v, want only the fresh node with both addresses", alive)
	}
	if !loaded.known[fresh].lastSeen.Equal(now.Add(-time.Hour)) {
		t.Fatalf("last-seen time not preserved: %v", loaded.known[fresh].lastSeen)
	}

	loaded.prune(now.Add(7 * 24 * time.Hour))
	if len(loaded.alive()) != 0 {
		t.Fatal("prune kept a node unseen for over a week")
	}

	missing := newNodeStore(filepath.Join(t.TempDir(), "absent.txt"), self, time.Hour, false)
	if err := missing.load(now); err != nil {
		t.Fatalf("a missing nodes file must not be an error: %v", err)
	}
}

func TestStoreCandidatesBecomeKnownOnlyWhenAlive(t *testing.T) {
	self, a, b := randomID(t), randomID(t), randomID(t)
	st := newNodeStore(filepath.Join(t.TempDir(), "n.txt"), self, time.Hour, false)
	st.addCandidates(fromPushed, "test", []peer.AddrInfo{
		{ID: self, Addrs: addrs("/ip4/203.0.113.1/tcp/4001")},
		{ID: a, Addrs: addrs("/ip4/93.184.216.1/tcp/4001")},
		{ID: b, Addrs: addrs("/ip4/198.51.100.1/tcp/4001")},
	})
	if st.isCandidate(self) || !st.isCandidate(a) || !st.isCandidate(b) || st.isKnown(a) {
		t.Fatal("candidates must exclude this node itself and not count as known")
	}
	st.markAlive(a, addrs("/ip4/93.184.216.1/tcp/4001"), time.Now())
	st.markFailed(b)
	if st.isCandidate(a) || !st.isKnown(a) || st.isCandidate(b) || st.isKnown(b) {
		t.Fatal("markAlive must promote, markFailed must drop")
	}
}

func TestStorePickMixesKnownAndCandidates(t *testing.T) {
	st := newNodeStore(filepath.Join(t.TempDir(), "n.txt"), randomID(t), time.Hour, false)
	var knownIDs []peer.ID
	for i := 0; i < 10; i++ {
		id := randomID(t)
		knownIDs = append(knownIDs, id)
		st.markAlive(id, addrs(fmt.Sprintf("/ip4/93.184.%d.1/tcp/4001", i)), time.Now())
		st.addCandidates(fromCurated, "test", []peer.AddrInfo{{ID: randomID(t), Addrs: addrs(fmt.Sprintf("/ip4/198.51.%d.1/tcp/4001", i))}})
	}
	picked := st.pick(8)
	known := 0
	for _, p := range picked {
		if st.isKnown(p.ID) {
			known++
		}
	}
	if len(picked) != 8 || known != 4 {
		t.Fatalf("picked %d (%d known), want 8 split evenly", len(picked), known)
	}

	// A candidate claiming a known id (a server that moved) is tried under
	// that known entry, at both addresses.
	moved := addrs("/ip4/203.0.113.77/tcp/4001")
	st.addCandidates(fromPushed, "test", []peer.AddrInfo{{ID: knownIDs[0], Addrs: moved}})
	for _, p := range st.pick(100) {
		if p.ID == knownIDs[0] && (len(p.Addrs) != 2 || !p.Addrs[0].Equal(moved[0])) {
			t.Fatalf("moved node picked with %v", p.Addrs)
		}
	}
	if n := len(st.pick(100)); n != 20 {
		t.Fatalf("pick(100) returned %d of 20 nodes", n)
	}
}

func TestStoreEvictsLeastRecentlySeen(t *testing.T) {
	st := newNodeStore(filepath.Join(t.TempDir(), "n.txt"), randomID(t), 30*24*time.Hour, false)
	start := time.Now()
	oldest := randomID(t)
	st.markAlive(oldest, addrs("/ip4/93.184.216.1/tcp/4001"), start.Add(-time.Hour))
	for i := 0; i < maxKnownNodes; i++ {
		st.markAlive(randomID(t), addrs(fmt.Sprintf("/ip4/93.%d.%d.1/tcp/4001", i/250, i%250)), start)
	}
	if len(st.known) != maxKnownNodes || st.isKnown(oldest) {
		t.Fatalf("%d known (oldest kept: %v), want %d without the oldest", len(st.known), st.isKnown(oldest), maxKnownNodes)
	}
}

// TestStoreCapsKnownNodesPerNetwork: many verified peer ids on one network
// (one machine, one rented block) count as at most two known servers, and
// established ones keep their places.
func TestStoreCapsKnownNodesPerNetwork(t *testing.T) {
	st := newNodeStore(filepath.Join(t.TempDir(), "n.txt"), randomID(t), 7*24*time.Hour, false)
	now := time.Now()
	first, second, third := randomID(t), randomID(t), randomID(t)
	st.markAlive(first, addrs("/ip4/93.184.216.1/tcp/4001"), now.Add(-2*time.Hour))
	st.markAlive(second, addrs("/ip4/93.184.216.2/tcp/4001"), now.Add(-time.Hour))
	if got := st.markAlive(third, addrs("/ip4/93.184.216.3/tcp/4001"), now); got != nil || st.isKnown(third) {
		t.Fatal("a third server on one /24 was admitted")
	}
	// Its address on another network still counts, alone.
	got := st.markAlive(third, addrs("/ip4/93.184.216.3/tcp/4001", "/ip6/2a01:4f8:1::3/tcp/4001"), now)
	if len(got) != 1 || !got[0].Equal(ma.StringCast("/ip6/2a01:4f8:1::3/tcp/4001")) {
		t.Fatalf("admitted %v, want only the IPv6 address", got)
	}
	// A known server is re-verified against the others, not against itself.
	if got := st.markAlive(first, addrs("/ip4/93.184.216.1/tcp/4001"), now); len(got) != 1 {
		t.Fatal("re-verifying an established server failed")
	}

	// Loading enforces the cap too, keeping the most recently seen.
	path := filepath.Join(t.TempDir(), "nodes.txt")
	var file strings.Builder
	ids := []peer.ID{randomID(t), randomID(t), randomID(t)}
	for i, id := range ids {
		fmt.Fprintf(&file, "/ip4/198.51.100.%d/tcp/4001/p2p/%s %s\n", i+1, id, now.Add(-time.Duration(i)*time.Hour).UTC().Format(time.RFC3339))
	}
	if err := os.WriteFile(path, []byte(file.String()), 0o644); err != nil {
		t.Fatal(err)
	}
	loaded := newNodeStore(path, randomID(t), 7*24*time.Hour, false)
	if err := loaded.load(now); err != nil {
		t.Fatal(err)
	}
	if !loaded.isKnown(ids[0]) || !loaded.isKnown(ids[1]) || loaded.isKnown(ids[2]) {
		t.Fatalf("loaded %v, want the two most recently seen", loaded.alive())
	}
}

// TestStoreCandidateSourcesDontCrowdEachOtherOut: a peer pushing junk as fast
// as it likes neither displaces the official list's or verified servers'
// candidates nor holds more than its share of the pushed queue, and every
// source gets checked each round.
func TestStoreCandidateSourcesDontCrowdEachOtherOut(t *testing.T) {
	st := newNodeStore(filepath.Join(t.TempDir(), "n.txt"), randomID(t), time.Hour, false)
	official := []peer.AddrInfo{{ID: randomID(t), Addrs: addrs("/ip4/198.51.100.1/tcp/4001")}}
	fromServer := []peer.AddrInfo{{ID: randomID(t), Addrs: addrs("/ip4/198.51.101.1/tcp/4001")}}
	st.addCandidates(fromCurated, "official", official)
	st.addCandidates(fromServers, "203.0.113.0/24", fromServer)

	junk := func() []peer.AddrInfo {
		var list []peer.AddrInfo
		for i := 0; i < 64; i++ {
			list = append(list, peer.AddrInfo{ID: randomID(t), Addrs: addrs(fmt.Sprintf("/ip4/1.0.%d.1/tcp/4001", i))})
		}
		return list
	}
	var lastPush []peer.AddrInfo
	for i := 0; i < 20; i++ {
		lastPush = junk()
		st.addCandidates(fromPushed, "192.0.2.0/24", lastPush)
	}
	if !st.isCandidate(official[0].ID) || !st.isCandidate(fromServer[0].ID) {
		t.Fatal("pushed junk displaced curated or server candidates")
	}
	if n := len(st.candidates[fromPushed].infos); n != maxCandidatesPerVia {
		t.Fatalf("one network holds %d pushed candidates, want %d", n, maxCandidatesPerVia)
	}
	if !st.isCandidate(lastPush[0].ID) || st.isCandidate(lastPush[maxCandidatesPerVia].ID) {
		t.Fatal("a network's latest push must replace its earlier ones, up to its share")
	}
	// Another network's push still gets in.
	phone := []peer.AddrInfo{{ID: randomID(t), Addrs: addrs("/ip4/198.51.102.1/tcp/4001")}}
	st.addCandidates(fromPushed, "203.0.114.0/24", phone)
	if !st.isCandidate(phone[0].ID) {
		t.Fatal("a second network's push was refused")
	}

	picked := peer.AddrInfosToIDs(st.pick(3))
	for _, want := range []peer.ID{official[0].ID, fromServer[0].ID} {
		if !slices.Contains(picked, want) {
			t.Fatalf("pick %v skipped a source's only candidate %s", picked, want)
		}
	}
}
