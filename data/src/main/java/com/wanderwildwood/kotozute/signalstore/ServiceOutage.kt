package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.repository.SendFailure
import com.wanderwildwood.kotozute.repository.SendRefused
import java.net.InetAddress

/**
 * Whether Signal itself is down, as Signal publishes it.
 *
 * Ported from upstream's `ServiceOutageDetectionJob`. The status is a DNS name rather than an
 * endpoint: `uptime.signal.org` resolves to 127.0.0.1 while the service is up and 127.0.0.2
 * while it is not. A lookup, so it answers even when the service it describes cannot.
 */
internal object ServiceOutage {

    enum class Status { UP, DOWN, UNKNOWN }

    /** `BuildConfig.SIGNAL_SERVICE_STATUS_URL` upstream. */
    private const val HOST = "uptime.signal.org"
    private const val IP_SUCCESS = "127.0.0.1"
    private const val IP_FAILURE = "127.0.0.2"

    /** Upstream's `CHECK_TIME`: no more than one look a minute, however many sends fail. */
    const val CHECK_INTERVAL_MS = 60_000L

    /**
     * Any other address is "a weird network state" upstream -- a captive portal, a resolver
     * that rewrites names -- and is retried rather than believed either way.
     */
    fun classify(address: String?): Status = when (address) {
        IP_SUCCESS -> Status.UP
        IP_FAILURE -> Status.DOWN
        else -> Status.UNKNOWN
    }

    /** Blocking. A resolver that cannot be reached at all is [Status.UNKNOWN]. */
    fun check(): Status =
        classify(runCatching { InetAddress.getByName(HOST).hostAddress }.getOrNull())

    /**
     * Whether a failed send is the kind that could be Signal being down.
     *
     * Upstream looks after any send that failed with an `IOException` and is being retried.
     * Narrower here: a refusal in the 4xx range -- unlinked, too old, rate limited, a proof
     * wanted -- is the server answering, which is proof it is up. What is left is a send
     * that never reached it, and a 5xx.
     */
    fun worthChecking(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.take(8).any {
            (it is SendRefused && it.failure == SendFailure.Unreachable) ||
                it is org.signal.network.exceptions.PushNetworkException ||
                (it is org.signal.network.exceptions.NonSuccessfulResponseCodeException && it.code >= 500)
        }
}
