package app.frad.chat.data

import android.content.Context
import java.io.File

/**
 * On-device storage for files sent/received over Wi-Fi Direct (M3), one subdirectory per
 * peer under the app's private storage — nothing here is ever exposed outside the app except
 * via a `FileProvider` content URI (see the manifest's `<provider>` entry and
 * `res/xml/file_paths.xml`) when the user explicitly opens a received file. Like
 * [app.frad.chat.contacts.ChatHistoryStore], a peer's files are only kept once they're a saved
 * contact: the chat controllers [delete] them when a chat with anyone else ends, removing a
 * contact deletes theirs, and [purgeExcept] sweeps up anything left behind.
 */
class MediaFileStore(private val context: Context) {

    fun fileFor(peerId: String, messageId: String): File = File(peerDir(peerId), messageId)

    /** Where a file being received from [peerId] is written to as it arrives. */
    fun newIncomingFile(peerId: String, messageId: String): File =
        fileFor(peerId, messageId).also { it.parentFile?.mkdirs() }

    /** Moves the sent file [source] (a temporary copy, see MediaSanitizer) into [peerId]'s files. */
    fun adopt(peerId: String, messageId: String, source: File): File {
        val file = newIncomingFile(peerId, messageId)
        if (!source.renameTo(file)) {
            source.copyTo(file, overwrite = true)
            source.delete()
        }
        return file
    }

    fun delete(peerId: String) {
        peerDir(peerId).deleteRecursively()
    }

    /** Deletes every peer's files except [keepPeerIds]' - leftovers of chats with people who were
     *  never saved, e.g. if the app was killed before such a chat could end cleanly. */
    fun purgeExcept(keepPeerIds: Set<String>) {
        File(context.filesDir, MEDIA_DIR).listFiles()?.forEach { dir ->
            if (dir.name !in keepPeerIds) dir.deleteRecursively()
        }
    }

    private fun peerDir(peerId: String): File = File(File(context.filesDir, MEDIA_DIR), peerId)

    private companion object {
        const val MEDIA_DIR = "wfd_media"
    }
}
