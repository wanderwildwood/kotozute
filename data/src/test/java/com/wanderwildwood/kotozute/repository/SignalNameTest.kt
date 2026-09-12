package com.wanderwildwood.kotozute.repository

import com.wanderwildwood.kotozute.signal.SignalName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One fallback, shared by every list that shows a Signal conversation.
 *
 * The inbox used to print the whole thirty-six-character service id while a new message
 * showed the first eight of the same person, so one person read as two different strangers
 * depending on where you met them.
 */
class SignalNameTest {

    private val serviceId = "11111111-2222-4333-8444-555555555555"

    @Test
    fun `a name wins`() {
        assertEquals("Alice", SignalName.of("Alice", "+15550001", serviceId))
    }

    @Test
    fun `a number stands in for a name`() {
        assertEquals("+15550001", SignalName.of("", "+15550001", serviceId))
    }

    @Test
    fun `with neither, enough of the service id to tell people apart`() {
        assertEquals("11111111", SignalName.of("", "", serviceId))
    }

    @Test
    fun `a username stands in where there is no name and no number`() {
        assertEquals("@ada", SignalName.of("", "", "@ada", serviceId))
    }

    @Test
    fun `a number beats a username`() {
        // Signal's order, and not the obvious one: somebody who has shared their number has
        // already said who they are, and a username is what is left when they have not.
        assertEquals("+15550001", SignalName.of("", "+15550001", "@ada", serviceId))
    }

    @Test
    fun `a username beats a service id`() {
        // The case this was added for: a contact the account knows only by username had
        // nothing to be shown as but eight characters of hexadecimal.
        assertEquals("@ada", SignalName.of("", "", "@ada", serviceId))
    }

    @Test
    fun `never the whole service id`() {
        assertEquals(SignalName.SHORT_SERVICE_ID, SignalName.of("", "", serviceId).length)
    }
}
