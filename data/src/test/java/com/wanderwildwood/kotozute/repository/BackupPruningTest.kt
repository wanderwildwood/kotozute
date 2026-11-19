package com.wanderwildwood.kotozute.repository

import com.wanderwildwood.kotozute.repository.BackupPruning.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An automatic backup deletes the oldest of this app's own backups beyond the number to keep,
 * and nothing else in the folder. The second half is the one that matters: the folder is the
 * user's, and may hold anything.
 */
class BackupPruningTest {

    private fun e(name: String, t: Long) = Entry(name, t)

    private val a = e("backup-20261101030000.json", 100)
    private val b = e("backup-20261102030000.json", 200)
    private val c = e("backup-20261103030000.json", 300)
    private val d = e("backup-20261104030000.json", 400)

    @Test
    fun `the oldest beyond the number to keep are deleted`() {
        assertEquals(
            listOf(b.name, a.name),
            BackupPruning.toDelete(listOf(a, b, c, d), keep = 2, justWritten = d.name))
    }

    @Test
    fun `nothing is deleted while there are no more than the number to keep`() {
        assertTrue(BackupPruning.toDelete(listOf(a, b), keep = 3, justWritten = b.name).isEmpty())
        assertTrue(BackupPruning.toDelete(listOf(a, b), keep = 2, justWritten = b.name).isEmpty())
    }

    @Test
    fun `order of the listing does not matter`() {
        assertEquals(
            listOf(a.name),
            BackupPruning.toDelete(listOf(c, a, d, b), keep = 3, justWritten = d.name))
    }

    @Test
    fun `files that are not this app's backups are never named`() {
        val others = listOf(
            e("notes.txt", 1),
            e("backup-20261101030000.json.bak", 2),
            e("backup-20261101030000 (1).json", 3),
            e("backup-2026110103.json", 4),
            e("my-backup-20261101030000.json", 5),
            e("Backup-20261101030000.json", 6),
            e("backup-20261101030000.JSON", 7))
        assertTrue(BackupPruning.toDelete(others + d, keep = 1, justWritten = d.name).isEmpty())
        assertEquals(
            listOf(a.name),
            BackupPruning.toDelete(others + listOf(a, d), keep = 1, justWritten = d.name))
    }

    @Test
    fun `the backup just written is kept even when its time looks old`() {
        val fresh = e("backup-20261105030000.json", 0)
        assertEquals(
            listOf(c.name, d.name),
            BackupPruning.toDelete(listOf(c, d, fresh), keep = 1, justWritten = fresh.name))
    }

    @Test
    fun `equal times fall back to the name`() {
        val x = e("backup-20261101030000.json", 0)
        val y = e("backup-20261102030000.json", 0)
        assertEquals(listOf(x.name), BackupPruning.toDelete(listOf(x, y), keep = 1, justWritten = y.name))
    }

    @Test
    fun `a keep below one deletes nothing`() {
        assertTrue(BackupPruning.toDelete(listOf(a, b), keep = 0, justWritten = b.name).isEmpty())
        assertTrue(BackupPruning.toDelete(listOf(a, b), keep = -1, justWritten = b.name).isEmpty())
    }
}
