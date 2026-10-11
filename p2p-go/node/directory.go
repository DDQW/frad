package node

// The node directory is how FRAD keeps knowing where its servers (cmd/bootstrap
// instances) are without any single list being the source of truth. Every
// participant - phone or server - answers NodesProtocolID with the servers it
// knows and takes the asker's list in return, so one reachable server is enough
// to learn about all the others, and a list that loses entries (a server shut
// down, the official list unreachable) is refilled by whoever you talk to next.
//
// Everything learned this way is untrusted input. This file validates it
// (public unicast addresses, plain TCP/QUIC transports, no relay circuits),
// bounds it (MaxNodeListEntries, maxNodeListBytes, maxAddrsPerNode) and limits
// how much of a merged list one network may take up (maxNodesPerNetwork), and
// callers only promote an entry to "known server" once they have connected to
// it themselves, seen it run FRAD's DHT (IsFradServer) and proven the
// addresses they list it at (VerifyNodeAddrs).
//
// Only ValidateNodeList and the constants cross the gomobile boundary; the rest
// takes libp2p types, which gomobile skips, and is shared with cmd/bootstrap
// and cmd/nodecrawl.

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/netip"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p/core/connmgr"
	"github.com/libp2p/go-libp2p/core/control"
	"github.com/libp2p/go-libp2p/core/event"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
	"github.com/libp2p/go-libp2p/core/transport"
	ma "github.com/multiformats/go-multiaddr"
	madns "github.com/multiformats/go-multiaddr-dns"
)

const (
	// NodesProtocolID is the node-list exchange. The side opening the stream
	// writes its list and closes its write side, then reads the other side's
	// list; the handler reads first and replies with its own. One round trip,
	// and both sides have learned something.
	NodesProtocolID = "/frad/nodes/1.0.0"

	// MaxNodeListEntries caps the addresses (lines) in one node-list message. A
	// list only has to be long enough to reach the network again, and a small
	// cap bounds what a hostile peer can push in one go.
	MaxNodeListEntries = 64
)

const (
	// maxNodeListBytes bounds one message on the wire. Reading stops one byte
	// past it, so an oversized message is a detectable protocol error rather
	// than something silently truncated mid-line.
	maxNodeListBytes = 16 << 10
	// maxNodeLineBytes bounds one entry; the longest legitimate one (a 253-byte
	// DNS name, a port and a peer id) is well under it.
	maxNodeLineBytes = 512
	// nodeExchangeTimeout bounds one whole exchange, so a peer that stalls can't
	// hold a stream - or one of a server's handler slots - open indefinitely.
	nodeExchangeTimeout = 10 * time.Second
	// openerListTimeout bounds how long the handler waits for the opener's
	// list (16 KiB at most - a couple of seconds even on a poor mobile link),
	// so a stream that never sends anything gives its slot back early.
	openerListTimeout = 5 * time.Second
	// maxAddrsPerNode lets one server list a few transports and address
	// families (TCP + QUIC, IPv4 + IPv6) without crowding out other servers.
	maxAddrsPerNode = 4
	// maxNodesPerNetwork caps how many distinct peers may share one IPv4 /24,
	// IPv6 /48 or DNS domain in a merged list. The cheap way to flood a list is
	// many peer ids on one machine or one rented address block; this keeps
	// those from displacing independently operated servers.
	maxNodesPerNetwork = 2
	// maxConcurrentNodeExchanges bounds inbound exchanges served at once, so a
	// burst of streams can't tie up a server beyond a fixed amount of memory.
	maxConcurrentNodeExchanges = 16
	// maxExchangesPerPeer and maxExchangesPerNetwork split those slots up, so
	// one peer - or one machine cycling through peer ids, which libp2p's
	// resource manager lets hold 8 connections per IPv4 address - can't
	// occupy all of them and lock everyone else out of the exchange.
	maxExchangesPerPeer    = 2
	maxExchangesPerNetwork = 4
	// selfReportWait bounds AwaitSelfReport's wait for a peer's identify
	// report; it normally lands within milliseconds of connecting.
	selfReportWait = 2 * time.Second
	// dialBackTimeout bounds proving one address (see VerifyNodeAddrs). A
	// real server answers in well under a second; a made-up address may
	// never answer at all.
	dialBackTimeout = 5 * time.Second
)

var (
	errNodeListTooLarge = fmt.Errorf("node list exceeds %d bytes", maxNodeListBytes)
	errNotGlobal        = errors.New("not a globally routable address")

	cgnatPrefix         = netip.MustParsePrefix("100.64.0.0/10")
	thisNetworkPrefix   = netip.MustParsePrefix("0.0.0.0/8")
	reservedIPv4Prefix  = netip.MustParsePrefix("240.0.0.0/4")
	globalUnicastPrefix = netip.MustParsePrefix("2000::/3")
	limitedBroadcast    = netip.MustParseAddr("255.255.255.255")
)

// ValidateNodeList returns the entries of a newline-delimited node list (blank
// lines and '#' comments allowed) that are acceptable from the internet, de-
// duplicated and grouped by peer id - see ParseNodeList. This is the gomobile
// entry point, so the app can check a list (typed in, scanned, or learned) with
// exactly the rules every other FRAD participant applies.
func ValidateNodeList(newlineList string) string {
	return FormatNodeList(ParseNodeList(newlineList, false))
}

// ParseNodeList parses newline-delimited node multiaddrs, each ending in
// /p2p/<peerid>. Blank lines and lines starting with '#' are skipped and every
// entry parseNodeAddr rejects is dropped silently - one bad line shouldn't cost
// the rest of a list. The result is grouped by peer id in first-seen order,
// with at most maxAddrsPerNode distinct addresses per peer.
//
// allowPrivate also accepts loopback, LAN and other non-global addresses. It
// exists for tests and LAN-only deployments; lists learned from the internet
// must be parsed without it.
func ParseNodeList(text string, allowPrivate bool) []peer.AddrInfo {
	var b nodeListBuilder
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		addr, id, err := parseNodeAddr(line, allowPrivate)
		if err != nil {
			continue
		}
		b.add(id, addr)
	}
	return b.infos
}

// FormatNodeList renders infos as one "<addr>/p2p/<peerid>" line per address.
func FormatNodeList(infos []peer.AddrInfo) string {
	var sb strings.Builder
	for _, info := range infos {
		for _, addr := range info.Addrs {
			sb.WriteString(nodeLine(addr, info.ID))
			sb.WriteByte('\n')
		}
	}
	return sb.String()
}

func nodeLine(addr ma.Multiaddr, id peer.ID) string {
	return addr.String() + "/p2p/" + id.String()
}

// MergeNodeLists combines lists in priority order - earlier lists, and earlier
// entries within a list, win - into one list grouped by peer id. It keeps at
// most maxAddrsPerNode addresses per peer, at most maxNodesPerNetwork peers per
// network (see NetworkKey) and at most maxEntries addresses overall. Entries
// are expected to be validated already (ParseNodeList, CheckNodeAddr).
func MergeNodeLists(maxEntries int, lists ...[]peer.AddrInfo) []peer.AddrInfo {
	var b nodeListBuilder
	peersByNetwork := make(map[string]map[peer.ID]bool)
	total := 0
	for _, list := range lists {
		for _, info := range list {
			for _, addr := range info.Addrs {
				if total >= maxEntries {
					return b.infos
				}
				key := NetworkKey(addr)
				members := peersByNetwork[key]
				if len(members) >= maxNodesPerNetwork && !members[info.ID] {
					continue
				}
				if !b.add(info.ID, addr) {
					continue
				}
				if members == nil {
					members = make(map[peer.ID]bool)
					peersByNetwork[key] = members
				}
				members[info.ID] = true
				total++
			}
		}
	}
	return b.infos
}

// nodeListBuilder accumulates addresses grouped by peer id in first-seen order.
type nodeListBuilder struct {
	infos []peer.AddrInfo
	index map[peer.ID]int
}

// add appends addr to id's entry, reporting false if it was already there or
// the entry is full.
func (b *nodeListBuilder) add(id peer.ID, addr ma.Multiaddr) bool {
	i, ok := b.index[id]
	if !ok {
		if b.index == nil {
			b.index = make(map[peer.ID]int)
		}
		i = len(b.infos)
		b.index[id] = i
		b.infos = append(b.infos, peer.AddrInfo{ID: id})
	}
	info := &b.infos[i]
	if len(info.Addrs) >= maxAddrsPerNode || ma.Contains(info.Addrs, addr) {
		return false
	}
	info.Addrs = append(info.Addrs, addr)
	return true
}

// parseNodeAddr parses one node-list entry into its transport address and
// peer id, applying CheckNodeAddr to the former.
func parseNodeAddr(line string, allowPrivate bool) (ma.Multiaddr, peer.ID, error) {
	if len(line) > maxNodeLineBytes {
		return nil, "", fmt.Errorf("entry longer than %d bytes", maxNodeLineBytes)
	}
	full, err := ma.NewMultiaddr(line)
	if err != nil {
		return nil, "", err
	}
	addr, id := peer.SplitAddr(full)
	if id == "" {
		return nil, "", errors.New("missing trailing /p2p/<peerid>")
	}
	if err := CheckNodeAddr(addr, allowPrivate); err != nil {
		return nil, "", err
	}
	return addr, id, nil
}

// CheckNodeAddr reports whether addr (a transport address, without the trailing
// /p2p/<peerid>) may appear in a node list: an IP address or DNS name followed
// by /tcp/<port> or /udp/<port>/quic-v1, the transports cmd/bootstrap listens
// on. Everything else is rejected on purpose: a /p2p-circuit address would
// route "server" traffic through some third party, /dnsaddr expands into
// further addresses nobody validated, and other transports aren't spoken by
// FRAD servers anyway. Non-global IPs (loopback, RFC 1918, CGNAT, ULA,
// link-local) and local-only DNS names are rejected unless allowPrivate.
func CheckNodeAddr(addr ma.Multiaddr, allowPrivate bool) error {
	if len(addr) < 2 {
		return fmt.Errorf("incomplete node address %q", addr)
	}
	if err := checkNodeHost(&addr[0], allowPrivate); err != nil {
		return err
	}
	return checkNodeTransport(addr[1:])
}

func checkNodeHost(c *ma.Component, allowPrivate bool) error {
	switch c.Code() {
	case ma.P_IP4, ma.P_IP6:
		ip, ok := netip.AddrFromSlice(c.RawValue())
		if !ok {
			return fmt.Errorf("invalid IP address in %s", c)
		}
		ip = ip.Unmap()
		// Never dialable, whatever the deployment.
		if ip.IsUnspecified() || ip.IsMulticast() || ip == limitedBroadcast {
			return fmt.Errorf("%s: %w", ip, errNotGlobal)
		}
		if !allowPrivate && !isGlobalUnicast(ip) {
			return fmt.Errorf("%s: %w", ip, errNotGlobal)
		}
		return nil
	case ma.P_DNS, ma.P_DNS4, ma.P_DNS6:
		return checkDNSName(c.Value(), allowPrivate)
	default:
		return fmt.Errorf("unsupported node address type /%s", c.Protocol().Name)
	}
}

func checkNodeTransport(rest ma.Multiaddr) error {
	isTCP := len(rest) == 1 && rest[0].Code() == ma.P_TCP
	isQUIC := len(rest) == 2 && rest[0].Code() == ma.P_UDP && rest[1].Code() == ma.P_QUIC_V1
	if !isTCP && !isQUIC {
		return fmt.Errorf("unsupported node transport %q (want /tcp/<port> or /udp/<port>/quic-v1)", rest)
	}
	if port, err := strconv.Atoi(rest[0].Value()); err != nil || port == 0 {
		return fmt.Errorf("invalid port in %q", rest)
	}
	return nil
}

// isGlobalUnicast reports whether ip could belong to a server on the open
// internet.
func isGlobalUnicast(ip netip.Addr) bool {
	ip = ip.Unmap()
	if !ip.IsValid() || ip.IsUnspecified() || ip.IsLoopback() || ip.IsPrivate() ||
		ip.IsLinkLocalUnicast() || ip.IsMulticast() {
		return false
	}
	if ip.Is4() {
		return !cgnatPrefix.Contains(ip) && !thisNetworkPrefix.Contains(ip) && !reservedIPv4Prefix.Contains(ip)
	}
	// Every globally routable IPv6 unicast address is allocated from 2000::/3;
	// this also rules out NAT64 and other prefixes that embed an IPv4 address.
	return globalUnicastPrefix.Contains(ip)
}

// checkDNSName accepts a plain host name. Names that are really IP literals
// are refused: the resolver would hand back the literal unchecked, which would
// smuggle e.g. 127.0.0.1 past the IP rules above.
func checkDNSName(name string, allowPrivate bool) error {
	name = strings.TrimSuffix(strings.ToLower(name), ".")
	if name == "" || len(name) > 253 {
		return fmt.Errorf("invalid DNS name %q", name)
	}
	labels := strings.Split(name, ".")
	for _, label := range labels {
		if !isDNSLabel(label) {
			return fmt.Errorf("invalid DNS name %q", name)
		}
	}
	if isAllDigits(labels[len(labels)-1]) {
		return fmt.Errorf("DNS name %q looks like an IP address", name)
	}
	if !allowPrivate && (len(labels) < 2 || isLocalOnlyDomain(name)) {
		return fmt.Errorf("DNS name %q: %w", name, errNotGlobal)
	}
	return nil
}

func isDNSLabel(label string) bool {
	if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
		return false
	}
	for _, r := range label {
		if (r < 'a' || r > 'z') && (r < '0' || r > '9') && r != '-' {
			return false
		}
	}
	return true
}

func isAllDigits(s string) bool {
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return s != ""
}

// isLocalOnlyDomain reports names that only ever resolve inside one network.
func isLocalOnlyDomain(name string) bool {
	for _, suffix := range []string{"localhost", "local", "internal", "lan", "home.arpa"} {
		if name == suffix || strings.HasSuffix(name, "."+suffix) {
			return true
		}
	}
	return false
}

// NetworkKey names the network an address counts against for the diversity
// caps: its IPv4 /24, IPv6 /48 or DNS domain. Non-global addresses (only ever
// seen in tests and LAN setups) each count as a network of their own.
func NetworkKey(addr ma.Multiaddr) string {
	if len(addr) == 0 {
		return ""
	}
	c := &addr[0]
	switch c.Code() {
	case ma.P_IP4, ma.P_IP6:
		ip, ok := netip.AddrFromSlice(c.RawValue())
		if !ok {
			return addr.String()
		}
		ip = ip.Unmap()
		if !isGlobalUnicast(ip) {
			// Only possible with allowPrivate (tests, LAN): such addresses aren't
			// a scarce internet resource worth rationing, so no shared bucket.
			return addr.String()
		}
		bits := 48
		if ip.Is4() {
			bits = 24
		}
		prefix, _ := ip.Prefix(bits)
		return prefix.String()
	case ma.P_DNS, ma.P_DNS4, ma.P_DNS6:
		return "dns:" + dnsDomain(c.Value())
	default:
		return addr.String()
	}
}

// dnsDomain reduces a host name to its last two labels: whoever owns one domain
// can mint unlimited subdomains, so they all share one bucket. This also lumps
// together e.g. every *.co.uk name - erring on the side of a stricter cap.
func dnsDomain(name string) string {
	labels := strings.Split(strings.TrimSuffix(strings.ToLower(name), "."), ".")
	if len(labels) > 2 {
		labels = labels[len(labels)-2:]
	}
	return strings.Join(labels, ".")
}

// ExchangeNodeList runs one NodesProtocolID exchange as the opening side: it
// sends mine to p and returns p's list, validated by ParseNodeList. The caller
// must already be connected to p or have its addresses in the peerstore.
func ExchangeNodeList(ctx context.Context, h host.Host, p peer.ID, mine []peer.AddrInfo, allowPrivate bool) ([]peer.AddrInfo, error) {
	ctx, cancel := context.WithTimeout(ctx, nodeExchangeTimeout)
	defer cancel()
	s, err := h.NewStream(ctx, p, protocol.ID(NodesProtocolID))
	if err != nil {
		return nil, fmt.Errorf("opening node-list stream to %s: %w", p, err)
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = s.SetDeadline(deadline)
	}
	theirs, err := exchangeAsOpener(s, mine, allowPrivate)
	if err != nil {
		_ = s.Reset()
		return nil, fmt.Errorf("node-list exchange with %s: %w", p, err)
	}
	_ = s.Close()
	return theirs, nil
}

func exchangeAsOpener(s network.Stream, mine []peer.AddrInfo, allowPrivate bool) ([]peer.AddrInfo, error) {
	if _, err := s.Write(encodeNodeList(mine)); err != nil {
		return nil, err
	}
	if err := s.CloseWrite(); err != nil {
		return nil, err
	}
	return readNodeList(s, allowPrivate)
}

// NodeListHandler returns the NodesProtocolID stream handler: it reads the
// opener's list, passes it (validated) to learned along with the connection it
// came in on, and replies with provide(). A message over the size or entry
// limits is a protocol error - the stream is reset and nothing from it is
// used. learned may be nil.
func NodeListHandler(provide func() []peer.AddrInfo, learned func(from network.Conn, infos []peer.AddrInfo), allowPrivate bool) network.StreamHandler {
	return nodeListHandler(provide, learned, allowPrivate, newExchangeLimiter())
}

func nodeListHandler(provide func() []peer.AddrInfo, learned func(network.Conn, []peer.AddrInfo), allowPrivate bool, limiter *exchangeLimiter) network.StreamHandler {
	return func(s network.Stream) {
		conn := s.Conn()
		src := exchangeSource{peer: conn.RemotePeer(), network: NetworkKey(conn.RemoteMultiaddr())}
		if !limiter.acquire(src) {
			_ = s.Reset()
			return
		}
		defer limiter.release(src)

		now := time.Now()
		_ = s.SetDeadline(now.Add(nodeExchangeTimeout))
		_ = s.SetReadDeadline(now.Add(openerListTimeout))
		theirs, err := readNodeList(s, allowPrivate)
		if err != nil {
			_ = s.Reset()
			return
		}
		if learned != nil && len(theirs) > 0 {
			learned(conn, theirs)
		}
		if _, err := s.Write(encodeNodeList(provide())); err != nil {
			_ = s.Reset()
			return
		}
		_ = s.Close()
	}
}

// exchangeSource is who an inbound exchange is charged to.
type exchangeSource struct {
	peer    peer.ID
	network string // NetworkKey of the connection's remote address
}

// exchangeLimiter hands out NodeListHandler's slots: maxConcurrentNodeExchanges
// overall, of which one peer may hold maxExchangesPerPeer and one network
// maxExchangesPerNetwork.
type exchangeLimiter struct {
	mu        sync.Mutex
	total     int
	byPeer    map[peer.ID]int
	byNetwork map[string]int
}

func newExchangeLimiter() *exchangeLimiter {
	return &exchangeLimiter{byPeer: make(map[peer.ID]int), byNetwork: make(map[string]int)}
}

func (l *exchangeLimiter) acquire(src exchangeSource) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.total >= maxConcurrentNodeExchanges || l.byPeer[src.peer] >= maxExchangesPerPeer ||
		l.byNetwork[src.network] >= maxExchangesPerNetwork {
		return false
	}
	l.total++
	l.byPeer[src.peer]++
	l.byNetwork[src.network]++
	return true
}

func (l *exchangeLimiter) release(src exchangeSource) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.total--
	if l.byPeer[src.peer]--; l.byPeer[src.peer] == 0 {
		delete(l.byPeer, src.peer)
	}
	if l.byNetwork[src.network]--; l.byNetwork[src.network] == 0 {
		delete(l.byNetwork, src.network)
	}
}

// inUse reports how many slots src's peer holds (for tests).
func (l *exchangeLimiter) inUse(p peer.ID) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.byPeer[p]
}

// encodeNodeList renders infos for the wire, stopping before either limit
// (MaxNodeListEntries lines, maxNodeListBytes) would be exceeded, so this side
// never sends what it would itself reject.
func encodeNodeList(infos []peer.AddrInfo) []byte {
	var buf bytes.Buffer
	lines := 0
	for _, info := range infos {
		for _, addr := range info.Addrs {
			line := nodeLine(addr, info.ID) + "\n"
			if lines >= MaxNodeListEntries || buf.Len()+len(line) > maxNodeListBytes {
				return buf.Bytes()
			}
			buf.WriteString(line)
			lines++
		}
	}
	return buf.Bytes()
}

// readNodeList reads one whole node-list message (until the sender closes its
// write side) and enforces the wire limits before parsing anything.
func readNodeList(r io.Reader, allowPrivate bool) ([]peer.AddrInfo, error) {
	data, err := io.ReadAll(io.LimitReader(r, maxNodeListBytes+1))
	if err != nil {
		return nil, err
	}
	if len(data) > maxNodeListBytes {
		return nil, errNodeListTooLarge
	}
	text := string(data)
	if n := countNodeListEntries(text); n > MaxNodeListEntries {
		return nil, fmt.Errorf("node list has %d entries, limit is %d", n, MaxNodeListEntries)
	}
	return ParseNodeList(text, allowPrivate), nil
}

// countNodeListEntries counts non-blank, non-comment lines, valid or not.
func countNodeListEntries(text string) int {
	n := 0
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line != "" && !strings.HasPrefix(line, "#") {
			n++
		}
	}
	return n
}

// IsFradServer reports whether identify has shown p to run FRAD's DHT in
// server mode - what tells a FRAD bootstrap node apart from a phone (whose DHT
// is a client and serves no queries) or an unrelated libp2p node. Only
// meaningful once identify has run: h.Connect waits for that when it dials,
// but not when a connection already existed - see AwaitSelfReport.
func IsFradServer(h host.Host, p peer.ID) bool {
	protos, err := h.Peerstore().SupportsProtocols(p, protocol.ID(DHTProtocolID))
	return err == nil && len(protos) > 0
}

// selfReportedAddrsKey is the peerstore metadata key RecordSelfReportedAddrs
// files each peer's own listen addresses under.
const selfReportedAddrsKey = "frad/self-reported-addrs"

// RecordSelfReportedAddrs keeps, until ctx ends, a copy of the listen
// addresses every peer reports about itself over identify. They are what
// VerifyNodeAddrs tries to prove: a peer's own claims are the only ones worth
// checking (it can only lie about itself), whereas the peerstore's address
// book mixes in addresses from every source, including whatever a hostile
// node list handed to Connect.
func RecordSelfReportedAddrs(ctx context.Context, h host.Host) error {
	sub, err := h.EventBus().Subscribe(new(event.EvtPeerIdentificationCompleted))
	if err != nil {
		return err
	}
	go func() {
		defer sub.Close()
		for {
			select {
			case <-ctx.Done():
				return
			case e, ok := <-sub.Out():
				if !ok {
					return
				}
				evt := e.(event.EvtPeerIdentificationCompleted)
				_ = h.Peerstore().Put(evt.Peer, selfReportedAddrsKey, slices.Clone(evt.ListenAddrs))
			}
		}
	}()
	return nil
}

// AwaitSelfReport waits until RecordSelfReportedAddrs has recorded p's
// identify report - so identify has completed and IsFradServer can be asked -
// for at most selfReportWait or until ctx ends, and reports whether it was.
// h.Connect alone doesn't guarantee that: when a connection to p already
// exists (e.g. one the DHT has just opened) it returns at once, possibly
// before identify on that connection has finished.
func AwaitSelfReport(ctx context.Context, h host.Host, p peer.ID) bool {
	deadline := time.NewTimer(selfReportWait)
	defer deadline.Stop()
	for !hasSelfReport(h, p) {
		select {
		case <-ctx.Done():
			return false
		case <-deadline.C:
			return false
		case <-time.After(10 * time.Millisecond):
		}
	}
	return true
}

func hasSelfReport(h host.Host, p peer.ID) bool {
	_, err := h.Peerstore().Get(p, selfReportedAddrsKey)
	return err == nil
}

// SelfReportedNodeAddrs returns what p last reported about itself over
// identify (see RecordSelfReportedAddrs), minus anything CheckNodeAddr
// rejects. These are claims, not facts: good enough to queue p as a candidate,
// never to list it - that takes VerifyNodeAddrs.
func SelfReportedNodeAddrs(h host.Host, p peer.ID, allowPrivate bool) []ma.Multiaddr {
	v, err := h.Peerstore().Get(p, selfReportedAddrsKey)
	if err != nil {
		return nil
	}
	reported, _ := v.([]ma.Multiaddr)
	var out []ma.Multiaddr
	for _, addr := range reported {
		if !ma.Contains(out, addr) && CheckNodeAddr(addr, allowPrivate) == nil {
			out = append(out, addr)
		}
	}
	return out
}

// VerifyNodeAddrs returns the addresses of p - a peer this host is connected
// to - that may go into a node list: the ones p has been shown to be
// reachable at, not merely said to be. Neither a list naming p nor p itself
// is proof. Anyone can claim "server X lives at Y", and identify's listen
// addresses are whatever p chooses to send - one machine could pose as
// servers on any number of networks and walk straight past the per-network
// diversity caps. So an address counts only once a connection to exactly that
// address has completed a handshake authenticated as p: the remote end of an
// outbound connection this host already has, or a dial-back to an address p
// reported for itself. What is returned is always the IP address that
// answered: a self-reported DNS name is resolved and its IP addresses are
// dialed back instead (see claimedIPAddrs), so the caps always see the
// network a server is really on, and a server announcing only a name can
// still be verified by a peer it only ever connected to. At most
// maxAddrsPerNode addresses are tried or returned. Waits for p's identify
// report first (AwaitSelfReport).
func VerifyNodeAddrs(ctx context.Context, h host.Host, p peer.ID, allowPrivate bool) []ma.Multiaddr {
	AwaitSelfReport(ctx, h, p)
	verified := dialedNodeAddrs(h, p, allowPrivate)
	if len(verified) >= maxAddrsPerNode {
		return verified
	}
	claims := claimedIPAddrs(ctx, h, p, allowPrivate, verified, maxAddrsPerNode-len(verified))
	return append(verified, dialBackAll(ctx, h, p, claims)...)
}

// claimedIPAddrs returns up to n of the addresses p reported for itself
// (SelfReportedNodeAddrs) that aren't in skip, in the order reported, with
// each DNS name replaced by the listable IP addresses it resolves to. At most
// maxAddrsPerNode names are looked up, all within one dialBackTimeout, so a
// report full of names that lead nowhere costs little.
func claimedIPAddrs(ctx context.Context, h host.Host, p peer.ID, allowPrivate bool, skip []ma.Multiaddr, n int) []ma.Multiaddr {
	ctx, cancel := context.WithTimeout(ctx, dialBackTimeout)
	defer cancel()
	var out []ma.Multiaddr
	lookups := 0
	for _, addr := range SelfReportedNodeAddrs(h, p, allowPrivate) {
		candidates := []ma.Multiaddr{addr}
		if !isIPAddr(addr) {
			if lookups == maxAddrsPerNode {
				continue
			}
			lookups++
			resolved, err := madns.DefaultResolver.Resolve(ctx, addr)
			if err != nil {
				continue
			}
			candidates = resolved
		}
		for _, c := range candidates {
			if len(out) >= n {
				return out
			}
			// Resolved addresses are vetted like any other: a name may point
			// anywhere, including at a private address.
			if isIPAddr(c) && CheckNodeAddr(c, allowPrivate) == nil && !ma.Contains(skip, c) && !ma.Contains(out, c) {
				out = append(out, c)
			}
		}
	}
	return out
}

// dialedNodeAddrs returns the listable remote addresses of this host's
// outbound connections to p: it dialed them itself, and the handshake proved
// p is there.
func dialedNodeAddrs(h host.Host, p peer.ID, allowPrivate bool) []ma.Multiaddr {
	var out []ma.Multiaddr
	for _, c := range h.Network().ConnsToPeer(p) {
		addr := c.RemoteMultiaddr()
		if len(out) < maxAddrsPerNode && c.Stat().Direction == network.DirOutbound &&
			!ma.Contains(out, addr) && CheckNodeAddr(addr, allowPrivate) == nil {
			out = append(out, addr)
		}
	}
	return out
}

func isIPAddr(addr ma.Multiaddr) bool {
	return len(addr) > 0 && (addr[0].Code() == ma.P_IP4 || addr[0].Code() == ma.P_IP6)
}

// dialBackAll dials back every address at once and returns, in order, those
// that turned out to be p's.
func dialBackAll(ctx context.Context, h host.Host, p peer.ID, addrs []ma.Multiaddr) []ma.Multiaddr {
	ok := make([]bool, len(addrs))
	var wg sync.WaitGroup
	for i, addr := range addrs {
		wg.Go(func() { ok[i] = dialBack(ctx, h, p, addr) })
	}
	wg.Wait()
	var out []ma.Multiaddr
	for i, addr := range addrs {
		if ok[i] {
			out = append(out, addr)
		}
	}
	return out
}

// transportDialer is what dialBack needs from h.Network(); libp2p's swarm
// provides it.
type transportDialer interface {
	TransportForDialing(ma.Multiaddr) transport.Transport
}

// dialBack reports whether a fresh connection to addr - that address alone,
// unlike a swarm dial, which takes whichever of p's addresses answers first -
// completes a handshake authenticated as p. The connection is closed straight
// away. Callers vet addr first (CheckNodeAddr), standing in for the dial
// gater, which a dial below the swarm doesn't pass through.
func dialBack(ctx context.Context, h host.Host, p peer.ID, addr ma.Multiaddr) bool {
	d, ok := h.Network().(transportDialer)
	if !ok {
		return false
	}
	t := d.TransportForDialing(addr)
	if t == nil {
		return false
	}
	ctx, cancel := context.WithTimeout(ctx, dialBackTimeout)
	defer cancel()
	conn, err := t.Dial(ctx, addr, p)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return conn.RemotePeer() == p
}

// PublicDialGater returns a connection gater that refuses to dial any address
// whose IP isn't globally routable (unless allowPrivate). Servers and the
// crawler dial addresses taken from untrusted node lists; this keeps a hostile
// list - including a DNS name that resolves to a private IP, since the gater
// sees resolved addresses - from making them probe their own LAN or cloud-
// internal network, which hosting providers also treat as abuse. Inbound
// connections are unaffected.
func PublicDialGater(allowPrivate bool) connmgr.ConnectionGater {
	return publicDialGater{allowPrivate: allowPrivate}
}

type publicDialGater struct {
	allowPrivate bool
}

func (g publicDialGater) InterceptPeerDial(peer.ID) bool { return true }

func (g publicDialGater) InterceptAddrDial(_ peer.ID, addr ma.Multiaddr) bool {
	if g.allowPrivate || len(addr) == 0 {
		return true
	}
	switch addr[0].Code() {
	case ma.P_IP4, ma.P_IP6:
		ip, ok := netip.AddrFromSlice(addr[0].RawValue())
		return ok && isGlobalUnicast(ip)
	default:
		// Unresolved names never reach the gater; anything else is up to the
		// transports.
		return true
	}
}

func (g publicDialGater) InterceptAccept(network.ConnMultiaddrs) bool { return true }

func (g publicDialGater) InterceptSecured(network.Direction, peer.ID, network.ConnMultiaddrs) bool {
	return true
}

func (g publicDialGater) InterceptUpgraded(network.Conn) (bool, control.DisconnectReason) {
	return true, 0
}
