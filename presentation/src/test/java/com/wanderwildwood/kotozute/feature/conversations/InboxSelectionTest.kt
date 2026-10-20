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

    // ---- Mark unread: one half per row ----------------------------------------

    private fun targets(
        ids: List<Long>,
        rows: List<InboxSelection.Row>,
        textIncoming: Set<Long> = emptySet(),
        signalIncoming: Set<String> = emptySet()
    ) = InboxSelection.unreadTargets(ids, rows, { it in textIncoming }, { it in signalIncoming })

    @Test
    fun `a text row is marked as itself`() {
        val chosen = targets(listOf(7L, 9L), listOf(text))
        assertEquals(listOf(7L, 9L), chosen.sms)
        assertTrue(chosen.signal.isEmpty())
    }

    @Test
    fun `a signal row with nothing from them on signal marks its text half`() {
        // The phone's case: somebody joined by number who has Signal but only ever texts
        // back. Marking the Signal half found nothing to mark and the row stayed read.
        val chosen = targets(listOf(alex.id), listOf(alex), textIncoming = setOf(42L))
        assertEquals(listOf(42L), chosen.sms)
        assertTrue(chosen.signal.isEmpty())
        val textNewer = targets(listOf(alex.id), listOf(alex.copy(textIsNewer = true)), textIncoming = setOf(42L))
        assertEquals(listOf(42L), textNewer.sms)
    }

    @Test
    fun `the half a tap opens is marked when it has something from them`() {
        val both = setOf(42L)
        val signalSide = targets(listOf(alex.id), listOf(alex), textIncoming = both, signalIncoming = setOf(alexKey))
        assertEquals(listOf(alexKey), signalSide.signal)
        assertTrue(signalSide.sms.isEmpty())
        val textSide = targets(
            listOf(alex.id), listOf(alex.copy(textIsNewer = true)), textIncoming = both, signalIncoming = setOf(alexKey)
        )
        assertEquals(listOf(42L), textSide.sms)
        assertTrue(textSide.signal.isEmpty())
    }

    @Test
    fun `text newer but nothing from them by text marks the signal half`() {
        val chosen = targets(listOf(alex.id), listOf(alex.copy(textIsNewer = true)), signalIncoming = setOf(alexKey))
        assertEquals(listOf(alexKey), chosen.signal)
        assertTrue(chosen.sms.isEmpty())
    }

    @Test
    fun `nothing from them on either half marks the half that opens`() {
        assertEquals(listOf(alexKey), targets(listOf(alex.id), listOf(alex)).signal)
        assertEquals(listOf(42L), targets(listOf(alex.id), listOf(alex.copy(textIsNewer = true))).sms)
    }

    @Test
    fun `a signal row with no text half, and a gone row, are handled`() {
        val chosen = targets(listOf(group.id, alex.id, 7L), listOf(group, text))
        assertEquals(listOf(groupKey), chosen.signal)
        assertEquals(listOf(7L), chosen.sms)
    }

    @Test
    fun `a text conversation chosen twice over is marked once`() {
        val chosen = targets(listOf(42L, alex.id), listOf(alex), textIncoming = setOf(42L))
        assertEquals(listOf(42L), chosen.sms)
    }
}
