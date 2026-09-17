package com.wanderwildwood.kotozute.common.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the app was doing when it died, kept on the phone for the person to read.
 *
 * ⚠ **There is no crash reporting of any kind in this app, deliberately** -- no Google services,
 * no analytics, nothing that phones home. That is right, and it has a cost nobody was carrying:
 * a crash on somebody else's phone is invisible. They can say "it closed itself" and that is the
 * whole of the evidence. This is the smallest thing that fixes that without giving anything away.
 *
 * ⚠ **Not [FileLoggingTree].** That one is gated behind a preference, writes to a folder somebody
 * chose, and writes **asynchronously** -- so at the moment this matters the process is already
 * being torn down and the write never lands. A crash record has to be written on the dying
 * thread, before the handler returns.
 *
 * ⛔ **Private storage, never the SD card.** A stack trace can carry message content in an
 * exception message, and this app's whole premise is that such content does not leave the phone.
 * Here it is readable by this app alone; putting it in Download would publish it to every app
 * holding a storage permission. Sending it anywhere is a separate, deliberate act, and the
 * screen that offers it shows the text first so nobody sends what they have not read.
 */
object CrashLog {

    private const val FILE = "last-crashes.txt"

    /** Enough to show a pattern, small enough that nobody scrolls for ever. */
    private const val KEEP_BYTES = 64 * 1024

    /**
     * Chains onto whatever handler is already there rather than replacing it.
     *
     * Android's own handler is what actually ends the process and tells the system a crash
     * happened; replacing it would leave the app hanging in a half-dead state, which is worse
     * than the crash. This writes its record and then hands the failure straight on.
     */
    fun install(context: Context, versionName: String) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Every step guarded: a failure to record a crash must never become the crash.
            runCatching { write(app, versionName, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** What is on file, oldest first, or empty if it has never crashed. */
    fun read(context: Context): String =
        runCatching { File(context.applicationContext.filesDir, FILE).readText() }
            .getOrDefault("")

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE).delete() }
    }

    private fun write(context: Context, versionName: String, threadName: String, error: Throwable) {
        val file = File(context.filesDir, FILE)
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val entry = buildString {
            append("---- ").append(when_)
            append("  v").append(versionName)
            append("  thread ").append(threadName).append('\n')
            append(trace).append('\n')
        }
        // Oldest trimmed rather than newest dropped: the crash somebody is looking at is the one
        // that just happened, and a file that refuses to grow would hide it behind history.
        val existing = runCatching { file.readText() }.getOrDefault("")
        val combined = (existing + entry).let {
            if (it.length <= KEEP_BYTES) it else it.takeLast(KEEP_BYTES)
        }
        file.writeText(combined)
    }
}
