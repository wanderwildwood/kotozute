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
