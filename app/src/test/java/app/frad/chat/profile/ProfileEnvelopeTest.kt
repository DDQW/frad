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

    @Test
    fun `only known interests are taken, at most five`() {
        val profile = decode {
            put("tags", org.json.JSONArray(listOf("music", "evil<script>", "books", "gaming", "sports", "fitness", "travel")))
        }
        assertEquals(setOf(Interest.MUSIC, Interest.BOOKS, Interest.GAMING, Interest.SPORTS, Interest.FITNESS), profile.interests)
        assertEquals(emptySet<Interest>(), decode { put("tags", "music") }.interests)
    }

    @Test
    fun `icebreakers start with what both people like`() {
        val openers = Interest.icebreakers(setOf(Interest.PETS, Interest.MUSIC))
        assertEquals(listOf(Interest.MUSIC.icebreaker, Interest.PETS.icebreaker), openers.take(2))
        assertEquals(3, openers.size)
        assertEquals(3, Interest.icebreakers(emptySet()).size)
    }

    @Test
    fun `a photo kept back for a swap is announced, not sent`() {
        assertEquals(true, decode { put("photoHidden", true) }.photoHidden)
        assertNull(decode { put("photoHidden", true) }.photo)
        // A photo that's there anyway wins over the flag.
        assertEquals(false, decode { put("photoHidden", true); put("photo", "AAEC") }.photoHidden)
    }
}
