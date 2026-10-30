package com.wanderwildwood.kotozute.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stands in for libsignal's exception of the same name, which the notice matches by name. */
private class UntrustedIdentityException : Exception("Untrusted identity")

class IdentityNoticeTest {

    @Test
    fun `typed refusals and wrapped libsignal errors are identity problems`() {
        assertTrue(IdentityNotice.isIdentityProblem(SafetyNumberChanged("direct:x", "Ada")))
        assertTrue(IdentityNotice.isIdentityProblem(SendRefused(SendFailure.SafetyNumberChanged(null))))
        assertTrue(IdentityNotice.isIdentityProblem(RuntimeException(UntrustedIdentityException())))
    }

    @Test
    fun `other failures are not`() {
        assertFalse(IdentityNotice.isIdentityProblem(SendRefused(SendFailure.Unreachable)))
        assertFalse(IdentityNotice.isIdentityProblem(IllegalStateException("no")))
    }

    @Test
    fun `the notice shows only for a direct chat with a waiting message and a changed key`() {
        assertTrue(IdentityNotice.shows(true, true, false, 1))
        assertTrue(IdentityNotice.shows(true, false, true, 2))
        assertFalse(IdentityNotice.shows(true, true, true, 0))
        assertFalse(IdentityNotice.shows(false, true, true, 1))
        assertFalse(IdentityNotice.shows(true, false, false, 3))
    }

    @Test
    fun `waiting messages go oldest first`() {
        assertEquals(listOf("a", "b", "c"), IdentityNotice.retryOrder(listOf("c" to 30L, "a" to 10L, "b" to 20L)))
    }

    @Test
    fun `accept then retry sends each waiting message once`() {
        val sent = mutableListOf<String>()
        val o = IdentityNotice.acceptAndRetry(listOf("a", "b"), { true }, { sent += it })
        assertEquals(IdentityNotice.Outcome(true, 2, 0), o)
        assertEquals(listOf("a", "b"), sent)
    }

    @Test
    fun `nothing is sent when accepting fails`() {
        val sent = mutableListOf<String>()
        val o = IdentityNotice.acceptAndRetry(listOf("a"), { false }, { sent += it })
        assertEquals(IdentityNotice.Outcome(false, 0, 1), o)
        assertTrue(sent.isEmpty())
        assertFalse(IdentityNotice.acceptAndRetry(listOf("a"), { error("x") }, { sent += it }).accepted)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a failed retry leaves it and the rest queued`() {
        val tried = mutableListOf<String>()
        val o = IdentityNotice.acceptAndRetry(listOf("a", "b", "c"), { true }) {
            tried += it
            if (it == "b") error("offline")
        }
        assertEquals(IdentityNotice.Outcome(true, 1, 2), o)
        assertEquals(listOf("a", "b"), tried)
    }
}
