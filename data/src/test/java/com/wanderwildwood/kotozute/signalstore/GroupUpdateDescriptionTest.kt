package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.GroupContextV2

/**
 * A group update is a message with a group on it and nothing a person typed.
 *
 * It is the only notice the other members of a new group get: `GroupManagerV2.createGroup`
 * sends one the moment the group exists, and `PushGroupSendJob` builds it with the group
 * context, a revision and no body. Unrecognised, it was filed as an empty bubble -- which is
 * how a group would announce itself here as a blank line.
 */
class GroupUpdateDescriptionTest {

    private val masterKey = okio.ByteString.of(*ByteArray(32) { it.toByte() })

    private fun update(revision: Int) = DataMessage(
        groupV2 = GroupContextV2(masterKey = masterKey, revision = revision)
    )

    @Test
    fun `a group's first update is the group being made`() {
        assertEquals("Created the group.", ContentNormalizer.describe(update(revision = 0)))
    }

    @Test
    fun `a later update is a change this app does not decode`() {
        assertEquals("Updated the group.", ContentNormalizer.describe(update(revision = 1)))
        assertEquals("Updated the group.", ContentNormalizer.describe(update(revision = 97)))
    }

    @Test
    fun `a message with no group and nothing else recognisable is still left alone`() {
        assertNull(ContentNormalizer.describe(DataMessage()))
    }

    @Test
    fun `a group call is a group call, not a change to the group`() {
        // Every group message carries the group's context; this one also says it is a call.
        val call = update(revision = 3).copy(
            groupCallUpdate = org.whispersystems.signalservice.internal.push.DataMessage.GroupCallUpdate(eraId = "era-1")
        )
        assertEquals("Group call", ContentNormalizer.describe(call))
        assertEquals("groupcall:g:era-1", ContentNormalizer.groupCallIdFor("g", "era-1"))
    }

    @Test
    fun `the words are the ones the app passes in, not built-in English`() {
        val words = ContentNormalizer.Placeholders(
            groupCall = "Appel de groupe",
            groupCreated = "Groupe créé.",
            groupUpdated = "Groupe mis à jour.",
            poll = "(un sondage)",
            contact = "(contact) %1\$s !",
            contactCard = "(une fiche)",
            payment = "(un paiement)"
        )
        val call = update(revision = 3).copy(
            groupCallUpdate = org.whispersystems.signalservice.internal.push.DataMessage.GroupCallUpdate(eraId = "era-1")
        )
        assertEquals("Appel de groupe", ContentNormalizer.describe(call, words))
        assertEquals("Groupe créé.", ContentNormalizer.describe(update(revision = 0), words))
        assertEquals("Groupe mis à jour.", ContentNormalizer.describe(update(revision = 2), words))
        assertEquals(
            "(un sondage)",
            ContentNormalizer.describe(DataMessage(pollCreate = DataMessage.PollCreate(question = "")), words)
        )
        assertEquals("(un paiement)", ContentNormalizer.describe(DataMessage(payment = DataMessage.Payment()), words))
        assertEquals("(contact) Ada Whitlock !", words.contactNamed("Ada Whitlock"))
        assertEquals("(contact) Ada Whitlock", ContentNormalizer.Placeholders().contactNamed("Ada Whitlock"))
    }

    @Test
    fun `a call, a group change and a closed poll are events, drawn as update rows`() {
        val call = update(revision = 3).copy(
            groupCallUpdate = org.whispersystems.signalservice.internal.push.DataMessage.GroupCallUpdate(eraId = "era-1")
        )
        assertTrue(ContentNormalizer.isEvent(update(revision = 0)))
        assertTrue(ContentNormalizer.isEvent(call))
        assertTrue(
            ContentNormalizer.isEvent(
                DataMessage(pollTerminate = org.whispersystems.signalservice.internal.push.DataMessage.PollTerminate(targetSentTimestamp = 1L))
            )
        )
    }

    @Test
    fun `a poll, a contact card or nothing at all is not an event`() {
        assertFalse(
            ContentNormalizer.isEvent(
                DataMessage(pollCreate = org.whispersystems.signalservice.internal.push.DataMessage.PollCreate(question = "Lunch?"))
            )
        )
        assertFalse(ContentNormalizer.isEvent(DataMessage()))
    }
}

