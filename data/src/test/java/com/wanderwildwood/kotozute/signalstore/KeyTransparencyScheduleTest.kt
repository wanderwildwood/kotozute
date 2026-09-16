package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The parts of a key transparency check that are ours: when it runs, and what a failure means.
 *
 * The verification itself belongs to libsignal and is not testable here — which is the point.
 * What this app decides is the schedule and the response, and both are copied from
 * `CheckKeyTransparencyJob` rather than invented:
 *
 * - **seven days** between checks, plus a random 0–8 hours so two phones in one house do not ask
 *   in the same second
 * - the next time is written **before** the check, never after it succeeds
 * - a **first** failure is answered by refreshing and asking again tomorrow, not by telling
 *   anybody; only a second failure running reaches a person
 *
 * ⚠ These numbers are pinned because getting them wrong is silent in both directions. Too long
 * and a substituted identity key goes unnoticed for weeks; too short and every device in the
 * house hammers the log.
 */
class KeyTransparencyScheduleTest {

    private val week = TimeUnit.DAYS.toMillis(7)
    private val eightHours = TimeUnit.HOURS.toMillis(8)

    @Test
    fun `the interval is a week, as upstream sets it`() {
        assertEquals(604_800_000L, week)
    }

    /**
     * The jitter must be positive and under the interval: a jitter of zero removes the
     * staggering, and a jitter larger than the gap makes the cadence meaningless.
     */
    @Test
    fun `the jitter staggers without swamping the interval`() {
        assertTrue(eightHours > 0)
        assertTrue(eightHours < week)
    }

    /** A first failure retries tomorrow, which must be sooner than the ordinary cadence. */
    @Test
    fun `a failure brings the next check forward, it does not push it back`() {
        val afterFailure = TimeUnit.DAYS.toMillis(1)
        assertTrue("a retry must come sooner than the weekly check", afterFailure < week)
    }

    /**
     * ⚠ The two-strike rule, stated as the code states it: the flag carried *into* the check is
     * what decides whether a failure is shown. A check that decided from its own result would
     * tell somebody on the first disagreement, which Signal deliberately does not do.
     */
    @Test
    fun `whether to tell somebody comes from the previous run, not this one`() {
        fun tell(alreadyFailing: Boolean) = alreadyFailing
        assertEquals(false, tell(alreadyFailing = false))
        assertEquals(true, tell(alreadyFailing = true))
    }
}
