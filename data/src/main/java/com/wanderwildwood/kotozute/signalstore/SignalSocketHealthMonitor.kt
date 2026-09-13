package com.wanderwildwood.kotozute.signalstore

import android.os.SystemClock
// RxJava **3**, not the RxJava 2 the rest of this app uses. Both are on the classpath
// and the service library's socket state is a v3 Observable, so a v2 Scheduler here is
// a type error rather than a subtle bug -- but only because they differ in package.
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.SleepTimer
import org.whispersystems.signalservice.api.websocket.HealthMonitor
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Keeps the websocket alive, and notices when it is only pretending to be.
 *
 * Ported from signal-cli's `SignalWebSocketHealthMonitor` (GPL-3.0, as is this app). It is not
 * in the service library -- the library defines the [HealthMonitor] interface and expects the
 * client to bring one -- so every client writes this, and writing a worse one is easy.
 *
 * The part that earns its keep is not sending the keepalive; it is **waiting for the reply**.
 * A TCP connection to a server that has stopped answering looks open indefinitely, so a socket
 * that only sends is a socket that can be dead for hours while every layer above believes it is
 * connected. So each round does two waits: send at 30s, then check at +20s that something came
 * back, and force a new socket if nothing did.
 *
 * The timer is [AlarmSleepTimer], and it has to be. A thread-sleeping timer is not running at
 * all during Doze, so the 30-second cadence was 30 seconds only while the device was awake --
 * which is to say the check written to notice a dead connection was itself asleep for exactly
 * as long as the connection was dead. Signal uses an alarm-backed timer wherever it has no
 * push to fall back on, which here is always.
 */
internal class SignalSocketHealthMonitor(
    private val sleepTimer: SleepTimer,
    /**
     * Called when the server says this device is no longer welcome.
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
     * Whether this socket should send keepalives at all.
     *
     * ⚠ Only one of the two sockets should. Both were given a keepalive sender, so the phone
     * ran **two** alarm-backed threads waking it on their own timers -- twice upstream's
     * exact-alarm wakeup rate, on a device whose whole point is to sit still, for a socket
     * that only carries outbound sealed-sender sends and profile fetches.
     *
     * Signal's monitor takes the same flag and its dependency provider passes true for the
     * authenticated socket and false for the unauthenticated one. The flag gates the keepalive
     * listener, so no sender thread is ever created for the second socket -- the rest of the
     * monitoring, the state watchdog and the alerts, still runs.
     */
    private val sendKeepAlives: Boolean = true
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

    @Volatile private var keepAliveSender: KeepAliveSender? = null
    @Volatile private var needsKeepAlive = false
    /**
     * Written on the executor, read from the keepalive thread. Volatile because those are
     * different threads: a stale read here means the sender concludes every keepalive went
     * unanswered and tears down a healthy socket.
     */
    @Volatile private var lastKeepAliveReceived = 0L

    fun monitor(webSocket: SignalWebSocket) {
        check(this.webSocket == null) { "monitor can only be called once" }
        executor.execute {
            this.webSocket = webSocket
            webSocket.state
                .subscribeOn(Schedulers.computation())
                .observeOn(Schedulers.computation())
                .distinctUntilChanged()
                .subscribe(::onStateChanged)
            if (sendKeepAlives) {
                webSocket.addKeepAliveChangeListener { executor.execute(::updateKeepAliveSenderStatus) }
            }
        }
    }

    private fun onStateChanged(connectionState: WebSocketConnectionState) {
        Timber.d("signal socket: state -> %s", connectionState)
        executor.execute {
            // ⚠ `&& sendKeepAlives` is the gate that actually works. Gating only the
            // keepalive *listener* leaves this line starting a sender on CONNECTED anyway,
            // which is how the second thread survived a first attempt at this. Upstream puts
            // the flag right here: `connectionState == CONNECTED && sendKeepAlives`.
            needsKeepAlive = connectionState == WebSocketConnectionState.CONNECTED && sendKeepAlives
            updateKeepAliveSenderStatus()
            when (connectionState) {
                // ⚠ A socket can sit in CONNECTING for ever, and nothing was watching it.
                //
                // A half-open TCP connection, a captive portal, a TLS handshake that stalls:
                // the connection never reaches CONNECTED, so keepalives never start and the
                // one thing that notices a dead socket never runs. The listen loop does not
                // help either -- its read timeout is deliberately treated as "carry on",
                // because a quiet account is the ordinary case. So the phone sits there
                // looking connected and receiving nothing, until something else disturbs it.
                //
                // Upstream arms a timer on entering CONNECTING and cancels it on any
                // transition out: thirty seconds, sixty if the last attempt already failed
                // here, and then `forceNewWebSocket()`.
                WebSocketConnectionState.CONNECTING -> {
                    connectingTimeout?.cancel(false)
                    val wait = if (failedInConnecting) CONNECTING_TIMEOUT_AGAIN else CONNECTING_TIMEOUT
                    connectingTimeout = executor.schedule({
                        Timber.w("signal socket: still connecting after %d ms; starting over", wait)
                        failedInConnecting = true
                        runCatching { webSocket?.forceNewWebSocket() }
                            .onFailure { Timber.w(it, "signal socket: could not start over") }
                    }, wait, TimeUnit.MILLISECONDS)
                }

                WebSocketConnectionState.CONNECTED -> {
                    connectingTimeout?.cancel(false)
                    connectingTimeout = null
                    failedInConnecting = false
                }

                WebSocketConnectionState.AUTHENTICATION_FAILED ->
                    // Not a network problem and not worth retrying: the credentials this
                    // device holds are no longer an account. Retrying is what it did before,
                    // for ever, in silence.
                    onRejected("This phone is no longer linked to Signal. Link it again to receive messages.")
                WebSocketConnectionState.REMOTE_DEPRECATED ->
                    onRejected("Signal will not accept this version any more. The app needs updating.")
                else -> {
                    // Any other transition is a transition *out* of connecting, so the
                    // watchdog has done its job or is no longer about anything.
                    connectingTimeout?.cancel(false)
                    connectingTimeout = null
                }
            }
        }
    }

    override fun onKeepAliveResponse(sentTimestamp: Long, isIdentifiedWebSocket: Boolean) {
        val received = System.currentTimeMillis()
        executor.execute { lastKeepAliveReceived = received }
    }

    override fun onMessageError(status: Int, isIdentifiedWebSocket: Boolean) {
        // 499 is the server saying this build is too old to talk to. Discarded before, so a
        // deprecated client looked exactly like a flaky connection.
        if (status == DEPRECATED_STATUS) {
            onRejected("Signal will not accept this version any more. The app needs updating.")
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
     * Called once per keepalive, so a clock changed *while connected* is noticed within a
     * minute rather than waiting for whenever the server next volunteers its time. Upstream's
     * `recheck` does the same arithmetic and clears the state when the clock looks right again.
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
        // Only the changes, because this runs every thirty seconds: a wrong clock stays wrong
        // for as long as it takes somebody to fix it, and saying so once a minute until then
        // buries everything else in the log.
        if (bad == clockWasOff) return
        clockWasOff = bad
        if (bad) {
            Timber.w("signal socket: local clock is %d ms off the server", skew)
        } else {
            Timber.i("signal socket: the local clock agrees with the server again")
        }
    }

    private fun updateKeepAliveSenderStatus() {
        val wanted = needsKeepAlive && webSocket?.shouldSendKeepAlives() == true
        when {
            keepAliveSender == null && wanted -> keepAliveSender = KeepAliveSender().also { it.start() }
            keepAliveSender != null && !wanted -> {
                keepAliveSender?.shutdown()
                keepAliveSender = null
            }
        }
    }

    private fun sendKeepAlives(): Boolean = needsKeepAlive && webSocket?.shouldSendKeepAlives() == true

    private inner class KeepAliveSender : Thread("signal-keepalive") {

        @Volatile private var shouldKeepRunning = true

        override fun run() {
            lastKeepAliveReceived = System.currentTimeMillis()
            var sentAt = System.currentTimeMillis()
            while (shouldKeepRunning && sendKeepAlives()) {
                try {
                    // Chosen each time round, as upstream chooses it: thirty seconds while
                    // somebody is looking at the app, sixty when nobody is. A keepalive is a
                    // radio wake, and on this phone nobody is looking most of the day -- there
                    // is no push, so the socket is the only way a message arrives, and it was
                    // paying the foreground rate around the clock.
                    // Upstream rechecks when the app is foregrounded, because it wants to
                    // take its blocking screen down promptly. This one only reports, so it
                    // rides the loop that is already running: two longs subtracted every
                    // thirty to sixty seconds, and no lifecycle wiring across modules for it.
                    recheckClock()
                    val cadence =
                        if (SignalForeground.onScreen()) KEEP_ALIVE_SEND_CADENCE
                        else KEEP_ALIVE_SEND_CADENCE_BACKGROUND
                    sleepUntil(sentAt + cadence)
                    if (shouldKeepRunning && sendKeepAlives()) {
                        sentAt = System.currentTimeMillis()
                        webSocket?.sendKeepAlive()
                    }
                    // The second wait is the whole point: an unanswered keepalive is the only
                    // evidence that an open-looking socket is dead.
                    sleepUntil(sentAt + KEEP_ALIVE_TIMEOUT)
                    if (shouldKeepRunning && sendKeepAlives() && lastKeepAliveReceived < sentAt) {
                        Timber.i("signal socket: missed keepalive; forcing a new socket")
                        webSocket?.forceNewWebSocket()
                    }
                } catch (t: Throwable) {
                    Timber.w(t, "signal socket: keepalive sender failed")
                }
            }
        }

        private fun sleepUntil(timeMillis: Long) {
            // ⚠ `shouldKeepRunning` in the condition, which it was not. Without it the loop
            // only ever looked at the wall clock, so a shutdown while parked here waited out
            // the full cadence -- and `sleepTimer` is an alarm, so the thread stayed parked
            // with an exact alarm armed against a socket that had already gone. Upstream's
            // loop tests the same flag.
            while (shouldKeepRunning && System.currentTimeMillis() < timeMillis) {
                val wait = timeMillis - System.currentTimeMillis()
                if (wait > 0) {
                    try {
                        sleepTimer.sleep(wait)
                    } catch (e: InterruptedException) {
                        Timber.w(e, "signal socket: health monitor interrupted")
                    }
                }
            }
        }

        fun shutdown() {
            shouldKeepRunning = false
            // ⚠ And wake it, which nothing did. The flag alone is only read between sleeps;
            // the interrupt is what ends the sleep this instant. `sleepUntil` catches
            // InterruptedException and the loop condition above then stops it.
            interrupt()
        }
    }

    companion object {
        /** The status the server answers with when the client is too old to talk to. */
        private const val DEPRECATED_STATUS = 499

        /** Must be greater than [KEEP_ALIVE_TIMEOUT], or the check races the send. */
        private val KEEP_ALIVE_SEND_CADENCE = TimeUnit.SECONDS.toMillis(30)

        /** Signal's `KEEP_ALIVE_SEND_CADENCE_BACKGROUND`, for when nobody is looking. */
        private val KEEP_ALIVE_SEND_CADENCE_BACKGROUND = TimeUnit.SECONDS.toMillis(60)

        /** Signal's `ALERT_IDLE_PRIMARY_DEVICE`: the account's primary has not been seen. */
        private const val ALERT_IDLE_PRIMARY_DEVICE = "idle-primary-device"

        /** How long a socket may sit in CONNECTING, and how long after it has already failed. */
        private val CONNECTING_TIMEOUT = TimeUnit.SECONDS.toMillis(30)
        private val CONNECTING_TIMEOUT_AGAIN = TimeUnit.SECONDS.toMillis(60)
        private val KEEP_ALIVE_TIMEOUT = TimeUnit.SECONDS.toMillis(20)

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
