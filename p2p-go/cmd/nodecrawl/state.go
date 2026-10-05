package main

import (
	"cmp"
	"encoding/json"
	"fmt"
	"math"
	"slices"

	"github.com/libp2p/go-libp2p/core/peer"
	ma "github.com/multiformats/go-multiaddr"

	p2pnode "frad.local/p2p-go/node"
)

const (
	// qualifyingStreak is how many consecutive runs (6 hours apart) a node
	// must be seen alive before it is listed. Running a server for a day is
	// cheap but not free, and it filters out the short-lived nodes a list
	// poisoning attempt would otherwise get listed straight away.
	qualifyingStreak = 3
	// maxListedEntries caps nodes.txt (lines, not counting pinned entries).
	maxListedEntries = 100
	// maxTrackedNodes bounds crawl-state.json. Every tracked node is a seed of
	// the next run, so this stays well under the default contact budget (200)
	// to leave room for discovering nodes nobody tracks yet.
	maxTrackedNodes = 150
)

// crawlState is crawl-state.json: what the crawler remembers between runs. It
// deliberately holds no timestamps or run counters, so a run that sees the
// same network as the last one writes byte-identical files and the workflow
// has nothing to commit.
type crawlState struct {
	Version int                   `json:"version"`
	Nodes   map[string]*nodeState `json:"nodes"` // by peer id
}

type nodeState struct {
	Addrs []string `json:"addrs"`
	// Streak counts consecutive runs the node was seen alive, saturating at
	// qualifyingStreak (counting further would only churn the file).
	Streak int `json:"streak"`
	// Missed is set when a listed node failed one check: it keeps its place
	// for that one run, so a restart during the crawl doesn't delist it.
	Missed bool `json:"missed,omitempty"`
}

// observation is what contacting one node showed.
type observation struct {
	alive bool
	addrs []ma.Multiaddr // what the node reported for itself, if alive
}

func newCrawlState() crawlState {
	return crawlState{Version: 1, Nodes: make(map[string]*nodeState)}
}

// update applies one run's observations. Nodes that weren't contacted this
// run (the crawl budget ran out) keep their state unchanged.
func (s *crawlState) update(results map[peer.ID]observation) {
	for id, obs := range results {
		key := id.String()
		ns := s.Nodes[key]
		switch {
		case obs.alive:
			if ns == nil {
				ns = &nodeState{}
				s.Nodes[key] = ns
			}
			ns.Addrs = addrStrings(obs.addrs)
			ns.Streak = min(ns.Streak+1, qualifyingStreak)
			ns.Missed = false
		case ns == nil:
			// Never seen alive: nothing worth remembering.
		case ns.Streak >= qualifyingStreak && !ns.Missed:
			ns.Missed = true
		default:
			// Not yet qualified (the streak must be consecutive), or a second
			// failure in a row: forget it. It can always be rediscovered.
			delete(s.Nodes, key)
		}
	}
	s.trim()
}

// trim keeps the maxTrackedNodes most established entries.
func (s *crawlState) trim() {
	keys := s.keysByPriority()
	if len(keys) <= maxTrackedNodes {
		return
	}
	for _, k := range keys[maxTrackedNodes:] {
		delete(s.Nodes, k)
	}
}

// keysByPriority orders the tracked nodes most established (longest streak)
// first, then by peer id.
func (s *crawlState) keysByPriority() []string {
	keys := make([]string, 0, len(s.Nodes))
	for k := range s.Nodes {
		keys = append(keys, k)
	}
	slices.SortFunc(keys, func(a, b string) int {
		return cmp.Or(cmp.Compare(s.Nodes[b].Streak, s.Nodes[a].Streak), cmp.Compare(a, b))
	})
	return keys
}

// tracked returns the state's nodes as seeds for the next crawl, most
// established first.
func (s *crawlState) tracked(allowPrivate bool) []peer.AddrInfo {
	return s.infos(s.keysByPriority(), allowPrivate)
}

// qualified returns the nodes eligible for listing, sorted by id.
func (s *crawlState) qualified(allowPrivate bool) []peer.AddrInfo {
	var keys []string
	for k, ns := range s.Nodes {
		if ns.Streak >= qualifyingStreak {
			keys = append(keys, k)
		}
	}
	out := s.infos(keys, allowPrivate)
	sortByID(out)
	return out
}

// infos converts the entries for keys back to AddrInfos, in order,
// re-validating every address - the state file is in a public repository and
// could have been edited by hand.
func (s *crawlState) infos(keys []string, allowPrivate bool) []peer.AddrInfo {
	var out []peer.AddrInfo
	for _, key := range keys {
		id, err := peer.Decode(key)
		if err != nil {
			continue
		}
		var addrs []ma.Multiaddr
		for _, a := range s.Nodes[key].Addrs {
			if addr, err := ma.NewMultiaddr(a); err == nil && p2pnode.CheckNodeAddr(addr, allowPrivate) == nil {
				addrs = append(addrs, addr)
			}
		}
		if len(addrs) > 0 {
			out = append(out, peer.AddrInfo{ID: id, Addrs: addrs})
		}
	}
	return out
}

func sortByID(infos []peer.AddrInfo) {
	slices.SortFunc(infos, func(a, b peer.AddrInfo) int { return cmp.Compare(a.ID, b.ID) })
}

func addrStrings(addrs []ma.Multiaddr) []string {
	out := make([]string, 0, len(addrs))
	for _, a := range addrs {
		out = append(out, a.String())
	}
	slices.Sort(out)
	return slices.Compact(out)
}

// selectListed decides the next nodes.txt. Pinned entries are always listed.
// The rest are qualified nodes, minus banned ones, in priority order: nodes
// already on the current list first (incumbency makes flooding the list with
// fresh nodes pointless), then newcomers, each group sorted by peer id so the
// choice is deterministic. p2pnode.MergeNodeLists applies the per-network
// diversity cap, with pinned entries taking their share first, and at most
// limit addresses besides the pinned ones make it in.
func selectListed(qualified, pinned, current []peer.AddrInfo, banned banList, limit int) []peer.AddrInfo {
	pinned = banned.filter(pinned)
	isPinned := make(map[peer.ID]bool, len(pinned))
	for _, p := range pinned {
		isPinned[p.ID] = true
	}
	isCurrent := make(map[peer.ID]bool, len(current))
	for _, c := range current {
		isCurrent[c.ID] = true
	}

	var incumbents, newcomers []peer.AddrInfo
	for _, q := range banned.filter(qualified) {
		switch {
		case isPinned[q.ID]:
		case isCurrent[q.ID]:
			incumbents = append(incumbents, q)
		default:
			newcomers = append(newcomers, q)
		}
	}
	sortByID(incumbents)
	sortByID(newcomers)

	listed := slices.Clone(pinned)
	budget := limit
	for _, m := range p2pnode.MergeNodeLists(math.MaxInt, pinned, incumbents, newcomers) {
		if isPinned[m.ID] || budget == 0 {
			continue
		}
		m.Addrs = m.Addrs[:min(len(m.Addrs), budget)]
		budget -= len(m.Addrs)
		listed = append(listed, m)
	}
	return listed
}

func (s crawlState) marshal() ([]byte, error) {
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return nil, err
	}
	return append(data, '\n'), nil
}

func parseState(data []byte) (crawlState, error) {
	s := newCrawlState()
	if len(data) == 0 {
		return s, nil
	}
	if err := json.Unmarshal(data, &s); err != nil {
		return s, fmt.Errorf("parsing crawl state: %w", err)
	}
	if s.Nodes == nil {
		s.Nodes = make(map[string]*nodeState)
	}
	for key, ns := range s.Nodes {
		if ns == nil {
			delete(s.Nodes, key)
		}
	}
	return s, nil
}

func loadState(path string) (crawlState, error) {
	text, err := readOptional(path)
	if err != nil {
		return newCrawlState(), err
	}
	return parseState([]byte(text))
}
