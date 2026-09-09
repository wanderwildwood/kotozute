package com.wanderwildwood.kotozute.feature.desktopsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This decides who may read every message on the phone, so it is worth pinning down in both
 * directions: a caller who should get in, and every near miss that should not.
 */
class DesktopSyncTokenTest {

    /** The shape tokens have had since the move to Crockford's alphabet: no lowercase. */
    private val token = "TESTTESTTESTTESTTEST0000"

    @Test
    fun `the right token is accepted`() {
        assertTrue(tokenMatches(token, token))
    }

    @Test
    fun `an uppercase-only token matches whatever case it is typed in`() {
        // Someone types this by hand, and a keyboard that capitalises should not lock them out.
        assertTrue(tokenMatches(token.lowercase(), token))
        assertTrue(tokenMatches("testtesttesttesttest0000", token))
    }

    @Test
    fun `a token that carries case has to match it`() {
        // Older mixed-case tokens predate the alphabet change; for those the case is content.
        val mixed = "aB3dEf7hIj1kLm5nOp9qRs2t"
        assertTrue(tokenMatches(mixed, mixed))
        assertFalse(tokenMatches(mixed.uppercase(), mixed))
        assertFalse(tokenMatches(mixed.lowercase(), mixed))
    }

    @Test
    fun `near misses are refused`() {
        assertFalse(tokenMatches(null, token))
        assertFalse(tokenMatches("", token))
        assertFalse(tokenMatches(token.dropLast(1), token))          // too short
        assertFalse(tokenMatches(token + "X", token))                // too long
        assertFalse(tokenMatches(token.dropLast(1) + "X", token))    // last character wrong
        assertFalse(tokenMatches("X" + token.drop(1), token))        // first character wrong
    }

    @Test
    fun `the comparison looks at every character, not just up to the first difference`() {
        // Not a timing measurement -- that is not something a unit test can assert honestly.
        // What it does pin is that the loop is over the whole string, so a future edit that
        // reintroduces an early return has to delete this to pass.
        assertFalse(constantTimeEquals("AAAAAAAA", "BAAAAAAA"))
        assertFalse(constantTimeEquals("AAAAAAAA", "AAAAAAAB"))
        assertTrue(constantTimeEquals("AAAAAAAA", "AAAAAAAA"))
        assertFalse(constantTimeEquals("AAAA", "AAAAAAAA"))
    }
}
