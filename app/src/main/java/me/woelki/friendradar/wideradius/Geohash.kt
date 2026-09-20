package me.woelki.friendradar.wideradius

/**
 * Standard base32 geohash encoding (see https://en.wikipedia.org/wiki/Geohash).
 * This is the only thing this app derives from a device's location for the
 * wide-range layer — see [me.woelki.friendradar.profile.Profile.coarseGeohash]
 * — and it's always truncated to [MAX_PRECISION] or coarser, so a shared
 * geohash never pins down more than a roughly city-sized cell, matching the
 * README's "only ever shares a coarse geohash cell" privacy line. The raw
 * latitude/longitude passed in here is never itself stored or transmitted.
 */
object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    const val MIN_PRECISION = 1
    const val MAX_PRECISION = 5

    /** Approximate cell width in km at each precision, index 0 = 1 character. */
    private val CELL_WIDTH_KM = doubleArrayOf(2500.0, 630.0, 78.0, 20.0, 2.4)

    fun encode(latitude: Double, longitude: Double, precision: Int = MAX_PRECISION): String {
        val clamped = precision.coerceIn(MIN_PRECISION, MAX_PRECISION)
        var latLow = -90.0
        var latHigh = 90.0
        var lonLow = -180.0
        var lonHigh = 180.0

        val result = StringBuilder(clamped)
        var bitBuffer = 0
        var bitCount = 0
        var isLongitudeBit = true

        while (result.length < clamped) {
            if (isLongitudeBit) {
                val mid = (lonLow + lonHigh) / 2
                if (longitude >= mid) {
                    bitBuffer = (bitBuffer shl 1) or 1
                    lonLow = mid
                } else {
                    bitBuffer = bitBuffer shl 1
                    lonHigh = mid
                }
            } else {
                val mid = (latLow + latHigh) / 2
                if (latitude >= mid) {
                    bitBuffer = (bitBuffer shl 1) or 1
                    latLow = mid
                } else {
                    bitBuffer = bitBuffer shl 1
                    latHigh = mid
                }
            }
            isLongitudeBit = !isLongitudeBit
            bitCount++
            if (bitCount == 5) {
                result.append(BASE32[bitBuffer])
                bitBuffer = 0
                bitCount = 0
            }
        }
        return result.toString()
    }

    /**
     * Maps a desired search radius to the finest geohash precision whose cell
     * is still at least [radiusKm] wide — i.e. the smallest cell that
     * comfortably contains the requested radius rather than clipping it.
     * Clamped to [MIN_PRECISION, MAX_PRECISION].
     */
    fun precisionForRadiusKm(radiusKm: Double): Int {
        var best = MIN_PRECISION
        for (i in CELL_WIDTH_KM.indices) {
            if (radiusKm <= CELL_WIDTH_KM[i]) best = i + 1
        }
        return best.coerceIn(MIN_PRECISION, MAX_PRECISION)
    }
}
