// Command nodecrawl maintains FRAD's official node list (nodes/nodes.txt in
// this repository). It crawls the network of FRAD servers breadth-first over
// the node-list protocol (node.NodesProtocolID), starting from the current
// list, nodes/pinned.txt and the servers it remembers in nodes/crawl-state.json,
// checks each server the way cmd/bootstrap does, and rewrites the list with
// the pinned entries plus every server seen alive in at least qualifyingStreak
// consecutive runs - diversity-capped, banned entries (nodes/banned.txt)
// excluded. The same inputs always produce byte-identical output, so the
// scheduled workflow (.github/workflows/nodes-crawl.yml) only commits real
// changes. See nodes/README.md for the trust model behind these rules.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"time"

	"github.com/libp2p/go-libp2p/core/peer"

	p2pnode "frad.local/p2p-go/node"
)

func main() {
	dir := flag.String("dir", "nodes", "directory holding nodes.txt, pinned.txt, banned.txt and crawl-state.json")
	maxContacts := flag.Int("max-contacts", 200, "most nodes contacted in one run")
	nodeTimeout := flag.Duration("node-timeout", 10*time.Second, "time allowed per node")
	parallelism := flag.Int("parallelism", 16, "nodes contacted at once")
	allowPrivate := flag.Bool("allow-private-addrs", false, "accept private/loopback addresses - only for LAN test networks")
	flag.Parse()

	cfg := crawlConfig{
		maxContacts:  *maxContacts,
		nodeTimeout:  *nodeTimeout,
		parallelism:  *parallelism,
		allowPrivate: *allowPrivate,
		logf:         log.Printf,
	}
	if err := run(context.Background(), *dir, cfg); err != nil {
		fmt.Fprintln(os.Stderr, "nodecrawl:", err)
		os.Exit(1)
	}
}

// run does one crawl: read dir, crawl, write nodes.txt and crawl-state.json.
func run(ctx context.Context, dir string, cfg crawlConfig) error {
	in, err := loadInputs(dir, cfg.allowPrivate)
	if err != nil {
		return err
	}

	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	h, err := newCrawlHost(ctx, cfg.allowPrivate)
	if err != nil {
		return err
	}
	defer h.Close()

	seeds := seedOrder(in.pinned, in.current, in.state.tracked(cfg.allowPrivate))
	mine := p2pnode.MergeNodeLists(p2pnode.MaxNodeListEntries, in.pinned, in.current)
	results := crawl(ctx, h, seeds, mine, in.banned, cfg)

	in.state.update(results)
	listed := selectListed(in.state.qualified(cfg.allowPrivate), in.pinned, in.current, in.banned, maxListedEntries)
	cfg.logf("contacted %d nodes, %d alive; listing %d nodes (%d pinned)",
		len(results), countAlive(results), len(listed), len(in.pinned))
	return writeOutputs(dir, listed, in.state)
}

func countAlive(results map[peer.ID]observation) int {
	n := 0
	for _, obs := range results {
		if obs.alive {
			n++
		}
	}
	return n
}

func writeOutputs(dir string, listed []peer.AddrInfo, state crawlState) error {
	stateJSON, err := state.marshal()
	if err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(dir, stateFile), stateJSON, 0o644); err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, nodesFile), []byte(renderNodeList(listed)), 0o644)
}
