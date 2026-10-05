package app.frad.chat.safety

/**
 * Gentle, on-device checks of chat text - nothing is blocked and nothing leaves the phone.
 *
 *  - [beforeSending]: a message to someone who isn't a saved contact that hands over a phone
 *    number, e-mail address, bank details or a street address gets a "send anyway?" first. That's
 *    exactly what's hard to take back once a stranger has it.
 *  - [onReceived]: a message that tries the usual lures - a link, money or gift cards, moving the
 *    conversation to another app (where FRAD's protections don't follow) - is marked as such.
 */
object Nudges {
    enum class Outgoing(val label: String) {
        PHONE_NUMBER("a phone number"),
        EMAIL("an e-mail address"),
        BANK_DETAILS("bank details"),
        STREET_ADDRESS("an address"),
    }

    enum class Incoming(val label: String) {
        LINK("contains a link"),
        MONEY("asks about money"),
        OTHER_APP("wants to move to another app"),
    }

    fun beforeSending(text: String): Set<Outgoing> = buildSet {
        if (IBAN.containsMatchIn(text)) add(Outgoing.BANK_DETAILS)
        // An IBAN's digit groups would otherwise also pass for a phone number.
        if (PHONE.containsMatchIn(IBAN.replace(text, " "))) add(Outgoing.PHONE_NUMBER)
        if (EMAIL.containsMatchIn(text)) add(Outgoing.EMAIL)
        if (STREET.containsMatchIn(text)) add(Outgoing.STREET_ADDRESS)
    }

    fun onReceived(text: String): Set<Incoming> = buildSet {
        if (LINK.containsMatchIn(text)) add(Incoming.LINK)
        if (MONEY.containsMatchIn(text)) add(Incoming.MONEY)
        if (OTHER_APP.containsMatchIn(text)) add(Incoming.OTHER_APP)
    }

    // At least 7 digits, optionally grouped and with a leading + or 0 - not dates or times.
    private val PHONE = Regex("""(?<![\w.])(\+|00|0)\d[\d /\-().]{5,}\d(?!\w)""")
    private val EMAIL = Regex("""[\w.+\-]+@[\w\-]+\.[\w.\-]{2,}""")
    // Written in one block or in groups of four, as people usually do.
    private val IBAN = Regex("""\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\b""")
    private val STREET = Regex(
        """\b[\p{L}\-]+\s?(stra(ß|ss)e|str\.|weg|gasse|allee|platz|ring|street|st\.|road|rd\.|avenue|ave\.|lane)\s+\d{1,4}[a-z]?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val LINK = Regex("""(https?://|www\.)\S+|\b[\w\-]+\.(com|net|org|de|io|me|ly|gg|xyz|link|click|top|ru|cn)(/\S*)?\b""", RegexOption.IGNORE_CASE)
    private val MONEY = Regex(
        """\b(paypal|venmo|cash ?app|western union|moneygram|gift ?cards?|gutschein(e|karten?)?|itunes|google play card|steam card|bitcoin|btc|crypto|usdt|ethereum|überweis\w*|geld|money|bank ?account|kontonummer|iban)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val OTHER_APP = Regex(
        """\b(whats ?app|telegram|signal|snap(chat)?|insta(gram)?|tiktok|kik|discord|skype|facebook|messenger|onlyfans|wechat|line app)\b""",
        RegexOption.IGNORE_CASE,
    )
}
