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
        DesktopSyncPairing.now = { android.os.SystemClock.elapsedRealtime() }
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

    /**
     * A code that appears to have more than its whole lifetime left is dead, not live.
     *
     * The deadline used to be stamped from the wall clock, which jumps. This is a phone
     * that spends time switched off, and after a boot with a stale RTC it reads fast until
     * the network corrects it -- and the relay comes up at boot, so a code shown in that
     * window carried a deadline from the fast clock. When time then synced backwards by two
     * hours, "now is past the deadline" stayed false for two hours and three minutes.
     *
     * The deadline is measured on elapsedRealtime now, which only counts forward, so this
     * should be unreachable. It is checked anyway: the thing being prevented is a live
     * pairing code sitting there for hours, and that is worth two comparisons.
     */
    @Test
    fun `a code with more than its lifetime left is refused`() {
        val code = DesktopSyncPairing.issue()
        assertEquals(code, DesktopSyncPairing.current())

        clock -= 2 * 60 * 60 * 1000L

        assertNull(DesktopSyncPairing.current())
        assertFalse(DesktopSyncPairing.redeem(code))
    }

    @Test
    fun `the countdown reads minutes and seconds`() {
        assertEquals("3:00", DesktopSyncPairing.countdown(DesktopSyncPairing.LIFETIME_MS))
        assertEquals("2:41", DesktopSyncPairing.countdown(161_000))
        assertEquals("0:01", DesktopSyncPairing.countdown(1))
        assertEquals("0:00", DesktopSyncPairing.countdown(0))
        assertEquals("0:00", DesktopSyncPairing.countdown(-5))
    }

    @Test
    fun `five wrong tries lock the dialog out until a code is asked for again`() {
        DesktopSyncPairing.issue()
        assertFalse(DesktopSyncPairing.lockedOut())
        repeat(DesktopSyncPairing.MAX_ATTEMPTS) { DesktopSyncPairing.redeem("not it") }
        assertNull(DesktopSyncPairing.current())
        assertTrue(DesktopSyncPairing.lockedOut())
        DesktopSyncPairing.issue()
        assertFalse(DesktopSyncPairing.lockedOut())
    }

    @Test
    fun `a spent or expired code is not a lockout`() {
        val code = DesktopSyncPairing.issue()
        assertTrue(DesktopSyncPairing.redeem(code))
        assertFalse(DesktopSyncPairing.lockedOut())
    }

    @Test
    fun `showing issues one when there is none and keeps it while it lasts`() {
        val first = DesktopSyncPairing.show()!!
        assertEquals(DesktopSyncPairing.LIFETIME_MS, first.leftMs)
        clock += DesktopSyncPairing.LIFETIME_MS - 1
        val again = DesktopSyncPairing.show()!!
        assertEquals(first.code, again.code)
        assertEquals(1L, again.leftMs)
    }

    @Test
    fun `a code that ran out is replaced at the boundary and only the new one works`() {
        val first = DesktopSyncPairing.show()!!
        clock += DesktopSyncPairing.LIFETIME_MS
        val next = DesktopSyncPairing.show()!!
        assertEquals(DesktopSyncPairing.LIFETIME_MS, next.leftMs)
        if (next.code != first.code) assertFalse(DesktopSyncPairing.redeem(first.code))
        assertTrue(DesktopSyncPairing.redeem(next.code))
    }

    @Test
    fun `a code that was used is replaced by a new one`() {
        val first = DesktopSyncPairing.show()!!
        assertTrue(DesktopSyncPairing.redeem(first.code))
        val next = DesktopSyncPairing.show()!!
        assertEquals(DesktopSyncPairing.LIFETIME_MS, next.leftMs)
        assertTrue(DesktopSyncPairing.redeem(next.code))
    }

    @Test
    fun `one code is valid at a time`() {
        val a = DesktopSyncPairing.issue()
        val b = DesktopSyncPairing.issue()
        if (a != b) assertFalse(DesktopSyncPairing.redeem(a))
        assertTrue(DesktopSyncPairing.redeem(b))
    }

    @Test
    fun `too many wrong tries leave nothing to show until one is asked for`() {
        val code = DesktopSyncPairing.show()!!.code
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(DesktopSyncPairing.MAX_ATTEMPTS) { assertFalse(DesktopSyncPairing.redeem(wrong)) }
        assertNull(DesktopSyncPairing.show())
        assertFalse(DesktopSyncPairing.redeem(code))
        assertTrue(DesktopSyncPairing.redeem(DesktopSyncPairing.issue()))
    }
}
