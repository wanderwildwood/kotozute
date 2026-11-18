package com.wanderwildwood.kotozute.interactor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scheduled message open for editing is held back from sending until the edit ends. */
class ScheduledEditsTest {

    @Test
    fun `a message is held back only while its edit is open`() {
        assertFalse(ScheduledEdits.isOpen(7))
        ScheduledEdits.begin(7)
        assertTrue(ScheduledEdits.isOpen(7))
        assertFalse(ScheduledEdits.isOpen(8))
        ScheduledEdits.end(7)
        assertFalse(ScheduledEdits.isOpen(7))
    }

    @Test
    fun `no edit is begun for a compose screen that is not editing one`() {
        ScheduledEdits.begin(-1)
        assertFalse(ScheduledEdits.isOpen(-1))
    }
}
