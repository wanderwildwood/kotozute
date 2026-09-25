package com.wanderwildwood.kotozute.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a refusal from the server retires the stream.
 *
 * ⚠ The case this exists for is the restart. The reason is already on file from before it,
 * nothing has connected yet, and the socket is being refused again. The old guard read that
 * as "already handled" and returned, so a phone the account had removed reconnected every
 * thirty seconds for as long as it was switched on.
 */
class RefusalIsNewsTest {

    private val unlinked = "This phone is no longer linked to Signal."

    @Test
    fun `after a restart the same refusal still stops the stream`() {
        assertTrue(SignalRepositoryImpl.refusalIsNews(unlinked, unlinked, streamWanted = true))
    }

    @Test
    fun `a first refusal is acted on`() {
        assertTrue(SignalRepositoryImpl.refusalIsNews("", unlinked, streamWanted = true))
        assertTrue(SignalRepositoryImpl.refusalIsNews("", unlinked, streamWanted = false))
    }

    @Test
    fun `the socket repeating itself after the stop is ignored`() {
        assertFalse(SignalRepositoryImpl.refusalIsNews(unlinked, unlinked, streamWanted = false))
    }
}
