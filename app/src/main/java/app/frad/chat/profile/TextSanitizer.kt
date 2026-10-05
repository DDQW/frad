package app.frad.chat.profile

/**
 * Cleans free text a peer chose (pseudonym, bio) before it's shown. Removes control and
 * Unicode "format" characters - bidi overrides/isolates and zero-width characters among them,
 * which could otherwise make a pseudonym render as something it isn't, e.g. visually swap the
 * `#TAG` that [Profile.displayName] appends to tell people apart - then caps the length by code
 * point, so a surrogate pair is never cut in half.
 */
object TextSanitizer {
    fun clean(text: String, maxLength: Int, allowNewlines: Boolean = false): String {
        val kept = StringBuilder()
        var count = 0
        var i = 0
        while (i < text.length && count < maxLength) {
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)
            val type = Character.getType(codePoint)
            val allowed = when {
                codePoint == '\n'.code -> allowNewlines
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() -> false
                type == Character.UNASSIGNED.toInt() || type == Character.PRIVATE_USE.toInt() -> false
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt() -> false
                else -> true
            }
            if (allowed) {
                kept.appendCodePoint(codePoint)
                count++
            }
        }
        return kept.toString().trim()
    }

    /** A pseudonym: one line, no `#` (reserved for the tag [Profile.displayName] adds). */
    fun pseudonym(text: String): String = clean(text.replace("#", ""), Profile.MAX_LENGTH)
}
