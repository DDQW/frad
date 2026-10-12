package app.frad.chat.wideradius

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeLinksTest {
    private val tcp = "/ip4/203.0.113.7/tcp/4001/p2p/12D3KooWEyoppNCUx8Yx66oV9fJnriXwCcXwDDUA2kj6vnc6iDEp"
    private val quic = "/ip4/203.0.113.7/udp/4001/quic-v1/p2p/12D3KooWEyoppNCUx8Yx66oV9fJnriXwCcXwDDUA2kj6vnc6iDEp"
    private val dns = "/dns4/frad.example.org/tcp/4001/p2p/12D3KooWEyoppNCUx8Yx66oV9fJnriXwCcXwDDUA2kj6vnc6iDEp"

    @Test
    fun `addresses survive a build and parse round trip`() {
        assertEquals(listOf(tcp, quic, dns), NodeLinks.parse(NodeLinks.build(listOf(tcp, quic, dns))))
    }

    @Test
    fun `anything that isn't a plain node address is dropped`() {
        val link = NodeLinks.build(listOf(tcp)) +
            "&addr=" + java.net.URLEncoder.encode("javascript:alert(1)", "UTF-8") +
            "&addr=" + java.net.URLEncoder.encode("/ip4/1.2.3.4/tcp/1", "UTF-8") + // no /p2p/
            "&other=x"
        assertEquals(listOf(tcp), NodeLinks.parse(link))
    }

    @Test
    fun `other links are not node links`() {
        assertTrue(NodeLinks.parse("https://example.org/?addr=$tcp").isEmpty())
        assertTrue(NodeLinks.parse("frad://something-else?addr=x").isEmpty())
    }

    @Test
    fun `address validation`() {
        assertTrue(NodeLinks.looksLikeNodeAddress(tcp))
        assertTrue(NodeLinks.looksLikeNodeAddress(dns))
        assertFalse(NodeLinks.looksLikeNodeAddress("/ip4/203.0.113.7/tcp/4001/p2p/short"))
        assertFalse(NodeLinks.looksLikeNodeAddress(tcp + "\n/ip4/6.6.6.6/tcp/1/p2p/x"))
        assertFalse(NodeLinks.looksLikeNodeAddress("/ip4/" + "1".repeat(400) + "/p2p/12D3KooWEyoppNCUx8Yx66oV9fJnriXwCcXwDDUA2kj6vnc6iDEp"))
    }

    @Test
    fun `at most eight addresses are taken from one link`() {
        val many = (1..20).map { "/ip4/203.0.113.$it/tcp/4001/p2p/12D3KooWEyoppNCUx8Yx66oV9fJnriXwCcXwDDUA2kj6vnc6iDEp" }
        assertEquals(8, NodeLinks.parse(NodeLinks.build(many) + many.drop(8).joinToString("") { "&addr=" + java.net.URLEncoder.encode(it, "UTF-8") }).size)
    }
}
