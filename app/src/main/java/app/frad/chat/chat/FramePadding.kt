package app.frad.chat.chat

/**
 * Encrypted frames reveal their length, and with it more than it seems: how long a message is
 * (or that it's just "typing…"/a receipt), roughly how big a profile photo is. Every encrypted
 * JSON frame is therefore padded with trailing spaces to a multiple of [BLOCK] bytes before
 * encryption - JSON ignores trailing whitespace, so this needs no format change and older peers
 * read padded frames as before.
 */
internal object FramePadding {
    /** Big enough that short texts, typing notices and receipts look alike; small enough not to
     *  slow BLE's tiny writes down noticeably. */
    const val BLOCK = 64

    fun pad(json: String): String {
        val length = json.toByteArray(Charsets.UTF_8).size
        val padding = (BLOCK - length % BLOCK) % BLOCK
        return if (padding == 0) json else json + " ".repeat(padding)
    }

    fun unpad(json: String): String = json.trimEnd(' ')
}
