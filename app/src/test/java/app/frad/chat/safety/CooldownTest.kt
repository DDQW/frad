package app.frad.chat.safety

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CooldownTest {

    @Test
    fun `allows the first request to any peer`() {
        val cooldown = Cooldown(minIntervalMillis = 1000, now = { 0 })
        assertTrue(cooldown.canRequest("peer-a"))
    }

    @Test
    fun `blocks a repeat request within the cooldown window`() {
        var clock = 0L
        val cooldown = Cooldown(minIntervalMillis = 1000, now = { clock })

        cooldown.recordRequest("peer-a")
        clock = 500
        assertFalse(cooldown.canRequest("peer-a"))
    }

    @Test
    fun `allows a repeat request once the cooldown window has passed`() {
        var clock = 0L
        val cooldown = Cooldown(minIntervalMillis = 1000, now = { clock })

        cooldown.recordRequest("peer-a")
        clock = 1500
        assertTrue(cooldown.canRequest("peer-a"))
    }

    @Test
    fun `cooldown is tracked independently per peer`() {
        var clock = 0L
        val cooldown = Cooldown(minIntervalMillis = 1000, now = { clock })

        cooldown.recordRequest("peer-a")
        clock = 100
        assertTrue(cooldown.canRequest("peer-b"))
        assertFalse(cooldown.canRequest("peer-a"))
    }

    @Test
    fun `someone just chatted with is refused until the interval has passed`() {
        var clock = 0L
        val cooldown = Cooldown(minIntervalMillis = 1000, now = { clock })

        cooldown.recordChatEnded("identity-a")
        clock = 500
        assertTrue(cooldown.justChatted("identity-a"))
        assertFalse(cooldown.justChatted("identity-b"))
        clock = 1500
        assertFalse(cooldown.justChatted("identity-a"))
    }
}
