package node

import (
	"fmt"
	"strings"
	"testing"

	"github.com/libp2p/go-libp2p"
	ma "github.com/multiformats/go-multiaddr"
)

func TestParseInterfaceIPs(t *testing.T) {
	got := parseInterfaceIPs(strings.Join([]string{
		"192.168.1.20",
		"2a01:4f8::20",
		"fe80::1%wlan0", // link-local: useless to anyone else
		"127.0.0.1",
		"::1",
		"0.0.0.0",
		"",
		"not an ip",
		"192.168.1.20",    // duplicate
		"::ffff:10.0.0.2", // IPv4-mapped
		" 2a01:4f8::30%rmnet_data0 ",
	}, "\n"))
	if fmt.Sprint(got) != "[192.168.1.20 2a01:4f8::20 10.0.0.2 2a01:4f8::30]" {
		t.Fatalf("got %v", got)
	}
}

func TestResolveUnspecified(t *testing.T) {
	ips := parseInterfaceIPs("192.168.1.20\n2a01:4f8::20\n203.0.113.5")
	for listen, want := range map[string]string{
		"/ip4/0.0.0.0/tcp/4001":        "[/ip4/192.168.1.20/tcp/4001 /ip4/203.0.113.5/tcp/4001]",
		"/ip6/::/udp/4002/quic-v1":     "[/ip6/2a01:4f8::20/udp/4002/quic-v1]",
		"/ip4/127.0.0.1/tcp/4001":      "[]",
		"/ip6/2a01:4f8::99/tcp/4001":   "[]",
		"/p2p-circuit":                 "[]",
		"/ip4/0.0.0.0/udp/9/quic-v1":   "[/ip4/192.168.1.20/udp/9/quic-v1 /ip4/203.0.113.5/udp/9/quic-v1]",
		"/dns4/example.org/tcp/4001":   "[]",
		"/ip6/::/tcp/4001/ws":          "[/ip6/2a01:4f8::20/tcp/4001/ws]",
		"/ip4/0.0.0.0/tcp/4001/tls/ws": "[/ip4/192.168.1.20/tcp/4001/tls/ws /ip4/203.0.113.5/tcp/4001/tls/ws]",
	} {
		if got := fmt.Sprint(resolveUnspecified(ma.StringCast(listen), ips)); got != want {
			t.Errorf("%s: got %s, want %s", listen, got, want)
		}
	}
}

// TestInterfaceAddrsAreAdvertised checks the Android 11+ workaround end to end:
// the IPs Kotlin supplies show up in the host's advertised addresses, on the
// ports the OS actually assigned to each listener, next to whatever libp2p
// detected itself.
func TestInterfaceAddrsAreAdvertised(t *testing.T) {
	h := newTestHost(t, &Config{InterfaceAddrs: "203.0.113.5\n2001:db8::5\nfe80::1%wlan0"})

	var want []string
	for _, listen := range h.h.Network().ListenAddresses() {
		s := listen.String()
		switch {
		case strings.HasPrefix(s, "/ip4/0.0.0.0/"):
			want = append(want, strings.Replace(s, "/ip4/0.0.0.0/", "/ip4/203.0.113.5/", 1))
		case strings.HasPrefix(s, "/ip6/::/"):
			want = append(want, strings.Replace(s, "/ip6/::/", "/ip6/2001:db8::5/", 1))
		}
	}
	if len(want) < 2 {
		t.Fatalf("expected at least the IPv4 TCP and QUIC listeners, got %v", h.h.Network().ListenAddresses())
	}
	advertised := h.h.Addrs()
	for _, w := range want {
		if !ma.Contains(advertised, ma.StringCast(w)) {
			t.Errorf("%s is not advertised; advertised: %v", w, advertised)
		}
	}
	for _, a := range advertised {
		if strings.Contains(a.String(), "fe80::1") {
			t.Errorf("link-local address advertised: %s", a)
		}
	}
}

func TestRelayOnlyIgnoresInterfaceAddrs(t *testing.T) {
	relayHost, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatal(err)
	}
	defer relayHost.Close()

	h := newTestHost(t, &Config{RelayOnly: true, BootstrapPeers: p2pAddrs(relayHost), InterfaceAddrs: "203.0.113.5"})
	for _, a := range h.h.Addrs() {
		if strings.Contains(a.String(), "203.0.113.5") {
			t.Fatalf("relay-only host advertises a direct address: %s", a)
		}
	}
}
