package app.frad.chat.chat

enum class MessageKind { TEXT, FILE }

/** [kind] `FILE` messages carry [fileName]/[mimeType]/[sizeBytes]/[localPath] instead of [text]. */
data class ChatMessage(
    val fromMe: Boolean,
    val text: String,
    val atMillis: Long,
    val kind: MessageKind = MessageKind.TEXT,
    val fileName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
    val localPath: String? = null,
)

/** Maximum size of a file this app will send or accept over any transport (Wi-Fi Direct or
 *  wide-range) — guards against accidental huge sends and against a peer claiming an
 *  implausible size in a file offer. */
const val MAX_TRANSFER_FILE_BYTES = 25L * 1024 * 1024

sealed interface ChatUiState {
    /** Broadcasting is off; nothing is happening. */
    data object Idle : ChatUiState

    /** Opted in: advertising presence and scanning for others who are too. [warning] says why
     *  others may not be able to find us right now (e.g. advertising unsupported on this phone),
     *  even though we can still find them. */
    data class Browsing(
        val nearbyPeers: List<app.frad.chat.pairing.NearbyPeer>,
        val warning: String? = null,
    ) : ChatUiState

    /** Opted in, but the radio this layer needs is unavailable right now (e.g. Bluetooth is
     *  switched off). Resumes on its own once it's back. */
    data class Paused(val reason: String) : ChatUiState

    data class Connecting(val target: app.frad.chat.pairing.NearbyPeer) : ChatUiState
    data object Handshaking : ChatUiState
    data class Chatting(
        val remotePeerId: String,
        val remoteDeviceFingerprint: String,
        val remotePseudonym: String,
        val remoteGender: app.frad.chat.profile.Gender,
        val remoteAge: Int?,
        val remoteBio: String,
        val remotePhoto: ByteArray?,
        val messages: List<ChatMessage>,
    ) : ChatUiState
    data class Ended(val reason: String) : ChatUiState
}
