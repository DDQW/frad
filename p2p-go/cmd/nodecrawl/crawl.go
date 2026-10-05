package main

import (
	"context"
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
	nodeTimeout  time.Duration // per node: connect, identify, list exchange
	parallelism  int           // nodes contacted at once
	allowPrivate bool          // tests and LAN networks only
	logf         func(format string, args ...any)
}

// newCrawlHost builds the crawler's libp2p host: it only dials out, never to a
// private address (the node lists it follows are untrusted), and records what
// nodes report about themselves (see p2pnode.VerifiedNodeAddrs).
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
// crawler, these are this repository's own records, so they aren't
// diversity-capped; duplicates are merged into their first occurrence.
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
// runs out of nodes or has contacted cfg.maxContacts of them. Each level is
// contacted in parallel but processed in order, and every later level is
// built from the merged, diversity-capped, sorted lists of the one before -
// so the same network always yields the same walk. mine is what the crawler
// shares in each exchange (the current official list), so servers hear about
// it too.
func crawl(ctx context.Context, h host.Host, seeds, mine []peer.AddrInfo, banned banList, cfg crawlConfig) map[peer.ID]observation {
	results := make(map[peer.ID]observation)
	level := banned.filter(seeds)
	for len(level) > 0 && len(results) < cfg.maxContacts {
		level = level[:min(len(level), cfg.maxContacts-len(results))]
		observed, learned := contactAll(ctx, h, level, mine, cfg)
		var heard []peer.AddrInfo
		for i, info := range level {
			results[info.ID] = observed[i]
			heard = append(heard, learned[i]...)
		}
		level = nextLevel(heard, results, banned)
	}
	return results
}

// nextLevel merges what was heard into one diversity-capped list, minus nodes
// already contacted and banned ones, sorted by peer id.
func nextLevel(heard []peer.AddrInfo, contacted map[peer.ID]observation, banned banList) []peer.AddrInfo {
	merged := p2pnode.MergeNodeLists(math.MaxInt, banned.filter(heard))
	merged = slices.DeleteFunc(merged, func(info peer.AddrInfo) bool {
		_, done := contacted[info.ID]
		return done
	})
	sortByID(merged)
	return merged
}

func contactAll(ctx context.Context, h host.Host, level, mine []peer.AddrInfo, cfg crawlConfig) ([]observation, [][]peer.AddrInfo) {
	observed := make([]observation, len(level))
	learned := make([][]peer.AddrInfo, len(level))
	slots := make(chan struct{}, max(cfg.parallelism, 1))
	var wg sync.WaitGroup
	for i, info := range level {
		wg.Go(func() {
			slots <- struct{}{}
			defer func() { <-slots }()
			observed[i], learned[i] = contact(ctx, h, info, mine, cfg)
		})
	}
	wg.Wait()
	return observed, learned
}

// contact checks one node the same way cmd/bootstrap does - reachable, and
// identify shows FRAD's DHT in server mode - and, if it passes, swaps node
// lists with it. Lists from anything else aren't followed.
func contact(ctx context.Context, h host.Host, info peer.AddrInfo, mine []peer.AddrInfo, cfg crawlConfig) (observation, []peer.AddrInfo) {
	ctx, cancel := context.WithTimeout(ctx, cfg.nodeTimeout)
	defer cancel()
	// One connection per node and run: nothing piles up over 200 contacts,
	// and every run judges each node afresh.
	defer func() { _ = h.Network().ClosePeer(info.ID) }()

	if err := h.Connect(ctx, info); err != nil || !p2pnode.IsFradServer(h, info.ID) {
		return observation{}, nil
	}
	theirs, err := p2pnode.ExchangeNodeList(ctx, h, info.ID, mine, cfg.allowPrivate)
	if err != nil {
		cfg.logf("%s: node list exchange failed: %v", info.ID, err)
	}
	// A FRAD server with no address worth listing (e.g. behind NAT without
	// -announce) is still worth hearing from, just not worth listing.
	addrs := p2pnode.AwaitVerifiedNodeAddrs(ctx, h, info.ID, cfg.allowPrivate)
	if len(addrs) == 0 {
		return observation{}, theirs
	}
	return observation{alive: true, addrs: addrs}, theirs
}
