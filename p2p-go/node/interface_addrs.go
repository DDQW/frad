package node

import (
	"net/netip"
	"slices"
	"strings"

	"github.com/multiformats/go-multiaddr"
)

// Android 11+ denies apps the netlink request (RTM_GETLINK) Go's
// net.Interfaces/net.InterfaceAddrs are built on, so go-libp2p's interface
// discovery fails and falls back to loopback: a phone would then only ever
// advertise 127.0.0.1/::1, and nobody - not even a peer on the same Wi-Fi -
// could dial it directly or hole-punch to it. Kotlin can still read the
// addresses (ConnectivityManager/LinkProperties) and hands them over in
// Config.InterfaceAddrs; this file turns them into advertised addresses.

// parseInterfaceIPs parses Config.InterfaceAddrs: newline-delimited IP
// addresses, an IPv6 zone suffix ("%wlan0") ignored. Addresses no other peer
// could use - unspecified, loopback, link-local, multicast - are dropped;
// private LAN addresses are kept, since peers on the same network can use them.
func parseInterfaceIPs(newlineDelimited string) []netip.Addr {
	var ips []netip.Addr
	for _, line := range strings.Split(newlineDelimited, "\n") {
		line, _, _ = strings.Cut(strings.TrimSpace(line), "%")
		ip, err := netip.ParseAddr(line)
		if err != nil {
			continue
		}
		ip = ip.Unmap()
		if ip.IsUnspecified() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsMulticast() {
			continue
		}
		if !slices.Contains(ips, ip) {
			ips = append(ips, ip)
		}
	}
	return ips
}

// interfaceAddrsFactory returns a libp2p AddrsFactory that adds, for every
// unspecified listen address (0.0.0.0 or ::, with the port the OS actually
// assigned), the same address with each of ips of that family in its place.
// Whatever libp2p computed itself - observed and NAT-mapped addresses, and the
// /p2p-circuit addresses AutoRelay adds - is kept as is.
func interfaceAddrsFactory(ips []netip.Addr, listenAddrs func() []multiaddr.Multiaddr) func([]multiaddr.Multiaddr) []multiaddr.Multiaddr {
	return func(addrs []multiaddr.Multiaddr) []multiaddr.Multiaddr {
		out := slices.Clone(addrs)
		for _, listenAddr := range listenAddrs() {
			out = append(out, resolveUnspecified(listenAddr, ips)...)
		}
		return multiaddr.Unique(out)
	}
}

// resolveUnspecified returns listenAddr with its unspecified IP replaced by
// each address in ips of the same family, or nothing if listenAddr doesn't
// start with an unspecified IP. Families must match: the IPv6 listener is
// IPv6-only, so it can't be reached on an IPv4 address and vice versa.
func resolveUnspecified(listenAddr multiaddr.Multiaddr, ips []netip.Addr) []multiaddr.Multiaddr {
	if len(listenAddr) < 2 {
		return nil
	}
	code := listenAddr[0].Code()
	if code != multiaddr.P_IP4 && code != multiaddr.P_IP6 {
		return nil
	}
	listenIP, ok := netip.AddrFromSlice(listenAddr[0].RawValue())
	if !ok || !listenIP.IsUnspecified() {
		return nil
	}
	var out []multiaddr.Multiaddr
	for _, ip := range ips {
		if ip.Is4() != (code == multiaddr.P_IP4) {
			continue
		}
		proto := "ip6"
		if ip.Is4() {
			proto = "ip4"
		}
		c, err := multiaddr.NewComponent(proto, ip.String())
		if err != nil {
			continue
		}
		out = append(out, c.Encapsulate(listenAddr[1:]))
	}
	return out
}
