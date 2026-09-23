package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.network.exceptions.PushNetworkException
import org.whispersystems.signalservice.api.push.exceptions.RateLimitException
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException
import java.io.IOException

/**
 * Which failed resends are worth trying again.
 *
 * Upstream's answer is one line, `ResendMessageJob.onShouldRetry`: `e instanceof
 * PushNetworkException`. An owed resend used to be retried on anything -- a rate limit
 * included, every quarter hour for a day -- so this pins the line to upstream's.
 */
class ResendRetryTest {
    @Test
    fun `a failure on the way to the server is worth trying again`() {
        assertTrue(SignalSender.isNetwork(PushNetworkException("socket closed")))
    }

    @Test
    fun `a network failure is found under whatever wraps it`() {
        assertTrue(SignalSender.isNetwork(IllegalStateException(PushNetworkException("reset"))))
    }

    @Test
    fun `the server's own answers are not`() {
        assertFalse(SignalSender.isNetwork(RateLimitException(413, "Rate limit exceeded")))
        assertFalse(SignalSender.isNetwork(ServerRejectedException()))
    }

    @Test
    fun `an input-output failure that is not the network's is not either`() {
        assertFalse(SignalSender.isNetwork(IOException("disk full")))
    }
}
