package com.wanderwildwood.kotozute.signalstore

import org.signal.core.util.logging.Log
import timber.log.Timber

/**
 * EXPERIMENT (signal-on-the-phone branch). Points the Signal service layer's logging at Timber.
 *
 * That layer logs through its own `Log`, which starts as a no-op. Everything it has to say
 * while linking or receiving -- "Macs do not match", "Version does not match expected",
 * "Device address is null" -- is discarded until something is registered here, and a failure
 * then reports only that it failed. This is the difference between debugging and guessing.
 *
 * Registered once; calling twice would double every line.
 */
object SignalServiceLog {

    @Volatile private var routed = false

    @Synchronized
    fun route() {
        if (routed) return
        routed = true
        Log.initialize(TimberLogger)
    }

    private object TimberLogger : Log.Logger() {
        // The boolean each of these carries is Signal's "keepLonger" hint for its own ring
        // buffer. There is no ring buffer here -- Timber's own trees decide what is kept --
        // so it is deliberately ignored rather than half-honoured.
        override fun v(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) =
            Timber.tag(tag).v(t, message.orEmpty())

        override fun d(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) =
            Timber.tag(tag).d(t, message.orEmpty())

        override fun i(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) =
            Timber.tag(tag).i(t, message.orEmpty())

        override fun w(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) =
            Timber.tag(tag).w(t, message.orEmpty())

        override fun e(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) =
            Timber.tag(tag).e(t, message.orEmpty())

        override fun flush() = Unit
    }
}
