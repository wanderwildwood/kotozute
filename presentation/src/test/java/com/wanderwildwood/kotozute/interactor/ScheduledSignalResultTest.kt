package com.wanderwildwood.kotozute.interactor

import com.wanderwildwood.kotozute.interactor.ScheduledSignalResult.Outcome
import com.wanderwildwood.kotozute.repository.SafetyNumberChanged
import com.wanderwildwood.kotozute.repository.SendFailure
import com.wanderwildwood.kotozute.repository.SendRefused
import com.wanderwildwood.kotozute.repository.SentButNotFiled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.IOException

class ScheduledSignalResultTest {

    @Test
    fun `a send that went is sent`() {
        assertEquals(Outcome.SENT, ScheduledSignalResult.of(null))
    }

    @Test
    fun `a send that went but could not be filed is sent, so it is not sent again`() {
        assertEquals(Outcome.SENT, ScheduledSignalResult.of(SentButNotFiled(IllegalStateException("disk full"))))
        assertEquals(Outcome.SENT, ScheduledSignalResult.of(RuntimeException(SentButNotFiled(IOException()))))
    }

    @Test
    fun `a changed safety number leaves the message waiting in the conversation`() {
        assertEquals(Outcome.WAITING_IN_THREAD, ScheduledSignalResult.of(SafetyNumberChanged("direct:x", "Ada Whitlock")))
        assertEquals(
            Outcome.WAITING_IN_THREAD,
            ScheduledSignalResult.of(SendRefused(SendFailure.SafetyNumberChanged("Tomas Reyes")))
        )
    }

    @Test
    fun `anything else did not go and keeps its row`() {
        assertEquals(Outcome.NOT_SENT, ScheduledSignalResult.of(IOException("no network")))
        assertEquals(Outcome.NOT_SENT, ScheduledSignalResult.of(SendRefused(SendFailure.Unreachable)))
    }

    @Test
    fun `a reused id with a new time is a different row`() {
        assertEquals(ScheduledSignalResult.key(4, 1000), ScheduledSignalResult.key(4, 1000))
        assertNotEquals(ScheduledSignalResult.key(4, 1000), ScheduledSignalResult.key(4, 2000))
    }
}
