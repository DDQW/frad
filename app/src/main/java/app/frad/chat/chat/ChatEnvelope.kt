package app.frad.chat.chat

import org.json.JSONObject

/** Longest chat text a user can send, in chars - keeps even a fully JSON-escaped envelope well
 *  under [app.frad.chat.ble.MAX_FRAME_BYTES], which the receiving side enforces. */
const val MAX_MESSAGE_CHARS = 4000

private const val MAX_FILE_NAME_CHARS = 255
private const val MAX_MIME_TYPE_CHARS = 127
private const val MAX_TRANSFER_ID_CHARS = 64

/** Metadata of a file offered over either transport. [transferId] is the sender's
 *  [app.frad.chat.crypto.TransferCipher.newTransferId], needed to derive this transfer's key. */
data class FileOffer(val transferId: String, val fileName: String, val mimeType: String, val sizeBytes: Long)

/**
 * The encrypted messages both [ChatController]s exchange once a chat is open - a small JSON
 * object whose `"k"` field says what it is. Shared so BLE and wide-range parse (and validate)
 * peer input the same way.
 */
sealed interface ChatEnvelope {
    /** [id] lets the receiver confirm delivery with an [Ack]. */
    data class Text(val text: String, val id: String? = null) : ChatEnvelope

    /** "Your message [id] arrived." */
    data class Ack(val id: String) : ChatEnvelope

    /** "I'm typing" - sent at most every few seconds while the user types; shown briefly. */
    data object Typing : ChatEnvelope

    /** "May I send you this file?" - nothing is transferred until the peer answers with an
     *  accepting [FileReply]; the transport-specific offer ([WfdOffer]/[WideOffer]) for the
     *  same transfer id follows only then. */
    data class FileRequest(val offer: FileOffer) : ChatEnvelope

    /** The answer to a [FileRequest]. */
    data class FileReply(val transferId: String, val accepted: Boolean) : ChatEnvelope

    /** BLE chats: the file itself follows over a one-off Wi-Fi Direct group, whose network
     *  name/passphrase ride along here. */
    data class WfdOffer(val offer: FileOffer, val networkName: String, val passphrase: String) : ChatEnvelope

    /** Wide-range chats: the file itself follows on a second libp2p stream. */
    data class WideOffer(val offer: FileOffer) : ChatEnvelope

    /** "Shall we swap photos?" - for profiles that keep the photo back (Profile.photoOnRequest). */
    data object PhotoRequest : ChatEnvelope

    /** The answer to a [PhotoRequest], or the requester's own photo after a yes: [photo] is the
     *  base64 thumbnail, null for "no thanks". */
    data class PhotoReply(val photo: String?) : ChatEnvelope

    /** A kind this version doesn't know. Ignored rather than treated as an error, so a newer
     *  peer adding a message type (typing indicator, receipts, ...) doesn't break older ones. */
    data class Unknown(val kind: String) : ChatEnvelope
}

object ChatEnvelopeJson {
    private const val KIND_TEXT = "txt"
    private const val KIND_ACK = "ack"
    private const val KIND_TYPING = "typing"
    private const val MAX_MESSAGE_ID_CHARS = 64
    private const val KIND_WFD_OFFER = "wfd"
    private const val KIND_WIDE_OFFER = "wide-transfer"
    private const val KIND_FILE_REQUEST = "file-req"
    private const val KIND_FILE_REPLY = "file-reply"
    private const val KIND_PHOTO_REQUEST = "photo-req"
    private const val KIND_PHOTO_REPLY = "photo"

    fun encode(envelope: ChatEnvelope): String = when (envelope) {
        is ChatEnvelope.Text -> JSONObject().put("k", KIND_TEXT).put("t", envelope.text).also { obj -> envelope.id?.let { obj.put("id", it) } }
        is ChatEnvelope.Ack -> JSONObject().put("k", KIND_ACK).put("id", envelope.id)
        ChatEnvelope.Typing -> JSONObject().put("k", KIND_TYPING)
        is ChatEnvelope.WfdOffer -> putOffer(JSONObject().put("k", KIND_WFD_OFFER), envelope.offer)
            .put("ssid", envelope.networkName)
            .put("pass", envelope.passphrase)
        is ChatEnvelope.WideOffer -> putOffer(JSONObject().put("k", KIND_WIDE_OFFER), envelope.offer)
        is ChatEnvelope.FileRequest -> putOffer(JSONObject().put("k", KIND_FILE_REQUEST), envelope.offer)
        is ChatEnvelope.FileReply -> JSONObject().put("k", KIND_FILE_REPLY).put("tid", envelope.transferId).put("ok", envelope.accepted)
        ChatEnvelope.PhotoRequest -> JSONObject().put("k", KIND_PHOTO_REQUEST)
        is ChatEnvelope.PhotoReply -> JSONObject().put("k", KIND_PHOTO_REPLY).also { obj -> envelope.photo?.let { obj.put("p", it) } }
        is ChatEnvelope.Unknown -> JSONObject().put("k", envelope.kind)
    }.toString()

    /** @throws org.json.JSONException if [json] isn't an envelope at all, or a text message is
     *  malformed - a protocol error the caller should answer by dropping the connection. A file
     *  offer that doesn't check out (see [readOffer]) only costs that one file: it comes back as
     *  [ChatEnvelope.Unknown], i.e. ignored, rather than ending the whole chat. */
    fun decode(json: String): ChatEnvelope {
        val obj = JSONObject(json)
        return when (val kind = obj.getString("k")) {
            KIND_TEXT -> ChatEnvelope.Text(obj.getString("t"), obj.optString("id", "").take(MAX_MESSAGE_ID_CHARS).ifEmpty { null })
            KIND_ACK -> obj.optString("id", "").take(MAX_MESSAGE_ID_CHARS).ifEmpty { null }?.let { ChatEnvelope.Ack(it) } ?: ChatEnvelope.Unknown(kind)
            KIND_TYPING -> ChatEnvelope.Typing
            KIND_WFD_OFFER -> runCatching {
                ChatEnvelope.WfdOffer(offer = readOffer(obj), networkName = obj.getString("ssid"), passphrase = obj.getString("pass"))
            }.getOrElse { ChatEnvelope.Unknown(kind) }
            KIND_WIDE_OFFER -> runCatching { ChatEnvelope.WideOffer(readOffer(obj)) }.getOrElse { ChatEnvelope.Unknown(kind) }
            KIND_FILE_REQUEST -> runCatching { ChatEnvelope.FileRequest(readOffer(obj)) }.getOrElse { ChatEnvelope.Unknown(kind) }
            KIND_FILE_REPLY -> runCatching {
                val transferId = obj.getString("tid")
                require(transferId.length in 1..MAX_TRANSFER_ID_CHARS) { "Invalid transfer id length ${transferId.length}" }
                ChatEnvelope.FileReply(transferId, obj.getBoolean("ok"))
            }.getOrElse { ChatEnvelope.Unknown(kind) }
            KIND_PHOTO_REQUEST -> ChatEnvelope.PhotoRequest
            KIND_PHOTO_REPLY -> ChatEnvelope.PhotoReply(obj.optString("p", "").ifEmpty { null })
            else -> ChatEnvelope.Unknown(kind)
        }
    }

    private fun putOffer(obj: JSONObject, offer: FileOffer): JSONObject = obj
        .put("tid", offer.transferId)
        .put("name", offer.fileName.take(MAX_FILE_NAME_CHARS))
        .put("mime", offer.mimeType.take(MAX_MIME_TYPE_CHARS))
        .put("size", offer.sizeBytes)

    private fun readOffer(obj: JSONObject): FileOffer {
        val transferId = obj.getString("tid")
        require(transferId.length in 1..MAX_TRANSFER_ID_CHARS) { "Invalid transfer id length ${transferId.length}" }
        val sizeBytes = obj.getLong("size")
        require(sizeBytes >= 0) { "Negative file size $sizeBytes" }
        return FileOffer(
            transferId = transferId,
            fileName = obj.getString("name").take(MAX_FILE_NAME_CHARS),
            mimeType = obj.getString("mime").take(MAX_MIME_TYPE_CHARS),
            sizeBytes = sizeBytes,
        )
    }
}
