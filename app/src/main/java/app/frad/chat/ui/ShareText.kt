package app.frad.chat.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands [text] to an app the user picks. Short text goes inline; a long report or crash log
 * goes as a file in the cache, because an intent's extras travel through a ~1 MB Binder buffer
 * and a big transcript would crash the chooser with TransactionTooLargeException.
 */
object ShareText {
    private const val DIR = "exports"
    private const val INLINE_LIMIT_CHARS = 64 * 1024

    fun share(context: Context, text: String, chooserTitle: String, subject: String? = null, fileName: String = "frad.txt") {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        if (subject != null) send.putExtra(Intent.EXTRA_SUBJECT, subject)
        if (text.length <= INLINE_LIMIT_CHARS) {
            send.putExtra(Intent.EXTRA_TEXT, text)
        } else {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            // Only the latest export is kept; older ones were already handed over.
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, fileName).apply { writeText(text) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.clipData = ClipData.newRawUri(fileName, uri) // carries the read grant through the chooser
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }
}
