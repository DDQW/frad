package me.woelki.friendradar.wideradius

/**
 * One browsing session's wide-range parameters. Compiled always (unlike
 * [WideRangeNode], which has a stub/real variant per [WideRangeNode.isSupported])
 * since it carries no gomobile-specific types.
 *
 * @param identitySeed 32 random bytes, fresh per session — this becomes the
 *   libp2p host's Ed25519 identity, deliberately unrelated to the app's
 *   long-term chat [me.woelki.friendradar.crypto.Identity], the same
 *   rotating-discovery-id/stable-chat-id split BLE's session id already has.
 * @param bootstrapPeers the user-configurable bootstrap/relay node list, each
 *   entry a full multiaddr including a trailing "/p2p/<peerid>" (see
 *   [me.woelki.friendradar.profile.Profile.bootstrapNodes]).
 * @param rendezvousTopic the geohash-derived DHT rendezvous string (see
 *   [Geohash]) both sides advertise/search for.
 */
class WideRangeConfig(
    val identitySeed: ByteArray,
    val bootstrapPeers: List<String>,
    val rendezvousTopic: String,
    val listenPort: Int = 0,
)
