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
    data class Text(val text: String) : ChatEnvelope

    /** BLE chats: the file itself follows over a one-off Wi-Fi Direct group, whose network
     *  name/passphrase ride along here. */
    data class WfdOffer(val offer: FileOffer, val networkName: String, val passphrase: String) : ChatEnvelope

    /** Wide-range chats: the file itself follows on a second libp2p stream. */
    data class WideOffer(val offer: FileOffer) : ChatEnvelope

    /** A kind this version doesn't know. Ignored rather than treated as an error, so a newer
     *  peer adding a message type (typing indicator, receipts, ...) doesn't break older ones. */
    data class Unknown(val kind: String) : ChatEnvelope
}

object ChatEnvelopeJson {
    private const val KIND_TEXT = "txt"
    private const val KIND_WFD_OFFER = "wfd"
    private const val KIND_WIDE_OFFER = "wide-transfer"

    fun encode(envelope: ChatEnvelope): String = when (envelope) {
        is ChatEnvelope.Text -> JSONObject().put("k", KIND_TEXT).put("t", envelope.text)
        is ChatEnvelope.WfdOffer -> putOffer(JSONObject().put("k", KIND_WFD_OFFER), envelope.offer)
            .put("ssid", envelope.networkName)
            .put("pass", envelope.passphrase)
        is ChatEnvelope.WideOffer -> putOffer(JSONObject().put("k", KIND_WIDE_OFFER), envelope.offer)
        is ChatEnvelope.Unknown -> JSONObject().put("k", envelope.kind)
    }.toString()

    /** @throws org.json.JSONException or [IllegalArgumentException] if [json] is malformed or a
     *  known kind is missing a required field - a protocol error the caller should answer by
     *  dropping the connection. */
    fun decode(json: String): ChatEnvelope {
        val obj = JSONObject(json)
        return when (val kind = obj.getString("k")) {
            KIND_TEXT -> ChatEnvelope.Text(obj.getString("t"))
            KIND_WFD_OFFER -> ChatEnvelope.WfdOffer(
                offer = readOffer(obj),
                networkName = obj.getString("ssid"),
                passphrase = obj.getString("pass"),
            )
            KIND_WIDE_OFFER -> ChatEnvelope.WideOffer(readOffer(obj))
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
