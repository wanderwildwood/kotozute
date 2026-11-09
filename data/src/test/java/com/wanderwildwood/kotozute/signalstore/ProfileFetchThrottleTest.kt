package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.network.NetworkResult
import org.signal.network.exceptions.NonSuccessfulResponseCodeException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Somebody whose profile never comes back must not be asked after every batch.
 *
 * The query that picks who to ask is SQL and has no test here (these tests run without a
 * database); what is pinned is the half that decides an answer counts as "asked".
 */
class ProfileFetchThrottleTest {

    private fun status(code: Int): NetworkResult<Unit> =
        NetworkResult.StatusCodeError(NonSuccessfulResponseCodeException(code))

    @Test
    fun `a 404 is an answer, and counts as asked`() {
        assertTrue(SignalProfiles.isNotFound(status(404)))
    }

    @Test
    fun `a failure that may pass is not`() {
        assertFalse(SignalProfiles.isNotFound(status(401)))
        assertFalse(SignalProfiles.isNotFound(status(429)))
        assertFalse(SignalProfiles.isNotFound(status(500)))
        assertFalse(SignalProfiles.isNotFound(NetworkResult.NetworkError<Unit>(IOException("offline"))))
        assertFalse(SignalProfiles.isNotFound(NetworkResult.Success(Unit)))
    }

    @Test
    fun `nameless people wait upstream's six hours`() {
        assertEquals(TimeUnit.HOURS.toMillis(6), SignalProfiles.NAMELESS_RETRY_MS)
    }
}
