# p2p-go

The Go side of FRAD's M4 wide-range (GPS-radius) discovery layer, built on
[go-libp2p](https://github.com/libp2p/go-libp2p). This module is outside the
Android app's normal build path — a plain `./gradlew test`/`assembleDebug`
never needs Go installed. See the root [README.md](../README.md) for how it
plugs into the app.

## Layout

- `node/` — the API `gomobile bind` turns into an Android `.aar`: a libp2p
  host + Kademlia DHT scoped to FRAD's own isolated protocol namespace
  (`node.ProtocolPrefix`, `"/frad"`) so it never touches the public IPFS DHT.
  Peer discovery is DHT rendezvous keyed by a geohash-derived topic string;
  chat/file-transfer bytes move over plain libp2p streams, with direct-vs-
  relayed dialing handled transparently by libp2p's own Swarm/AutoRelay.
- `cmd/bootstrap/` — a standalone daemon: a mode-Server DHT node plus a
  circuit-relay v2 relay service. It has no rendezvous/chat logic of its own —
  it exists purely so the isolated `/frad` DHT has somewhere to bootstrap from,
  and so peers behind NAT have a relay to fall back to.

## The bootstrap problem (read this before expecting wide-range chat to work)

A brand-new, isolated Kademlia DHT has zero peers until something connects to
a node that's already in it. The public IPFS bootstrap nodes are useless here
by construction — they run the public `/ipfs/kad/1.0.0` protocol, not FRAD's
own `/frad/kad/1.0.0`. So wide-range discovery does not work out of the box:
at least one `cmd/bootstrap` instance must be running and reachable, and its
multiaddr entered into FRAD's Settings → bootstrap-node list on *both* devices
trying to find each other. Anyone can run one — that's the point (no
company-run backend) — but this repo does not deploy or operate one for you.

## Running a bootstrap/relay node

```bash
cd p2p-go
go build -o bootstrap ./cmd/bootstrap
./bootstrap -port 4001 -identity-key-file bootstrap_identity.key
```

Run it somewhere with a public IP and an open TCP+UDP port (a small VPS is
plenty — this is a thin relay/DHT-server process, not a chat participant, and
never sees plaintext chat content). It prints its multiaddrs on startup,
e.g.:

```
FRAD bootstrap/relay node ready. Share these multiaddrs with users for their bootstrap-node list:
  /ip4/203.0.113.7/tcp/4001/p2p/12D3KooW...
  /ip4/203.0.113.7/udp/4001/quic-v1/p2p/12D3KooW...
```

Paste one of the printed lines (the public-IP one) into FRAD's Settings on
each device that should be able to find the other over the wide-range layer.
`-identity-key-file` persists the node's identity across restarts so its
multiaddr/peer id stays stable — without it, every restart would mint a new
peer id and invalidate everyone's saved address.

## Building the `.aar` for the app

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
# from the repo root:
./gradlew gomobileBind
```

Requires Go 1.22+, the Android NDK (side-by-side with the SDK), and
`ANDROID_HOME`/`ANDROID_NDK_HOME` set. This produces
`p2p-go/build/p2pgo.aar`, which `app/build.gradle.kts` picks up automatically
on the next build — see the root README's "Building" section.

## Verifying without a device

```bash
go build ./...
go vet ./...
go test ./...
```

`node`'s test suite includes an end-to-end smoke test (three loopback libp2p
hosts: a mode-Server bootstrap + two mode-Auto app peers) that proves DHT
rendezvous and a relay-transparent `OpenStream` round trip actually work —
this is real network code, just never leaving localhost. What it does **not**
prove: behavior across the real internet, through real NAT, or through a
real circuit-relay v2 hop when direct dialing fails — that needs two physical
devices and a real, publicly-reachable `cmd/bootstrap` instance.
