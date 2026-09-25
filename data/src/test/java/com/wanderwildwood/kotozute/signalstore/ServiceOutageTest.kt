package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.repository.SendFailure
import com.wanderwildwood.kotozute.repository.SendRefused
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signal's own "is Signal down" answer, and which failed sends are worth asking it about.
 *
 * Upstream's `ServiceOutageDetectionJob` believes exactly two addresses and treats anything
 * else as a broken network rather than an answer. Getting that wrong either way puts a false
 * "Signal is down" in front of somebody whose Wi-Fi is merely a hotel's.
 */
class ServiceOutageTest {

    @Test
    fun `only the two published addresses are answers`() {
        assertEquals(ServiceOutage.Status.UP, ServiceOutage.classify("127.0.0.1"))
        assertEquals(ServiceOutage.Status.DOWN, ServiceOutage.classify("127.0.0.2"))
        // A captive portal answers every name with itself.
        assertEquals(ServiceOutage.Status.UNKNOWN, ServiceOutage.classify("10.0.0.1"))
        assertEquals(ServiceOutage.Status.UNKNOWN, ServiceOutage.classify(null))
    }

    @Test
    fun `a send that never reached the server is worth a look`() {
        assertTrue(ServiceOutage.worthChecking(SendRefused(SendFailure.Unreachable)))
        assertTrue(
            ServiceOutage.worthChecking(
                org.signal.network.exceptions.PushNetworkException(java.io.IOException("reset"))
            )
        )
    }

    @Test
    fun `a 5xx is worth a look and a 4xx is the server answering`() {
        assertTrue(
            ServiceOutage.worthChecking(org.signal.network.exceptions.NonSuccessfulResponseCodeException(503))
        )
        assertFalse(
            ServiceOutage.worthChecking(org.signal.network.exceptions.NonSuccessfulResponseCodeException(403))
        )
        assertFalse(
            ServiceOutage.worthChecking(org.signal.network.exceptions.NonSuccessfulResponseCodeException(499))
        )
    }

    @Test
    fun `a refusal about the message is not about the service`() {
        assertFalse(ServiceOutage.worthChecking(SendRefused(SendFailure.Unlinked)))
        assertFalse(ServiceOutage.worthChecking(SendRefused(SendFailure.TheyLeft)))
        assertFalse(ServiceOutage.worthChecking(IllegalStateException("cannot send to x")))
    }
}
