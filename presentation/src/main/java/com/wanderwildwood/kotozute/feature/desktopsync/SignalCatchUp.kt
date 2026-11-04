package com.wanderwildwood.kotozute.feature.desktopsync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Signal's catch-up for Desktop Sync: what the app's Signal screen does when it opens (ask the
 * server for anything it is holding, then hold the socket open), run when a browser opens the
 * page and when the service starts by itself, which no screen does.
 *
 * Signal Android does the same on coming to the foreground, and its conversation list waits for
 * the queue to be drained (IncomingMessageObserver.decryptionDrained) before calling itself up
 * to date. So the page's first list waits here too, for at most [waitMs], rather than showing
 * the rows as they were when the stream last ran and changing under the reader seconds later.
 *
 * One at a time: an ask while one runs joins it. Android-free, so it is tested on a desk.
 */
class SignalCatchUp(
    private val waitMs: Long = WAIT_MS,
    private val run: (Runnable) -> Unit = { Thread(it, "desktop-signal-catch-up").apply { isDaemon = true }.start() },
    private val work: () -> Unit,
) {
    companion object {
        /** Long enough for a held queue to drain on a slow connection; short enough to be a wait, not a hang. */
        const val WAIT_MS = 4_000L
    }

    @Volatile private var running: CountDownLatch? = null

    /** Starts a catch-up unless one is running. */
    fun start() {
        val latch: CountDownLatch
        synchronized(this) {
            if (running != null) return
            latch = CountDownLatch(1)
            running = latch
        }
        run(Runnable {
            try {
                runCatching(work)
            } finally {
                synchronized(this) { if (running === latch) running = null }
                latch.countDown()
            }
        })
    }

    /** Waits for a running catch-up to finish, for at most [waitMs]. True when none is left running. */
    fun awaitDone(): Boolean = running?.await(waitMs, TimeUnit.MILLISECONDS) ?: true
}
