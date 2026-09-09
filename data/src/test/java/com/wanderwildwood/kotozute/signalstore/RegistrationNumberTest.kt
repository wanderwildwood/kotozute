package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which strings are allowed to be registered as a phone number.
 *
 * This is the one check in registration whose failure cannot be undone from this side. A
 * rejected request is a message on a screen; a number that is *accepted* and is not the one
 * the person meant sends a verification code to a stranger, and — if that stranger's number
 * is on Signal — is an attempt to take over their account. So the rule is deliberately strict
 * and deliberately unhelpful: it does not tidy input up, it refuses anything that is not
 * already exactly what the server expects.
 *
 * E.164: a plus, a country code that cannot begin with zero, and no more than fifteen digits
 * in total.
 */
class RegistrationNumberTest {

    private fun accepts(number: String) = SignalRegistrar.E164.matches(number)

    @Test
    fun `a plain international number is accepted`() {
        assertTrue(accepts("+15550001234"))
        assertTrue(accepts("+442071838750"))
        assertTrue(accepts("+81312345678"))
    }

    @Test
    fun `the plus is required`() {
        // The commonest thing someone will type, and the one most worth refusing: without the
        // country code there is no way to know which country's number this is, and assuming
        // one is how you register somebody else.
        assertFalse(accepts("5550001234"))
        assertFalse(accepts("07123456789"))
    }

    @Test
    fun `punctuation people actually type is refused rather than stripped`() {
        // Stripping would be friendlier and is exactly the wrong thing here: the screen can
        // offer to tidy this up and show the result back for confirmation, but nothing may
        // quietly reinterpret a number on its way to being registered.
        assertFalse(accepts("+1 555 000 1234"))
        assertFalse(accepts("+1 (555) 000-1234"))
        assertFalse(accepts("+1-555-000-1234"))
        assertFalse(accepts("+1.555.000.1234"))
    }

    @Test
    fun `a country code cannot begin with zero`() {
        assertFalse(accepts("+0155500012"))
        assertFalse(accepts("+0"))
    }

    @Test
    fun `too short and too long are both refused`() {
        // E.164 allows fifteen digits at most, and nothing real is shorter than seven.
        assertFalse(accepts("+1234"))
        assertTrue(accepts("+1234567"))
        assertTrue(accepts("+123456789012345"))
        assertFalse(accepts("+1234567890123456"))
    }

    @Test
    fun `letters and empty input are refused`() {
        assertFalse(accepts(""))
        assertFalse(accepts("+"))
        assertFalse(accepts("+1555000CALL"))
        assertFalse(accepts("not a number"))
    }

    @Test
    fun `surrounding whitespace is not silently forgiven`() {
        // A trailing space is invisible on a phone screen and easy to acquire from a paste.
        // Refusing it puts the problem in front of the person while it is still fixable.
        assertFalse(accepts(" +15550001234"))
        assertFalse(accepts("+15550001234 "))
        assertFalse(accepts("+15550001234\n"))
    }

    @Test
    fun `a primary device is device one`() {
        // Not something the server tells us, and a wrong value here authenticates as a device
        // that does not exist — so it is pinned rather than assumed at each call site.
        assert(SignalRegistrar.PRIMARY_DEVICE_ID == 1)
    }
}
