# FRAD

A GPS/Bluetooth-based random chat app for meeting people nearby — fully
peer-to-peer, no account, no company-run backend, 100% free and open source
software, built for distribution on [F-Droid](https://f-droid.org).

## Why P2P instead of a normal server-backed app?

Two strangers' phones can't just find each other's IP address with zero
infrastructure — but "zero infrastructure" and "no *company-owned* backend"
are different things. FRAD uses two discovery layers:

1. **Local (implemented, M1)** — Bluetooth LE advertising/scanning. No
   internet, no server of any kind, works within roughly 10–100m. This is the
   layer the current code implements.
2. **Wide-range / GPS-radius (implemented, not yet verified, M4)** — an open,
   decentralized discovery protocol (go-libp2p, following the same proven
   approach the [Berty](https://github.com/berty/berty) messenger uses on
   mobile) so you can find people within a chosen radius over the internet.
   No single party operates or controls this — anyone can run a
   bootstrap/relay node ([`p2p-go/cmd/bootstrap`](p2p-go/README.md)), and the
   node list is user-configurable rather than hard-locked, which is also what
   keeps this out of F-Droid's "Non-Free/Tethered Network Services"
   anti-feature categories. M3's file transfer only covers the local layer
   (Wi-Fi Direct, same short range as BLE) — M4 has its own file-transfer
   path over whatever connection carries a wide-range chat: a second libp2p
   stream to the peer, direct or relayed entirely at libp2p's own discretion,
   since Wi-Fi Direct can't reach across the internet.

Every chat is end-to-end encrypted (Noise_XX handshake, X25519 + ChaCha20-
Poly1305) directly between the two phones, regardless of which discovery layer
found them.

## Privacy & safety design

- No account, phone number, or email, ever. Identity is a random keypair
  generated on-device.
- Exact GPS coordinates are never transmitted or stored remotely — the local
  layer never touches GPS at all, and the wide layer only ever shares a
  coarse geohash cell (city-sized or coarser; see `wideradius/Geohash.kt` and
  `wideradius/CoarseLocation.kt`, which reduces a location fix to a geohash
  and discards the raw coordinate in the same function call).
- "Available to chat" is an explicit opt-in toggle, off by default, not a
  silent background broadcast.
- The rotating id you're discovered by is *not* your long-term identity key —
  a peer only learns who they actually matched with once an encrypted session
  is already established with them specifically, so passively scanning for
  nearby devices can't be used to build a tracking profile.
- On-device block list and report flow (there's no central authority to
  report *to*, so "report" = immediately block + keep a local note of why).

See the full milestone/architecture rationale in the original design doc if
you have it, or ask in an issue — the summary above is the durable version.

## Project status

- **M0 — scaffold**: done (this commit).
- **M1 — local BLE chat MVP**: implemented, and **builds cleanly** —
  `./gradlew test` (18 tests: Noise_XX handshake round-trip/tamper-detection,
  message framing, random matching, cooldown) and `./gradlew assembleDebug`
  both pass. What's *not* yet verified is the real BLE radio path — that
  needs two physical phones (BLE doesn't work realistically in the emulator):
  install the debug APK on both, toggle "Become visible nearby" on both, then
  "Chat with someone nearby" on one, and see whether discovery/pairing/chat
  actually works end-to-end over real Bluetooth hardware.
- **M2 — persistence/UX polish**: implemented. Pseudonym/profile, contacts and
  the block list all persist on-device (`SharedPreferences`), and so does chat
  history — but only for peers you've explicitly saved as a contact; a chat
  with anyone else leaves nothing on disk once it ends, and removing a contact
  erases their history too. Saved history is viewable read-only from the
  Contacts tab (you can't message a saved contact on demand, since discovery
  is still anonymous/rotating — you can only see what was said in past chats).
- **M3 — Wi-Fi Direct file transfer**: implemented for Android 10+ (API 29).
  Once two phones are chatting over BLE, either side can attach any file (up
  to 25 MB); the two phones form a one-off Wi-Fi Direct group (its network
  name/passphrase relayed over the already-encrypted BLE channel) and stream
  the file over a socket, encrypted with a key derived from that same chat's
  Noise session — never the chat's own message key, so a concurrent text
  message and file transfer can't collide on one nonce counter. Received
  files are auto-accepted (same trust model as text messages) and shown
  inline if they're an image, or as a name/size chip with an "Open" button
  otherwise. Below API 29, the attach button doesn't appear — BLE text chat
  is unaffected. Like chat history, a file is only kept on disk once its
  peer is a saved contact. **Not yet verified**: the actual Wi-Fi Direct
  radio path needs two physical Android 10+ phones, the same real-hardware
  caveat M1's BLE path has.
- **M4 — wide-range (go-libp2p) discovery, chat, and file transfer**:
  implemented. A Kademlia DHT scoped to FRAD's own isolated `/frad` protocol
  namespace (see [`p2p-go/`](p2p-go/README.md), built into an Android `.aar`
  via `gomobile bind`) does peer discovery by geohash-derived rendezvous
  topic; once found, the same Noise_XX session type BLE uses drives an
  encrypted chat and, on request, a second libp2p stream for file transfer
  (25 MB cap, same as M3), with direct-vs-relayed dialing left entirely to
  libp2p. The chat/discovery Kotlin architecture was also generalized (see
  `chat/`) so BLE and wide-range share one `ChatController` interface and UI.
  **Not yet verified**: unlike M1/M3, this isn't just a real-hardware gap —
  a wide-range DHT has no peers until something connects to a bootstrap
  node (see the "chicken-and-egg" note in `p2p-go/README.md`), so verifying
  this end-to-end needs *both* two physical devices with internet access
  *and* an actually-running `p2p-go/cmd/bootstrap` instance reachable by
  both, neither of which this repo provides. What **is** locally verified
  (a Go test, not a device test): DHT rendezvous and a relay-transparent
  stream round trip both work between three in-process libp2p hosts (see
  `p2p-go/node`'s test suite).
- **M5 — abuse hardening**: in progress. So far: blocking is now resilient to identity resets —
  right after the Noise handshake, both sides also exchange a hashed, per-device fingerprint
  (`safety/DeviceFingerprint.kt`, derived from `Settings.Secure.ANDROID_ID`) and `BlockList`
  matches on either it or the long-term peer id, so someone who clears app data or reinstalls to
  shake off a block is still caught as long as it's the same physical device. Not yet done:
  wide-range Sybil/spam resistance (e.g. proof-of-work — see `safety/Cooldown.kt`) and
  bootstrap/relay DoS protection.
- **M6 — F-Droid release packaging**: in-repo groundwork done, submission not started. F-Droid
  builds each app from source and signs it with F-Droid's own key, so nothing here is required
  for that build itself to work — the two things this milestone actually covers are (1) making
  this repo's *own* GitHub-Releases APK signable with a real key instead of the Gradle debug
  keystore, and (2) making sure the build is one F-Droid's reproducible-builds pipeline can
  verify. So far: `app/build.gradle.kts` reads an optional release signing key from a
  gitignored `keystore.properties` (see `keystore.properties.sample`) or equivalently-named env
  vars, falling back to debug signing when neither is present (which is still true today — no
  real key has been generated yet); `.github/workflows/release.yml` decodes and wires that key
  in from repo secrets when configured; until then, the debug fallback itself now uses a fixed,
  checked-in `ci-debug.keystore` instead of AGP's implicit per-machine one, so consecutive CI
  builds share a signing identity and install as updates over each other (previously every CI
  run minted a new random debug key, breaking updates with a signing-certificate mismatch). Every
  Gradle/Kotlin/AndroidX/Bouncy Castle dependency
  version here is already pinned exactly (no `+`/dynamic ranges), and `p2p-go/go.mod` +
  `go.sum` pin the Go side the same way, both of which reproducible builds need. The
  `metadata/en-US/` fastlane-format description F-Droid's listing uses already exists. Not yet
  done: actually generating and safely storing a maintainer release key, submitting a build
  recipe/metadata PR to the separate [fdroiddata](https://gitlab.com/fdroid/fdroiddata) repo,
  and getting a real F-Droid reproducible-build pass (their `gomobile`/NDK toolchain pin for
  `p2p-go/` hasn't been checked against what F-Droid's build server provides).

**Versioning:** stay under `1.0.0` until M2–M6 above are done — a `1.0` tag
implies feature-complete, which this isn't yet.

## Downloads

Every push to `master` is built and republished as the ["latest"
release](../../releases/tag/latest) on the Releases page — grab the APK there
if you just want to install it without building anything. Tagged versions
(`vX.Y.Z`) get their own numbered release the same way. These builds are
currently signed with a fixed, checked-in, deliberately non-secret debug
key (`ci-debug.keystore`) rather than a dedicated release key, since no
maintainer key has been generated yet — that's fine for installing directly,
and every build shares the same signing identity so updating over a
previous install works, but it offers no protection against someone else
rebuilding an APK that also verifies against it. That'll change once a real
release key exists (see `app/build.gradle.kts` and
`keystore.properties.sample`, part of the M6 groundwork above). F-Droid's
own listing, once it exists, signs with F-Droid's key regardless of any of
this.

**Updating from a build before 2026-09-21:** older APKs were signed with
whatever machine happened to build them — GitHub Actions' auto-generated
debug key, different on every CI run — so installing a current build over one
of those will fail with a signing-certificate mismatch. Uninstall the old one
first; every build from now on shares the fixed key above, so this is a
one-time fix.

**Updating from a build before the `me.woelki.frad` rename:** the app's
package (`applicationId`) was `me.woelki.friendradar` until this project's
internal naming caught up with calling it FRAD everywhere. Android treats a
different `applicationId` as a different app entirely — there's no such
thing as "updating" across that change, and your old install's local data
(profile, contacts, chat history, block list) doesn't carry over. Uninstall
the old one and install fresh; this is also a one-time fix, and combines with
the signing-key one above if you're coming from a build before both.

## Building

Requires JDK 17+ and the Android SDK (easiest: open the project root in a
recent Android Studio and let it configure both).

```bash
./gradlew test            # crypto + pairing + framing + wide-range unit tests, no device needed
./gradlew assembleDebug   # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease # builds app/build/outputs/apk/release/app-release.apk
```

These three never need Go or the Android NDK installed — the wide-range
(M4) layer's Kotlin side compiles against a stub in place of the real
go-libp2p binding whenever that binding hasn't been built (see
`app/build.gradle.kts`), so the app still builds and runs without it, just
with wide-range chat unavailable. To build the real binding:

```bash
go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init  # once, needs Go 1.22+
./gradlew gomobileBind   # needs the Android NDK too (ANDROID_HOME/ANDROID_NDK_HOME set)
```

then rebuild normally. See [`p2p-go/README.md`](p2p-go/README.md) for the Go
side, including running a bootstrap/relay node (required for wide-range
discovery to find anyone at all).

Install the APK on two physical Android phones (API 26+) to test the actual
BLE discovery/chat flow — grant the Bluetooth permission prompt on both,
toggle "Become visible nearby" on both, then "Chat with someone nearby" on
one. Testing the wide-range flow additionally needs the real `.aar` built
in and a reachable bootstrap node configured in Profile on both phones.

## Project layout

- `chat/` — transport-neutral chat types (`ChatUiState`, `ChatMessage`,
  `MessageKind`) and the `ChatController` interface both `BleChatController`
  and `WideRangeChatController` implement, so `ui/` only ever talks to
  "whichever discovery layer is active right now."
- `crypto/` — identity keypair + the Noise_XX end-to-end encryption handshake
  and session. Transport-agnostic; reused as-is by the wide-range layer.
  `TransferCipher` encrypts a file transfer with a key derived from the
  chat's Noise session (`ChatSession.deriveTransferKey`, a different info
  string per transport) but independent of the chat's own message key;
  `ChunkedTransfer` frames those encrypted chunks over a plain suspend
  read/write callback (used by the wide-range layer; `WifiDirectTransferManager`
  keeps its own copy of this logic over a `java.io` socket).
- `ble/` — BLE presence advertising/scanning (`BlePeripheralServer`,
  `BleCentralClient`), message fragmentation over the GATT MTU (`Framing`,
  also reused by the wide-range layer's framing, just never split into more
  than one piece), and `BleChatController`, which wires all of the above plus
  pairing/safety and Wi-Fi Direct file-transfer orchestration into the state
  machine the UI drives.
- `wifidirect/` — `WifiDirectTransferManager`, the one place `WifiP2pManager`
  is touched: creates/joins a one-off Wi-Fi Direct group per file transfer
  and streams the encrypted bytes over a socket. API 29+ only.
- `wideradius/` — the M4 wide-range layer: `WideRangeNode` wraps the
  gomobile-generated go-libp2p binding (see [`p2p-go/`](p2p-go/README.md))
  with the same suspend/`Result` idiom `WifiDirectTransferManager` uses, and
  compiles to a stub instead whenever that binding hasn't been built (see
  "Building" above). `WideRangeChatController` is the wide-range analogue of
  `BleChatController`. `Geohash` (+ `CoarseLocation`) derives the coarse DHT
  rendezvous topic from the user's area.
- `pairing/` — `RandomMatcher`, the on-device "pick someone nearby" logic,
  plus `NearbyPeer`/`SignalStrength`, the discovery-result type shared by
  both BLE and wide-range (BLE has an RSSI; wide-range doesn't).
- `profile/` — the user's local, freely-editable pseudonym, shown together with a
  short tag derived from the peer id so two people with the same pseudonym stay
  distinguishable, plus the wide-range settings (coarse area, search radius,
  bootstrap/relay node list). Pseudonym is exchanged with a peer right after
  the Noise handshake completes.
- `contacts/` — on-device address book of peers saved from a past chat
  (`ContactStore`), plus their persisted chat transcript (`ChatHistoryStore`,
  saved contacts only).
- `data/` — `MediaFileStore`, on-device storage for files sent/received over
  Wi-Fi Direct or wide-range, one subdirectory per peer; exposed to other
  apps only via a `FileProvider` when the user explicitly opens a received file.
- `safety/` — block list, report flow, request cooldown/rate-limiting, and (M5)
  `DeviceFingerprint`, a hashed `ANDROID_ID`-derived id exchanged alongside the peer id so a
  block survives the other side resetting their identity keypair; transport-agnostic, used
  identically by both controllers.
- `ui/` — Jetpack Compose screens + the `ChatViewModel`, which now holds one
  `BleChatController` and one `WideRangeChatController` and routes between
  them by `ChatMode`.
- [`p2p-go/`](p2p-go/README.md) — the Go side of M4: `node/` (built into an
  Android `.aar` via `gomobile bind`) and `cmd/bootstrap/` (a standalone
  bootstrap/relay daemon anyone can run — see that README for why one is
  required and how to run it).

## License

GPLv3 — see [LICENSE](LICENSE).
