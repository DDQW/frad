package app.frad.chat.profile

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/** What a peer's profile looks like once decoded on the other end of a chat - see
 *  [ProfileEnvelope]. [photo] is only ever the small thumbnail [ProfilePhoto] produces, kept in
 *  memory for the chat's duration; nothing here is persisted unless the peer is saved as a
 *  contact, same as [app.frad.chat.chat.ChatMessage]s aren't. */
data class RemoteProfile(
    val pseudonym: String,
    val gender: Gender,
    val age: Int?,
    val bio: String,
    val photo: ByteArray?,
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

    fun encode(context: Context, profile: Profile): String {
        val json = JSONObject()
            .put(KEY_PSEUDONYM, profile.pseudonym)
            .put(KEY_GENDER, profile.gender.name)
            .put(KEY_BIO, profile.bio)
        profile.age?.let { json.put(KEY_AGE, it) }
        ProfilePhoto.bytesOrNull(context)?.let { json.put(KEY_PHOTO, Base64.encodeToString(it, Base64.NO_WRAP)) }
        return json.toString()
    }

    /** Falls back to sensible defaults for any field a differently-versioned peer might not send,
     *  rather than failing the whole chat over one missing/malformed field. */
    fun decode(json: String): RemoteProfile {
        val obj = JSONObject(json)
        val gender = runCatching { Gender.valueOf(obj.optString(KEY_GENDER)) }.getOrDefault(Gender.MALE)
        val age = if (obj.has(KEY_AGE)) obj.optInt(KEY_AGE).takeIf { it in Profile.MIN_AGE..Profile.MAX_AGE } else null
        val photo = obj.optString(KEY_PHOTO, "").ifEmpty { null }?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        return RemoteProfile(
            pseudonym = obj.optString(KEY_PSEUDONYM, "Guest"),
            gender = gender,
            age = age,
            bio = obj.optString(KEY_BIO, ""),
            photo = photo,
        )
    }
}
