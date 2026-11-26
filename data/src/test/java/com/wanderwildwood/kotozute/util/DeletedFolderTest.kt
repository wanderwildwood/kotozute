package com.wanderwildwood.kotozute.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The Deleted folder's two rules: where a Delete goes, and when a deleted conversation is due. */
class DeletedFolderTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val deletedAt = 1_790_000_000_000L // some afternoon in 2026

    @Test fun `with the setting on a delete goes to the folder`() {
        assertTrue(DeletedFolder.holdsDelete(keepDeleted = true))
    }

    @Test fun `with the setting off a delete is final at once`() {
        assertFalse(DeletedFolder.holdsDelete(keepDeleted = false))
    }

    @Test fun `thirty days is the keep`() {
        assertEquals(30L * day, DeletedFolder.KEEP_MS)
    }

    @Test fun `not in the folder is never due`() {
        assertFalse(DeletedFolder.isDue(0L, deletedAt + 365 * day))
    }

    @Test fun `a day short of thirty is not due`() {
        assertFalse(DeletedFolder.isDue(deletedAt, deletedAt + 29 * day))
        assertFalse(DeletedFolder.isDue(deletedAt, deletedAt + 30 * day - 1))
    }

    @Test fun `thirty days to the millisecond is due`() {
        assertTrue(DeletedFolder.isDue(deletedAt, deletedAt + 30 * day))
    }

    @Test fun `long past thirty days is due`() {
        assertTrue(DeletedFolder.isDue(deletedAt, deletedAt + 400 * day))
    }

    @Test fun `just deleted is not due`() {
        assertFalse(DeletedFolder.isDue(deletedAt, deletedAt))
    }

    @Test fun `a stamp from a clock that ran ahead is not due`() {
        // Deleted while the clock read a year ahead, then the clock was put right.
        assertFalse(DeletedFolder.isDue(deletedAt + 365 * day, deletedAt))
    }
}
