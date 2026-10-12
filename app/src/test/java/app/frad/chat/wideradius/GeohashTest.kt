package app.frad.chat.wideradius

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeohashTest {

    @Test
    fun `known coordinate encodes to the expected geohash prefix`() {
        // https://en.wikipedia.org/wiki/Geohash's worked example: (57.64911, 10.40744) -> "u4pruydqqvj" at full
        // precision - this app caps at MAX_PRECISION (5), so only the first 5 characters are checked.
        assertEquals("u4pr", Geohash.encode(57.64911, 10.40744, precision = 4))
        assertEquals("u4pru", Geohash.encode(57.64911, 10.40744, precision = 5))
    }

    @Test
    fun `a coarser prefix is always a prefix of a finer encoding of the same point`() {
        val fine = Geohash.encode(48.8566, 2.3522, precision = 5)
        val coarse = Geohash.encode(48.8566, 2.3522, precision = 3)
        assertTrue(fine.startsWith(coarse))
    }

    @Test
    fun `precision is clamped to the app's coarse-only range`() {
        assertEquals(Geohash.MIN_PRECISION, Geohash.encode(0.0, 0.0, precision = 0).length)
        assertEquals(Geohash.MAX_PRECISION, Geohash.encode(0.0, 0.0, precision = 12).length)
    }

    @Test
    fun `radius maps to a cell no smaller than requested`() {
        assertEquals(1, Geohash.precisionForRadiusKm(3000.0))
        assertEquals(3, Geohash.precisionForRadiusKm(50.0))
        assertEquals(5, Geohash.precisionForRadiusKm(1.0))
    }

    @Test
    fun `radius-derived precision never exceeds the app's coarse-only range`() {
        assertEquals(Geohash.MAX_PRECISION, Geohash.precisionForRadiusKm(0.0))
        assertEquals(Geohash.MIN_PRECISION, Geohash.precisionForRadiusKm(1_000_000.0))
    }

    @Test
    fun `neighbours match the classic lookup-table algorithm`() {
        // Expected values computed with the well-known neighbour/border table algorithm
        // (as in ngeohash), independent of the centre-offset approach Geohash.neighbors uses.
        assertEquals(
            listOf("gbsus", "gbsut", "gbsuu", "gbsuw", "gbsuy", "gbsvh", "gbsvj", "gbsvn"),
            Geohash.neighbors("gbsuv").sorted(),
        )
        assertEquals(
            listOf("u4pre", "u4prg", "u4prs", "u4prt", "u4prv", "u4r25", "u4r2h", "u4r2j"),
            Geohash.neighbors("u4pru").sorted(),
        )
        assertEquals(listOf("u0", "u1", "u2", "u4", "u6", "u8", "u9", "ud"), Geohash.neighbors("u3").sorted())
    }

    @Test
    fun `neighbours wrap around the antimeridian`() {
        assertEquals(
            listOf("2pbpb", "80000", "80002", "rzzzy", "rzzzz", "xbpbn", "xbpbq", "xbpbr"),
            Geohash.neighbors("xbpbp").sorted(),
        )
    }

    @Test
    fun `cells on the polar edge have no neighbours beyond the pole`() {
        val northernmost = Geohash.encode(89.99, 10.0, precision = 2)
        val neighbours = Geohash.neighbors(northernmost)
        assertEquals(5, neighbours.size)
        assertTrue(neighbours.all { Geohash.decode(it).first < Geohash.decode(northernmost).first + 1e-9 })
    }
}
