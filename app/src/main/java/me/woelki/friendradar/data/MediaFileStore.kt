package me.woelki.friendradar.data

import android.content.Context
import java.io.File

/**
 * On-device storage for files sent/received over Wi-Fi Direct (M3), one subdirectory per
 * peer under the app's private storage — nothing here is ever exposed outside the app except
 * via a `FileProvider` content URI (see the manifest's `<provider>` entry and
 * `res/xml/file_paths.xml`) when the user explicitly opens a received file. Like
 * [me.woelki.friendradar.contacts.ChatHistoryStore], a peer's files are only worth keeping
 * once they're a saved contact; [delete] is called from the same place `ChatHistoryStore.clear`
 * already is when a contact is removed.
 */
class MediaFileStore(private val context: Context) {

    fun write(peerId: String, messageId: String, bytes: ByteArray): File {
        val file = fileFor(peerId, messageId)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    fun fileFor(peerId: String, messageId: String): File = File(peerDir(peerId), messageId)

    fun delete(peerId: String) {
        peerDir(peerId).deleteRecursively()
    }

    private fun peerDir(peerId: String): File = File(File(context.filesDir, MEDIA_DIR), peerId)

    private companion object {
        const val MEDIA_DIR = "wfd_media"
    }
}
