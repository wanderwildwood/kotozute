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
 *
 * ⚠ This shape is Signal's **last** check and was for a long time this app's only one. It is
 * precisely the check a plausible typo passes -- a US number with a digit dropped is still a
 * plus and seven-to-fifteen digits -- which is why the country rules below it exist.
 */
class RegistrationNumberTest {

    private fun accepts(number: String) = E164Numbers.matchesGenericShape(number)

    // The numbers here are from ranges reserved for fiction -- Ofcom's 020 7946 0xxx for the
    // UK, 555 for the US -- rather than invented ones that could turn out to belong to
    // somebody. This is a public repository.
    @Test
    fun `a plain international number is accepted`() {
        assertTrue(accepts("+15550001234"))
        assertTrue(accepts("+442079460958"))
        assertTrue(accepts("+81300001234"))
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
    fun `a US number must have exactly ten digits`() {
        // The case the generic shape cannot catch, and the reason Signal has a rule per
        // country: every one of these passes the shape test above.
        assertTrue(accepts("+1555000123"))
        assertFalse(E164Numbers.matchesCountryRule("+1555000123", "US"))
        assertTrue(accepts("+155500012345"))
        assertFalse(E164Numbers.matchesCountryRule("+155500012345", "US"))
        assertTrue(E164Numbers.matchesCountryRule("+15550001234", "US"))
    }

    @Test
    fun `a brazilian number must be the right length`() {
        // Upstream leaves the leading 9 optional, so both of these are allowed; what the rule
        // catches is the wrong number of digits around it.
        assertTrue(E164Numbers.matchesCountryRule("+5511912345678", "BR"))
        assertTrue(E164Numbers.matchesCountryRule("+551112345678", "BR"))
        assertFalse(E164Numbers.matchesCountryRule("+55111234567", "BR"))
        assertFalse(E164Numbers.matchesCountryRule("+5511123456789", "BR"))
    }

    @Test
    fun `a country with no rule of its own is left to the generic shape`() {
        // Signal has exactly two country rules. Everywhere else the shape check and
        // libphonenumber are the whole answer, and inventing more rules here would refuse
        // valid numbers in countries neither of us has checked.
        assertTrue(E164Numbers.matchesCountryRule("+442079460958", "GB"))
        assertTrue(E164Numbers.matchesCountryRule("+81300001234", "JP"))
        assertTrue(E164Numbers.matchesCountryRule("+15550001234", null))
    }

    @Test
    fun `a primary device is device one`() {
        // Not something the server tells us, and a wrong value here authenticates as a device
        // that does not exist — so it is pinned rather than assumed at each call site.
        assert(SignalRegistrar.PRIMARY_DEVICE_ID == 1)
    }
}
