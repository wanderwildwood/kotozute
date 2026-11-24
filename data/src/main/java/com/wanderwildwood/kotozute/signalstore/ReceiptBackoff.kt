package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.repository.SendFailure
import java.util.concurrent.TimeUnit

/**
 * How long owed receipts wait after the server refused one with a challenge or a rate limit.
 *
 * Upstream f7174c6658 ("Move receipt jobs to grpc and enhance challenge handling"):
 * `SendReadReceiptJob`, `SendViewedReceiptJob` and `SendDeliveryReceiptJob` answer
 * `SendError.ChallengeRequired` with `tryPushChallengeAndToJobResult(TAG, defaultBackoff())` and
 * `SendError.RateLimited` with `toJobResult(TAG, defaultBackoff())` (`SendErrorJobResults.kt`):
 * the receipt is kept and retried after `maxOf(defaultBackoff, retryAfter)`, for the job's
 * day-long lifespan, rather than failed.
 *
 * Here the keeping was already done -- a receipt that does not go is written to
 * [SignalReceiptStore] and retried at the end of each batch and on the periodic round for that
 * same day -- but the retry ignored what the server said: a refused receipt went again at the
 * very next batch, however soon, and the server's `retryAfter` was read by nobody. This is the
 * wait.
 *
 * ⚠ **No push challenge.** Upstream first tries `ProofRequiredExceptionHandler
 * .tryThrottledPushChallenge()` and retries at once if it is solved. That challenge arrives over
 * FCM, and this app has no FCM, so there is nothing to solve here; it does the rest of what
 * upstream does, the wait and the retry. The challenge can be answered on the account's primary.
 *
 * One departure, and why: upstream's wait belongs to each job (one per recipient queue); here it
 * is one wait for every owed receipt, because a challenge is put on the account, not on one
 * recipient, and the retry pass sends them all together. A fresh receipt for a newly arrived
 * message is still tried at once, as a new upstream job would be.
 */
internal object ReceiptBackoff {

    /**
     * Sixty seconds: `RemoteConfig.defaultMaxBackoff`'s default (key `android.defaultMaxBackoff`),
     * the ceiling of upstream's `Job.defaultBackoff()`. Used as the floor of the wait, so a
     * refusal that names no time still waits at least as long as upstream's backoff ever does.
     * Copied rather than read, because this app receives no remote config.
     */
    val DEFAULT_MAX_BACKOFF_MS: Long = TimeUnit.SECONDS.toMillis(60)

    /**
     * The longest any wait is held: a receipt's own lifespan (`setLifespan(TimeUnit.DAYS.toMillis(1))`,
     * [SignalReceiptStore.RECEIPT_LIFESPAN_MS]). Every receipt owed by then has been given up,
     * so a longer wait could only hold back ones owed later.
     */
    private val MAX_WAIT_MS: Long = SignalReceiptStore.RECEIPT_LIFESPAN_MS

    @Volatile private var restingUntil = 0L

    /**
     * How long to wait after [failure], or null when it is not a challenge or a rate limit --
     * those keep the retry they had.
     */
    internal fun waitFor(failure: SendFailure): Long? {
        val said = when (failure) {
            is SendFailure.ProofRequired -> TimeUnit.SECONDS.toMillis(failure.retryAfterSeconds)
            SendFailure.ProofNeeded -> 0L
            is SendFailure.RateLimited -> TimeUnit.SECONDS.toMillis(failure.retryAfterSeconds)
            is SendFailure.TooFast -> failure.waitMillis ?: 0L
            else -> return null
        }
        return maxOf(DEFAULT_MAX_BACKOFF_MS, said).coerceAtMost(MAX_WAIT_MS)
    }

    /**
     * Whether the retry pass should hold back at [now], given a wait that ends at [until].
     *
     * A wait ending further off than [MAX_WAIT_MS] is a clock that moved back, and does not hold:
     * the cost of one more attempt is a request, and the cost of holding is receipts that never go.
     */
    internal fun holds(now: Long, until: Long): Boolean = now < until && until - now <= MAX_WAIT_MS

    /** Notes a refused receipt; only a challenge or a rate limit starts a wait. */
    fun refused(failure: SendFailure, now: Long = System.currentTimeMillis()) {
        val wait = waitFor(failure) ?: return
        synchronized(this) {
            // Never shortened by a later, smaller wait; a stale one (see [holds]) is replaced.
            restingUntil = if (holds(now, restingUntil)) maxOf(restingUntil, now + wait) else now + wait
        }
    }

    /** Whether owed receipts wait now: the moment the wait ends, or null when they do not. */
    fun waitingUntil(now: Long = System.currentTimeMillis()): Long? =
        restingUntil.takeIf { holds(now, it) }
}
