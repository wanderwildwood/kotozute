package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SyncMessage.MessageRequestResponse

/**
 * Which message-request responses delete a conversation here, and which are somebody else's job.
 *
 * ⚠ The deleting ones arrive **nowhere else**. `SyncMessage.deleteForMe` is the ordinary
 * "I deleted this" sync, and the message-request flow deliberately does not emit one: upstream
 * calls `deleteConversation(threadId, syncThreadDelete = false)` at
 * `SyncMessageProcessor:1235`, because this message *is* the sync.
 *
 * The rest are covered by a better route. `ACCEPT` sets profile sharing and clears blocked;
 * `BLOCK` and `BLOCK_AND_SPAM` set blocked -- all of which live in the account's storage
 * records, re-read whenever the primary sends `fetchLatest`, so they arrive as state rather
 * than as an event. `SPAM` reports to the service and changes nothing locally.
 *
 * Pinned because the enum has seven values and only two of them mean "and remove it from this
 * phone". Getting that set wrong in either direction is silent: too few and a conversation the
 * account dismissed stays for ever, too many and one it kept disappears.
 */
class MessageRequestDeleteTest {

    private fun deletes(type: MessageRequestResponse.Type): Boolean =
        type == MessageRequestResponse.Type.DELETE ||
            type == MessageRequestResponse.Type.BLOCK_AND_DELETE

    @Test
    fun `the two that delete`() {
        assertTrue(deletes(MessageRequestResponse.Type.DELETE))
        assertTrue(deletes(MessageRequestResponse.Type.BLOCK_AND_DELETE))
    }

    @Test
    fun `and the five that do not`() {
        assertFalse(deletes(MessageRequestResponse.Type.UNKNOWN))
        assertFalse(deletes(MessageRequestResponse.Type.ACCEPT))
        assertFalse(deletes(MessageRequestResponse.Type.BLOCK))
        assertFalse(deletes(MessageRequestResponse.Type.SPAM))
        assertFalse(deletes(MessageRequestResponse.Type.BLOCK_AND_SPAM))
    }

    /**
     * ⚠ If upstream adds a type, this fails rather than the new one silently falling into
     * "does not delete". A value added to this enum is a decision somebody has to make here.
     */
    @Test
    fun `every type in the enum is accounted for`() {
        assertEquals(7, MessageRequestResponse.Type.entries.size)
    }
}
