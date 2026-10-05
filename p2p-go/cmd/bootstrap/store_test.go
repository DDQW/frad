package main

import (
	"crypto/rand"
	"fmt"
	"os"
	"path/filepath"
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
	st.addCandidates([]peer.AddrInfo{
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
		st.addCandidates([]peer.AddrInfo{{ID: randomID(t), Addrs: addrs(fmt.Sprintf("/ip4/198.51.%d.1/tcp/4001", i))}})
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
	st.addCandidates([]peer.AddrInfo{{ID: knownIDs[0], Addrs: moved}})
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
