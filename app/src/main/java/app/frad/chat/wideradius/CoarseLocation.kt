package app.frad.chat.wideradius

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * One-shot, coarse-only location lookup used solely to compute a [Geohash] cell for
 * [app.frad.chat.profile.Profile.coarseGeohash] — the raw coordinate is read here and
 * nowhere else; it is immediately reduced to a geohash and discarded, never itself stored or
 * transmitted, matching the README's "only ever shares a coarse geohash cell" line.
 *
 * Deliberately only ever requests [Manifest.permission.ACCESS_COARSE_LOCATION] (never
 * `ACCESS_FINE_LOCATION`) and only reads [LocationManager.NETWORK_PROVIDER]/`PASSIVE_PROVIDER`
 * fixes, never GPS — both choices the OS already treats as coarse-precision inputs.
 */
object CoarseLocation {
    /** @return a geohash at [precision] characters, or null if the permission isn't granted, no
     *  last-known fix is available yet, or this device has no location services at all. */
    fun lastKnownGeohash(context: Context, precision: Int): String? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val location = runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            ?: runCatching { manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
            ?: return null

        return Geohash.encode(location.latitude, location.longitude, precision)
    }
}
