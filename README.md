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
2. **Wide-range / GPS-radius (planned, M4)** — an open, decentralized
   discovery protocol (go-libp2p, following the same proven approach the
   [Berty](https://github.com/berty/berty) messenger uses on mobile) so you
   can find people within a chosen radius over the internet. No single party
   operates or controls this — anyone can run a bootstrap/relay node, and the
   node list is user-configurable rather than hard-locked, which is also what
   keeps this out of F-Droid's "Non-Free/Tethered Network Services"
   anti-feature categories. M3's file transfer only covers the local layer
   (Wi-Fi Direct, same short range as BLE) — M4 needs its own file-transfer
   path over whatever connection carries a wide-range chat (direct socket
   when both peers are reachable, a relay node otherwise for peers behind
   NAT), since Wi-Fi Direct can't reach across the internet.

Every chat is end-to-end encrypted (Noise_XX handshake, X25519 + ChaCha20-
Poly1305) directly between the two phones, regardless of which discovery layer
found them.

## Privacy & safety design

- No account, phone number, or email, ever. Identity is a random keypair
  generated on-device.
- Exact GPS coordinates are never transmitted or stored remotely — the local
  layer never touches GPS at all, and the planned wide layer only ever shares
  a coarse geohash cell.
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
- M4–M6 (the wide-range DHT layer, abuse hardening, F-Droid release
  packaging): not started.

**Versioning:** stay under `1.0.0` until M2–M6 above are done — a `1.0` tag
implies feature-complete, which this isn't yet.

## Downloads

Every push to `master` is built and republished as the ["latest"
release](../../releases/tag/latest) on the Releases page — grab the APK there
if you just want to install it without building anything. Tagged versions
(`vX.Y.Z`) get their own numbered release the same way. These builds are
currently signed with the Gradle-generated debug key (see `app/build.gradle.kts`)
rather than a dedicated release key, since there's no other distribution
channel yet — that's fine for installing directly, but will change before this
ships anywhere like F-Droid or Play.

## Building

Requires JDK 17+ and the Android SDK (easiest: open the project root in a
recent Android Studio and let it configure both).

```bash
./gradlew test            # crypto + pairing + framing unit tests, no device needed
./gradlew assembleDebug   # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease # builds app/build/outputs/apk/release/app-release.apk
```

Install the APK on two physical Android phones (API 26+) to test the actual
BLE discovery/chat flow — grant the Bluetooth permission prompt on both,
toggle "Become visible nearby" on both, then "Chat with someone nearby" on
one.

## Project layout

- `crypto/` — identity keypair + the Noise_XX end-to-end encryption handshake
  and session. Transport-agnostic; reused by the wide-range layer later.
  `TransferCipher` encrypts a Wi-Fi Direct file transfer with a key derived
  from the chat's Noise session (`ChatSession.deriveTransferKey`) but
  independent of the chat's own message key.
- `ble/` — BLE presence advertising/scanning (`BlePeripheralServer`,
  `BleCentralClient`), message fragmentation over the GATT MTU (`Framing`),
  and `BleChatController`, which wires all of the above plus pairing/safety
  and Wi-Fi Direct file-transfer orchestration into the state machine the UI
  drives.
- `wifidirect/` — `WifiDirectTransferManager`, the one place `WifiP2pManager`
  is touched: creates/joins a one-off Wi-Fi Direct group per file transfer
  and streams the encrypted bytes over a socket. API 29+ only.
- `pairing/` — `RandomMatcher`, the on-device "pick someone nearby" logic.
- `profile/` — the user's local, freely-editable pseudonym, shown together with a
  short tag derived from the peer id so two people with the same pseudonym stay
  distinguishable. Exchanged with a peer right after the Noise handshake completes.
- `contacts/` — on-device address book of peers saved from a past chat
  (`ContactStore`), plus their persisted chat transcript (`ChatHistoryStore`,
  saved contacts only).
- `data/` — `MediaFileStore`, on-device storage for files sent/received over
  Wi-Fi Direct, one subdirectory per peer; exposed to other apps only via a
  `FileProvider` when the user explicitly opens a received file.
- `safety/` — block list, report flow, request cooldown/rate-limiting.
- `ui/` — Jetpack Compose screens + the `ChatViewModel` that bridges to
  `BleChatController`.

## License

GPLv3 — see [LICENSE](LICENSE).
