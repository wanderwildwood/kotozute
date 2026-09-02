package com.wanderwildwood.kotozute.feature.desktopsync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * This is the only thing on the relay that answers without a token, so what bounds it is
 * worth pinning down rather than trusting to a reading of the code.
 */
class DesktopSyncPairingTest {

    private var clock = 1_000_000L

    @Before
    fun setUp() {
        DesktopSyncPairing.now = { clock }
        DesktopSyncPairing.clear()
    }

    @After
    fun tearDown() {
        DesktopSyncPairing.now = { System.currentTimeMillis() }
        DesktopSyncPairing.clear()
    }

    @Test
    fun `a code is six digits`() {
        val code = DesktopSyncPairing.issue()
        assertEquals(6, code.length)
        assertTrue("not all digits: $code", code.all { it.isDigit() })
    }

    @Test
    fun `nothing to guess until someone asks`() {
        assertNull(DesktopSyncPairing.current())
        assertFalse(DesktopSyncPairing.redeem("000000"))
    }

    @Test
    fun `the right code works once and then is spent`() {
        val code = DesktopSyncPairing.issue()
        assertTrue(DesktopSyncPairing.redeem(code))
        assertFalse("a spent code was accepted again", DesktopSyncPairing.redeem(code))
        assertNull(DesktopSyncPairing.current())
    }

    @Test
    fun `it expires`() {
        val code = DesktopSyncPairing.issue()
        clock += DesktopSyncPairing.LIFETIME_MS - 1
        assertTrue("expired a millisecond early", DesktopSyncPairing.redeem(code))

        val second = DesktopSyncPairing.issue()
        clock += DesktopSyncPairing.LIFETIME_MS
        assertNull(DesktopSyncPairing.current())
        assertFalse("an expired code was accepted", DesktopSyncPairing.redeem(second))
    }

    @Test
    fun `guessing burns the code rather than the guesser's patience`() {
        val code = DesktopSyncPairing.issue()
        val wrong = code.map { c -> if (c == '0') '1' else '0' }.joinToString("")
        repeat(DesktopSyncPairing.MAX_ATTEMPTS) {
            assertFalse(DesktopSyncPairing.redeem(wrong))
        }
        assertNull("the code survived its attempt budget", DesktopSyncPairing.current())
        assertFalse("the real code still worked after the budget ran out",
            DesktopSyncPairing.redeem(code))
    }

    @Test
    fun `issuing again replaces the old one`() {
        val first = DesktopSyncPairing.issue()
        val second = DesktopSyncPairing.issue()
        assertFalse("two codes were live at once", DesktopSyncPairing.redeem(first))
        assertTrue(DesktopSyncPairing.redeem(second))
    }

    @Test
    fun `spacing and punctuation in what was typed are ignored`() {
        val code = DesktopSyncPairing.issue()
        val spaced = code.substring(0, 3) + " " + code.substring(3)
        assertTrue("a space between the halves was rejected", DesktopSyncPairing.redeem(spaced))
    }

    @Test
    fun `a wrong length does not match`() {
        DesktopSyncPairing.issue()
        assertFalse(DesktopSyncPairing.redeem("12345"))
        assertFalse(DesktopSyncPairing.redeem(""))
        assertFalse(DesktopSyncPairing.redeem(null))
    }
}
