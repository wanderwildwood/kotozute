package com.wanderwildwood.kotozute.signalstore

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
 * ⚠ The timer is [org.signal.core.util.UptimeSleepTimer], which sleeps a thread. Android's
 * Doze defers that, so the 30-second cadence is 30 seconds only while the device is awake --
 * during Doze it stretches, and a connection can sit dead until the next maintenance window.
 * Signal's own Android client uses an alarm-backed timer for exactly this reason. Not solved
 * here; recorded so it is a known limit rather than a surprise.
 */
internal class SignalSocketHealthMonitor(private val sleepTimer: SleepTimer) : HealthMonitor {

    private val executor = Executors.newSingleThreadExecutor()
    private var webSocket: SignalWebSocket? = null

    @Volatile private var keepAliveSender: KeepAliveSender? = null
    private var needsKeepAlive = false
    private var lastKeepAliveReceived = 0L

    fun monitor(webSocket: SignalWebSocket) {
        check(this.webSocket == null) { "monitor can only be called once" }
        executor.execute {
            this.webSocket = webSocket
            webSocket.state
                .subscribeOn(Schedulers.computation())
                .observeOn(Schedulers.computation())
                .distinctUntilChanged()
                .subscribe(::onStateChanged)
            webSocket.addKeepAliveChangeListener { executor.execute(::updateKeepAliveSenderStatus) }
        }
    }

    private fun onStateChanged(connectionState: WebSocketConnectionState) {
        executor.execute {
            needsKeepAlive = connectionState == WebSocketConnectionState.CONNECTED
            updateKeepAliveSenderStatus()
        }
    }

    override fun onKeepAliveResponse(sentTimestamp: Long, isIdentifiedWebSocket: Boolean) {
        val received = System.currentTimeMillis()
        executor.execute { lastKeepAliveReceived = received }
    }

    override fun onMessageError(status: Int, isIdentifiedWebSocket: Boolean) = Unit

    override fun onReceivedAlerts(alerts: Array<out String>, isIdentifiedWebSocket: Boolean) {
        if (alerts.isNotEmpty()) Timber.i("signal socket: server alerts: %s", alerts.joinToString(", "))
    }

    /**
     * A clock more than a day out breaks more than timestamps: sealed sender certificates and
     * the protocol's own replay windows are time-bound, so messages start failing for reasons
     * that look like anything but the clock.
     */
    override fun onServerTimestamp(serverTimestamp: Long, isIdentifiedWebSocket: Boolean) {
        val skew = abs(System.currentTimeMillis() - serverTimestamp)
        if (skew > TimeUnit.DAYS.toMillis(1)) {
            Timber.w("signal socket: local clock is %d ms off the server", skew)
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
                    sleepUntil(sentAt + KEEP_ALIVE_SEND_CADENCE)
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
            while (System.currentTimeMillis() < timeMillis) {
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
        }
    }

    companion object {
        /** Must be greater than [KEEP_ALIVE_TIMEOUT], or the check races the send. */
        private val KEEP_ALIVE_SEND_CADENCE = TimeUnit.SECONDS.toMillis(30)
        private val KEEP_ALIVE_TIMEOUT = TimeUnit.SECONDS.toMillis(20)
    }
}
