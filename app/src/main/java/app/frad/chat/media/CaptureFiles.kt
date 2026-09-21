package app.frad.chat.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Temp files for an in-progress camera photo/video capture or in-app audio recording, before
 * their bytes are read back and handed to [app.frad.chat.chat.ChatController.sendFile] (which
 * copies them into [app.frad.chat.data.MediaFileStore] for the message they become) - see
 * res/xml/file_paths.xml's "captures" cache-path. Callers delete the file once it's been sent
 * (or the capture was cancelled) since nothing here needs to outlive that.
 */
object CaptureFiles {
    private const val DIR = "captures"

    fun newImageFile(context: Context): File = newFile(context, "jpg")
    fun newVideoFile(context: Context): File = newFile(context, "mp4")
    fun newAudioFile(context: Context): File = newFile(context, "m4a")

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Camera-capture intents (ACTION_IMAGE_CAPTURE/ACTION_VIDEO_CAPTURE) hand our FileProvider
     *  URI to a separate camera app via EXTRA_OUTPUT; some OEM camera apps only honor a write
     *  grant handed out explicitly like this rather than one implied by the intent alone. */
    fun grantWriteAccess(context: Context, action: String, uri: Uri) {
        val resolved = context.packageManager.queryIntentActivities(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)
        for (info in resolved) {
            context.grantUriPermission(
                info.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    fun revokeAccess(context: Context, uri: Uri) {
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun newFile(context: Context, extension: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        return File(dir, "capture_${System.currentTimeMillis()}.$extension")
    }
}
