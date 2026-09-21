package app.frad.chat.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Thin wrapper around [MediaRecorder] for a chat voice-message attachment - one recording at a
 *  time, always released whether it's sent ([stop]) or discarded ([cancel]). */
class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null

    fun start(output: File) {
        @Suppress("DEPRECATION")
        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        newRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(output.absolutePath)
            prepare()
            start()
        }
        recorder = newRecorder
    }

    /** Stops and releases the recorder; false means the recording is too short/broken to send
     *  (MediaRecorder.stop() throws in that case), and the caller should discard the file. */
    fun stop(): Boolean {
        val current = recorder ?: return false
        recorder = null
        val stopped = runCatching { current.stop() }.isSuccess
        current.release()
        return stopped
    }

    fun cancel() {
        val current = recorder ?: return
        recorder = null
        runCatching { current.stop() }
        current.release()
    }
}
