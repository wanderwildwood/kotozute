package com.wanderwildwood.kotozute.feature.lock

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.SharedMemory
import timber.log.Timber

/**
 * The memory wiper (Molly's "secure RAM wiper"), run after the app has locked and ended.
 *
 * When a process ends, Android takes its memory back but does not clear it: the pages that
 * held decrypted messages and keys keep their contents until something else is given them.
 * This claims as much free memory as the phone can spare, a few megabytes at a time, and
 * writes over every page -- which is what puts those pages through a wipe now rather than
 * whenever. Molly does the same with native code; [SharedMemory] gives the same pages from
 * Kotlin, outside the Java heap and its limit.
 *
 * It runs in its own process (`:wipe` in the manifest), started by the job scheduler because
 * Android 12 will not let an app in the background start a service, and a lock is mostly
 * from the background. It never pushes other
 * apps out of memory: it takes only memory that is already free, keeps a quarter of RAM untouched,
 * stops at 384 MB, and backs off when told to trim.
 */
class WipeMemoryJob : JobService() {

    @Volatile private var stop = false
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            val wiped = runCatching { wipe() }.onFailure { Timber.w(it, "wipe: stopped early") }.getOrDefault(0L)
            Timber.i("wipe: %d MiB of free memory overwritten", wiped / (1024 * 1024))
            jobFinished(params, false)
        }.also { it.priority = Thread.MIN_PRIORITY; it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stop = true
        return false
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) stop = true
    }

    override fun onLowMemory() { stop = true }

    /** A /proc/meminfo field in bytes, or -1. */
    private fun meminfo(field: String): Long = runCatching {
        java.io.File("/proc/meminfo").useLines { lines ->
            lines.first { it.startsWith("$field:") }.filter(Char::isDigit).toLong() * 1024
        }
    }.getOrDefault(-1L)

    /**
     * ⛔ Only memory that is free right now (MemFree), never "available" memory: available counts
     * cache the kernel must reclaim first, and reclaiming under pressure is what makes Android's
     * low-memory killer close other apps. The first version claimed available memory and, on the
     * Kompakt (2.8 GB), five background apps were closed in two seconds (2026-11-19). A dead
     * process's pages go straight to the free pool, so free memory is where they are anyway.
     */
    private fun wipe(): Long {
        val total = meminfo("MemTotal").takeIf { it > 0 } ?: return 0
        val floor = maxOf(total / 4, 256L * 1024 * 1024)
        val chunks = ArrayList<SharedMemory>()
        val pattern = ByteArray(64 * 1024)
        var wiped = 0L
        try {
            while (!stop && wiped < CAP_BYTES) {
                val free = meminfo("MemFree")
                if (free < 0 || free < floor + CHUNK_BYTES) break
                val mem = runCatching { SharedMemory.create(null, CHUNK_BYTES) }.getOrNull() ?: break
                chunks += mem
                val buf = mem.mapReadWrite()
                try {
                    while (buf.remaining() >= pattern.size) buf.put(pattern)
                    if (buf.hasRemaining()) buf.put(pattern, 0, buf.remaining())
                } finally {
                    SharedMemory.unmap(buf)
                }
                wiped += CHUNK_BYTES
                Thread.sleep(PAUSE_MS)
            }
        } finally {
            chunks.forEach { runCatching { it.close() } }
        }
        return wiped
    }

    companion object {
        private const val JOB_ID = 0x5719E
        private const val CHUNK_BYTES = 4 * 1024 * 1024
        /** More than the app's own process ever holds; the rest of free memory is not ours to churn. */
        private const val CAP_BYTES = 384L * 1024 * 1024
        private const val PAUSE_MS = 15L

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, WipeMemoryJob::class.java))
                .setOverrideDeadline(0)
                .build()
            runCatching { js.schedule(job) }.onFailure { Timber.w(it, "wipe: could not queue the wiper") }
        }
    }
}
