package main

import (
	"crypto/rand"
	"fmt"
	"os"

	"github.com/libp2p/go-libp2p/core/crypto"
)

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
