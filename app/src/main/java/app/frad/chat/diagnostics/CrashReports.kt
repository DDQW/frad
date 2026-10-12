package app.frad.chat.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Opt-in crash reports that never leave the phone by themselves: when the switch is on, a crash
 * writes its stack trace - with anything that could identify someone redacted (see [Redaction]) -
 * to app storage, and the user can look at it and share it themselves (e.g. in a bug report).
 * No crash-reporting service, no automatic upload.
 */
object CrashReports {
    private const val DIR = "crash_reports"
    private const val MAX_REPORTS = 5

    /** Call once from Application.onCreate; [enabled] is read at crash time. */
    fun install(context: Context, appVersion: String, enabled: () -> Boolean) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { if (enabled()) write(app, appVersion, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Newest first. */
    fun reports(context: Context): List<File> =
        File(context.filesDir, DIR).listFiles()?.sortedByDescending { it.name }.orEmpty()

    fun deleteAll(context: Context) {
        File(context.filesDir, DIR).deleteRecursively()
    }

    private fun write(context: Context, appVersion: String, threadName: String, error: Throwable) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("FRAD $appVersion crash report")
            appendLine("Android API ${Build.VERSION.SDK_INT}")
            appendLine("Thread: ${Redaction.redact(threadName)}")
            appendLine()
            append(Redaction.redact(trace))
        }
        File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(text)
        dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_REPORTS)?.forEach { it.delete() }
    }
}

/** Removes what could identify a person or device from text that's meant to be shared. */
object Redaction {
    private val rules = listOf(
        Regex("""\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b""") to "<bt-address>",
        Regex("""/p2p/[1-9A-HJ-NP-Za-km-z]{32,64}""") to "/p2p/<peer>",
        Regex("""\b12D3KooW[1-9A-HJ-NP-Za-km-z]{40,50}\b""") to "<peer>",
        Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""") to "<ip>",
        Regex("""\b([0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}\b""") to "<ip6>",
        Regex("""[\w.+\-]+@[\w\-]+\.[\w.\-]{2,}""") to "<email>",
        // Long hex/base64 runs: keys, ids, session ids, fingerprints.
        Regex("""\b[0-9a-fA-F]{16,}\b""") to "<hex>",
        Regex("""\b[A-Za-z0-9_\-+/]{24,}={0,2}""") to "<id>",
    )

    fun redact(text: String): String = rules.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }
}
