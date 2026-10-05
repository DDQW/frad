package main

import (
	"cmp"
	"errors"
	"fmt"
	"io/fs"
	"math/rand/v2"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p/core/peer"
	ma "github.com/multiformats/go-multiaddr"

	p2pnode "frad.local/p2p-go/node"
)

const (
	// maxKnownNodes bounds the persisted list. Each entry is a verified server,
	// which an attacker has to actually run, so this only needs to be generous
	// enough for a large healthy network.
	maxKnownNodes = 512
	// maxCandidateEntries bounds unverified addresses waiting for a check -
	// these cost an attacker nothing, so the cap (and the diversity limits
	// p2pnode.MergeNodeLists applies) is what keeps them from piling up.
	maxCandidateEntries = 256
	// lastSeenPersistGranularity is how stale the persisted last-seen time of
	// an otherwise unchanged node may get before a sighting rewrites the file.
	lastSeenPersistGranularity = time.Hour
)

const nodesFileHeader = `# FRAD bootstrap node: other FRAD servers this node has verified (reachable and
# running FRAD's DHT in server mode), with when each was last seen alive.
# Format: <multiaddr>/p2p/<peerid> <last seen, RFC 3339>. Entries not seen for
# 7 days are dropped. Maintained by the node itself; safe to delete.
`

// nodeStore is this server's view of the FRAD server network. Known nodes have
// passed a liveness check and are persisted with when that last happened;
// candidates are addresses heard from elsewhere - the official list, gossip,
// apps pushing their lists - that nobody here has checked yet. Only known
// nodes are ever handed on to others.
type nodeStore struct {
	path         string
	self         peer.ID
	ttl          time.Duration
	allowPrivate bool

	mu         sync.Mutex
	known      map[peer.ID]knownNode
	candidates []peer.AddrInfo
	dirty      bool
}

type knownNode struct {
	addrs    []ma.Multiaddr
	lastSeen time.Time
}

func newNodeStore(path string, self peer.ID, ttl time.Duration, allowPrivate bool) *nodeStore {
	return &nodeStore{
		path: path, self: self, ttl: ttl, allowPrivate: allowPrivate,
		known: make(map[peer.ID]knownNode),
	}
}

// load reads the persisted known nodes, skipping comments, malformed lines and
// entries older than ttl. A missing file just means a fresh node.
func (st *nodeStore) load(now time.Time) error {
	data, err := os.ReadFile(st.path)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	st.mu.Lock()
	defer st.mu.Unlock()
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Fields(line)
		if len(fields) != 2 || strings.HasPrefix(fields[0], "#") {
			continue
		}
		lastSeen, err := time.Parse(time.RFC3339, fields[1])
		if err != nil || now.Sub(lastSeen) > st.ttl {
			continue
		}
		for _, info := range p2pnode.ParseNodeList(fields[0], st.allowPrivate) {
			st.mergeKnownLocked(info, lastSeen)
		}
	}
	return nil
}

// mergeKnownLocked adds info's addresses to its known entry, as loaded from
// disk where one peer spans several lines.
func (st *nodeStore) mergeKnownLocked(info peer.AddrInfo, lastSeen time.Time) {
	if info.ID == st.self {
		return
	}
	k := st.known[info.ID]
	for _, addr := range info.Addrs {
		if !ma.Contains(k.addrs, addr) {
			k.addrs = append(k.addrs, addr)
		}
	}
	if lastSeen.After(k.lastSeen) {
		k.lastSeen = lastSeen
	}
	st.known[info.ID] = k
}

// save persists the known nodes if anything changed since the last save.
func (st *nodeStore) save() error {
	st.mu.Lock()
	if !st.dirty {
		st.mu.Unlock()
		return nil
	}
	text := st.formatLocked()
	st.dirty = false
	st.mu.Unlock()

	if err := writeFileAtomic(st.path, []byte(text)); err != nil {
		st.mu.Lock()
		st.dirty = true
		st.mu.Unlock()
		return err
	}
	return nil
}

func (st *nodeStore) formatLocked() string {
	ids := make([]peer.ID, 0, len(st.known))
	for id := range st.known {
		ids = append(ids, id)
	}
	slices.Sort(ids)
	var sb strings.Builder
	sb.WriteString(nodesFileHeader)
	for _, id := range ids {
		k := st.known[id]
		for _, addr := range k.addrs {
			fmt.Fprintf(&sb, "%s/p2p/%s %s\n", addr, id, k.lastSeen.UTC().Format(time.RFC3339))
		}
	}
	return sb.String()
}

// writeFileAtomic replaces path in one step, so a crash mid-write can't leave
// a truncated list behind.
func writeFileAtomic(path string, data []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), filepath.Base(path)+".*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name()) // no-op once renamed
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Chmod(0o644); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}

// markAlive records id as a verified server reachable at addrs, as of now.
func (st *nodeStore) markAlive(id peer.ID, addrs []ma.Multiaddr, now time.Time) {
	if id == st.self || len(addrs) == 0 {
		return
	}
	st.mu.Lock()
	defer st.mu.Unlock()
	prev, existed := st.known[id]
	st.known[id] = knownNode{addrs: slices.Clone(addrs), lastSeen: now}
	st.removeCandidateLocked(id)
	st.evictOverflowLocked()
	// Federation peers are re-sighted every minute; a week-long expiry doesn't
	// need the file rewritten that often.
	if !existed || !slices.EqualFunc(prev.addrs, addrs, ma.Multiaddr.Equal) ||
		now.Sub(prev.lastSeen) >= lastSeenPersistGranularity {
		st.dirty = true
	}
}

// markFailed drops id from the candidates after a failed check. A known node
// stays known until its last sighting is older than ttl: one missed check is
// no reason to forget a server.
func (st *nodeStore) markFailed(id peer.ID) {
	st.mu.Lock()
	defer st.mu.Unlock()
	st.removeCandidateLocked(id)
}

func (st *nodeStore) removeCandidateLocked(id peer.ID) {
	st.candidates = slices.DeleteFunc(st.candidates, func(c peer.AddrInfo) bool { return c.ID == id })
}

// evictOverflowLocked forgets the least recently seen nodes beyond maxKnownNodes.
func (st *nodeStore) evictOverflowLocked() {
	for len(st.known) > maxKnownNodes {
		var oldest peer.ID
		for id, k := range st.known {
			if oldest == "" || k.lastSeen.Before(st.known[oldest].lastSeen) ||
				(k.lastSeen.Equal(st.known[oldest].lastSeen) && id < oldest) {
				oldest = id
			}
		}
		delete(st.known, oldest)
	}
}

// addCandidates queues infos (already validated) for a liveness check.
// Existing candidates keep their place: new ones only fill free space.
func (st *nodeStore) addCandidates(infos []peer.AddrInfo) {
	infos = slices.DeleteFunc(slices.Clone(infos), func(i peer.AddrInfo) bool { return i.ID == st.self })
	st.mu.Lock()
	defer st.mu.Unlock()
	st.candidates = p2pnode.MergeNodeLists(maxCandidateEntries, st.candidates, infos)
}

// prune forgets known nodes not seen within ttl of now.
func (st *nodeStore) prune(now time.Time) {
	st.mu.Lock()
	defer st.mu.Unlock()
	for id, k := range st.known {
		if now.Sub(k.lastSeen) > st.ttl {
			delete(st.known, id)
			st.dirty = true
		}
	}
}

// pick chooses up to n nodes to check, at random: about half known nodes (to
// refresh their sightings and hear their lists) and half candidates, topping
// up from either side when the other runs short. A candidate claiming a known
// peer id - a server that moved - is merged into that known entry, so its new
// address gets tried too.
func (st *nodeStore) pick(n int) []peer.AddrInfo {
	st.mu.Lock()
	known := st.knownListLocked()
	var fresh []peer.AddrInfo
	for _, c := range st.candidates {
		if i := slices.IndexFunc(known, func(k peer.AddrInfo) bool { return k.ID == c.ID }); i >= 0 {
			known[i].Addrs = append(slices.Clone(c.Addrs), known[i].Addrs...)
			continue
		}
		fresh = append(fresh, c)
	}
	st.mu.Unlock()

	rand.Shuffle(len(known), func(i, j int) { known[i], known[j] = known[j], known[i] })
	rand.Shuffle(len(fresh), func(i, j int) { fresh[i], fresh[j] = fresh[j], fresh[i] })
	takeKnown := min(len(known), max(n/2, n-len(fresh)))
	takeFresh := min(len(fresh), n-takeKnown)
	return append(known[:takeKnown:takeKnown], fresh[:takeFresh]...)
}

// alive lists the known nodes, most recently seen first.
func (st *nodeStore) alive() []peer.AddrInfo {
	st.mu.Lock()
	defer st.mu.Unlock()
	list := st.knownListLocked()
	slices.SortFunc(list, func(a, b peer.AddrInfo) int {
		return cmp.Or(st.known[b.ID].lastSeen.Compare(st.known[a.ID].lastSeen), cmp.Compare(a.ID, b.ID))
	})
	return list
}

func (st *nodeStore) knownListLocked() []peer.AddrInfo {
	list := make([]peer.AddrInfo, 0, len(st.known))
	for id, k := range st.known {
		list = append(list, peer.AddrInfo{ID: id, Addrs: slices.Clone(k.addrs)})
	}
	return list
}

func (st *nodeStore) isKnown(id peer.ID) bool {
	st.mu.Lock()
	defer st.mu.Unlock()
	_, ok := st.known[id]
	return ok
}

func (st *nodeStore) isCandidate(id peer.ID) bool {
	st.mu.Lock()
	defer st.mu.Unlock()
	return slices.ContainsFunc(st.candidates, func(c peer.AddrInfo) bool { return c.ID == id })
}
