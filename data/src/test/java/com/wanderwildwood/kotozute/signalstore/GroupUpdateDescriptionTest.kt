package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
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
}

