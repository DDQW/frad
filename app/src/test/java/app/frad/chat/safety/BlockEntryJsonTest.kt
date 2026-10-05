package app.frad.chat.safety

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockEntryJsonTest {
    @Test
    fun `entries round trip with and without optional fields`() {
        val entries = listOf(
            BlockEntry(listOf("peer", "device"), pseudonym = "Alex", blockedAtMillis = 42L, reason = "spam"),
            BlockEntry(listOf("legacy-id"), pseudonym = null, blockedAtMillis = 0L, reason = null),
        )
        assertEquals(entries, BlockEntryJson.decode(BlockEntryJson.encode(entries)))
    }

    @Test
    fun `the first id is the entry's key`() {
        assertEquals("peer", BlockEntry(listOf("peer", "device"), null, 0L, null).key)
    }

    @Test
    fun `entries without ids are skipped rather than crashing`() {
        assertEquals(emptyList<BlockEntry>(), BlockEntryJson.decode("""[{"at":1},{"ids":[]}]"""))
    }
}
