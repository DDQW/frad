package main

import (
	"cmp"
	"errors"
	"fmt"
	"io/fs"
	"maps"
	"math"
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
	// which an attacker has to actually run - and at most 2 of them per
	// network count (see admissibleLocked) - so this only needs to be generous
	// enough for a large healthy network.
	maxKnownNodes = 512
	// maxCandidatesPerSource bounds the unverified addresses waiting for a
	// check, per candidateSource. These cost an attacker nothing, so the caps
	// (and the diversity limits p2pnode.MergeNodeLists applies) are what keep
	// them from piling up.
	maxCandidatesPerSource = 128
	// maxCandidatesPerVia bounds how many of a source's queued candidates one
	// network may have contributed (see addCandidates).
	maxCandidatesPerVia = 16
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
	candidates [numCandidateSources]candidateQueue
	dirty      bool
}

// candidateSource is where a candidate came from. Each source has its own
// bounded queue and pick draws from all of them in turn, so flooding one -
// anyone at all can push lists - can't crowd out the others: whatever
// happens to the pushed queue, the official list and verified servers' lists
// still get checked.
type candidateSource int

const (
	// fromCurated: -peer and the official list, chosen by people.
	fromCurated candidateSource = iota
	// fromServers: lists from verified servers, and servers this node has
	// dialed (in practice, through the DHT).
	fromServers
	// fromPushed: lists pushed by anyone else over the node-list protocol, and
	// servers that connected to this node.
	fromPushed
	numCandidateSources
)

// candidateQueue is one source's candidates, newest first, with the network
// each entry came from (see addCandidates).
type candidateQueue struct {
	infos []peer.AddrInfo
	via   map[peer.ID]string
}

type knownNode struct {
	addrs    []ma.Multiaddr
	lastSeen time.Time
}

func newNodeStore(path string, self peer.ID, ttl time.Duration, allowPrivate bool) *nodeStore {
	st := &nodeStore{
		path: path, self: self, ttl: ttl, allowPrivate: allowPrivate,
		known: make(map[peer.ID]knownNode),
	}
	for i := range st.candidates {
		st.candidates[i].via = make(map[peer.ID]string)
	}
	return st
}

// load reads the persisted known nodes, skipping comments, malformed lines and
// entries older than ttl. A missing file just means a fresh node. Entries are
// admitted like fresh sightings, most recent first, so the diversity cap holds
// whatever the file says.
func (st *nodeStore) load(now time.Time) error {
	data, err := os.ReadFile(st.path)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	loaded := make(map[peer.ID]knownNode)
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
			mergeKnown(loaded, info, lastSeen)
		}
	}

	st.mu.Lock()
	defer st.mu.Unlock()
	for _, info := range byLastSeen(loaded) {
		if info.ID == st.self {
			continue
		}
		if addrs := st.admissibleLocked(info.ID, info.Addrs); len(addrs) > 0 {
			st.known[info.ID] = knownNode{addrs: addrs, lastSeen: loaded[info.ID].lastSeen}
		}
	}
	st.evictOverflowLocked()
	return nil
}

// mergeKnown adds info's addresses to its entry in m, as loaded from disk
// where one peer spans several lines.
func mergeKnown(m map[peer.ID]knownNode, info peer.AddrInfo, lastSeen time.Time) {
	k := m[info.ID]
	for _, addr := range info.Addrs {
		if !ma.Contains(k.addrs, addr) {
			k.addrs = append(k.addrs, addr)
		}
	}
	if lastSeen.After(k.lastSeen) {
		k.lastSeen = lastSeen
	}
	m[info.ID] = k
}

// byLastSeen lists m most recently seen first, peer id breaking ties.
func byLastSeen(m map[peer.ID]knownNode) []peer.AddrInfo {
	list := make([]peer.AddrInfo, 0, len(m))
	for id, k := range m {
		list = append(list, peer.AddrInfo{ID: id, Addrs: slices.Clone(k.addrs)})
	}
	slices.SortFunc(list, func(a, b peer.AddrInfo) int {
		return cmp.Or(m[b.ID].lastSeen.Compare(m[a.ID].lastSeen), cmp.Compare(a.ID, b.ID))
	})
	return list
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

// markAlive records id as a verified server reachable at addrs (as proven by
// p2pnode.VerifyNodeAddrs), as of now. It returns the addresses it accepted:
// none if id's networks already hold their share of other known servers.
func (st *nodeStore) markAlive(id peer.ID, addrs []ma.Multiaddr, now time.Time) []ma.Multiaddr {
	if id == st.self || len(addrs) == 0 {
		return nil
	}
	st.mu.Lock()
	defer st.mu.Unlock()
	addrs = st.admissibleLocked(id, addrs)
	if len(addrs) == 0 {
		return nil
	}
	prev, existed := st.known[id]
	st.known[id] = knownNode{addrs: addrs, lastSeen: now}
	st.removeCandidateLocked(id)
	st.evictOverflowLocked()
	// Federation peers are re-sighted often; a week-long expiry doesn't need
	// the file rewritten that often.
	if !existed || !slices.EqualFunc(prev.addrs, addrs, ma.Multiaddr.Equal) ||
		now.Sub(prev.lastSeen) >= lastSeenPersistGranularity {
		st.dirty = true
	}
	return addrs
}

// admissibleLocked returns the addresses of id that fit the per-network
// diversity cap (p2pnode.MergeNodeLists) next to every other known node -
// which keep their places: once a network holds its share of servers, more
// peer ids there, however fresh their sightings, can't push those out, nor
// fill this store and evict servers elsewhere.
func (st *nodeStore) admissibleLocked(id peer.ID, addrs []ma.Multiaddr) []ma.Multiaddr {
	others := slices.DeleteFunc(byLastSeen(st.known), func(k peer.AddrInfo) bool { return k.ID == id })
	for _, m := range p2pnode.MergeNodeLists(math.MaxInt, others, []peer.AddrInfo{{ID: id, Addrs: addrs}}) {
		if m.ID == id {
			return m.Addrs
		}
	}
	return nil
}

// seenSince reports whether id is a known node last seen at or after t.
func (st *nodeStore) seenSince(id peer.ID, t time.Time) bool {
	st.mu.Lock()
	defer st.mu.Unlock()
	k, ok := st.known[id]
	return ok && !k.lastSeen.Before(t)
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
	for i := range st.candidates {
		st.candidates[i].remove(id)
	}
}

func (q *candidateQueue) remove(id peer.ID) {
	q.infos = slices.DeleteFunc(q.infos, func(c peer.AddrInfo) bool { return c.ID == id })
	delete(q.via, id)
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

// addCandidates queues infos (already validated) from src for a liveness
// check. Newer entries go first, so a full queue sheds its oldest ones rather
// than refusing new ones. via names the network the list came from (see
// p2pnode.NetworkKey); outside fromCurated, each via may hold at most
// maxCandidatesPerVia entries of a queue, and its newest list replaces what
// it sent before - so one machine, however many peer ids it runs and however
// often it pushes, keeps a fixed small share and can't flush the queue.
func (st *nodeStore) addCandidates(src candidateSource, via string, infos []peer.AddrInfo) {
	infos = slices.DeleteFunc(slices.Clone(infos), func(i peer.AddrInfo) bool { return i.ID == st.self })
	st.mu.Lock()
	defer st.mu.Unlock()
	q := &st.candidates[src]
	if src != fromCurated {
		infos = infos[:min(len(infos), maxCandidatesPerVia)]
		q.infos = slices.DeleteFunc(q.infos, func(c peer.AddrInfo) bool { return q.via[c.ID] == via })
	}
	for _, info := range infos {
		q.via[info.ID] = via
	}
	q.infos = p2pnode.MergeNodeLists(maxCandidatesPerSource, infos, q.infos)
	queued := make(map[peer.ID]bool, len(q.infos))
	for _, info := range q.infos {
		queued[info.ID] = true
	}
	maps.DeleteFunc(q.via, func(id peer.ID, _ string) bool { return !queued[id] })
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
// up from either side when the other runs short. Candidates are drawn from
// each source in turn (see candidateSource). A candidate claiming a known
// peer id - a server that moved - is merged into that known entry, so its new
// address gets tried too.
func (st *nodeStore) pick(n int) []peer.AddrInfo {
	st.mu.Lock()
	known := st.knownListLocked()
	var fresh [numCandidateSources][]peer.AddrInfo
	numFresh := 0
	for src, q := range st.candidates {
		for _, c := range q.infos {
			if i := slices.IndexFunc(known, func(k peer.AddrInfo) bool { return k.ID == c.ID }); i >= 0 {
				known[i].Addrs = append(slices.Clone(c.Addrs), known[i].Addrs...)
				continue
			}
			fresh[src] = append(fresh[src], c)
			numFresh++
		}
	}
	st.mu.Unlock()

	rand.Shuffle(len(known), func(i, j int) { known[i], known[j] = known[j], known[i] })
	for _, list := range fresh {
		rand.Shuffle(len(list), func(i, j int) { list[i], list[j] = list[j], list[i] })
	}
	takeKnown := min(len(known), max(n/2, n-numFresh))
	return append(known[:takeKnown:takeKnown], interleave(fresh[:], n-takeKnown)...)
}

// interleave takes up to n entries from lists in turn - one from each, then
// the next from each - skipping peer ids already taken.
func interleave(lists [][]peer.AddrInfo, n int) []peer.AddrInfo {
	var out []peer.AddrInfo
	taken := make(map[peer.ID]bool)
	for progress := true; progress && len(out) < n; {
		progress = false
		for i := range lists {
			for len(lists[i]) > 0 && len(out) < n {
				c := lists[i][0]
				lists[i] = lists[i][1:]
				if !taken[c.ID] {
					taken[c.ID] = true
					out = append(out, c)
					progress = true
					break
				}
			}
		}
	}
	return out
}

// alive lists the known nodes, most recently seen first.
func (st *nodeStore) alive() []peer.AddrInfo {
	st.mu.Lock()
	defer st.mu.Unlock()
	return byLastSeen(st.known)
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
	for _, q := range st.candidates {
		if slices.ContainsFunc(q.infos, func(c peer.AddrInfo) bool { return c.ID == id }) {
			return true
		}
	}
	return false
}
