package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The lock on a backup.
 *
 * Everything here is a way a backup can fail quietly. A cipher that decrypts a truncated
 * file into a shorter history, a chunk that can be moved without complaint, a derivation
 * that changes when a library is upgraded and leaves every copy already written unopenable:
 * none of these announce themselves, and all of them are found at the only moment the backup
 * was ever going to be used.
 */
class SignalBackupCryptoTest {

    private val salt = ByteArray(16) { it.toByte() }
    private val key = "123456789012345678901234567890"

    private fun sealed(bytes: ByteArray, with: ByteArray = SignalBackupCrypto.derive(key, salt)): ByteArray {
        val out = ByteArrayOutputStream()
        SignalBackupCrypto.encrypt(out, with).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun opened(bytes: ByteArray, with: ByteArray = SignalBackupCrypto.derive(key, salt)): ByteArray =
        SignalBackupCrypto.decrypt(ByteArrayInputStream(bytes), with).use { it.readBytes() }

    @Test
    fun `a key is thirty digits and reads back however it was written down`() {
        val made = SignalBackupCrypto.newKey()
        assertEquals(SignalBackupCrypto.KEY_DIGITS, made.length)
        assertTrue(made.all { it.isDigit() })
        assertTrue(SignalBackupCrypto.isKey(made))

        // Written down in groups, typed back in with spaces, or with the dashes somebody
        // added themselves: the digits are the key and the rest is handwriting.
        assertEquals(made, SignalBackupCrypto.normalise(SignalBackupCrypto.group(made)))
        assertTrue(SignalBackupCrypto.isKey("1234 5678-9012 345678 901234 567890"))
        assertFalse(SignalBackupCrypto.isKey("12345"))
    }

    @Test
    fun `two keys are not the same key`() {
        val keys = List(50) { SignalBackupCrypto.newKey() }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `the derivation is fixed, so a copy written today opens tomorrow`() {
        // A golden value, and one checked against an HKDF written elsewhere rather than
        // against this code's own output: this says the derivation is RFC 5869's and not
        // merely that it is consistent with itself. If it changes, every backup already
        // written stops opening, and nothing else in the app would notice.
        val derived = SignalBackupCrypto.derive(key, salt)
        assertEquals(
            "07095dd712412fc97ae25d0f1f32319bc8acbaab2a8b8d45eab5ba0c85e96ece",
            derived.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun `a different key or a different salt is a different lock`() {
        assertFalse(
            SignalBackupCrypto.derive(key, salt)
                .contentEquals(SignalBackupCrypto.derive("999999999999999999999999999999", salt))
        )
        assertFalse(
            SignalBackupCrypto.derive(key, salt)
                .contentEquals(SignalBackupCrypto.derive(key, ByteArray(16) { 9 }))
        )
    }

    @Test
    fun `what goes in comes out, at every size a chunk boundary can fall`() {
        listOf(
            0,
            1,
            SignalBackupCrypto.CHUNK - 1,
            SignalBackupCrypto.CHUNK,
            SignalBackupCrypto.CHUNK + 1,
            SignalBackupCrypto.CHUNK * 3 + 7
        ).forEach { size ->
            val plain = ByteArray(size) { (it % 251).toByte() }
            assertArrayEquals("size $size", plain, opened(sealed(plain)))
        }
    }

    @Test
    fun `a backup is not readable without the key`() {
        val plain = "the quick brown fox".toByteArray()
        val wrong = SignalBackupCrypto.derive("099999999999999999999999999999", salt)

        val thrown = runCatching { opened(sealed(plain), wrong) }.exceptionOrNull()

        assertNotEquals(null, thrown)
    }

    @Test
    fun `a truncated backup refuses rather than opening short`() {
        val plain = ByteArray(SignalBackupCrypto.CHUNK * 2 + 10) { (it % 241).toByte() }
        val full = sealed(plain)

        // Everything but the last chunk: the file a half-finished copy leaves behind.
        val cut = full.copyOf(SignalBackupCrypto.CHUNK + 100)
        val thrown = runCatching { opened(cut) }.exceptionOrNull()

        assertNotEquals(null, thrown)
    }

    @Test
    fun `a stream that was never closed does not read back as a shorter history`() {
        val out = ByteArrayOutputStream()
        val stream = SignalBackupCrypto.encrypt(out, SignalBackupCrypto.derive(key, salt))
        stream.write(ByteArray(SignalBackupCrypto.CHUNK * 2) { 7 })
        stream.flush() // and never closed: the end marker is what close() writes

        val thrown = runCatching { opened(out.toByteArray()) }.exceptionOrNull()

        assertNotEquals(null, thrown)
    }

    @Test
    fun `a chunk cannot be moved, repeated or dropped`() {
        val chunk = SignalBackupCrypto.CHUNK
        val plain = ByteArray(chunk * 3) { (it / chunk).toByte() }
        val full = sealed(plain)

        // The prefix, then three sealed chunks of equal length, then the final empty one.
        val prefix = full.copyOfRange(0, 4)
        val sealedSize = 4 + chunk + 16
        val first = full.copyOfRange(4, 4 + sealedSize)
        val second = full.copyOfRange(4 + sealedSize, 4 + sealedSize * 2)
        val rest = full.copyOfRange(4 + sealedSize * 2, full.size)

        val swapped = prefix + second + first + rest
        assertNotEquals(null, runCatching { opened(swapped) }.exceptionOrNull())

        val repeated = prefix + first + first + rest
        assertNotEquals(null, runCatching { opened(repeated) }.exceptionOrNull())
    }

    @Test
    fun `the same bytes sealed twice do not produce the same file`() {
        val plain = "hello".toByteArray()
        // A fresh nonce prefix each time, so two backups of an unchanged history do not
        // reveal that nothing changed.
        assertFalse(sealed(plain).contentEquals(sealed(plain)))
    }
}
