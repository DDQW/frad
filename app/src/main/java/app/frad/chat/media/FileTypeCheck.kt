package app.frad.chat.media

/**
 * The type a received file is *shown and opened as* comes from the sender's offer - so it's
 * checked against the file's first bytes. A "photo.jpg" that is really an APK or a script must
 * not be handed to the system as an image (or shown with an image preview); when what the bytes
 * are doesn't match what the sender claimed, the file is treated as an unknown binary instead,
 * which Android only opens after the user picks an app for it.
 */
object FileTypeCheck {
    const val UNKNOWN = "application/octet-stream"

    fun verifiedMimeType(bytes: ByteArray, claimed: String): String {
        val claimedLower = claimed.lowercase()
        val detected = detect(bytes)
        return when {
            // Packages are never what a chat file claims to be.
            detected == "application/vnd.android.package-archive" -> UNKNOWN
            claimedLower.startsWith("image/") || claimedLower.startsWith("video/") || claimedLower.startsWith("audio/") ->
                if (detected != null && detected.substringBefore('/') == claimedLower.substringBefore('/')) detected else UNKNOWN
            detected != null && detected.substringBefore('/') in MEDIA_TYPES -> detected
            else -> claimedLower.takeIf { it.matches(MIME_PATTERN) } ?: UNKNOWN
        }
    }

    /** The type the content itself announces, for the formats a chat realistically carries. */
    internal fun detect(bytes: ByteArray): String? = when {
        bytes.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        bytes.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
        bytes.startsWith('G'.code, 'I'.code, 'F'.code, '8'.code) -> "image/gif"
        bytes.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) && bytes.hasAt(8, "WEBP") -> "image/webp"
        bytes.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) && bytes.hasAt(8, "WAVE") -> "audio/wav"
        bytes.hasAt(4, "ftyp") -> isoMediaType(bytes)
        bytes.startsWith(0x1A, 0x45, 0xDF, 0xA3) -> "video/webm"
        bytes.startsWith('O'.code, 'g'.code, 'g'.code, 'S'.code) -> "audio/ogg"
        bytes.startsWith('I'.code, 'D'.code, '3'.code) || bytes.startsWith(0xFF, 0xFB) || bytes.startsWith(0xFF, 0xF3) -> "audio/mpeg"
        bytes.startsWith('%'.code, 'P'.code, 'D'.code, 'F'.code) -> "application/pdf"
        bytes.startsWith('P'.code, 'K'.code, 0x03, 0x04) -> if (bytes.containsAscii("AndroidManifest.xml")) "application/vnd.android.package-archive" else "application/zip"
        else -> null
    }

    /** ISO base media files: the brand after "ftyp" tells HEIC/AVIF pictures from MP4/3GP/M4A. */
    private fun isoMediaType(bytes: ByteArray): String {
        val brand = if (bytes.size >= 12) String(bytes, 8, 4, Charsets.US_ASCII) else ""
        return when (brand) {
            "heic", "heix", "mif1", "msf1" -> "image/heic"
            "avif", "avis" -> "image/avif"
            "M4A " -> "audio/mp4"
            "3gp4", "3gp5", "3gp6", "3g2a" -> "video/3gpp"
            else -> "video/mp4"
        }
    }

    private val MEDIA_TYPES = setOf("image", "video", "audio")
    private val MIME_PATTERN = Regex("[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}")

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it].toInt() and 0xFF == prefix[it] }

    private fun ByteArray.hasAt(offset: Int, ascii: String): Boolean =
        size >= offset + ascii.length && ascii.indices.all { this[offset + it].toInt() == ascii[it].code }

    /** Zip entry names are stored in plain ASCII in the local headers near the start. */
    private fun ByteArray.containsAscii(needle: String, searchLimit: Int = 64 * 1024): Boolean {
        val limit = minOf(size, searchLimit) - needle.length
        for (i in 0..limit) if (hasAt(i, needle)) return true
        return false
    }
}
