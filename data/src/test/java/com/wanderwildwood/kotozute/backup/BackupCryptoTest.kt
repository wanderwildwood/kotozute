package com.wanderwildwood.kotozute.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class BackupCryptoTest {

    // Argon2 is native; the format is tested with a stand-in key derivation.
    private fun key(code: String, p: BackupCrypto.Params): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(code.toByteArray() + p.salt)

    private val json = """{"messageCount":1,"messages":[]}""".toByteArray()

    @Test fun roundTrip() {
        val p = BackupCrypto.newParams(3, 32)
        val file = BackupCrypto.encrypt(key("1".repeat(30), p), p, json)
        assertTrue(BackupCrypto.isEncrypted(file))
        val read = BackupCrypto.params(file)
        assertEquals(3, read.iterations); assertEquals(32, read.memoryMib)
        assertArrayEquals(json, BackupCrypto.decrypt(key("1".repeat(30), read), file))
    }

    @Test fun plainJsonIsNotEncrypted() {
        assertFalse(BackupCrypto.isEncrypted(json))
    }

    @Test(expected = BackupCrypto.WrongCode::class) fun wrongCodeRefused() {
        val p = BackupCrypto.newParams(3, 32)
        val file = BackupCrypto.encrypt(key("1".repeat(30), p), p, json)
        BackupCrypto.decrypt(key("2".repeat(30), p), file)
    }

    /** Weakening the parameters in the header breaks the file rather than opening it. */
    @Test(expected = BackupCrypto.WrongCode::class) fun editedHeaderRefused() {
        val p = BackupCrypto.newParams(3, 32)
        val k = key("1".repeat(30), p)
        val file = BackupCrypto.encrypt(k, p, json)
        file[4 + 1 + 16 + 3] = 1 // iterations 3 -> 1
        BackupCrypto.decrypt(k, file)
    }

    @Test fun codeShape() {
        val c = BackupCrypto.newCode()
        assertEquals(30, c.length); assertTrue(c.all(Char::isDigit))
        assertEquals(c, BackupCrypto.normalize(BackupCrypto.grouped(c)))
        assertEquals(c, BackupCrypto.normalize(BackupCrypto.grouped(c).replace(' ', '-')))
        assertNull(BackupCrypto.normalize(c.dropLast(1)))
        assertNull(BackupCrypto.normalize(c + "x"))
    }
}
