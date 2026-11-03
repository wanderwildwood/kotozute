package com.wanderwildwood.kotozute.feature.desktopsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison every token check rests on. Which tokens are accepted, and from where, is in
 * DesktopSyncSecurityTest.
 */
class DesktopSyncTokenTest {

    @Test
    fun `the comparison looks at every character, not just up to the first difference`() {
        assertFalse(constantTimeEquals("AAAAAAAA", "BAAAAAAA"))
        assertFalse(constantTimeEquals("AAAAAAAA", "AAAAAAAB"))
        assertTrue(constantTimeEquals("AAAAAAAA", "AAAAAAAA"))
        assertFalse(constantTimeEquals("AAAA", "AAAAAAAA"))
    }
}
