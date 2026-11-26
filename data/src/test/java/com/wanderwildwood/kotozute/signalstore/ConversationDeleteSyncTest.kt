package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.signalstore.ConversationDeleteSync.Request
import com.wanderwildwood.kotozute.signalstore.ConversationDeleteSync.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A conversation delete from another device removes what it covers and no more.
 *
 * It is how a trim on the main device arrives as well as a delete, and it names the newest
 * messages it covers. Upstream (`SyncMessageProcessor.handleSynchronizeConversationDeletes`)
 * removes up to the newest of those it holds, and nothing when it holds none. Emptying the
 * conversation regardless took every message from a phone whose main device had only trimmed.
 */
class ConversationDeleteSyncTest {

    private val ada = "11111111-1111-4111-8111-111111111111"
    private val self = "22222222-2222-4222-8222-222222222222"
    private val key = "direct:$ada"

    private fun row(author: String, at: Long) = Row("$author:$at", at, local = false)

    private val rows = listOf(row(ada, 100), row(self, 200), row(ada, 300), row(self, 400), row(ada, 500))

    @Test
    fun `a trim takes what is as old as the newest named message and keeps the rest`() {
        val gone = ConversationDeleteSync.doomed(Request(key, recent = listOf(self to 200L, ada to 100L)), rows)
        assertEquals(setOf("$ada:100", "$self:200"), gone)
    }

    @Test
    fun `a delete that names nothing held here removes nothing`() {
        assertTrue(ConversationDeleteSync.doomed(Request(key, recent = listOf(ada to 999L), full = true), rows).isEmpty())
        assertTrue(ConversationDeleteSync.doomed(Request(key, full = true), rows).isEmpty())
    }

    @Test
    fun `a full delete keeps what arrived after the messages it names`() {
        val gone = ConversationDeleteSync.doomed(Request(key, recent = listOf(ada to 300L), full = true), rows)
        assertEquals(setOf("$ada:100", "$self:200", "$ada:300"), gone)
    }

    @Test
    fun `a full delete naming the newest message empties the conversation`() {
        val gone = ConversationDeleteSync.doomed(Request(key, recent = listOf(ada to 500L, self to 400L), full = true), rows)
        assertEquals(rows.map { it.id }.toSet(), gone)
    }

    @Test
    fun `the non-expiring names are used only when none of the others is held`() {
        val fallback = Request(key, recent = listOf(ada to 999L), recentNonExpiring = listOf(self to 200L))
        assertEquals(setOf("$ada:100", "$self:200"), ConversationDeleteSync.doomed(fallback, rows))
        val both = Request(key, recent = listOf(ada to 100L), recentNonExpiring = listOf(self to 400L))
        assertEquals(setOf("$ada:100"), ConversationDeleteSync.doomed(both, rows))
    }

    @Test
    fun `a full delete takes the call lines left behind, a trim does not`() {
        val withCall = rows + Row("call:600", 600, local = true)
        val named = listOf(ada to 500L)
        assertEquals(
            withCall.map { it.id }.toSet(),
            ConversationDeleteSync.doomed(Request(key, recent = named, full = true), withCall)
        )
        assertFalse("call:600" in ConversationDeleteSync.doomed(Request(key, recent = named), withCall))
    }

    @Test
    fun `call lines do not go with a full delete while a real message remains`() {
        val withCall = rows + Row("call:250", 250, local = true) + Row("call:600", 600, local = true)
        val gone = ConversationDeleteSync.doomed(Request(key, recent = listOf(self to 200L), full = true), withCall)
        assertEquals(setOf("$ada:100", "$self:200"), gone)
    }

    @Test
    fun `a message request delete is the whole conversation`() {
        assertEquals(rows.map { it.id }.toSet(), ConversationDeleteSync.doomed(Request(key, whole = true), rows))
    }

    @Test
    fun `which rows exist on this phone only`() {
        assertTrue(ConversationDeleteSync.isLocal("call:1", update = false))
        assertTrue(ConversationDeleteSync.isLocal("groupcall:1", update = false))
        assertTrue(ConversationDeleteSync.isLocal("local:1", update = false))
        assertTrue(ConversationDeleteSync.isLocal("$ada:1", update = true))
        assertFalse(ConversationDeleteSync.isLocal("$ada:1", update = false))
    }
}
