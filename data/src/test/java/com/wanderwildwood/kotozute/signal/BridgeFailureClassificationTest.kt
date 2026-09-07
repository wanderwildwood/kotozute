package com.wanderwildwood.kotozute.signal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

/**
 * This decides whether a person is interrupted, so it is worth pinning down.
 *
 * Getting it wrong in one direction leaves Signal silently dead, which is the bug this
 * exists to fix. Getting it wrong in the other posts a notification every night that a
 * laptop is asleep, which would be worse, because it trains people to ignore it.
 */
class BridgeFailureClassificationTest {

    @Test
    fun `a refusal is terminal`() {
        assertTrue(isTerminalBridgeFailure(BridgeRejected(401, "unauthorized")))
        assertTrue(isTerminalBridgeFailure(BridgeRejected(403, "forbidden")))
    }

    @Test
    fun `a pinning failure is terminal, however deeply it is wrapped`() {
        // This is the shape OkHttp actually hands us: the reason is never the top of the
        // chain, so a check that only looked at the throwable itself would miss it.
        val wrapped = IOException(
            "stream failed",
            SSLHandshakeException("handshake").apply {
                initCause(CertificateException("bridge certificate does not match the paired one"))
            }
        )
        assertTrue(isTerminalBridgeFailure(wrapped))
    }

    @Test
    fun `an absent host is not terminal`() {
        assertFalse(isTerminalBridgeFailure(ConnectException("connection refused")))
        assertFalse(isTerminalBridgeFailure(SocketTimeoutException("timeout")))
        assertFalse(isTerminalBridgeFailure(UnknownHostException("no dns")))
        assertFalse(isTerminalBridgeFailure(IOException("500 from https://bridge/v1/state")))
        assertFalse(isTerminalBridgeFailure(null))
    }

    @Test
    fun `a cause chain that loops does not hang`() {
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        assertFalse(isTerminalBridgeFailure(b))
    }
}
