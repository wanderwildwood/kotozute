package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moving the media root backup key out of a plain file and into the encrypted store.
 *
 * Two things must hold whatever happens part way: the key is never lost (a device linked
 * earlier was handed it), and the plain copy never outlives a completed move.
 */
class MediaRootKeyMoveTest {

    private val key = ByteArray(32) { (it + 1).toByte() }
    private val otherKey = ByteArray(32) { (it + 100).toByte() }

    /** The old preferences file and the encrypted store, as two variables. */
    private class World(var old: ByteArray?, var oldPresent: Boolean = old != null, var stored: ByteArray? = null) {
        var storeFails = false
        var storeThrows = false
        var removeFails = false
        var stores = 0

        fun run(): MediaRootKeyMove.Result = MediaRootKeyMove.run(
            oldPresent = { oldPresent },
            readOld = { old },
            readStored = { stored },
            store = {
                stores++
                if (storeThrows) throw IllegalStateException("the store would not open")
                if (!storeFails) stored = it
            },
            removeOld = {
                if (removeFails) false else { old = null; oldPresent = false; true }
            }
        )
    }

    @Test
    fun `nothing in the old place is nothing to do`() {
        val w = World(old = null)
        assertEquals(MediaRootKeyMove.Result.NOTHING_TO_MOVE, w.run())
        assertNull(w.stored)
        assertEquals(0, w.stores)
    }

    @Test
    fun `the key is stored encrypted and the plain copy removed`() {
        val w = World(old = key)
        assertEquals(MediaRootKeyMove.Result.MOVED, w.run())
        assertArrayEquals(key, w.stored)
        assertFalse(w.oldPresent)
        // And a second run finds nothing left to do.
        assertEquals(MediaRootKeyMove.Result.NOTHING_TO_MOVE, w.run())
        assertEquals(1, w.stores)
    }

    @Test
    fun `a store that silently keeps nothing leaves the plain copy alone`() {
        val w = World(old = key).apply { storeFails = true }
        assertEquals(MediaRootKeyMove.Result.NOT_STORED, w.run())
        assertArrayEquals("the only copy must survive", key, w.old)
        assertTrue(w.oldPresent)
        // Next time, with the store working, it goes through.
        w.storeFails = false
        assertEquals(MediaRootKeyMove.Result.MOVED, w.run())
        assertArrayEquals(key, w.stored)
        assertFalse(w.oldPresent)
    }

    @Test
    fun `a store that throws leaves the plain copy alone`() {
        val w = World(old = key).apply { storeThrows = true }
        assertTrue(runCatching { w.run() }.exceptionOrNull() is IllegalStateException)
        assertArrayEquals(key, w.old)
        assertTrue(w.oldPresent)
        assertNull(w.stored)
    }

    @Test
    fun `stopped between storing and removing, the next run only removes`() {
        // What a crash after the store's commit and before the preference's leaves behind.
        val w = World(old = key, stored = key)
        assertEquals(MediaRootKeyMove.Result.MOVED, w.run())
        assertEquals("the stored key is not written a second time", 0, w.stores)
        assertArrayEquals(key, w.stored)
        assertFalse(w.oldPresent)
    }

    @Test
    fun `a plain copy that will not delete is reported and tried again`() {
        val w = World(old = key).apply { removeFails = true }
        assertEquals(MediaRootKeyMove.Result.OLD_COPY_KEPT, w.run())
        assertArrayEquals(key, w.stored)
        w.removeFails = false
        assertEquals(MediaRootKeyMove.Result.MOVED, w.run())
        assertFalse(w.oldPresent)
        assertEquals(1, w.stores)
    }

    @Test
    fun `a key already in the encrypted store is never overwritten from the plain file`() {
        val w = World(old = otherKey, stored = key)
        assertEquals(MediaRootKeyMove.Result.MOVED, w.run())
        assertArrayEquals(key, w.stored)
        assertFalse(w.oldPresent)
    }

    @Test
    fun `an old value that is not a key is removed and not stored`() {
        // Undecodable, and the wrong length: neither is a key, and neither may stay behind.
        listOf<ByteArray?>(null, ByteArray(5), ByteArray(33)).forEach { junk ->
            val w = World(old = junk, oldPresent = true)
            assertEquals(MediaRootKeyMove.Result.MOVED, w.run())
            assertNull(w.stored)
            assertFalse(w.oldPresent)
            assertEquals(0, w.stores)
        }
    }
}
