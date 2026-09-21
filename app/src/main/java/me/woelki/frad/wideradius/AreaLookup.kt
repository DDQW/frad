package me.woelki.frad.wideradius

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Human-readable place names for the profile screen, purely a display/typing convenience over
 * [me.woelki.frad.profile.Profile.coarseGeohash] - that geohash remains the only thing ever
 * stored or shared with a peer. A name here is resolved from (or down to) that same coarse cell,
 * so it never carries more precision than the geohash already does.
 *
 * Backed by OpenStreetMap's Nominatim (nominatim.org), not the platform `Geocoder`: the latter
 * has no working backend on a meaningful number of real devices - confirmed via a logcat capture
 * on one test device reporting `Geocoder.isPresent() == false` despite full network connectivity
 * - making it unreliable enough to not be worth keeping even as a first attempt.
 *
 * This is the app's one deliberate exception to otherwise never depending on a service nobody
 * user-configured (compare the wide-range bootstrap/relay nodes, which are never baked in),
 * accepted because there's no realistically self-hostable alternative simple enough for this
 * app's scope, and because only the geohash cell's center point is ever sent - never the exact
 * GPS fix, and never anything tied to a peer or a chat. Subject to Nominatim's usage policy
 * (nominatim.org/release-docs/latest/api/Usage-Policy) - an identifying User-Agent and roughly
 * one request per second - both trivially satisfied by a manual, one-tap-at-a-time UI action.
 */
object AreaLookup {
    private const val TAG = "AreaLookup"
    private const val USER_AGENT = "FRAD-Android/1 (+https://github.com/DDQW/frad)"
    private const val BASE_URL = "https://nominatim.openstreetmap.org"

    /** Reverse: the center of [geohash]'s cell -> a short label like "Berlin, Germany", or null
     *  on any network failure or if nothing was found there. */
    suspend fun nameFor(context: Context, geohash: String): String? {
        val (lat, lon) = Geohash.decode(geohash)
        val url = "$BASE_URL/reverse?format=jsonv2&lat=$lat&lon=$lon&zoom=10&accept-language=en"
        val body = fetch(url) ?: return null
        return runCatching { readableName(JSONObject(body).optJSONObject("address")) }
            .onFailure { Log.w(TAG, "reverse geocode: failed to parse response", it) }
            .getOrNull()
    }

    /** Forward: a typed place name -> a geohash at [precision], or null if nothing matched. */
    suspend fun geohashFor(context: Context, query: String, precision: Int): String? {
        if (query.isBlank()) return null
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/search?format=jsonv2&q=$encoded&limit=1&accept-language=en"
        val body = fetch(url) ?: return null
        return runCatching {
            val results = JSONArray(body)
            if (results.length() == 0) return@runCatching null
            val first = results.getJSONObject(0)
            Geohash.encode(first.getString("lat").toDouble(), first.getString("lon").toDouble(), precision)
        }.onFailure { Log.w(TAG, "forward geocode: failed to parse response", it) }.getOrNull()
    }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "geocoding request failed: HTTP ${connection.responseCode}")
                    null
                } else {
                    connection.inputStream.bufferedReader().use { it.readText() }
                }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "geocoding request failed", it) }.getOrNull()
    }

    private fun readableName(address: JSONObject?): String? {
        if (address == null) return null
        val area = address.optString("city").ifEmpty { null }
            ?: address.optString("town").ifEmpty { null }
            ?: address.optString("village").ifEmpty { null }
            ?: address.optString("county").ifEmpty { null }
            ?: address.optString("state").ifEmpty { null }
        val country = address.optString("country").ifEmpty { null }
        return when {
            area != null && country != null -> "$area, $country"
            area != null -> area
            country != null -> country
            else -> null
        }
    }
}
