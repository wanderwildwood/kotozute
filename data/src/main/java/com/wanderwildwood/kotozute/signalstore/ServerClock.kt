package com.wanderwildwood.kotozute.signalstore

import android.os.SystemClock

/**
 * The server's time as last heard, for the questions that must be answered in server time.
 *
 * Upstream keeps `MiscellaneousValues.lastKnownServerTimeOffset` (phone clock minus server
 * clock, set by `setLastKnownServerTime` from each server timestamp) and reads
 * `estimatedServerTime` as `System.currentTimeMillis() - lastKnownServerTimeOffset`. Its first
 * reader that matters here is `SealedSenderAccessUtil.getSealedSenderCertificate`, which since
 * 5be9220226 judges the certificate's expiry by that estimate rather than by the phone clock.
 *
 * One departure, and why: the pair is held against [SystemClock.elapsedRealtime], as
 * [SignalSocketHealthMonitor] already does for the skew check (upstream `ClockSkewDetector`
 * caches the same pair), so a phone clock changed after the server was heard does not drag the
 * estimate with it. And it lives in memory: until the socket hears the server after a process
 * start, the offset is unknown and callers fall back to the phone clock, which is what
 * upstream's offset of zero means before its first server timestamp.
 */
internal object ServerClock {

    /** The server's time and the monotonic reading when it was heard; one object so both change together. */
    private class Heard(val serverTime: Long, val elapsedAt: Long)

    @Volatile private var heard: Heard? = null

    /** Told by the socket's health monitor each time the server sends its time. */
    fun heard(serverTime: Long, elapsedAt: Long) {
        heard = Heard(serverTime, elapsedAt)
    }

    /** Phone clock minus server clock now, or null when the server has not been heard since start. */
    fun offset(): Long? = heard?.let {
        offsetFrom(System.currentTimeMillis(), it.serverTime, it.elapsedAt, SystemClock.elapsedRealtime())
    }

    /**
     * Phone clock minus server clock, with the server's time carried forward monotonically:
     * [serverTime] was true at reading [takenAt], so at [elapsedNow] the server reads that plus
     * the difference. The signed form of [SignalSocketHealthMonitor.skewFrom].
     */
    internal fun offsetFrom(now: Long, serverTime: Long, takenAt: Long, elapsedNow: Long): Long =
        now - (serverTime + (elapsedNow - takenAt))
}
