package com.wanderwildwood.kotozute.util

import java.io.File

/**
 * Unencrypted working copies of Signal attachments in the cache directory, and their removal.
 *
 * Three kinds, each named exactly; nothing else in the cache is looked at.
 *
 * - `voice-<id>.aac` and `view-once-video`, directly in the cache: what builds before this one
 *   wrote to play a voice message or a view-once video, and deleted when playback ended. A
 *   process that died first left the file. Nothing writes these any more -- both now play
 *   from memory -- so one found here belongs to no playback and goes whatever its age.
 * - `signal-shared/`: the copy staged when an attachment is opened in another app or a
 *   picture is copied. It has to be in the clear, since another app reads it, and Android
 *   does not say when that app has finished with it or when the clipboard has moved on. So
 *   it goes by age: a copy is left alone for [SHARED_KEPT_MS] after it was staged and removed
 *   at the first clean after that.
 *
 * A copy an older build staged directly in the cache under the sender's file name cannot be
 * told from any other cache file, and is left for Android to clear with the cache.
 */
object SignalWorkingCopies {

    /** Inside the cache directory: copies staged for another app. */
    const val SHARED_DIR = "signal-shared"

    /** How long a staged copy is left for the other app, or a paste, to read. */
    const val SHARED_KEPT_MS = 2L * 60 * 60 * 1000

    private const val VIEW_ONCE_VIDEO = "view-once-video"
    private val OLD_VOICE_COPY = Regex("voice-[A-Za-z0-9._-]+\\.aac")

    /** Where to stage a copy for another app. Made if missing. */
    fun sharedDir(cacheDir: File): File = File(cacheDir, SHARED_DIR).apply { mkdirs() }

    /**
     * Removes what is described above.
     *
     * @return how many files went.
     */
    fun clean(cacheDir: File, now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        cacheDir.listFiles().orEmpty()
            .filter { it.isFile && (it.name == VIEW_ONCE_VIDEO || OLD_VOICE_COPY.matches(it.name)) }
            .forEach { if (it.delete()) removed++ }
        File(cacheDir, SHARED_DIR).listFiles().orEmpty()
            .filter { it.isFile && isStale(it.lastModified(), now) }
            .forEach { if (it.delete()) removed++ }
        return removed
    }

    /**
     * Whether a staged copy has had its time. A stamp in the future, or none, reads as new:
     * the cost of waiting is a file in the cache for longer, and the cost of being wrong is a
     * picture that will not paste.
     */
    internal fun isStale(lastModified: Long, now: Long): Boolean =
        lastModified in 1..now && now - lastModified >= SHARED_KEPT_MS
}
