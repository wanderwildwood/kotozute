package com.wanderwildwood.kotozute.signalstore

import android.os.SystemClock
// RxJava **3**, not the RxJava 2 the rest of this app uses. Both are on the classpath
// and the service library's socket state is a v3 Observable, so a v2 Scheduler here is
// a type error rather than a subtle bug -- but only because they differ in package.
import io.reactivex.rxjava3.schedulers.Schedulers
import org.whispersystems.signalservice.api.websocket.HealthMonitor
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Watches the websocket: a connection that never completes, a device the server no longer
 * accepts, the server's alerts, and the clock.
 *
 * First ported from signal-cli's `SignalWebSocketHealthMonitor` (GPL-3.0, as is this app). It is
 * not in the service library -- the library defines the [HealthMonitor] interface and expects
 * the client to bring one.
 *
 * ⚠ **It no longer sends keepalives, because Signal no longer does** (upstream 37f8b149dd,
 * "Remove now-unnecessary manual keepalives", 2026-11-06). SignalWebSocket.sendKeepAlive,
 * HealthMonitor.onKeepAliveResponse and WebSocketConnection.sendKeepAlive are gone from the
 * service layer, and Signal's own monitor lost its KeepAliveSender and its `SleepTimer` in the
 * same commit; this follows it. Until then this class ran an alarm-backed sender on the
 * authenticated socket (30 s on screen, 180 s off, measured on a Kompakt 2026-10-01) and forced
 * a new socket when a keepalive went unanswered for 20 s. What keeps the connection alive, and
 * notices a dead one, is now libsignal's chat connection alone.
 */
internal class SignalSocketHealthMonitor(
    /**
     * Called when the server says this device is no longer welcome, with a
     * [com.wanderwildwood.kotozute.repository.SignalRefusal] kind, never a sentence.
     *
     * ⚠ Without it the loop reconnects for ever. When the account owner removes this linked
     * device -- an ordinary thing to do, and exactly what the unpair path does -- the server
     * refuses the connection, the phone stops receiving anything, and nothing tells the
     * reader: it goes on trying every minute, indefinitely, looking connected in the UI while
     * no message can arrive.
     */
    private val onRejected: (String) -> Unit = {},
    /**
     * Called with whether the server says the account's **primary** has gone idle.
     *
     * Not a rejection -- nothing is wrong yet -- but the warning that comes before one: a
     * linked device whose primary stays idle is eventually unlinked, and everything on it
     * goes. See [onReceivedAlerts].
     */
    private val onPrimaryIdle: (Boolean) -> Unit = {},
    /**
     * Called with whether the socket is currently reaching for the server.
     *
     * ⚠ "Not connected" and "not connected *yet*" are the same fact to everything below this
     * line and completely different facts to the person holding the phone. A phone that has
     * just woken is always disconnected for a moment, and a composer that says sending
     * cannot happen -- without saying it is about to be able to -- reads as broken on every
     * single launch. Somebody reported exactly that.
     */
    private val onConnecting: (Boolean) -> Unit = {}
) : HealthMonitor {

    // Scheduled rather than plain, because the connecting watchdog needs to fire later on the
    // same single thread everything else here runs on; one thread keeps the state below free
    // of locks.
    private val executor = Executors.newSingleThreadScheduledExecutor()

    /** The armed connecting watchdog, if a connection is in progress. */
    private var connectingTimeout: java.util.concurrent.ScheduledFuture<*>? = null

    /** Whether the last attempt already timed out here; upstream waits longer the second time. */
    private var failedInConnecting = false

    /**
     * The last time the server told us, and the monotonic reading when it did.
     *
     * A pair, because the second is what makes the first still useful later: `elapsedRealtime`
     * counts since boot and no clock change touches it, so the server's time can be carried
     * forward and compared again without another round trip.
     */
    @Volatile private var lastServerTime = 0L

    @Volatile private var lastServerTimeAt = 0L

    /** Whether the clock was last seen to disagree, so only changes are reported. */
    @Volatile private var clockWasOff = false
    @Volatile private var webSocket: SignalWebSocket? = null


    fun monitor(webSocket: SignalWebSocket) {
        check(this.webSocket == null) { "monitor can only be called once" }
        executor.execute {
            this.webSocket = webSocket
            webSocket.state
                .subscribeOn(Schedulers.computation())
                .observeOn(Schedulers.computation())
                .distinctUntilChanged()
                .subscribe(::onStateChanged)
        }
    }

    private fun onStateChanged(connectionState: WebSocketConnectionState) {
        Timber.d("signal socket: state -> %s", connectionState)
        executor.execute {
            recheckClock()
            when (connectionState) {
                // ⚠ A socket can sit in CONNECTING for ever, and nothing was watching it.
                //
                // A half-open TCP connection, a captive portal, a TLS handshake that stalls:
                // the connection never reaches CONNECTED. The listen loop does not
                // help either -- its read timeout is deliberately treated as "carry on",
                // because a quiet account is the ordinary case. So the phone sits there
                // looking connected and receiving nothing, until something else disturbs it.
                //
                // Upstream arms a timer on entering CONNECTING and cancels it on any
                // transition out: thirty seconds, sixty if the last attempt already failed
                // here, and then `forceNewWebSocket()`.
                WebSocketConnectionState.CONNECTING -> {
                    onConnecting(true)
                    connectingTimeout?.cancel(false)
                    val wait = if (failedInConnecting) CONNECTING_TIMEOUT_AGAIN else CONNECTING_TIMEOUT
                    connectingTimeout = executor.schedule({
                        Timber.w("signal socket: still connecting after %d ms; starting over", wait)
                        failedInConnecting = true
                        runCatching { webSocket?.forceNewWebSocket() }
                            .onFailure {
                                // The health monitor keeps running and tries again on its own
                                // schedule; a socket that will not restart now is one the next
                                // check finds still unhealthy and restarts then.
                                Timber.w(it, "signal socket: could not start over; the next check tries again")
                            }
                    }, wait, TimeUnit.MILLISECONDS)
                }

                WebSocketConnectionState.CONNECTED -> {
                    onConnecting(false)
                    connectingTimeout?.cancel(false)
                    connectingTimeout = null
                    failedInConnecting = false
                }

                WebSocketConnectionState.AUTHENTICATION_FAILED ->
                    // Not a network problem and not worth retrying: the credentials this
                    // device holds are no longer an account. Retrying is what it did before,
                    // for ever, in silence.
                    onRejected(com.wanderwildwood.kotozute.repository.SignalRefusal.UNLINKED)
                WebSocketConnectionState.REMOTE_DEPRECATED ->
                    onRejected(com.wanderwildwood.kotozute.repository.SignalRefusal.DEPRECATED)
                else -> {
                    // Any other transition is a transition *out* of connecting, so the
                    // watchdog has done its job or is no longer about anything -- and
                    // nothing is being reached for any more, whatever the reason.
                    onConnecting(false)
                    connectingTimeout?.cancel(false)
                    connectingTimeout = null
                }
            }
        }
    }

    override fun onMessageError(status: Int, isIdentifiedWebSocket: Boolean) {
        // 499 is the server saying this build is too old to talk to. Discarded before, so a
        // deprecated client looked exactly like a flaky connection.
        if (status == DEPRECATED_STATUS) {
            onRejected(com.wanderwildwood.kotozute.repository.SignalRefusal.DEPRECATED)
        }
    }

    /**
     * What the server wants this device to know, as opposed to what it is sending it.
     *
     * ⚠ One of these matters and it was being joined into a log line with the rest.
     * `idle-primary-device` is the server saying the account's **primary** has not been seen
     * for a long time -- and a linked device whose primary stays idle is eventually unlinked,
     * taking every message on it. It is the one warning that arrives before that happens, and
     * it reached a debug log nobody reads.
     *
     * Upstream ignores alerts on the unauthenticated socket entirely and, on the authenticated
     * one, records exactly this alert against the account, gated on actually being a linked
     * device -- a primary cannot be told its own primary is idle.
     */
    override fun onReceivedAlerts(alerts: Array<out String>, isIdentifiedWebSocket: Boolean) {
        // Nothing on the unauthenticated socket is about this account.
        if (!isIdentifiedWebSocket) return
        executor.execute {
            if (alerts.isNotEmpty()) {
                Timber.i("signal socket: server alerts: %s", alerts.joinToString(", "))
            }
            val idle = alerts.contains(ALERT_IDLE_PRIMARY_DEVICE)
            runCatching { onPrimaryIdle(idle) }
                .onFailure { Timber.w(it, "signal socket: could not record the primary's state") }
        }
    }

    /**
     * A clock more than a day out breaks more than timestamps: sealed sender certificates and
     * the protocol's own replay windows are time-bound, so messages start failing for reasons
     * that look like anything but the clock.
     *
     * ⚠ The server's time is kept against [SystemClock.elapsedRealtime], which no clock change
     * can move, so the skew can be recomputed later without another round trip. That is the
     * case this could not see before: the clock was only ever compared at the moment a
     * timestamp arrived, so a clock changed *while connected* went unnoticed until the next
     * one -- and a badly wrong clock is precisely the state in which the server keeps tearing
     * the connection down, so the next one may be a long time coming.
     *
     * Ported from Signal's `ClockSkewDetector`, which caches the same pair for the same reason.
     *
     * ⚠ Reported, not acted on. Upstream blocks the socket while skew is detected and puts a
     * whole screen in front of the person. The nearest thing here is [onRejected], and it is
     * the wrong shape: it stops the stream and stays stopped until the device is unpaired,
     * whereas a wrong clock is fixed by correcting the clock and should recover by itself.
     * Blocking delivery over a fault this app cannot verify it has is the worse trade.
     */
    override fun onServerTimestamp(serverTimestamp: Long, isIdentifiedWebSocket: Boolean) {
        val takenAt = SystemClock.elapsedRealtime()
        lastServerTime = serverTimestamp
        lastServerTimeAt = takenAt
        reportSkew(skewFrom(System.currentTimeMillis(), serverTimestamp, takenAt, takenAt))
    }

    /**
     * Re-checks the clock against the last server time, without asking the server again.
     *
     * Called on every socket state change. It used to ride the keepalive loop, once per
     * keepalive; that loop is gone (see the class comment), so a clock changed *while
     * connected* is now noticed at the next reconnect or the next time the server volunteers
     * its time, whichever is first. Upstream's `recheck` does the same arithmetic and clears
     * the state when the clock looks right again.
     */
    private fun recheckClock() {
        val takenAt = lastServerTimeAt
        if (takenAt == 0L) {
            // Nothing to compare against, so nothing is known to be wrong. Upstream's `recheck`
            // resets here too rather than letting an old answer stand.
            reportSkew(0L)
            return
        }
        reportSkew(
            skewFrom(System.currentTimeMillis(), lastServerTime, takenAt, SystemClock.elapsedRealtime())
        )
    }

    private fun reportSkew(skew: Long) {
        val bad = skew > ALLOWED_SKEW
        // Only the changes: a wrong clock stays wrong for as long as it takes somebody to fix
        // it, and saying so at every check until then buries everything else in the log.
        if (bad == clockWasOff) return
        clockWasOff = bad
        if (bad) {
            Timber.w("signal socket: local clock is %d ms off the server", skew)
        } else {
            Timber.i("signal socket: the local clock agrees with the server again")
        }
    }

    companion object {
        /** The status the server answers with when the client is too old to talk to. */
        private const val DEPRECATED_STATUS = 499

        /** Signal's `ALERT_IDLE_PRIMARY_DEVICE`: the account's primary has not been seen. */
        private const val ALERT_IDLE_PRIMARY_DEVICE = "idle-primary-device"

        /** How long a socket may sit in CONNECTING, and how long after it has already failed. */
        private val CONNECTING_TIMEOUT = TimeUnit.SECONDS.toMillis(30)
        private val CONNECTING_TIMEOUT_AGAIN = TimeUnit.SECONDS.toMillis(60)

        /**
         * How far the clock may be out before it is worth saying so.
         *
         * Signal's `client.maxAllowedClockSkewSeconds`, whose default is `24.hours`. Not a
         * round number picked here: sealed sender certificates are valid for a day, so a
         * clock out by more than that fails them whichever way it leans.
         */
        private val ALLOWED_SKEW = TimeUnit.HOURS.toMillis(24)

        /**
         * How far [now] is from the server's time, carried forward monotonically.
         *
         * Upstream's `skewFrom`, with the carry-forward spelled out rather than done at the
         * call site: [serverTime] was true at monotonic reading [takenAt], so at reading
         * [elapsedNow] the server's clock now reads that plus the difference. Neither reading
         * comes from the wall clock, so changing the wall clock cannot hide the change.
         *
         * Pure, and internal, so it can be driven past the threshold in a test. A phone whose
         * clock happens to be right proves nothing about a detector.
         */
        internal fun skewFrom(now: Long, serverTime: Long, takenAt: Long, elapsedNow: Long): Long =
            abs(now - (serverTime + (elapsedNow - takenAt)))

        /** The bound the above is measured against, exposed for the same reason. */
        internal val ALLOWED_SKEW_MS: Long get() = ALLOWED_SKEW
    }

}
