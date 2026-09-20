package me.woelki.friendradar.wideradius

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
}
