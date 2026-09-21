package me.woelki.frad.safety

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
}
