package com.wanderwildwood.kotozute.feature.desktopsync

import java.security.SecureRandom

/**
 * A short code that a browser can exchange for the relay's token, once.
 *
 * The link is otherwise read off the phone and typed into a computer: a host, a port, and a
 * 24-character token. A reader of the forum thread reported exactly what you would expect --
 * that telling lI1 from O0 in it takes a while. Six digits and a hostname is a different
 * kind of task.
 *
 * It is the one thing on the relay that answers without a token, so it is bounded in every
 * direction that matters: six digits, three minutes, five attempts, one use, and it only
 * exists at all after someone has asked for it on the phone. A code nobody asked for cannot
 * be guessed, because there is nothing there to guess.
 */
object DesktopSyncPairing {

    /** Long enough to walk to the computer, short enough not to linger. */
    const val LIFETIME_MS = 3 * 60 * 1000L

    /** A wrong code is a typo once or twice; five is someone else. */
    const val MAX_ATTEMPTS = 5

    private val random = SecureRandom()

    private var digits: String? = null
    private var expiresAt: Long = 0
    private var attemptsLeft: Int = 0

    /**
     * Swappable so the tests can hold time still.
     *
     * elapsedRealtime, not currentTimeMillis: the wall clock jumps. This is a phone that
     * spends time switched off, and after a boot with a stale RTC it can read minutes or
     * hours fast until the network corrects it -- and the relay comes up at boot, so a code
     * shown in that window is stamped from the fast clock. When time then syncs backwards,
     * "now >= expiresAt" stays false for however far it jumped, and a three-minute code
     * stays live and redeemable for hours. elapsedRealtime counts since boot and only ever
     * goes forward.
     */
    var now: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /**
     * Issue a code, replacing any outstanding one. Asking again on the phone is the natural
     * thing to do when a code has gone stale, and two live codes would double the guessing
     * surface for no benefit.
     */
    @Synchronized
    fun issue(): String {
        val code = (0 until 6).map { random.nextInt(10) }.joinToString("")
        digits = code
        expiresAt = now() + LIFETIME_MS
        attemptsLeft = MAX_ATTEMPTS
        return code
    }

    /** The outstanding code, or null if there is none or it has expired. */
    @Synchronized
    fun current(): String? {
        if (digits == null) return null
        // Expired, or the clock moved backwards past the moment this was issued. The second
        // check should be unreachable now that the deadline is measured on elapsedRealtime,
        // which only counts forward -- but a code that appears to have more than its whole
        // lifetime left is wrong however it got that way, and the failure it guards against
        // is a live pairing code sitting there for hours.
        if (now() >= expiresAt || expiresAt - now() > LIFETIME_MS) clear()
        return digits
    }

    /** Milliseconds left on the outstanding code, or 0. */
    @Synchronized
    fun remainingMs(): Long = if (current() == null) 0 else (expiresAt - now()).coerceAtLeast(0)

    /**
     * True if [supplied] is the outstanding code. Consumes it either way it ends: a correct
     * code is spent, and a run of wrong ones burns the code rather than the attacker's
     * patience.
     */
    @Synchronized
    fun redeem(supplied: String?): Boolean {
        val expected = current() ?: return false
        val given = supplied?.filter { it.isDigit() } ?: return false
        if (given.length != expected.length) {
            spendAttempt()
            return false
        }
        // Constant time over the digits. The window is small and the space is only a
        // million, so a timing side channel here is not the likely attack -- but comparing
        // in constant time costs nothing and removes the question.
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor given[i].code)
        if (diff != 0) {
            spendAttempt()
            return false
        }
        clear()
        return true
    }

    private fun spendAttempt() {
        attemptsLeft -= 1
        if (attemptsLeft <= 0) clear()
    }

    @Synchronized
    fun clear() {
        digits = null
        expiresAt = 0
        attemptsLeft = 0
    }
}
