// Command bootstrap runs a standalone FRAD wide-range bootstrap + relay node.
// It never joins any rendezvous topic and never advertises/finds chat peers —
// it exists so FRAD's isolated Kademlia DHT (see node.ProtocolPrefix) has
// somewhere to bootstrap from, and so peers behind NAT have a circuit-relay v2
// relay to fall back to. Anyone can run this (see ../../README.md); share the
// multiaddr it prints with users who want it in their (user-configurable)
// bootstrap/relay node list.
//
// Independently operated nodes federate into one network instead of forming
// islands: -peer names other nodes to stay connected to and to seed the DHT
// with, and every node keeps a list of the other FRAD servers it has verified
// (-nodes-file), swaps that list with them and with every app that asks (see
// node.NodesProtocolID), and picks up the official list published in this
// repository (-official-list-url, see nodes/README.md). Losing any one source
// of server addresses - including the official list - doesn't lose the
// network.
//
// This binary is a separately-run operator tool: building/deploying/hosting
// an actual instance of it is out of scope for the app changes in this repo.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/relay"
	ma "github.com/multiformats/go-multiaddr"
)

// defaultOfficialListURLs are mirrors of nodes/nodes.txt in this repository,
// tried in order: jsDelivr keeps serving the file if raw.githubusercontent.com
// is blocked or down, and the Codeberg and GitLab copies (pushed by the
// crawler workflow) outlive GitHub altogether.
const defaultOfficialListURLs = "https://raw.githubusercontent.com/DDQW/frad/master/nodes/nodes.txt," +
	"https://cdn.jsdelivr.net/gh/DDQW/frad@master/nodes/nodes.txt," +
	"https://codeberg.org/DDQW/frad-nodes/raw/branch/main/nodes.txt," +
	"https://gitlab.com/DDQW/frad-nodes/-/raw/main/nodes.txt"

func main() {
	cfg, err := parseFlags(os.Args[1:])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	priv, err := loadOrCreateIdentity(cfg.keyFile)
	if err != nil {
		fmt.Fprintln(os.Stderr, "identity error:", err)
		os.Exit(1)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	s, err := newServer(cfg, priv)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	defer s.close()

	s.printReady()
	s.run(ctx)
}

// stringsFlag is a repeatable string flag.
type stringsFlag []string

func (f *stringsFlag) String() string     { return strings.Join(*f, ",") }
func (f *stringsFlag) Set(v string) error { *f = append(*f, v); return nil }

func parseFlags(args []string) (config, error) {
	fs := flag.NewFlagSet("bootstrap", flag.ContinueOnError)
	port := fs.Int("port", 4001, "TCP/QUIC listen port (IPv4 and IPv6)")
	keyFile := fs.String("identity-key-file", "bootstrap_identity.key",
		"path to persist this node's identity so its multiaddr/peer id stays stable across restarts")
	// libp2p's own relay defaults (2 minutes, 128 KiB per direction) are sized for a
	// short hole-punching handshake, not for carrying a FRAD chat - a relayed chat
	// would drop after two minutes and a file transfer (up to 25 MB) could never
	// finish. These defaults fit one chat session plus a maximum-size file.
	maxDuration := fs.Duration("relay-max-duration", time.Hour,
		"maximum lifetime of one relayed connection")
	maxData := fs.Int64("relay-max-data", 160<<20,
		"maximum bytes relayed per connection and direction")
	unlimited := fs.Bool("relay-unlimited", false,
		"relay without any per-connection time/data limit (overrides the two flags above)")
	var peers, announce stringsFlag
	fs.Var(&peers, "peer",
		"multiaddr (with /p2p/<peerid>) of another bootstrap node to federate with; repeatable")
	fs.Var(&announce, "announce",
		"public multiaddr (without /p2p) to advertise instead of the detected ones, e.g. on a NAT'd cloud VM; repeatable")
	nodesFile := fs.String("nodes-file", "",
		"where to persist verified FRAD servers (default: nodes.txt next to -identity-key-file)")
	officialURLs := fs.String("official-list-url", defaultOfficialListURLs,
		"comma-separated mirrors of the official node list, tried in order; empty disables")
	allowPrivate := fs.Bool("allow-private-addrs", false,
		"accept and dial private/loopback addresses in node lists - only for LAN test networks")
	if err := fs.Parse(args); err != nil {
		return config{}, err
	}

	cfg := config{
		listenAddrs: []string{
			fmt.Sprintf("/ip4/0.0.0.0/tcp/%d", *port),
			fmt.Sprintf("/ip4/0.0.0.0/udp/%d/quic-v1", *port),
			fmt.Sprintf("/ip6/::/tcp/%d", *port),
			fmt.Sprintf("/ip6/::/udp/%d/quic-v1", *port),
		},
		keyFile:      *keyFile,
		nodesFile:    *nodesFile,
		officialURLs: splitList(*officialURLs),
		relayOpts:    []relay.Option{relay.WithLimit(&relay.RelayLimit{Duration: *maxDuration, Data: *maxData})},
		allowPrivate: *allowPrivate,
		timing:       defaultTiming,
		logf:         log.Printf,
	}
	if *unlimited {
		cfg.relayOpts = []relay.Option{relay.WithInfiniteLimits()}
	}
	if cfg.nodesFile == "" {
		cfg.nodesFile = filepath.Join(filepath.Dir(*keyFile), "nodes.txt")
	}
	var err error
	if cfg.federation, err = parsePeers(peers); err != nil {
		return config{}, err
	}
	if cfg.announce, err = parseAnnounce(announce); err != nil {
		return config{}, err
	}
	return cfg, nil
}

func splitList(s string) []string {
	var out []string
	for _, item := range strings.Split(s, ",") {
		if item = strings.TrimSpace(item); item != "" {
			out = append(out, item)
		}
	}
	return out
}

// parsePeers parses -peer values, grouping several addresses of one peer.
func parsePeers(values []string) ([]peer.AddrInfo, error) {
	var infos []peer.AddrInfo
	for _, v := range values {
		addr, err := ma.NewMultiaddr(v)
		if err != nil {
			return nil, fmt.Errorf("-peer %q: %w", v, err)
		}
		info, err := peer.AddrInfoFromP2pAddr(addr)
		if err != nil {
			return nil, fmt.Errorf("-peer %q needs a trailing /p2p/<peerid>: %w", v, err)
		}
		infos = appendAddrInfo(infos, *info)
	}
	return infos, nil
}

func appendAddrInfo(infos []peer.AddrInfo, info peer.AddrInfo) []peer.AddrInfo {
	for i := range infos {
		if infos[i].ID == info.ID {
			infos[i].Addrs = append(infos[i].Addrs, info.Addrs...)
			return infos
		}
	}
	return append(infos, info)
}

func parseAnnounce(values []string) ([]ma.Multiaddr, error) {
	var addrs []ma.Multiaddr
	for _, v := range values {
		addr, err := ma.NewMultiaddr(v)
		if err != nil {
			return nil, fmt.Errorf("-announce %q: %w", v, err)
		}
		if _, id := peer.SplitAddr(addr); id != "" {
			return nil, errors.New("-announce takes addresses without /p2p/<peerid>; the node adds its own")
		}
		addrs = append(addrs, addr)
	}
	return addrs, nil
}
