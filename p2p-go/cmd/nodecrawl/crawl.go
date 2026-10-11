package main

import (
	"cmp"
	"context"
	"maps"
	"math"
	"slices"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/peer"
	ma "github.com/multiformats/go-multiaddr"

	p2pnode "frad.local/p2p-go/node"
)

type crawlConfig struct {
	maxContacts  int           // nodes contacted per run, at most
	maxLevels    int           // breadth-first levels per run, the seeds included
	nodeTimeout  time.Duration // per node: connect, identify, list exchange, dial-backs
	timeout      time.Duration // the whole crawl; see crawl
	parallelism  int           // nodes contacted at once
	allowPrivate bool          // tests and LAN networks only
	logf         func(format string, args ...any)
}

// newCrawlHost builds the crawler's libp2p host: it only dials out, never to a
// private address (the node lists it follows are untrusted), and records what
// nodes report about themselves, for p2pnode.VerifyNodeAddrs to prove.
func newCrawlHost(ctx context.Context, allowPrivate bool) (host.Host, error) {
	h, err := libp2p.New(libp2p.NoListenAddrs, libp2p.ConnectionGater(p2pnode.PublicDialGater(allowPrivate)))
	if err != nil {
		return nil, err
	}
	if err := p2pnode.RecordSelfReportedAddrs(ctx, h); err != nil {
		_ = h.Close()
		return nil, err
	}
	return h, nil
}

// seedOrder lists the nodes a run checks first, in priority order: every
// pinned and currently listed node (so each listed node is re-checked every
// single run - the budget always covers them), then the nodes still working
// towards qualifying, in the order given. Unlike what the network tells the
// crawler, these are this repository's own records - the list and the state
// were diversity-capped when written (selectListed, crawlState.trim), the
// pinned entries are the maintainers' choice - so they aren't capped again
// here; duplicates are merged into their first occurrence.
func seedOrder(pinned, current, tracked []peer.AddrInfo) []peer.AddrInfo {
	priority := append(slices.Clone(pinned), current...)
	sortByID(priority)
	var seeds []peer.AddrInfo
	index := make(map[peer.ID]int)
	for _, info := range append(priority, tracked...) {
		i, seen := index[info.ID]
		if !seen {
			index[info.ID] = len(seeds)
			seeds = append(seeds, peer.AddrInfo{ID: info.ID})
			i = len(seeds) - 1
		}
		for _, addr := range info.Addrs {
			if !ma.Contains(seeds[i].Addrs, addr) {
				seeds[i].Addrs = append(seeds[i].Addrs, addr)
			}
		}
	}
	return seeds
}

// crawl walks the FRAD server network breadth-first from seeds (see
// seedOrder), following the node lists of every node found alive, until it
// runs out of nodes, levels (cfg.maxLevels) or contacts (cfg.maxContacts), or
// cfg.timeout has passed. Each level is contacted in parallel but processed
// in order, and every later level is built from the lists heard in the one
// before (see nextLevel) - so the same network always yields the same walk.
// mine is what the crawler shares in each exchange (the current official
// list), so servers hear about it too.
//
// The timeout is what guarantees results get written at all: a chain of
// hostile nodes, each stalling its answer to just under cfg.nodeTimeout and
// naming only the next, could otherwise keep one level after another busy
// past the workflow's own time limit, run after run. Once it has passed no
// new contact starts; those in flight finish (they are bounded by
// cfg.nodeTimeout). Nodes never contacted aren't in the result, so they keep
// their state (see crawlState.update).
func crawl(ctx context.Context, h host.Host, seeds, mine []peer.AddrInfo, banned banList, cfg crawlConfig) map[peer.ID]observation {
	deadline := time.Now().Add(cfg.timeout)
	results := make(map[peer.ID]observation)
	level := banned.filter(seeds)
	for depth := 0; depth < cfg.maxLevels && len(level) > 0; depth++ {
		if len(results) >= cfg.maxContacts || time.Now().After(deadline) {
			break
		}
		level = level[:min(len(level), cfg.maxContacts-len(results))]
		var heard []heardList
		for _, r := range contactAll(ctx, h, level, mine, deadline, cfg) {
			if !r.contacted {
				continue
			}
			results[r.id] = r.obs
			if r.obs.alive && len(r.learned) > 0 {
				heard = append(heard, heardList{via: sourceNetwork(r.obs.addrs), infos: r.learned})
			}
		}
		level = nextLevel(heard, results, banned)
	}
	if time.Now().After(deadline) {
		cfg.logf("crawl stopped after %s; nodes not reached keep their state", cfg.timeout)
	}
	return results
}

// heardList is the node list one contacted node answered with, and the
// network that node was verified on (see sourceNetwork).
type heardList struct {
	via   string
	infos []peer.AddrInfo
}

// sourceNetwork is the network a live node's list is credited to in
// nextLevel: the lowest p2pnode.NetworkKey among the addresses it was
// verified at - the same one every run, unlike the address a dial happened
// to succeed on first.
func sourceNetwork(verified []ma.Multiaddr) string {
	var keys []string
	for _, addr := range verified {
		keys = append(keys, p2pnode.NetworkKey(addr))
	}
	return slices.Min(keys)
}

// nextLevel builds the next level from the lists heard in the last one: minus
// banned nodes and nodes already contacted, diversity-capped, and in the
// order the contact budget is spent in.
//
// The servers found alive so far take their networks' places first, at the
// addresses they were verified at, so a network that has already shown its
// share of servers gets no more contacts: one machine running a chain of
// peer ids, each listing the next, can't spend the run's budget on itself.
//
// Nor can a list full of made-up entries, each on a network of its own: what
// was heard is grouped by the network of the nodes that said it, and the
// groups take turns - one new peer from each, then the next from each - so
// every network that answered gets the same share of what is left of the
// budget, however much it lists and however low the peer ids it picks. Each
// group is in peer id order and the groups in network order, so the walk
// stays deterministic.
func nextLevel(heard []heardList, contacted map[peer.ID]observation, banned banList) []peer.AddrInfo {
	groups := make(map[string][]peer.AddrInfo)
	for _, l := range heard {
		fresh := slices.DeleteFunc(banned.filter(l.infos), func(info peer.AddrInfo) bool {
			_, done := contacted[info.ID]
			return done
		})
		groups[l.via] = append(groups[l.via], fresh...)
	}
	var turns [][]peer.AddrInfo
	for _, via := range slices.Sorted(maps.Keys(groups)) {
		group := groups[via]
		slices.SortStableFunc(group, func(a, b peer.AddrInfo) int { return cmp.Compare(a.ID, b.ID) })
		turns = append(turns, group)
	}
	merged := p2pnode.MergeNodeLists(math.MaxInt, aliveNodes(contacted), takeTurns(turns))
	return slices.DeleteFunc(merged, func(info peer.AddrInfo) bool {
		_, done := contacted[info.ID]
		return done
	})
}

// takeTurns flattens groups by taking one peer from each in turn, then the
// next from each, and so on. A peer an earlier group already gave doesn't use
// up a group's turn; its entry is still passed on, for its addresses.
func takeTurns(groups [][]peer.AddrInfo) []peer.AddrInfo {
	var out []peer.AddrInfo
	taken := make(map[peer.ID]bool)
	for left := true; left; {
		left = false
		for i := range groups {
			for len(groups[i]) > 0 {
				info := groups[i][0]
				groups[i] = groups[i][1:]
				out = append(out, info)
				if !taken[info.ID] {
					taken[info.ID] = true
					break
				}
			}
			left = left || len(groups[i]) > 0
		}
	}
	return out
}

// aliveNodes lists the nodes results found alive, at their verified
// addresses, sorted by peer id.
func aliveNodes(results map[peer.ID]observation) []peer.AddrInfo {
	var alive []peer.AddrInfo
	for id, obs := range results {
		if obs.alive {
			alive = append(alive, peer.AddrInfo{ID: id, Addrs: obs.addrs})
		}
	}
	sortByID(alive)
	return alive
}

// contactResult is one node's outcome in contactAll. contacted is false for
// a node whose check never started (the deadline had passed) or was cut
// short (ctx ended): neither says anything about the node.
type contactResult struct {
	id        peer.ID
	contacted bool
	obs       observation
	learned   []peer.AddrInfo
}

func contactAll(ctx context.Context, h host.Host, level, mine []peer.AddrInfo, deadline time.Time, cfg crawlConfig) []contactResult {
	results := make([]contactResult, len(level))
	slots := make(chan struct{}, max(cfg.parallelism, 1))
	var wg sync.WaitGroup
	for i, info := range level {
		results[i].id = info.ID
		wg.Go(func() {
			slots <- struct{}{}
			defer func() { <-slots }()
			if time.Now().After(deadline) {
				return
			}
			obs, learned := contact(ctx, h, info, mine, cfg)
			results[i] = contactResult{id: info.ID, contacted: ctx.Err() == nil, obs: obs, learned: learned}
		})
	}
	wg.Wait()
	return results
}

// contact checks one node the same way cmd/bootstrap does - reachable, and
// identify shows FRAD's DHT in server mode - and, if it passes, swaps node
// lists with it. The node is recorded at the addresses it has been proven
// reachable at (p2pnode.VerifyNodeAddrs), never at those it merely claims.
// Only the lists of nodes found alive are followed: nextLevel shares the
// budget out by the network each list's sender was verified on.
func contact(ctx context.Context, h host.Host, info peer.AddrInfo, mine []peer.AddrInfo, cfg crawlConfig) (observation, []peer.AddrInfo) {
	ctx, cancel := context.WithTimeout(ctx, cfg.nodeTimeout)
	defer cancel()
	// One connection per node and run: nothing piles up over 200 contacts,
	// and every run judges each node afresh.
	defer func() { _ = h.Network().ClosePeer(info.ID) }()

	// Every contact dials afresh, and Connect waits for identify when it
	// dials, so IsFradServer can be asked straight away.
	if err := h.Connect(ctx, info); err != nil || !p2pnode.IsFradServer(h, info.ID) {
		return observation{}, nil
	}
	theirs, err := p2pnode.ExchangeNodeList(ctx, h, info.ID, mine, cfg.allowPrivate)
	if err != nil {
		cfg.logf("%s: node list exchange failed: %v", info.ID, err)
	}
	// Empty only if the node wasn't reached by a direct dial to a listable
	// address (e.g. through a relay) and answers at none it claims: not
	// listed, and with no network to credit its list to, not followed.
	addrs := p2pnode.VerifyNodeAddrs(ctx, h, info.ID, cfg.allowPrivate)
	if len(addrs) == 0 {
		return observation{}, nil
	}
	return observation{alive: true, addrs: addrs}, theirs
}
