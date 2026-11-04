package com.wanderwildwood.kotozute.feature.desktopsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class SignalCatchUpTest {

    @Test
    fun nothingRunningMeansNothingToWaitFor() {
        assertTrue(SignalCatchUp(work = {}).awaitDone())
    }

    @Test
    fun theFirstListWaitsForTheCatchUpToFinish() {
        val release = CountDownLatch(1)
        val done = AtomicInteger()
        val catchUp = SignalCatchUp(waitMs = 5_000) { release.await(); done.incrementAndGet() }
        catchUp.start()
        Thread { Thread.sleep(200); release.countDown() }.start()
        assertTrue(catchUp.awaitDone())
        assertEquals(1, done.get())
    }

    @Test
    fun aSlowCatchUpHoldsTheListOnlySoLong() {
        val release = CountDownLatch(1)
        val catchUp = SignalCatchUp(waitMs = 100) { release.await() }
        catchUp.start()
        val t0 = System.currentTimeMillis()
        assertFalse(catchUp.awaitDone())
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
        release.countDown()
    }

    @Test
    fun anAskWhileOneRunsJoinsIt() {
        val release = CountDownLatch(1)
        val runs = AtomicInteger()
        val catchUp = SignalCatchUp(waitMs = 5_000) { runs.incrementAndGet(); release.await() }
        catchUp.start()
        catchUp.start()
        catchUp.start()
        release.countDown()
        assertTrue(catchUp.awaitDone())
        Thread.sleep(50)
        assertEquals(1, runs.get())
        // Done, so the next page open runs it again.
        catchUp.start()
        assertTrue(catchUp.awaitDone())
        assertEquals(2, runs.get())
    }

    @Test
    fun aFailingCatchUpStillLetsTheListGo() {
        val catchUp = SignalCatchUp(waitMs = 5_000) { throw IllegalStateException("no network") }
        catchUp.start()
        assertTrue(catchUp.awaitDone())
    }
}
