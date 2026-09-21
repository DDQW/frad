package app.frad.chat.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * The user's own profile photo, stored locally as a deliberately tiny, low-quality thumbnail -
 * not because storage is scarce, but because [ProfileEnvelope] sends it automatically to whoever
 * you match with, over the same small-fragment BLE control channel that carries the Noise
 * handshake itself (see [app.frad.chat.ble.BleCentralClient] - MTU is never negotiated up, on
 * purpose, after that caused real connection failures). A bigger image would mean many more
 * fragment writes on a channel where a single dropped one disconnects the whole chat, trading a
 * nicer-looking avatar for materially less reliable connections. [MAX_DIMENSION]/[JPEG_QUALITY]
 * keep the encoded, base64'd result in the low single-digit kilobytes - roughly the same order of
 * magnitude as the handshake messages that already work reliably.
 */
object ProfilePhoto {
    const val MAX_DIMENSION = 48
    private const val JPEG_QUALITY = 60

    /** Reads, downscales, and stores [uri] as this device's profile photo. Returns false (and
     *  stores nothing) if the image can't be read/decoded. */
    fun store(context: Context, uri: Uri): Boolean {
        val original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return false
        val scaled = downscale(original, MAX_DIMENSION)
        file(context).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        return true
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    fun bytesOrNull(context: Context): ByteArray? {
        val f = file(context)
        return if (f.exists()) f.readBytes() else null
    }

    fun hasPhoto(context: Context): Boolean = file(context).exists()

    private fun file(context: Context): File = File(context.filesDir, "profile_photo.jpg")

    private fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largestSide = maxOf(bitmap.width, bitmap.height)
        if (largestSide <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largestSide
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }
}
