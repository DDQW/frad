package main

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"strings"

	"github.com/libp2p/go-libp2p/core/peer"
	ma "github.com/multiformats/go-multiaddr"

	p2pnode "frad.local/p2p-go/node"
)

// File names inside the -dir directory (nodes/ in the repository).
const (
	nodesFile  = "nodes.txt"
	pinnedFile = "pinned.txt"
	bannedFile = "banned.txt"
	stateFile  = "crawl-state.json"
)

const nodesHeader = `# FRAD official node list - see README.md next to this file.
#
# FRAD servers (p2p-go/cmd/bootstrap) the scheduled crawler (p2p-go/cmd/nodecrawl)
# has found alive in at least 3 consecutive runs, plus the maintainers' pinned.txt.
# Generated - don't edit by hand; change pinned.txt or banned.txt instead.
#
# One multiaddr per line, ending in /p2p/<peerid>; lines starting with # are
# comments. Apps and servers treat every entry as a candidate and check it
# themselves before relying on it.
`

// inputs is everything one crawl run starts from.
type inputs struct {
	current []peer.AddrInfo // the nodes.txt being replaced
	pinned  []peer.AddrInfo
	banned  banList
	state   crawlState
}

func loadInputs(dir string, allowPrivate bool) (inputs, error) {
	var in inputs
	var err error
	if in.current, err = readNodeList(filepath.Join(dir, nodesFile), allowPrivate); err != nil {
		return in, err
	}
	if in.pinned, err = readPinned(filepath.Join(dir, pinnedFile), allowPrivate); err != nil {
		return in, err
	}
	if in.banned, err = readBanned(filepath.Join(dir, bannedFile)); err != nil {
		return in, err
	}
	if in.state, err = loadState(filepath.Join(dir, stateFile)); err != nil {
		return in, err
	}
	return in, nil
}

// readOptional returns a file's content, or "" if it doesn't exist.
func readOptional(path string) (string, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return "", nil
	}
	return string(data), err
}

// readNodeList reads the crawler's own previous output leniently: whatever
// no longer validates is simply left out of the next one.
func readNodeList(path string, allowPrivate bool) ([]peer.AddrInfo, error) {
	text, err := readOptional(path)
	if err != nil {
		return nil, err
	}
	return p2pnode.ParseNodeList(text, allowPrivate), nil
}

// readPinned reads the maintainers' pinned entries strictly: a typo there
// should fail the crawl loudly rather than silently drop a pinned server.
func readPinned(path string, allowPrivate bool) ([]peer.AddrInfo, error) {
	text, err := readOptional(path)
	if err != nil {
		return nil, err
	}
	for i, line := range contentLines(text) {
		if len(p2pnode.ParseNodeList(line, allowPrivate)) == 0 {
			return nil, fmt.Errorf("%s: entry %d (%q) isn't a valid public node multiaddr ending in /p2p/<peerid>", path, i+1, line)
		}
	}
	return p2pnode.ParseNodeList(text, allowPrivate), nil
}

// contentLines returns the non-blank, non-comment lines of text, trimmed.
func contentLines(text string) []string {
	var lines []string
	for _, line := range strings.Split(text, "\n") {
		if line = strings.TrimSpace(line); line != "" && !strings.HasPrefix(line, "#") {
			lines = append(lines, line)
		}
	}
	return lines
}

// banList is banned.txt: each line is a peer id, a multiaddr ending in
// /p2p/<peerid> (bans that peer), or a multiaddr without one, which bans every
// address it is a prefix of - "/ip4/203.0.113.7" bans all ports on that IP.
type banList struct {
	ids      map[peer.ID]bool
	prefixes []ma.Multiaddr
}

func readBanned(path string) (banList, error) {
	b := banList{ids: make(map[peer.ID]bool)}
	text, err := readOptional(path)
	if err != nil {
		return b, err
	}
	for i, line := range contentLines(text) {
		if id, err := peer.Decode(line); err == nil {
			b.ids[id] = true
			continue
		}
		addr, err := ma.NewMultiaddr(line)
		if err != nil {
			return b, fmt.Errorf("%s: entry %d (%q) is neither a peer id nor a multiaddr", path, i+1, line)
		}
		if _, id := peer.SplitAddr(addr); id != "" {
			b.ids[id] = true
			continue
		}
		b.prefixes = append(b.prefixes, addr)
	}
	return b, nil
}

func (b banList) bansAddr(addr ma.Multiaddr) bool {
	return slices.ContainsFunc(b.prefixes, func(prefix ma.Multiaddr) bool {
		return len(prefix) <= len(addr) && addr[:len(prefix)].Equal(prefix)
	})
}

// filter drops banned peers entirely and banned addresses from the rest.
func (b banList) filter(infos []peer.AddrInfo) []peer.AddrInfo {
	var out []peer.AddrInfo
	for _, info := range infos {
		if b.ids[info.ID] {
			continue
		}
		addrs := slices.DeleteFunc(slices.Clone(info.Addrs), b.bansAddr)
		if len(addrs) > 0 {
			out = append(out, peer.AddrInfo{ID: info.ID, Addrs: addrs})
		}
	}
	return out
}

// renderNodeList is the exact nodes.txt content for infos: the header, then
// one line per address, sorted, so the same selection always yields the same
// bytes and the scheduled workflow only commits real changes.
func renderNodeList(infos []peer.AddrInfo) string {
	lines := strings.Fields(p2pnode.FormatNodeList(infos))
	slices.Sort(lines)
	var sb strings.Builder
	sb.WriteString(nodesHeader)
	for _, line := range lines {
		sb.WriteString(line)
		sb.WriteByte('\n')
	}
	return sb.String()
}
