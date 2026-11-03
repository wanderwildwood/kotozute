package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 9, second report: messages read on another device lost their notification here but
 * the conversation stayed unread. A read sync names single messages, and only those were
 * marked; Signal marks the conversation read up to the newest message the sync names.
 */
class ReadSinceTest {

    private class FakeTable(val rows: MutableList<ReadSince.Row> = mutableListOf()) : ReadSince.Table {
        override fun byId(id: String) = rows.firstOrNull { it.id == id }
        override fun byRevision(author: String, revisionTs: Long) =
            rows.firstOrNull { it.senderUuid == author && it.revisionTs == revisionTs && revisionTs > 0 }
        override fun unreadIncoming(threadKey: String, upTo: Long) =
            rows.filter { it.threadKey == threadKey && !it.outgoing && !it.read && it.date <= upTo }
    }

    private val ada = "11111111-2222-3333-4444-555555555555"
    private val tomas = "66666666-7777-8888-9999-000000000000"
    private val adaThread = "direct:$ada"

    private fun row(
        id: String, date: Long, thread: String = adaThread, sender: String = ada,
        outgoing: Boolean = false, read: Boolean = false, revisionTs: Long = 0
    ) = ReadSince.Row(id, thread, date, sender, revisionTs, outgoing, read)

    @Test
    fun `a sync for a message marks it read`() {
        val table = FakeTable(mutableListOf(row("$ada:100", 100)))
        val plan = ReadSince.plan(listOf("$ada:100"), table)
        assertEquals(setOf("$ada:100"), plan.present)
        assertEquals(setOf("$ada:100"), plan.toMark)
    }

    /** A missed call is never named by a read sync; Signal's read-since marks it anyway. */
    @Test
    fun `everything unread before the synced message in its conversation is read too`() {
        val table = FakeTable(mutableListOf(
            row("call:42", 50),
            row("$ada:100", 100),
            row("$ada:200", 200)
        ))
        val plan = ReadSince.plan(listOf("$ada:200"), table)
        assertEquals(setOf("call:42", "$ada:100", "$ada:200"), plan.toMark)
    }

    @Test
    fun `a later message the sync did not reach stays unread`() {
        val table = FakeTable(mutableListOf(row("$ada:100", 100), row("$ada:300", 300)))
        val plan = ReadSince.plan(listOf("$ada:100"), table)
        assertEquals(setOf("$ada:100"), plan.toMark)
    }

    @Test
    fun `other conversations are left alone`() {
        val table = FakeTable(mutableListOf(
            row("$ada:100", 100),
            row("$tomas:50", 50, thread = "direct:$tomas", sender = tomas)
        ))
        val plan = ReadSince.plan(listOf("$ada:100"), table)
        assertEquals(setOf("$ada:100"), plan.toMark)
    }

    /** Signal counts a sync for a message already read: the sweep still runs from it. */
    @Test
    fun `a sync naming a message already read still clears what came before it`() {
        val table = FakeTable(mutableListOf(row("$ada:100", 100), row("$ada:200", 200, read = true)))
        val plan = ReadSince.plan(listOf("$ada:200"), table)
        assertEquals(setOf("$ada:200"), plan.present)
        assertEquals(setOf("$ada:100"), plan.toMark)
    }

    /**
     * An edited message is filed under the original's timestamp; the device that read it
     * names the newest revision, as Signal's own read sync does (it marks the latest
     * revision row, whose date_sent is the edit's).
     */
    @Test
    fun `a sync naming an edit finds the message it rewrote`() {
        val table = FakeTable(mutableListOf(row("$ada:100", 100, revisionTs = 150)))
        val plan = ReadSince.plan(listOf("$ada:150"), table)
        assertEquals(setOf("$ada:150"), plan.present)
        assertEquals(setOf("$ada:100"), plan.toMark)
    }

    @Test
    fun `a sync for nothing on file is not present and marks nothing`() {
        val table = FakeTable(mutableListOf(row("$ada:100", 100)))
        val plan = ReadSince.plan(listOf("$ada:999"), table)
        assertTrue(plan.present.isEmpty())
        assertTrue(plan.toMark.isEmpty())
    }

    /** The ledger and the rule together, as the repository wires them. */
    private class Wired(val table: FakeTable) : ReadSyncLedger.Rows {
        override fun markRead(ids: Collection<String>, readAt: Long): Set<String> {
            val plan = ReadSince.plan(ids, table)
            table.rows.replaceAll { if (it.id in plan.toMark) ReadSince.Row(it.id, it.threadKey, it.date, it.senderUuid, it.revisionTs, it.outgoing, true) else it }
            return plan.present
        }
    }

    private fun msg(id: String, ts: Long, revisionTs: Long = 0, outgoing: Boolean = false) =
        com.wanderwildwood.kotozute.signal.BridgeMessage(
            id = id, threadKey = adaThread, ts = ts, senderUuid = if (outgoing) "me" else ada,
            senderNumber = "", outgoing = outgoing, body = "x", groupId = "", quoteTs = 0,
            read = outgoing, source = "live", attachmentsJson = "", revisionTs = revisionTs
        )

    /** A batch holding an edit and the sync for it: the edit is not a new row, but it is filed. */
    @Test
    fun `a sync for an edit in the same batch is applied once the edit is filed`() {
        val table = FakeTable(mutableListOf(row("$ada:100", 100)))
        val ledger = ReadSyncLedger(Wired(table))
        ledger.applied(listOf("$ada:150"), readAt = 5, now = 0)      // edit not filed yet
        assertEquals(1, ledger.waiting())
        table.rows[0] = row("$ada:100", 100, revisionTs = 150)       // the edit rewrites its row
        val edit = msg("$ada:100", 100, revisionTs = 150)
        ledger.filed(ReadSince.filedKeys(fresh = emptyList(), all = listOf(edit)), now = 1)
        assertTrue(table.rows[0].read)
        assertEquals(0, ledger.waiting())
    }

    @Test
    fun `filed keys are new incoming ids and incoming edits, never our own`() {
        val keys = ReadSince.filedKeys(
            fresh = listOf(msg("$ada:100", 100), msg("me:120", 120, outgoing = true)),
            all = listOf(msg("$ada:100", 100), msg("$ada:90", 90, revisionTs = 130), msg("me:120", 120, revisionTs = 125, outgoing = true))
        )
        assertEquals(listOf("$ada:100", "$ada:130"), keys)
    }

    /** The reported case end to end: a batch with two messages, a missed call before them, and one sync. */
    @Test
    fun `the conversation is clear after a batch whose sync names only the newest message`() {
        val table = FakeTable(mutableListOf(row("call:7", 40)))
        val ledger = ReadSyncLedger(Wired(table))
        ledger.applied(listOf("$ada:200"), readAt = 5, now = 0)
        table.rows += row("$ada:100", 100)
        table.rows += row("$ada:200", 200)
        ledger.filed(listOf("$ada:100", "$ada:200"), now = 1)
        assertTrue(table.rows.all { it.read })
    }

    @Test
    fun `our own messages are never touched`() {
        val table = FakeTable(mutableListOf(
            row("me:90", 90, outgoing = true),
            row("$ada:100", 100)
        ))
        val plan = ReadSince.plan(listOf("$ada:100"), table)
        assertEquals(setOf("$ada:100"), plan.toMark)
    }
}
