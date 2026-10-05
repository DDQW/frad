package app.frad.chat.wideradius

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeListsTest {
    private val a = "/ip4/203.0.113.7/tcp/4001/p2p/12D3KooWGzBsVqGyF3vV7z5dJqXz8u3Mf9pY2r6QkTn4hLwXcA1e"
    private val a2 = "/ip4/203.0.113.7/udp/4001/quic-v1/p2p/12D3KooWGzBsVqGyF3vV7z5dJqXz8u3Mf9pY2r6QkTn4hLwXcA1e"
    private val a3 = "/ip6/2001:db8::7/tcp/4001/p2p/12D3KooWGzBsVqGyF3vV7z5dJqXz8u3Mf9pY2r6QkTn4hLwXcA1e"
    private val b = "/dns4/frad.example.org/tcp/4001/p2p/12D3KooWQ8NpMrbwJ6xCmPgzLaPsYHAtpALwTRY4fxFdDZtYLR9N"

    @Test
    fun `the official list format is parsed, comments and junk skipped`() {
        val text = """
            # FRAD official node list
            $a

            not a multiaddr
            /ip4/1.2.3.4/tcp/1
            $b
            $a
        """.trimIndent()
        assertEquals(listOf(a, b), NodeLists.parse(text))
    }

    @Test
    fun `one server can't crowd out the others`() {
        assertEquals(listOf(a, a2, b), NodeLists.diverse(listOf(a, a2, a3, b, b), max = 10))
        assertEquals(listOf(a, a2), NodeLists.diverse(listOf(a, a2, a3, b), max = 2))
    }
}
