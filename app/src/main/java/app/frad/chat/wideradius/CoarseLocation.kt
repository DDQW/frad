package app.frad.chat.wideradius

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot, coarse-only location lookup used solely to compute a [Geohash] cell for
 * [app.frad.chat.profile.Profile.coarseGeohash] — the raw coordinate is read here and
 * nowhere else; it is immediately reduced to a geohash and discarded, never itself stored or
 * transmitted, matching the README's "only ever shares a coarse geohash cell" line.
 *
 * Deliberately only ever requests [Manifest.permission.ACCESS_COARSE_LOCATION] (never
 * `ACCESS_FINE_LOCATION`), so whatever provider answers, the OS hands out a coarsened fix.
 */
object CoarseLocation {
    private const val FRESH_FIX_TIMEOUT_MILLIS = 15_000L

    /** @return a geohash at [precision] characters, or null if the permission isn't granted, no
     *  fix could be obtained within a few seconds, or this device has no location services. Uses
     *  the last known fix when there is one, otherwise asks for a fresh one - the last known fix
     *  is often missing on a phone nothing else has asked for a location recently. */
    suspend fun currentGeohash(context: Context, precision: Int): String? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val location = lastKnown(manager) ?: freshFix(manager) ?: return null
        return Geohash.encode(location.latitude, location.longitude, precision)
    }

    @Suppress("MissingPermission") // checked in currentGeohash
    private fun lastKnown(manager: LocationManager): Location? =
        runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            ?: runCatching { manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()

    @Suppress("MissingPermission") // checked in currentGeohash
    private suspend fun freshFix(manager: LocationManager): Location? {
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        val executor = Executors.newSingleThreadExecutor()
        return try {
            withTimeoutOrNull(FRESH_FIX_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    val cancel = androidx.core.os.CancellationSignal()
                    continuation.invokeOnCancellation { cancel.cancel() }
                    LocationManagerCompat.getCurrentLocation(manager, provider, cancel, executor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }
            }
        } catch (e: SecurityException) {
            null
        } finally {
            executor.shutdown()
        }
    }
}
