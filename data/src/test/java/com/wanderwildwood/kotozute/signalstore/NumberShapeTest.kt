package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.signalstore.SignalContactStore.Companion.looksLikeANumber
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a stored number is shaped like one.
 *
 * The rule is `BadE164MigrationJob`'s, copied rather than invented, and these are its own
 * documented cases:
 *
 * > - Contains an invalid char anywhere (i.e. not a digit or +)
 * > - A shortcode that doesn't start with a number
 * > - A non-shortcode (longcode?) that doesn't start with +{digit}
 * > - A number with exactly 7 chars (strange but true -- neither shortcodes nor longcodes can
 * >   be 7 chars long)
 *
 * Pinned here because a rule copied from another codebase drifts silently: the query and this
 * function state the same four rules twice, and if one is edited the check starts answering a
 * question nobody asked.
 */
class NumberShapeTest {

    @Test
    fun `an ordinary number is fine`() {
        assertTrue(looksLikeANumber("+17045550148"))
        assertTrue(looksLikeANumber("+442071838750"))
    }

    @Test
    fun `a shortcode of bare digits is fine`() {
        assertTrue(looksLikeANumber("62966"))
    }

    /** As the address book writes them, and the shape that cannot be found by an equality match. */
    @Test
    fun `anything but digits and a plus is not`() {
        assertFalse(looksLikeANumber("(704) 555-0148"))
        assertFalse(looksLikeANumber("+1 704 555 0148"))
        assertFalse(looksLikeANumber("704-555-0148"))
    }

    @Test
    fun `a long number that does not start with a plus is not`() {
        assertFalse(looksLikeANumber("17045550148"))
    }

    /** Upstream's odd one, and the reason the rule is copied rather than reasoned out. */
    @Test
    fun `exactly seven characters is never a number, however it is written`() {
        assertFalse(looksLikeANumber("1234567"))
        assertFalse(looksLikeANumber("+123456"))
    }

    @Test
    fun `a shortcode that does not start with a digit is not`() {
        assertFalse(looksLikeANumber("+1234"))
    }
}
