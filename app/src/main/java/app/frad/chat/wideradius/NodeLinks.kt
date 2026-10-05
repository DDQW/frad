package app.frad.chat.wideradius

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * `frad://node?addr=<multiaddr>&addr=…` links for handing bootstrap/relay node addresses to
 * someone else: share one from Profile, or put it in a QR code - any camera app opens it in FRAD.
 * Anyone (any app, any web page) can fire such a link, so the app only ever *offers* to add what
 * [parse] accepts, and the user confirms.
 */
object NodeLinks {
    private const val PREFIX = "frad://node?"
    private const val MAX_NODES = 8
    private const val MAX_ADDRESS_CHARS = 300

    fun build(addresses: List<String>): String =
        PREFIX + addresses.take(MAX_NODES).joinToString("&") { "addr=" + URLEncoder.encode(it, "UTF-8") }

    /** The valid node addresses in [link], or empty if it isn't a node link at all. */
    fun parse(link: String): List<String> {
        if (!link.startsWith(PREFIX)) return emptyList()
        return link.removePrefix(PREFIX).split('&')
            .mapNotNull { part -> part.takeIf { it.startsWith("addr=") }?.removePrefix("addr=") }
            .mapNotNull { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            .filter(::looksLikeNodeAddress)
            .distinct()
            .take(MAX_NODES)
    }

    /** A multiaddr of the shape FRAD's bootstrap nodes print: a network part and a trailing
     *  /p2p/<peer id>, nothing that could smuggle anything else in. Full parsing happens in Go. */
    fun looksLikeNodeAddress(address: String): Boolean =
        address.length in 1..MAX_ADDRESS_CHARS &&
            Regex("""/(ip4|ip6|dns|dns4|dns6)/[A-Za-z0-9.:\-]+(/[A-Za-z0-9\-]+(/[A-Za-z0-9\-]+)?)*/p2p/[1-9A-HJ-NP-Za-km-z]{32,64}""").matches(address)
}
