package app.frad.chat.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSanitizerTest {
    @Test
    fun `bidi overrides and zero-width characters are removed`() {
        // RIGHT-TO-LEFT OVERRIDE, ZERO WIDTH SPACE, FIRST STRONG ISOLATE, BOM
        assertEquals("abcd", TextSanitizer.clean("a‮b​c⁨d﻿", 50))
    }

    @Test
    fun `control characters go, newlines only where allowed`() {
        assertEquals("ab", TextSanitizer.clean("a\u0000\u0007b", 50))
        assertEquals("ab", TextSanitizer.clean("a\nb", 50))
        assertEquals("a\nb", TextSanitizer.clean("a\nb", 50, allowNewlines = true))
    }

    @Test
    fun `length is capped by code point without splitting surrogate pairs`() {
        val emoji = "😀" // one code point, two chars
        assertEquals(emoji + emoji, TextSanitizer.clean(emoji.repeat(5), maxLength = 2))
        assertEquals("abc", TextSanitizer.clean("abcdef", maxLength = 3))
    }

    @Test
    fun `pseudonyms lose the tag separator so they can't fake another person's tag`() {
        assertEquals("Alex9F21A0", TextSanitizer.pseudonym("Alex#9F21A0"))
        assertEquals(Profile.MAX_LENGTH, TextSanitizer.pseudonym("x".repeat(100)).length)
    }

    @Test
    fun `ordinary international text is untouched`() {
        assertEquals("Jürgen 李 مرحبا", TextSanitizer.clean("Jürgen 李 مرحبا", 50))
    }
}
