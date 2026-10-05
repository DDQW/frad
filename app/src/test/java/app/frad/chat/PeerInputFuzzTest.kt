package app.frad.chat

import kotlin.random.Random
import app.frad.chat.ble.FrameReassembler
import app.frad.chat.ble.FrameTooLargeException
import app.frad.chat.chat.ChatEnvelope
import app.frad.chat.chat.ChatEnvelopeJson
import app.frad.chat.chat.FileOffer
import app.frad.chat.media.FileTypeCheck
import app.frad.chat.profile.ProfileEnvelope
import app.frad.chat.wideradius.NodeLinks
import org.json.JSONException
import org.junit.Assert.fail
import org.junit.Test

/**
 * Everything here parses bytes a stranger sent. Random and mutated input may be rejected - with
 * the exceptions the callers expect and turn into "drop that connection" - but must never crash
 * with anything else (NullPointerException, ClassCastException, IndexOutOfBounds, OOM, ...).
 */
class PeerInputFuzzTest {
    private val random = Random(20261005)

    private val seeds = listOf(
        ChatEnvelopeJson.encode(ChatEnvelope.Text("hello", id = "m-1")),
        ChatEnvelopeJson.encode(ChatEnvelope.Ack("m-1")),
        ChatEnvelopeJson.encode(ChatEnvelope.WfdOffer(FileOffer("t1", "a.jpg", "image/jpeg", 10), "DIRECT-x", "pass")),
        ChatEnvelopeJson.encode(ChatEnvelope.FileRequest(FileOffer("t2", "b.pdf", "application/pdf", 99))),
        ChatEnvelopeJson.encode(ChatEnvelope.FileReply("t2", true)),
        """{"pseudonym":"Alex","gender":"MALE","age":30,"bio":"hi","tags":["music","books"],"photo":"AAEC"}""",
    )

    private fun mutate(seed: String): String {
        val chars = seed.toCharArray().toMutableList()
        repeat(1 + random.nextInt(6)) {
            when (random.nextInt(4)) {
                0 -> if (chars.isNotEmpty()) chars.removeAt(random.nextInt(chars.size))
                1 -> chars.add(random.nextInt(chars.size + 1), "{}[]\":,0-9eE.\\u\u0000￿#".random(random))
                2 -> if (chars.isNotEmpty()) chars[random.nextInt(chars.size)] = random.nextInt(0x20, 0x7F).toChar()
                3 -> if (chars.size > 2) {
                    val cut = random.nextInt(chars.size)
                    chars.subList(cut, chars.size).clear()
                }
            }
        }
        return chars.joinToString("")
    }

    private fun randomText(): String = String(CharArray(random.nextInt(0, 200)) { random.nextInt(0, 0x2FF).toChar() })

    private inline fun survives(input: String, block: () -> Unit) {
        try {
            block()
        } catch (e: JSONException) {
            // a protocol error the caller handles
        } catch (e: IllegalArgumentException) {
            // ditto (require(...) checks)
        } catch (e: Throwable) {
            fail("${e::class.simpleName} on input <$input>: ${e.message}")
        }
    }

    @Test
    fun `chat envelopes`() {
        repeat(4_000) {
            val input = if (it % 4 == 0) randomText() else mutate(seeds.random(random))
            survives(input) { ChatEnvelopeJson.decode(input) }
        }
    }

    @Test
    fun `profiles`() {
        repeat(4_000) {
            val input = if (it % 4 == 0) randomText() else mutate(seeds.random(random))
            survives(input) { ProfileEnvelope.decode(input) }
        }
    }

    @Test
    fun `node links`() {
        val seed = NodeLinks.build(listOf("/ip4/203.0.113.7/tcp/4001/p2p/12D3KooWGzBsVqGyF3vV7z5dJqXz8u3Mf9pY2r6QkTn4hLwXcA1e"))
        repeat(4_000) {
            val input = if (it % 4 == 0) randomText() else mutate(seed)
            survives(input) { NodeLinks.parse(input) }
        }
    }

    @Test
    fun `frames reassembled from arbitrary bytes`() {
        repeat(2_000) {
            val reassembler = FrameReassembler(maxMessageSize = 4096)
            try {
                repeat(random.nextInt(1, 20)) { reassembler.offer(random.nextBytes(random.nextInt(0, 64))) }
            } catch (e: FrameTooLargeException) {
                // dropped connection, as intended
            }
        }
    }

    @Test
    fun `file type check on arbitrary bytes`() {
        repeat(2_000) {
            FileTypeCheck.verifiedMimeType(random.nextBytes(random.nextInt(0, 64)), randomText())
        }
    }
}
