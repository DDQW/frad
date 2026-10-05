package app.frad.chat.profile

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileEnvelopeTest {
    private fun decode(build: JSONObject.() -> Unit) = ProfileEnvelope.decode(JSONObject().apply(build).toString())

    @Test
    fun `a peer's oversized or spoofing fields are cleaned and capped`() {
        val profile = decode {
            put("pseudonym", "Alex#AB12CD‮" + "x".repeat(100))
            put("gender", "FEMALE")
            put("bio", "hi\u0000" + "y".repeat(1000))
        }
        assertEquals(Profile.MAX_LENGTH, profile.pseudonym.length)
        assertFalse(profile.pseudonym.contains('#'))
        assertEquals(Profile.MAX_BIO_LENGTH, profile.bio.length)
        assertEquals(Gender.FEMALE, profile.gender)
    }

    @Test
    fun `an oversized photo is dropped, a thumbnail kept`() {
        val thumbnail = ByteArray(2_000) { it.toByte() }
        assertArrayEquals(thumbnail, decode { put("photo", Base64.getEncoder().encodeToString(thumbnail)) }.photo)
        val huge = ByteArray(ProfileEnvelope.MAX_PHOTO_BYTES * 4)
        assertNull(decode { put("photo", Base64.getEncoder().encodeToString(huge)) }.photo)
    }

    @Test
    fun `missing or blank pseudonym falls back to Guest`() {
        assertEquals("Guest", decode { }.pseudonym)
        assertEquals("Guest", decode { put("pseudonym", "​​") }.pseudonym)
    }
}
