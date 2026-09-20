package me.woelki.friendradar.ble

import java.util.UUID

/**
 * FRAD's BLE GATT profile. One custom service with two characteristics
 * used as a simple bidirectional byte stream: the central (the peer who
 * initiated the connection) writes into [INBOX_CHARACTERISTIC_UUID], and the
 * peripheral (GATT server) pushes its replies out via notifications on
 * [OUTBOX_CHARACTERISTIC_UUID]. Both carry [FrameWriter]-framed bytes — first
 * the three Noise_XX handshake messages, then encrypted chat ciphertexts.
 *
 * UUIDs are randomly generated and specific to this app/protocol version.
 */
object GattProfile {
    val SERVICE_UUID: UUID = UUID.fromString("6f7269e0-2b1a-4c3e-9c0a-2f6a1b8d0a01")
    val INBOX_CHARACTERISTIC_UUID: UUID = UUID.fromString("6f7269e1-2b1a-4c3e-9c0a-2f6a1b8d0a01")
    val OUTBOX_CHARACTERISTIC_UUID: UUID = UUID.fromString("6f7269e2-2b1a-4c3e-9c0a-2f6a1b8d0a01")
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Advertised alongside the service UUID so scanners can tell "available to chat" peers
     *  apart without connecting; rotates every [me.woelki.friendradar.pairing] session, see
     *  that package for why it must never be the long-term identity key. */
    const val MAX_ADVERTISED_SESSION_ID_BYTES = 8

    const val DEFAULT_MTU = 517 // maximum allowed by the platform; falls back gracefully if refused
    const val ATT_HEADER_SIZE = 3
    const val LEGACY_FRAGMENT_SIZE = 20 - ATT_HEADER_SIZE // if MTU negotiation is refused entirely
}
