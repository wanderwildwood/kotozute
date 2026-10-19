package com.wanderwildwood.kotozute.feature.conversations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inbox's selection holds text rows and Signal rows side by side. Getting the split
 * wrong would not crash: a Signal row's action would land on a telephony thread, or a
 * text row's on nothing.
 */
class InboxSelectionTest {

    private val alexKey = "direct:00000000-0000-4000-8000-000000000000"
    private val groupKey = "group:GROUPID=="
    private val alex = InboxSelection.Row(
        id = InboxItem.signalStableId(alexKey), signalKey = alexKey, joinedId = 42L
    )
    private val group = InboxSelection.Row(id = InboxItem.signalStableId(groupKey), signalKey = groupKey)
    private val text = InboxSelection.Row(id = 7L)

    @Test
    fun `a mixed selection splits by rail`() {
        val chosen = InboxSelection.split(listOf(7L, alex.id, 9L, group.id), listOf(text, alex, group))
        assertEquals(listOf(7L, 9L), chosen.sms)
        assertEquals(listOf(alexKey, groupKey), chosen.signal)
        assertEquals(listOf(42L), chosen.joined)
    }

    @Test
    fun `a signal id whose row has gone is dropped, not guessed`() {
        val chosen = InboxSelection.split(listOf(alex.id, group.id), listOf(group))
        assertEquals(listOf(groupKey), chosen.signal)
        assertTrue(chosen.joined.isEmpty())
    }

    @Test
    fun `a swiped text id needs no row`() {
        val chosen = InboxSelection.split(listOf(7L), emptyList())
        assertEquals(listOf(7L), chosen.sms)
        assertTrue(chosen.signal.isEmpty())
    }

    @Test
    fun `no joined id of zero is passed on`() {
        val unjoined = alex.copy(joinedId = 0L)
        assertTrue(InboxSelection.split(listOf(unjoined.id), listOf(unjoined)).joined.isEmpty())
    }

    @Test
    fun `pin and mute offer whichever the selection mostly is not`() {
        val pinnedMuted = InboxSelection.offer(listOf(alex.copy(pinned = true, muted = true)))
        assertFalse(pinnedMuted.pin)
        assertFalse(pinnedMuted.mute)
        val plain = InboxSelection.offer(listOf(text))
        assertTrue(plain.pin)
        assertTrue(plain.mute)
        // A tie pins and mutes, as the bar always did for text rows.
        val tie = InboxSelection.offer(listOf(text, alex.copy(pinned = true, muted = true)))
        assertTrue(tie.pin)
        assertTrue(tie.mute)
        val mostly = InboxSelection.offer(listOf(text, alex.copy(pinned = true), group.copy(pinned = true)))
        assertFalse(mostly.pin)
    }

    @Test
    fun `read is offered for one unread row, and for any several`() {
        assertTrue(InboxSelection.offer(listOf(alex.copy(unread = true))).read)
        assertFalse(InboxSelection.offer(listOf(alex)).read)
        assertTrue(InboxSelection.offer(listOf(alex, text)).read)
        assertFalse(InboxSelection.offer(emptyList()).read)
    }

    @Test
    fun `rename and add contact are for one text row only`() {
        assertTrue(InboxSelection.offer(listOf(text)).rename)
        assertFalse(InboxSelection.offer(listOf(alex)).rename)
        assertFalse(InboxSelection.offer(listOf(text, text.copy(id = 8L))).rename)
        assertTrue(InboxSelection.offer(listOf(text.copy(canAddContact = true))).addContact)
        assertFalse(InboxSelection.offer(listOf(text.copy(canAddContact = true), alex)).addContact)
    }
}
