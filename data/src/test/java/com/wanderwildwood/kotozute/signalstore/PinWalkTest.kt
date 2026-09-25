package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.api.svr.SecureValueRecovery.RestoreResponse

/**
 * Walking SVR2's enclaves with a PIN. Guesses are limited and running out deletes the data, so
 * the property that matters is that a PIN is answered by the first enclave holding data, and
 * no other enclave is ever asked after that.
 */
class PinWalkTest {

    private fun walk(vararg answers: RestoreResponse): Pair<SignalRegistrar.Walked, List<String>> {
        val asked = mutableListOf<String>()
        val byEnclave = answers.withIndex().associate { "e${it.index}" to it.value }
        val walked = SignalRegistrar.PinWalk.walkEnclaves(byEnclave.keys.toList()) { enclave ->
            asked += enclave
            byEnclave.getValue(enclave)
        }
        return walked to asked
    }

    @Test
    fun `a wrong PIN stops at the first enclave, and the next is never asked`() {
        val (walked, asked) = walk(RestoreResponse.PinMismatch(7), RestoreResponse.Missing)
        assertEquals(listOf("e0"), asked)
        assertEquals(7, (walked.response as RestoreResponse.PinMismatch).triesRemaining)
    }

    /** The control: no data moves on, so the second enclave's answer is the one returned. */
    @Test
    fun `no data in the current enclave moves on to the legacy one`() {
        val (walked, asked) = walk(RestoreResponse.Missing, RestoreResponse.PinMismatch(3))
        assertEquals(listOf("e0", "e1"), asked)
        assertEquals("e1", walked.enclave)
    }

    @Test
    fun `a vanished enclave counts as no data`() {
        val (_, asked) = walk(RestoreResponse.EnclaveNotFound, RestoreResponse.Missing)
        assertEquals(listOf("e0", "e1"), asked)
    }

    @Test
    fun `no data anywhere is null, not a guess`() {
        val (walked, _) = walk(RestoreResponse.Missing, RestoreResponse.Missing)
        assertNull(walked.response)
    }

    @Test
    fun `a fault is the answer and is not retried elsewhere`() {
        val (walked, asked) = walk(RestoreResponse.NetworkError(java.io.IOException("x")), RestoreResponse.Missing)
        assertEquals(listOf("e0"), asked)
        assertTrue(walked.response is RestoreResponse.NetworkError)
    }

    @Test
    fun `a throw is an application error, not a second try`() {
        val asked = mutableListOf<String>()
        val walked = SignalRegistrar.PinWalk.walkEnclaves(listOf("a", "b")) {
            asked += it; throw IllegalStateException("boom")
        }
        assertEquals(listOf("a"), asked)
        assertTrue(walked.response is RestoreResponse.ApplicationError)
    }
}
