package app.frad.chat.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decodes an image no larger than needed to show it at [maxDimension] pixels on its longer side.
 * Images here come from strangers (a profile photo, a received file): decoding one at full
 * resolution lets a small, highly compressed file with huge dimensions exhaust memory and crash
 * the app, so the size is read first and the decode subsampled accordingly.
 */
internal fun decodeSampled(bytes: ByteArray, maxDimension: Int): Bitmap? =
    decodeSampled(maxDimension) { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }

internal fun decodeSampled(path: String, maxDimension: Int): Bitmap? =
    decodeSampled(maxDimension) { options -> BitmapFactory.decodeFile(path, options) }

private inline fun decodeSampled(maxDimension: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= maxDimension || bounds.outHeight / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }
    return decode(BitmapFactory.Options().apply { inSampleSize = sampleSize })
}

/** A tiny copy of [bitmap] ([width] pixels wide) - drawn scaled up without filtering, it shows
 *  only coarse blocks of colour. */
internal fun pixelated(bitmap: Bitmap, width: Int = 12): Bitmap {
    val height = (bitmap.height * width / bitmap.width.coerceAtLeast(1)).coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

/** One pixel per module - draw it scaled up with FilterQuality.None so the edges stay sharp. */
internal fun qrBitmap(code: app.frad.chat.qr.QrCode): Bitmap {
    val pixels = IntArray(code.size * code.size) { i ->
        if (code[i % code.size, i / code.size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, code.size, code.size, Bitmap.Config.ARGB_8888)
}
