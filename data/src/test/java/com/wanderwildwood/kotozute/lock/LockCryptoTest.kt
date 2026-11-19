package com.wanderwildwood.kotozute.lock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockCryptoTest {

    private val b64 = LockCrypto.JavaBase64

    @Test fun sealOpenRoundTrip() {
        val key = LockCrypto.newKey()
        val plain = "the lock key".toByteArray()
        assertArrayEquals(plain, LockCrypto.open(key, LockCrypto.seal(key, plain)))
    }

    @Test fun wrongKeyOpensNothing() {
        val sealed = LockCrypto.seal(LockCrypto.newKey(), byteArrayOf(1, 2, 3))
        assertNull(LockCrypto.open(LockCrypto.newKey(), sealed))
    }

    @Test fun alteredBytesOpenNothing() {
        val key = LockCrypto.newKey()
        val sealed = LockCrypto.seal(key, byteArrayOf(1, 2, 3))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertNull(LockCrypto.open(key, sealed))
    }

    @Test fun tooShortOpensNothing() {
        assertNull(LockCrypto.open(LockCrypto.newKey(), ByteArray(10)))
    }

    @Test fun coverUncoverRoundTrip() {
        val key = LockCrypto.newKey()
        val sealed = "c2VhbGVkIGJ5IHRoZSBrZXlzdG9yZQ=="
        val covered = LockCrypto.cover(key, sealed, b64)
        assertTrue(LockCrypto.isCovered(covered))
        assertNotEquals(sealed, covered)
        assertFalse(covered.contains(sealed))
        assertEquals(sealed, LockCrypto.uncover(key, covered, b64))
    }

    /** A value the lock never touched passes through: mixed states after a crash still open. */
    @Test fun uncoveredPassesThrough() {
        val plain = "c2VhbGVk"
        assertFalse(LockCrypto.isCovered(plain))
        assertEquals(plain, LockCrypto.uncover(LockCrypto.newKey(), plain, b64))
    }

    @Test fun coveredUnderAnotherKeyIsNullNotGarbage() {
        val covered = LockCrypto.cover(LockCrypto.newKey(), "x", b64)
        assertNull(LockCrypto.uncover(LockCrypto.newKey(), covered, b64))
    }

    @Test fun nullIsNotCovered() {
        assertFalse(LockCrypto.isCovered(null))
    }
}
