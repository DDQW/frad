package app.frad.chat.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/** A file as it will actually be sent - see [MediaSanitizer]: a temporary copy in the cache, which
 *  whoever sends it takes over (and deletes or keeps). */
class OutgoingFile(val file: File, val fileName: String, val mimeType: String) {
    val sizeBytes: Long get() = file.length()
}

/** The file is bigger than [limitBytes] - nothing was kept. */
class FileTooLargeException(val limitBytes: Long) : Exception("File larger than $limitBytes bytes")

/**
 * Removes what photos and videos say about the sender beyond their content before they go to a
 * stranger. A camera photo's EXIF block typically carries the exact GPS position it was taken at
 * (plus time, device model, serial-ish ids), and a phone video's MP4 header the same location -
 * sending them byte for byte would undo FRAD's promise never to share more than a coarse area.
 *
 *  - Images are decoded and re-encoded (which writes no metadata at all), turned upright first
 *    since the EXIF orientation is dropped too, and scaled down to [MAX_IMAGE_DIMENSION] -
 *    plenty for a phone screen and far quicker to send.
 *  - Videos are remuxed track by track without the location atom (the muxer only writes one when
 *    told to); the rotation hint is carried over.
 *  - Their file names (often "IMG_<date>_<time>", sometimes worse) become neutral ones.
 *  - Anything else is sent unchanged - the user picked that document on purpose.
 */
object MediaSanitizer {
    const val MAX_IMAGE_DIMENSION = 2048
    private const val JPEG_QUALITY = 85

    /** @throws FileTooLargeException if the result is over [maxBytes];
     *  any other Exception if a photo/video can't be cleaned - the caller should refuse to send it
     *  rather than fall back to the original with its metadata. */
    fun prepare(context: Context, uri: Uri, mimeType: String, fileName: String, maxBytes: Long): OutgoingFile {
        val resolver = context.contentResolver
        val prepared = when {
            mimeType.startsWith("image/") && mimeType != "image/gif" -> cleanImage(context, resolver, uri, mimeType)
            mimeType.startsWith("video/") -> cleanVideo(context, uri)
            else -> OutgoingFile(copyToCache(context, resolver, uri, maxBytes), fileName, mimeType)
        }
        if (prepared.sizeBytes > maxBytes) {
            prepared.file.delete()
            throw FileTooLargeException(maxBytes)
        }
        return prepared
    }

    /** A fresh temporary file for an outgoing copy (see [clearLeftovers]). */
    private fun newOutgoingFile(context: Context, suffix: String): File {
        val dir = File(context.cacheDir, OUTGOING_DIR).apply { mkdirs() }
        return File.createTempFile("out", suffix, dir)
    }

    /** Deletes outgoing copies a killed app left behind - old ones only, since the BLE service may
     *  still be sending a fresh one while the UI starts up again. */
    fun clearLeftovers(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        File(context.cacheDir, OUTGOING_DIR).listFiles()
            ?.filter { nowMillis - it.lastModified() > LEFTOVER_AGE_MILLIS }
            ?.forEach { it.delete() }
    }

    private fun copyToCache(context: Context, resolver: ContentResolver, uri: Uri, maxBytes: Long): File {
        val output = newOutgoingFile(context, ".bin")
        try {
            resolver.openInputStream(uri)?.use { input ->
                output.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw FileTooLargeException(maxBytes)
                        out.write(buffer, 0, n)
                    }
                }
            } ?: error("Couldn't open file")
            return output
        } catch (e: Exception) {
            output.delete()
            throw e
        }
    }

    private fun cleanImage(context: Context, resolver: ContentResolver, uri: Uri, mimeType: String): OutgoingFile {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not a decodable image" }
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= MAX_IMAGE_DIMENSION || bounds.outHeight / (sampleSize * 2) >= MAX_IMAGE_DIMENSION) {
            sampleSize *= 2
        }
        val decoded = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: error("Couldn't decode image")

        val orientation = runCatching {
            resolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val upright = applyOrientation(scaleDown(decoded), orientation)

        // PNG keeps transparency; everything else becomes a JPEG.
        val png = mimeType == "image/png"
        val output = newOutgoingFile(context, if (png) ".png" else ".jpg")
        output.outputStream().use { out ->
            upright.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        return OutgoingFile(output, if (png) "image.png" else "photo.jpg", if (png) "image/png" else "image/jpeg")
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_IMAGE_DIMENSION) return bitmap
        val scale = MAX_IMAGE_DIMENSION.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun cleanVideo(context: Context, uri: Uri): OutgoingFile {
        val output = newOutgoingFile(context, ".mp4")
        var done = false
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, uri, null)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = HashMap<Int, Int>()
            var maxInputSize = 1 shl 20
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue // drops metadata/timed-text tracks
                if (mime.startsWith("video/") && format.containsKey(KEY_ROTATION)) {
                    muxer.setOrientationHint(format.getInteger(KEY_ROTATION))
                }
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = maxOf(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                extractor.selectTrack(i)
                trackMap[i] = muxer.addTrack(format)
            }
            require(trackMap.isNotEmpty()) { "No audio/video tracks" }
            // Deliberately no muxer.setLocation(): that's the metadata this whole step exists to drop.
            muxer.start()
            val buffer = ByteBuffer.allocate(maxInputSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val track = trackMap[extractor.sampleTrackIndex]
                if (track != null) {
                    info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(track, buffer, info)
                }
                extractor.advance()
            }
            muxer.stop()
            done = true
            return OutgoingFile(output, "video.mp4", "video/mp4")
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
            if (!done) output.delete()
        }
    }

    private const val OUTGOING_DIR = "outgoing"
    private const val LEFTOVER_AGE_MILLIS = 24L * 60 * 60 * 1000

    /** MediaFormat.KEY_ROTATION, by value - the constant itself only exists from API 23 docs on. */
    private const val KEY_ROTATION = "rotation-degrees"
}
