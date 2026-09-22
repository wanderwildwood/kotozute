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

    @Test
    fun `a saved contact is not renamed every day`() {
        // The reported bug. Saved as "Mum", profile "Anna". What she is shown as was being
        // compared with what her profile says, so each daily fetch said "Mum is now called
        // Anna", stored Anna, and the next sync put Mum back. The fetch compares profile with
        // profile now, and her profile has not changed.
        val held = "Anna"
        val shown = "Mum"
        assertFalse(SignalProfiles.noteworthyNameChange(held, "Anna"))
        // ...and the name the reader gave her stays.
        assertFalse(SignalProfiles.profileNameIsShown(shown, held))
    }

    @Test
    fun `a saved contact who really changes their profile name is still told`() {
        assertTrue(SignalProfiles.noteworthyNameChange("Anna", "Anna B"))
        assertFalse(SignalProfiles.profileNameIsShown("Mum", "Anna"))
    }

    @Test
    fun `somebody known only by their profile follows it`() {
        assertTrue(SignalProfiles.profileNameIsShown("Anna", "Anna"))
        assertTrue(SignalProfiles.profileNameIsShown(null, null))
        assertTrue(SignalProfiles.profileNameIsShown("", "Anna"))
    }

    @Test
    fun `the first fetch after upgrading leaves a shown name alone`() {
        // No profile name is held for anybody until the first fetch after v36. The name
        // shown may be the reader's own, and nothing says otherwise, so it is kept.
        assertFalse(SignalProfiles.profileNameIsShown("Mum", null))
        assertFalse(SignalProfiles.noteworthyNameChange(null, "Anna"))
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
