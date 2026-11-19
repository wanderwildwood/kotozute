package com.wanderwildwood.kotozute.feature.lock

import android.app.ActivityManager
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
 * from the background. It stops well before the phone runs short, so it never pushes other
 * apps out of memory: it watches the system's low-memory line and backs off when told to trim.
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

    private fun wipe(): Long {
        val am = getSystemService(ActivityManager::class.java) ?: return 0
        val info = ActivityManager.MemoryInfo()
        val chunks = ArrayList<SharedMemory>()
        val pattern = ByteArray(64 * 1024)
        var total = 0L
        try {
            while (!stop) {
                am.getMemoryInfo(info)
                // Keep twice the system's own low-memory line free: well short of where it
                // starts closing other apps.
                if (info.lowMemory || info.availMem < info.threshold * 2 + CHUNK_BYTES) break
                val mem = runCatching { SharedMemory.create(null, CHUNK_BYTES) }.getOrNull() ?: break
                chunks += mem
                val buf = mem.mapReadWrite()
                try {
                    while (buf.remaining() >= pattern.size) buf.put(pattern)
                    if (buf.hasRemaining()) buf.put(pattern, 0, buf.remaining())
                } finally {
                    SharedMemory.unmap(buf)
                }
                total += CHUNK_BYTES
            }
        } finally {
            chunks.forEach { runCatching { it.close() } }
        }
        return total
    }

    companion object {
        private const val JOB_ID = 0x5719E
        private const val CHUNK_BYTES = 4 * 1024 * 1024

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, WipeMemoryJob::class.java))
                .setOverrideDeadline(0)
                .build()
            runCatching { js.schedule(job) }.onFailure { Timber.w(it, "wipe: could not queue the wiper") }
        }
    }
}
