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
  `node/directory.go` is the node-directory protocol every participant speaks
  (see [below](#node-directory-and-federation)); its Go-only helpers are
  shared with the two commands.
- `cmd/bootstrap/` — a standalone daemon: a mode-Server DHT node plus a
  circuit-relay v2 relay service, federating with other such nodes. It has no
  rendezvous/chat logic of its own — it exists so the isolated `/frad` DHT has
  somewhere to bootstrap from, and so peers behind NAT have a relay to fall
  back to.
- `cmd/nodecrawl/` — the crawler that maintains the official node list in
  [`../nodes/`](../nodes/README.md), run every 6 hours by
  `.github/workflows/nodes-crawl.yml`.

## The bootstrap problem (read this before expecting wide-range chat to work)

A brand-new, isolated Kademlia DHT has zero peers until something connects to
a node that's already in it. The public IPFS bootstrap nodes are useless here
by construction — they run the public `/ipfs/kad/1.0.0` protocol, not FRAD's
own `/frad/kad/1.0.0`. So wide-range discovery needs at least one
`cmd/bootstrap` instance running and reachable. Anyone can run one — that's
the point (no company-run backend) — but this repository does not operate one.

Once any server exists, knowing about one is enough to learn about all of
them: servers federate with each other and every participant swaps server
lists with everyone it talks to (next section), and the crawler publishes the
servers it finds in [`nodes/nodes.txt`](../nodes/README.md). Until the first
public server is up, that list is empty and a node address still has to be
entered by hand in FRAD's Settings → bootstrap-node list.

## Node directory and federation

The goal: no single list, server or website whose loss takes the network's
server list with it.

**The node-list protocol** (`/frad/nodes/1.0.0`, `node/directory.go`) is a
single round trip: whoever opens the stream sends its list and closes its
write side, then reads the other side's list. Every FRAD participant serves
it - servers answer with their own addresses plus the servers they have
verified, apps with the list Kotlin gave them (`Host.SetKnownNodes`) plus
their configured bootstrap nodes - and remembers what the asker sent.

- Wire format: UTF-8, one multiaddr per line ending in `/p2p/<peerid>`, at
  most 64 entries (`MaxNodeListEntries`) and 16 KiB per message, 10 s per
  exchange. Going over a limit is a protocol error (the stream is reset and
  nothing from it is used). Lines starting with `#` are comments.
- Serving it: at most 16 exchanges at once, of which one peer may hold 2 and
  one network (as below) 4, and at most 5 s to wait for the opener's list - a
  stream that never finishes gives its slot back early, and one peer, or one
  machine cycling peer ids, can't lock everyone else out.
- Validation (`ParseNodeList`, exported to Kotlin as `ValidateNodeList`): an IP
  or DNS name (`/dns`, `/dns4`, `/dns6`) followed by `/tcp/<port>` or
  `/udp/<port>/quic-v1`. Rejected: loopback, private (RFC 1918), CGNAT
  (100.64/10), ULA (fc00::/7), link-local, unspecified and multicast IPs,
  local-only names (`localhost`, `*.local`, ...), DNS names that are really IP
  literals, `/p2p-circuit` and `/dnsaddr` addresses and any other transport.
- Merging (`MergeNodeLists`): at most 4 addresses per peer id and at most 2
  peers per IPv4 /24, IPv6 /48 or DNS domain, earlier sources winning - so
  many fake servers on one machine, address block or domain can't crowd out
  independent ones.

**Servers** (`cmd/bootstrap`) build on that:

- `-peer` (repeatable) names other bootstrap nodes to federate with: they're
  connected at startup, kept connected (checked every minute, protected from
  the connection manager) and seed the DHT, so independently operated servers
  form one `/frad` DHT instead of islands. The DHT re-seeds from these plus
  every server verified since, whenever its routing table runs low.
- Known nodes: other servers this node has verified, persisted with when each
  was last seen alive in `-nodes-file` (default `nodes.txt` next to the
  identity key) and dropped after 7 days unseen. At most 512, and at most 2
  per network (counted on their proven addresses, see below; the ones
  already known keep their places), so peer ids on one machine can't fill
  them - or the 64 entries shared with apps.
- Candidates: addresses heard from elsewhere, in three queues of 128 by
  source: `-peer` and the official list (fetched at start and every 6 hours
  from `-official-list-url`, mirrors tried in order, 10 s timeout, 64 KiB
  cap); verified servers' lists and servers this node dialed itself (in
  practice through the DHT); and everything anyone can send - lists apps and
  unverified peers push, and servers that connected in. Outside the first
  queue one network may hold 16 entries of a queue, its latest list
  replacing its earlier ones, and every round draws from all three in turn,
  so pushing junk as fast as one likes can't keep the official list or
  other servers' lists from being checked. A candidate becomes known only
  after this node checks it itself.
- Every 10 minutes (first round 30 s after start) a gossip round queues every
  connected FRAD server it doesn't know yet as a candidate, then picks up to
  8 known nodes and candidates at random, connects, and keeps a node only if
  identify shows it runs `/frad/kad/1.0.0` - FRAD's DHT in server mode, which
  no phone and no unrelated libp2p node does - and swaps lists with it.
- A server vouches for another only at addresses shown to reach it
  (`VerifyNodeAddrs`): the remote address of a connection it dialed itself,
  or an address the other server reported for itself over identify that
  answers a fresh dial-back with a handshake as that peer id. A third party's
  "server X lives at Y" is no proof, and neither is X's own: one machine
  could claim addresses in any number of networks and pass as that many
  servers. It is always the IP address that answered that gets listed: a
  self-reported DNS name is resolved and its IPs are dialed back, so the
  caps see the network a server is really on. A server that only ever
  connected in is checked like any candidate, never vouched for on the
  strength of the connection.
- Node lists are untrusted input, so a server never dials a private address
  (`PublicDialGater`, which sees DNS names already resolved) - no probing its
  own LAN or cloud network on a hostile list's say-so.

**Apps** (`node.Host`): `SetKnownNodes` sets what the phone shares,
`ExchangeNodesWithBootstrapPeers` swaps lists with every configured bootstrap
node, `ExchangeNodesWithPeer` with any peer (e.g. a chat partner), and
`TakeLearnedNodes` hands over what others pushed. Everything returned is
validated and capped, but still unverified - the app should treat it as
candidates. (Wiring these into `WideRangeChatController`/Profile storage is
app-side work; `WideRangeNode` exposes them.) `Config.BootstrapPeers` takes a
copy of the official `nodes.txt` as is: blank lines, `#` comments and lines
that don't parse are skipped, and only a list without a single usable entry
is an error.

**The official list** in [`nodes/`](../nodes/README.md) is one more source,
not an authority: the crawler lists servers seen alive in 3 consecutive runs,
diversity-capped, plus maintainer-pinned entries and minus banned ones. See
that README for the trust model and why each rule exists.

## Running a bootstrap/relay node

```bash
cd p2p-go
go build -o bootstrap ./cmd/bootstrap
./bootstrap -port 4001 -identity-key-file bootstrap_identity.key \
  -peer /dns4/other-frad-server.example.org/tcp/4001/p2p/12D3KooW...
```

Run it somewhere with a public IP and an open TCP+UDP port (a small VPS is
plenty — this is a thin relay/DHT-server process, not a chat participant, and
never sees plaintext chat content). It listens on IPv4 and IPv6 and prints
its multiaddrs on startup, e.g.:

```
FRAD bootstrap/relay node ready. Share these multiaddrs with users for their bootstrap-node list:
  /ip4/203.0.113.7/tcp/4001/p2p/12D3KooW...
  /ip4/203.0.113.7/udp/4001/quic-v1/p2p/12D3KooW...
```

| Flag | Default | |
|---|---|---|
| `-port` | `4001` | TCP and QUIC port, IPv4 and IPv6 |
| `-identity-key-file` | `bootstrap_identity.key` | keeps the peer id (and so every saved address) stable across restarts |
| `-peer` | - | another bootstrap node to federate with (multiaddr with `/p2p/<id>`); repeatable |
| `-announce` | - | public multiaddr (without `/p2p`) to advertise instead of the detected ones; repeatable |
| `-nodes-file` | `nodes.txt` next to the key | where verified servers are persisted |
| `-official-list-url` | the two mirrors of `nodes/nodes.txt` | comma-separated, tried in order; empty disables |
| `-relay-max-duration` | `1h` | lifetime of one relayed connection |
| `-relay-max-data` | 160 MiB | bytes relayed per connection and direction |
| `-relay-unlimited` | `false` | lifts both relay limits |
| `-allow-private-addrs` | `false` | accept and dial private/loopback node addresses - LAN test networks only |

Behind 1:1 NAT (most cloud VMs) the detected addresses are private ones nobody
can dial: pass the public ones with `-announce` (e.g.
`-announce /ip4/203.0.113.7/tcp/4001 -announce /ip4/203.0.113.7/udp/4001/quic-v1`).
Without a public address the node still works as a relay/DHT server for
whoever is told its address, but it won't list itself in node lists, so
nobody discovers it (it warns about this at startup).

Relaying: libp2p's own defaults cap a relayed connection at 2 minutes and
128 KiB per direction — enough for a hole-punching handshake, not for a chat
or a file. This node relays each connection for up to `-relay-max-duration`
and `-relay-max-data` bytes per direction (default 160 MiB: room for one
maximum-size 100 MB file plus the chat). It also declares itself publicly reachable at startup:
libp2p otherwise only enables the relay service after AutoNAT has confirmed
that, which a small, isolated network like FRAD's may never manage.

## The app host

- Listens dual-stack (`/ip4/0.0.0.0` and `/ip6/::`, TCP and QUIC); many
  mobile networks only make a phone directly reachable over IPv6.
- Android 11+ denies apps the netlink call Go uses to list network
  interfaces, so libp2p alone would only ever advertise loopback addresses.
  Kotlin passes the device's IPs in `Config.InterfaceAddrs`
  (from `ConnectivityManager`), and an `AddrsFactory` advertises each
  unspecified listen address under those IPs, on the ports the OS assigned
  (`node/interface_addrs.go`). Ignored in relay-only mode.
- Reachability is forced to private, because AutoRelay only starts looking
  for a relay after an AutoNAT verdict FRAD's small network may never produce:
  without it a phone never reserves a relay slot on its bootstrap nodes and
  can't be reached when direct dialing fails.

## Building the `.aar` for the app

```bash
# from the repo root:
./gradlew gomobileBind
```

Requires Go (the version `go.mod` names; `GOTOOLCHAIN=auto` fetches it) and the
Android NDK (`ANDROID_NDK_HOME`, or a side-by-side NDK under `ANDROID_HOME`).
`gomobile` and `gobind` don't need installing: they're pinned as tool
dependencies in `go.mod`, and the task installs exactly those into
`p2p-go/build/bin` (no `gomobile init`, which would fetch the latest `gobind`)
and binds with `-trimpath -ldflags="-s -w -checklinkname=0"` (go-libp2p's `anet` links to `net.zoneCache`) for `android/arm`, `android/arm64`
and `android/amd64`, API 26. This produces `p2p-go/build/p2pgo.aar`, which
`app/build.gradle.kts` picks up automatically on the next build — see the
root README's "Building" section. The CI job `android-real-p2p` does exactly
this on every push, so the real Kotlin wrapper can't silently drift from the
Go API again.

## Verifying without a device

```bash
gofmt -l .
go vet ./...
go test -race ./...
```

Everything runs on loopback, with real libp2p hosts:

- `node`: DHT rendezvous and an `OpenStream` round trip between two app hosts
  via a bootstrap node; a chat stream over a relayed (limited) connection;
  relay-only mode never connecting directly; a default-mode host reserving a
  relay slot on its own; the interface-address workaround; node-list
  validation, merging and wire limits; the exchange handler's per-peer
  limits; app-to-app and app-to-server exchanges; a commented bootstrap
  list; address verification (claimed addresses that don't answer, a
  dial-back reaching some other peer id, a server announcing only a DNS
  name) and the dial gater.
- `cmd/bootstrap`: federation via `-peer` (including a shared DHT), a server
  learned through gossip being verified and persisted, non-servers,
  unreachable candidates and a server claiming addresses it isn't at being
  dropped, connected servers queued by who dialed whom, the official list
  (with mirror fallback and size cap), `-announce`, and the store's expiry,
  per-network caps and candidate queues.
- `cmd/nodecrawl`: a three-run crawl that lists exactly the real servers at
  the addresses they answer on, byte-identical output when nothing changes,
  delisting after two misses, the contact budget, level limit and overall
  timeout, the budget shared between networks, bans and pins - plus the
  selection, seeding and state-trimming rules on their own.

What they do **not** prove: behavior across the real internet and through
real NAT/hole punching — that needs two physical devices and a real,
publicly-reachable `cmd/bootstrap` instance.
