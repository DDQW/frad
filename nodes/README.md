# FRAD official node list

`nodes.txt` lists public FRAD servers ([`p2p-go/cmd/bootstrap`](../p2p-go/README.md)):
the bootstrap/relay nodes the wide-range layer needs to find anyone at all. It
is one of several ways a FRAD participant learns about servers, and on purpose
not the only one:

- every server keeps its own list of the servers it has verified (`-nodes-file`),
  stays connected to the servers it federates with (`-peer`), and swaps lists
  with every server and app it talks to (the `/frad/nodes/1.0.0` protocol, see
  [`p2p-go/README.md`](../p2p-go/README.md#node-directory-and-federation));
- apps swap lists with their bootstrap nodes and with each other;
- servers fetch this file every 6 hours as one more source of candidates.

So if this file, this repository or GitHub disappears, nothing that's already
running loses the network: it only stops being a convenient starting point
for newcomers.

## Where to fetch it

The same file, served by two independent CDNs:

- https://raw.githubusercontent.com/DDQW/frad/master/nodes/nodes.txt
- https://cdn.jsdelivr.net/gh/DDQW/frad@master/nodes/nodes.txt

`cmd/bootstrap` tries them in that order (`-official-list-url`). The format is
one multiaddr per line, each ending in `/p2p/<peerid>`; lines starting with `#`
are comments.

## Who gets listed

Anyone. There is no registration and no application: run `cmd/bootstrap` on a
publicly reachable address (`-announce` it if the machine is behind NAT), and
connect it to the network - `-peer` an existing server, or just let it be found
through the official list and the servers' own gossip. The crawler
([`p2p-go/cmd/nodecrawl`](../p2p-go/cmd/nodecrawl), run every 6 hours by
[`nodes-crawl.yml`](../.github/workflows/nodes-crawl.yml)) walks the network
from the current list, and a server is listed once it has passed the crawler's
check in **3 consecutive runs** (12-18 hours). "Passing" means:

- the crawler could connect to it,
- identify shows it runs FRAD's DHT (`/frad/kad/1.0.0`) in server mode - which
  tells a FRAD server apart from a phone or some other libp2p node, and
- it has at least one publicly routable TCP or QUIC address.

The addresses listed are the ones the server reports *for itself* (or the one
the crawler actually reached it on) - never what some other node claimed about
it. Anyone can say "server X lives at Y"; only X can speak for X.

A listed server that misses one crawl keeps its place (it was probably
restarting); one that misses two in a row is delisted and has to earn its 3
sightings again.

## Pinned and banned entries

- `pinned.txt`: servers the maintainers vouch for, e.g. ones they run
  themselves. Always listed, checked first in every crawl, and exempt from the
  sightings rule. Kept short on purpose.
- `banned.txt`: never listed and never contacted. An entry is a peer id, a full
  node address (bans that peer id) or an address prefix such as
  `/ip4/203.0.113.7` (bans every address starting with it). A ban beats a pin.

Both are edited by hand, in a reviewed commit that says why. A malformed line
in either fails the crawl instead of being skipped, so a typo can't silently
drop a pinned server or let a banned one back in.

## Why these rules: list poisoning

The list is a target: whoever fills it decides where new users go first. The
rules make that expensive rather than impossible.

- **3 consecutive sightings** - an attacker has to keep real servers (running
  FRAD's DHT, answering on public addresses) up for most of a day before they
  are listed, rather than dumping a burst of fake entries into the gossip.
  Nodes that come and go don't qualify either.
- **Diversity caps** - at most 2 servers per IPv4 /24, IPv6 /48 or DNS domain
  (any `*.example.org` name counts as `example.org`), and at most 4 addresses
  per server. Many servers on one machine, one rented address block or one
  domain look like many entries but are one operator; the caps keep them from
  crowding out independently run servers. The same caps apply wherever a FRAD
  node merges lists, not just here.
- **Incumbents first** - when there are more qualifying servers than the 100
  entries the list holds, servers already listed keep their place and
  newcomers fill what's left. Flooding the network with fresh servers can't
  push established ones out.
- **Pinned entries take their share first** - pinned servers count against the
  diversity caps before anything else is considered.
- **Everyone checks for themselves** - servers treat this list (like any list)
  as candidates and only vouch for servers they have verified themselves; apps
  should do the same. The official list speeds things up; it is not an
  authority.

What this does **not** protect against: an attacker willing to run many real
servers across many networks for a long time, and the maintainers of this
repository themselves (who control `pinned.txt`, `banned.txt` and the crawler).
The second is why no participant depends on this list alone.

## Files

- `nodes.txt` - the list. Generated; don't edit by hand.
- `pinned.txt`, `banned.txt` - maintained by hand, see above.
- `crawl-state.json` - what the crawler remembers between runs: each tracked
  server's addresses and how many consecutive runs it has been seen alive. It
  holds no timestamps, so a crawl that finds nothing new writes identical files
  and the workflow commits nothing.

To run a crawl locally (it reads and rewrites the files in place):

```bash
cd p2p-go
go run ./cmd/nodecrawl -dir ../nodes
```
