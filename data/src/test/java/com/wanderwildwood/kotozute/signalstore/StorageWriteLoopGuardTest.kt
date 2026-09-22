package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard that stops two devices rewriting the account's manifest at each other for ever.
 *
 * ⛔ **A loop here is not a local bug.** Two clients that each disagree with what the other
 * just wrote will argue as fast as the network allows, and every device on the account pays
 * for it. `docs/DECISION-storage-write.md` puts this *before* the write is enabled for that
 * reason, and it is testable without a network, an account or a second device — which is
 * exactly why it can be got right before any of those exist.
 */
class StorageWriteLoopGuardTest {

    private class Memory : StorageWriteLoopGuard.State {
        override var contentLevel = 0
        override var contentUpdatedAt = 0L
        override var rateLevel = 0
        override var rateUpdatedAt = 0L
        override var recentFingerprints: List<Int> = emptyList()
    }

    private val hour = StorageWriteLoopGuard.CONTENT_DRIP_MS

    private fun guard(state: StorageWriteLoopGuard.State = Memory()) = StorageWriteLoopGuard(state)

    // --- the loop itself ----------------------------------------------------------------------

    /**
     * ⛔ The case this exists for: the same payload, over and over, because something on the
     * other end keeps reverting it. Allowed a few times, then refused.
     */
    @Test
    fun `the same payload going up repeatedly is eventually refused`() {
        val g = guard()
        var now = 1_000L
        var denied: StorageWriteLoopGuard.Decision.Denied? = null
        repeat(10) {
            val d = g.onWriteAttempt(fingerprint = 42, fetchedRemoteManifest = true, isRetry = false, now = now)
            if (d is StorageWriteLoopGuard.Decision.Denied && denied == null) denied = d
            now += 1_000
        }
        assertNotNull("a repeating payload was never refused", denied)
        assertEquals(StorageWriteLoopGuard.Cause.REPEATED_PAYLOAD, denied!!.cause)
    }

    /**
     * The control. A *different* payload each time is ordinary work, not a loop, and must not
     * be throttled by the content bucket however many there are.
     */
    @Test
    fun `different payloads are not a loop`() {
        val g = guard()
        var now = 1_000L
        repeat(20) { i ->
            val d = g.onWriteAttempt(fingerprint = i, fetchedRemoteManifest = true, isRetry = false, now = now)
            assertTrue("changing payloads were treated as a loop at $i", d is StorageWriteLoopGuard.Decision.Allowed)
            now += 1_000
        }
    }

    @Test
    fun `the content bucket drains with time`() {
        val g = guard()
        var now = 1_000L
        // Fill it.
        repeat(5) { g.onWriteAttempt(42, fetchedRemoteManifest = true, isRetry = false, now = now); now += 1_000 }
        assertTrue(g.onWriteAttempt(42, true, false, now) is StorageWriteLoopGuard.Decision.Denied)
        // Wait it out and the same payload is allowed again.
        now += hour * (StorageWriteLoopGuard.CONTENT_CAPACITY + 1)
        assertTrue(
            "the bucket never drained",
            g.onWriteAttempt(42, true, false, now) is StorageWriteLoopGuard.Decision.Allowed
        )
    }

    // --- the exemptions -----------------------------------------------------------------------

    /**
     * ⚠ A conflict retry is exempt. Without this, one refused write costs as much as the
     * several attempts it takes to resolve — so resolving a conflict would look like a loop.
     */
    @Test
    fun `retries are exempt`() {
        val g = guard()
        var now = 1_000L
        repeat(50) {
            val d = g.onWriteAttempt(42, fetchedRemoteManifest = true, isRetry = true, now = now)
            assertTrue("a retry was refused", d is StorageWriteLoopGuard.Decision.Allowed)
            now += 1_000
        }
    }

    /** A write that did not follow a fresh read is not part of the cycle a loop is made of. */
    @Test
    fun `a write with no fresh manifest is not charged`() {
        val g = guard()
        var now = 1_000L
        repeat(50) {
            val d = g.onWriteAttempt(42, fetchedRemoteManifest = false, isRetry = false, now = now)
            assertTrue(d is StorageWriteLoopGuard.Decision.Allowed)
            now += 1_000
        }
    }

    // --- the rate bucket ----------------------------------------------------------------------

    /**
     * The second bucket, for loops whose payload is never stable — which the content bucket
     * cannot see at all.
     */
    @Test
    fun `an unstable payload is still bounded, by rate`() {
        val g = guard()
        var now = 1_000L
        var denied: StorageWriteLoopGuard.Decision.Denied? = null
        repeat(StorageWriteLoopGuard.RATE_CAPACITY + 10) { i ->
            val d = g.onWriteAttempt(fingerprint = i, fetchedRemoteManifest = true, isRetry = false, now = now)
            if (d is StorageWriteLoopGuard.Decision.Denied && denied == null) denied = d
            now += 1_000
        }
        assertNotNull("an endless stream of new payloads was never bounded", denied)
        assertEquals(StorageWriteLoopGuard.Cause.WRITE_RATE, denied!!.cause)
    }

    // --- refund and convergence ----------------------------------------------------------------

    @Test
    fun `a failed write is refunded from the content bucket`() {
        val state = Memory()
        val g = guard(state)
        g.onWriteAttempt(42, true, false, 1_000L)
        g.onWriteAttempt(42, true, false, 2_000L)
        val before = state.contentLevel
        g.onWriteFailed(3_000L)
        assertEquals("the refund did not happen", before - 1, state.contentLevel)
    }

    @Test
    fun `converging clears the argument`() {
        val state = Memory()
        val g = guard(state)
        repeat(3) { g.onWriteAttempt(42, true, false, 1_000L) }
        g.onConverged()
        assertEquals(0, state.contentLevel)
        assertTrue(state.recentFingerprints.isEmpty())
    }

    // --- the fingerprint ------------------------------------------------------------------------

    /**
     * ⚠ Order must not matter. Inserts are built in whatever order rows came out of a query,
     * and an order-sensitive hash would make the same payload look new every time — defeating
     * the content bucket entirely while appearing to work.
     */
    @Test
    fun `the fingerprint ignores the order inserts were built in`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5, 6)
        assertEquals(
            StorageWriteLoopGuard.fingerprintOf(listOf(a, b)),
            StorageWriteLoopGuard.fingerprintOf(listOf(b, a))
        )
    }

    @Test
    fun `a different payload fingerprints differently`() {
        assertTrue(
            StorageWriteLoopGuard.fingerprintOf(listOf(byteArrayOf(1))) !=
                StorageWriteLoopGuard.fingerprintOf(listOf(byteArrayOf(2)))
        )
    }

    /** A write of nothing but deletes has no payload to compare, and says so. */
    @Test
    fun `deletes alone have no fingerprint`() {
        assertNull(StorageWriteLoopGuard.fingerprintOf(emptyList()))
    }

    /** ⚠ A clock that goes backwards must not credit a drain that never happened. */
    @Test
    fun `time going backwards drains nothing`() {
        val state = Memory()
        val g = guard(state)
        repeat(3) { g.onWriteAttempt(42, true, false, 10 * hour) }
        val level = state.contentLevel
        g.onWriteAttempt(42, true, false, 1_000L)
        assertTrue("a backwards clock refilled the bucket", state.contentLevel >= level)
    }
}
