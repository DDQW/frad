package me.woelki.frad.wideradius

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Human-readable place names for the profile screen, purely a display/typing convenience over
 * [me.woelki.frad.profile.Profile.coarseGeohash] — that geohash remains the only thing ever
 * stored or shared with a peer. A name here is resolved from (or down to) that same coarse cell,
 * so it never carries more precision than the geohash already does; nothing here is transmitted.
 */
object AreaLookup {

    /** Reverse: the center of [geohash]'s cell -> a short label like "Berlin, Germany", or null
     *  if the platform has no geocoder or found nothing there. */
    suspend fun nameFor(context: Context, geohash: String): String? {
        val (lat, lon) = Geohash.decode(geohash)
        val address = firstAddress(
            context = context,
            modern = { geocoder, onResult -> geocoder.getFromLocation(lat, lon, 1, onResult) },
            legacy = { geocoder -> geocoder.getFromLocation(lat, lon, 1) },
        )
        return address?.readableName()
    }

    /** Forward: a typed place name -> a geohash at [precision], or null if nothing matched. */
    suspend fun geohashFor(context: Context, query: String, precision: Int): String? {
        if (query.isBlank()) return null
        val address = firstAddress(
            context = context,
            modern = { geocoder, onResult -> geocoder.getFromLocationName(query, 1, onResult) },
            legacy = { geocoder -> geocoder.getFromLocationName(query, 1) },
        ) ?: return null
        return Geohash.encode(address.latitude, address.longitude, precision)
    }

    private suspend fun firstAddress(
        context: Context,
        modern: (Geocoder, Geocoder.GeocodeListener) -> Unit,
        legacy: (Geocoder) -> List<Address>?,
    ): Address? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                modern(geocoder) { results -> if (continuation.isActive) continuation.resume(results.firstOrNull()) }
            }
        } else {
            // The pre-33 API is synchronous network I/O; keep it off the caller's thread.
            @Suppress("DEPRECATION")
            withContext(Dispatchers.IO) { runCatching { legacy(geocoder)?.firstOrNull() }.getOrNull() }
        }
    }

    private fun Address.readableName(): String? {
        val area = locality ?: subAdminArea ?: adminArea
        return when {
            area != null && countryName != null -> "$area, $countryName"
            area != null -> area
            countryName != null -> countryName
            else -> null
        }
    }
}
