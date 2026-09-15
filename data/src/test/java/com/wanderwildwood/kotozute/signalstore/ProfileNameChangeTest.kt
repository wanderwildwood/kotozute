package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a name arriving is a change worth telling the reader about.
 *
 * A contact's displayed name changing under the reader is how one person gets mistaken for
 * another, so Signal writes a permanent row in the conversation rather than relabelling
 * silently. The hard part is not the note; it is not writing one for every contact at once.
 */
class ProfileNameChangeTest {

    @Test
    fun `a name replacing a different one is worth saying`() {
        assertTrue(SignalProfiles.noteworthyNameChange("Lydia", "Lydia N"))
    }

    @Test
    fun `the first name ever learned is not a change`() {
        // Nearly every contact starts with no name at all. Without this, the first successful
        // profile fetch would write "they changed their name" into every conversation at once
        // -- wrong, and the kind of noise that teaches a reader to ignore the real one.
        assertFalse(SignalProfiles.noteworthyNameChange(null, "Lydia"))
        assertFalse(SignalProfiles.noteworthyNameChange("", "Lydia"))
        assertFalse(SignalProfiles.noteworthyNameChange("   ", "Lydia"))
    }

    @Test
    fun `a name going away is not a change`() {
        // An empty answer is the fetch failing to say, not somebody choosing to be nameless,
        // and the store keeps the old name. Saying "they changed their name to nothing" would
        // describe our own gap as their decision.
        assertFalse(SignalProfiles.noteworthyNameChange("Lydia", ""))
        assertFalse(SignalProfiles.noteworthyNameChange("Lydia", "  "))
    }

    @Test
    fun `the same name arriving again is not a change`() {
        // The common case by far: the profile is re-fetched on a schedule and almost always
        // says exactly what it said last time.
        assertFalse(SignalProfiles.noteworthyNameChange("Lydia", "Lydia"))
    }
}

/**
 * When a number arriving is a change worth telling the reader about.
 *
 * The same shape as the name-change rule, and for a reason this app has that upstream does not:
 * one person is one row across two rails, so their number changing re-pairs the Signal half of
 * that row with a different text conversation. Nothing else would say so.
 */
class NumberChangeTest {

    @Test
    fun `a number replacing a different one is worth saying`() {
        assertTrue(SignalContactStore.noteworthyNumberChange("+15551110000", "+15552220000"))
    }

    @Test
    fun `the first number ever learned is not a change`() {
        // A contact discovered by account id has no number until one is found. Without this,
        // every one of them would announce a change the moment discovery ran.
        assertFalse(SignalContactStore.noteworthyNumberChange(null, "+15551110000"))
        assertFalse(SignalContactStore.noteworthyNumberChange("", "+15551110000"))
    }

    @Test
    fun `a number going away is not a change`() {
        // The write is fill-only for blanks, so the old number stays. Saying it changed to
        // nothing would describe this app's own gap as the contact's decision.
        assertFalse(SignalContactStore.noteworthyNumberChange("+15551110000", ""))
        assertFalse(SignalContactStore.noteworthyNumberChange("+15551110000", "   "))
    }

    @Test
    fun `the same number arriving again is not a change`() {
        assertFalse(SignalContactStore.noteworthyNumberChange("+15551110000", "+15551110000"))
    }
}
