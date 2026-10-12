package app.frad.chat.profile

import android.content.Context
import java.util.Base64
import org.json.JSONObject

/** What a peer's profile looks like once decoded on the other end of a chat - see
 *  [ProfileEnvelope]. [photo] is only ever the small thumbnail [ProfilePhoto] produces, kept in
 *  memory for the chat's duration; nothing here is persisted unless the peer is saved as a
 *  contact, same as [app.frad.chat.chat.ChatMessage]s aren't. */
data class RemoteProfile(
    val pseudonym: String,
    val gender: Gender?,
    val age: Int?,
    val bio: String,
    val photo: ByteArray?,
    val interests: Set<Interest> = emptySet(),
    /** They have a photo but only swap it on request (see [Profile.photoOnRequest]). */
    val photoHidden: Boolean = false,
)

/**
 * Encodes/decodes the one extra message both sides of a chat exchange right after the Noise
 * handshake and device-fingerprint check (see `BleChatController`/`WideRangeChatController`'s
 * `EXPECT_PROFILE` step) - a single small JSON blob replacing what used to be just the bare
 * pseudonym string, so a peer's gender/age/bio/photo show up automatically once matched, the same
 * way the pseudonym already did.
 */
object ProfileEnvelope {
    private const val KEY_PSEUDONYM = "pseudonym"
    private const val KEY_GENDER = "gender"
    private const val KEY_AGE = "age"
    private const val KEY_BIO = "bio"
    private const val KEY_PHOTO = "photo"
    private const val KEY_INTERESTS = "tags"
    private const val KEY_PHOTO_HIDDEN = "photoHidden"

    fun encode(context: Context, profile: Profile): String {
        val json = JSONObject()
            .put(KEY_PSEUDONYM, profile.pseudonym)
            .put(KEY_BIO, profile.bio)
        profile.gender?.let { json.put(KEY_GENDER, it.name) }
        if (profile.interests.isNotEmpty()) json.put(KEY_INTERESTS, org.json.JSONArray(profile.interests.map { it.key }))
        if (profile.shareAge) profile.age?.let { json.put(KEY_AGE, it) }
        // java.util.Base64 (API 26+, same unwrapped standard alphabet android.util.Base64.NO_WRAP
        // produced before) rather than android.util.Base64, so this also runs in JVM unit tests.
        ProfilePhoto.bytesOrNull(context)?.let {
            if (profile.photoOnRequest) json.put(KEY_PHOTO_HIDDEN, true) else json.put(KEY_PHOTO, encodePhoto(it))
        }
        return json.toString()
    }

    /** Upper bound on the photo a peer may send - [ProfilePhoto] thumbnails are a few KiB. */
    const val MAX_PHOTO_BYTES = 16 * 1024

    /** Falls back to sensible defaults for any field a differently-versioned peer might not send,
     *  rather than failing the whole chat over one missing/malformed field. Text is cleaned and
     *  capped to the same limits our own profile has (see [TextSanitizer]), and an oversized
     *  photo is dropped - all of it comes from a stranger. */
    fun decode(json: String): RemoteProfile {
        val obj = JSONObject(json)
        val gender = runCatching { Gender.valueOf(obj.optString(KEY_GENDER)) }.getOrNull()
        val age = if (obj.has(KEY_AGE)) obj.optInt(KEY_AGE).takeIf { it in Profile.MIN_AGE..Profile.MAX_AGE } else null
        val photo = decodePhoto(obj.optString(KEY_PHOTO, ""))
        return RemoteProfile(
            pseudonym = TextSanitizer.pseudonym(obj.optString(KEY_PSEUDONYM, "")).ifEmpty { "Guest" },
            gender = gender,
            age = age,
            bio = TextSanitizer.clean(obj.optString(KEY_BIO, ""), Profile.MAX_BIO_LENGTH, allowNewlines = true),
            photo = photo,
            interests = decodeInterests(obj),
            photoHidden = photo == null && obj.optBoolean(KEY_PHOTO_HIDDEN, false),
        )
    }

    fun encodePhoto(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** A peer's photo, as sent with the profile or swapped later; null if absent, malformed or
     *  larger than a [ProfilePhoto] thumbnail can be. */
    fun decodePhoto(base64: String): ByteArray? = base64.ifEmpty { null }
        ?.takeIf { it.length <= (MAX_PHOTO_BYTES + 2) / 3 * 4 }
        ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    /** Only known keys, at most [Interest.MAX_PER_PROFILE] of them - anything else is ignored. */
    private fun decodeInterests(obj: JSONObject): Set<Interest> {
        val array = obj.optJSONArray(KEY_INTERESTS) ?: return emptySet()
        return (0 until minOf(array.length(), 32))
            .mapNotNull { Interest.fromKey(array.optString(it, "")) }
            .take(Interest.MAX_PER_PROFILE)
            .toSet()
    }
}
