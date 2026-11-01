package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue 9: a read sync arriving in the same batch as its message must not be lost. */
class ReadSyncLedgerTest {

    private class FakeRows : ReadSyncLedger.Rows {
        val read = mutableMapOf<String, Boolean>()
        fun file(id: String) { read.putIfAbsent(id, false) }
        override fun markRead(ids: Collection<String>, readAt: Long): Set<String> {
            val present = mutableSetOf<String>()
            ids.forEach { if (it in read) { present += it; read[it] = true } }
            return present
        }
    }

    private val ada = "11111111-2222-3333-4444-555555555555:1000"

    /** The old behaviour, as the receive loop ran it: sync first, filing after. */
    @Test
    fun `without the ledger the sync is lost when the batch holds both`() {
        val rows = FakeRows()
        rows.markRead(listOf(ada), 5)   // sync applied before the batch is filed
        rows.file(ada)
        assertFalse(rows.read.getValue(ada))
    }

    @Test
    fun `sync in the same batch as its message is applied once the message is filed`() {
        val rows = FakeRows()
        val ledger = ReadSyncLedger(rows)
        ledger.applied(listOf(ada), readAt = 5, now = 10)
        assertEquals(1, ledger.waiting())
        rows.file(ada)
        val nowRead = ledger.filed(listOf(ada), now = 11)
        assertEquals(setOf(ada), nowRead)
        assertTrue(rows.read.getValue(ada))
        assertEquals(0, ledger.waiting())
    }

    @Test
    fun `a sync for a message already on file applies at once and keeps nothing`() {
        val rows = FakeRows().apply { file(ada) }
        val ledger = ReadSyncLedger(rows)
        ledger.applied(listOf(ada), 5, now = 1)
        assertTrue(rows.read.getValue(ada))
        assertEquals(0, ledger.waiting())
    }

    @Test
    fun `an unrelated message filed later is left unread`() {
        val rows = FakeRows()
        val ledger = ReadSyncLedger(rows)
        ledger.applied(listOf(ada), 5, now = 1)
        val other = "tomas:2000"
        rows.file(other)
        assertTrue(ledger.filed(listOf(other), now = 2).isEmpty())
        assertFalse(rows.read.getValue(other))
        assertEquals(1, ledger.waiting())
    }

    @Test
    fun `a sync that never finds its message is forgotten after a day`() {
        val rows = FakeRows()
        val ledger = ReadSyncLedger(rows)
        ledger.applied(listOf(ada), 5, now = 0)
        rows.file(ada)
        assertTrue(ledger.filed(listOf(ada), now = ReadSyncLedger.DAY_MS + 1).isEmpty())
        assertFalse(rows.read.getValue(ada))
    }

    @Test
    fun `the pile is capped`() {
        val ledger = ReadSyncLedger(FakeRows(), capacity = 3)
        ledger.applied((1..10).map { "x:$it" }, 5, now = 0)
        assertEquals(3, ledger.waiting())
    }

    @Test
    fun `each sync keeps its own reading time`() {
        val rows = FakeRows()
        val times = mutableMapOf<String, Long>()
        val spy = object : ReadSyncLedger.Rows {
            override fun markRead(ids: Collection<String>, readAt: Long): Set<String> {
                ids.forEach { if (it in rows.read) times[it] = readAt }
                return rows.markRead(ids, readAt)
            }
        }
        val ledger = ReadSyncLedger(spy)
        ledger.applied(listOf("a:1"), 100, now = 0)
        ledger.applied(listOf("b:2"), 200, now = 0)
        rows.file("a:1"); rows.file("b:2")
        ledger.filed(listOf("a:1", "b:2"), now = 1)
        assertEquals(100L, times["a:1"]); assertEquals(200L, times["b:2"])
    }
}
