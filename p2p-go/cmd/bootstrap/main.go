// Command bootstrap runs a standalone FRAD wide-range bootstrap + relay node.
// It never joins any rendezvous topic and never advertises/finds chat peers —
// it exists purely so FRAD's isolated Kademlia DHT (see node.ProtocolPrefix)
// has somewhere to bootstrap from, and so peers behind NAT have a circuit-
// relay v2 relay to fall back to. Anyone can run this (see ../../README.md);
// share the multiaddr it prints with users who want it in their
// (user-configurable) bootstrap/relay node list.
//
// This binary is a separately-run operator tool: building/deploying/hosting
// an actual instance of it is out of scope for the app changes in this repo.
package main

import (
	"context"
	"crypto/rand"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/libp2p/go-libp2p"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/protocol"

	p2pnode "frad.local/p2p-go/node"
)

func main() {
	port := flag.Int("port", 4001, "TCP/QUIC listen port")
	keyFile := flag.String("identity-key-file", "bootstrap_identity.key",
		"path to persist this node's identity so its multiaddr/peer id stays stable across restarts")
	flag.Parse()

	priv, err := loadOrCreateIdentity(*keyFile)
	if err != nil {
		fmt.Fprintln(os.Stderr, "identity error:", err)
		os.Exit(1)
	}

	h, err := libp2p.New(
		libp2p.Identity(priv),
		libp2p.ListenAddrStrings(
			fmt.Sprintf("/ip4/0.0.0.0/tcp/%d", *port),
			fmt.Sprintf("/ip4/0.0.0.0/udp/%d/quic-v1", *port),
		),
		// Runs the circuit-relay v2 SERVICE (this node relays for others),
		// unlike node.Host's client-only libp2p.EnableRelay().
		libp2p.EnableRelayService(),
	)
	if err != nil {
		fmt.Fprintln(os.Stderr, "host error:", err)
		os.Exit(1)
	}
	defer h.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	kadDHT, err := dht.New(h, dht.ProtocolPrefix(protocol.ID(p2pnode.ProtocolPrefix)), dht.Mode(dht.ModeServer))
	if err != nil {
		fmt.Fprintln(os.Stderr, "dht error:", err)
		os.Exit(1)
	}
	defer kadDHT.Close()
	if err := kadDHT.Bootstrap(ctx); err != nil {
		fmt.Fprintln(os.Stderr, "dht bootstrap error:", err)
		os.Exit(1)
	}

	fmt.Println("FRAD bootstrap/relay node ready. Share these multiaddrs with users for their bootstrap-node list:")
	for _, addr := range h.Addrs() {
		fmt.Printf("  %s/p2p/%s\n", addr, h.ID())
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	<-sig
}

func loadOrCreateIdentity(path string) (crypto.PrivKey, error) {
	if data, err := os.ReadFile(path); err == nil {
		return crypto.UnmarshalPrivateKey(data)
	}
	priv, _, err := crypto.GenerateEd25519Key(rand.Reader)
	if err != nil {
		return nil, err
	}
	data, err := crypto.MarshalPrivateKey(priv)
	if err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return nil, fmt.Errorf("persisting identity key to %s: %w", path, err)
	}
	return priv, nil
}
