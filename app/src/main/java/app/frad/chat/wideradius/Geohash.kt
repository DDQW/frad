package app.frad.chat.wideradius

/**
 * Standard base32 geohash encoding (see https://en.wikipedia.org/wiki/Geohash).
 * This is the only thing this app derives from a device's location for the
 * wide-range layer — see [app.frad.chat.profile.Profile.coarseGeohash]
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

    /** Inverse of [encode]: the center point of a geohash cell. Used only to turn a stored/typed
     *  geohash back into coordinates for a human-readable place name lookup (see
     *  [app.frad.chat.wideradius.AreaLookup]) — never re-transmitted at this precision. */
    fun decode(geohash: String): Pair<Double, Double> {
        var latLow = -90.0
        var latHigh = 90.0
        var lonLow = -180.0
        var lonHigh = 180.0
        var isLongitudeBit = true

        for (char in geohash.lowercase()) {
            val charIndex = BASE32.indexOf(char)
            if (charIndex < 0) continue
            for (bit in 4 downTo 0) {
                val bitValue = (charIndex shr bit) and 1
                if (isLongitudeBit) {
                    val mid = (lonLow + lonHigh) / 2
                    if (bitValue == 1) lonLow = mid else lonHigh = mid
                } else {
                    val mid = (latLow + latHigh) / 2
                    if (bitValue == 1) latLow = mid else latHigh = mid
                }
                isLongitudeBit = !isLongitudeBit
            }
        }
        return (latLow + latHigh) / 2 to (lonLow + lonHigh) / 2
    }

    /**
     * The (up to) 8 cells surrounding [geohash] at the same precision. Geohash cells have hard
     * borders: two people a few hundred metres apart can sit in different cells and would never
     * find each other through their own cell's rendezvous topic alone - see
     * [app.frad.chat.wideradius.WideRangeChatController], which advertises in all nine.
     * Longitude wraps around the antimeridian; rows beyond a pole are simply left out.
     */
    fun neighbors(geohash: String): List<String> {
        val precision = geohash.length
        require(precision in MIN_PRECISION..MAX_PRECISION) { "Unsupported geohash precision $precision" }
        val (latitude, longitude) = decode(geohash)
        val bits = precision * 5
        val cellWidth = 360.0 / (1L shl ((bits + 1) / 2)) // longitude takes the odd bit
        val cellHeight = 180.0 / (1L shl (bits / 2))

        val result = LinkedHashSet<String>()
        for (dLat in -1..1) {
            val lat = latitude + dLat * cellHeight
            if (lat <= -90.0 || lat >= 90.0) continue
            for (dLon in -1..1) {
                if (dLat == 0 && dLon == 0) continue
                var lon = longitude + dLon * cellWidth
                if (lon >= 180.0) lon -= 360.0
                if (lon < -180.0) lon += 360.0
                result += encode(lat, lon, precision)
            }
        }
        result -= geohash
        return result.toList()
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
