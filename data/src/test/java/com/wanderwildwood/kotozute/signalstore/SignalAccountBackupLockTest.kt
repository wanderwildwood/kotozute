package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A copy locked to the account rather than to digits on a piece of paper.
 *
 * Signal derives a backup key from the account's own entropy pool, so a copy opens on any
 * device that can reach the account and there is nothing to write down or lose. This app
 * minted thirty digits at the moment of writing, showed them once and stored them nowhere:
 * losing the slip lost the archive, which is the one failure a backup exists to prevent.
 */
class SignalAccountBackupLockTest {

    private val backupKey = ByteArray(32) { (it * 7 + 1).toByte() }
    private val salt = ByteArray(16) { it.toByte() }

    private fun sealed(bytes: ByteArray, with: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        SignalBackupCrypto.encrypt(out, with).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun opened(bytes: ByteArray, with: ByteArray): ByteArray =
        SignalBackupCrypto.decrypt(ByteArrayInputStream(bytes), with).use { it.readBytes() }

    @Test
    fun `the account key opens what the account key locked`() {
        val plain = "a conversation nobody else should read".toByteArray()
        val derived = SignalBackupCrypto.deriveFromAccount(backupKey, salt)
        assertArrayEquals(plain, opened(sealed(plain, derived), derived))
    }

    @Test
    fun `the same account key and salt derive the same key, on any device`() {
        // The whole point: another phone on the same account derives this without being told
        // anything, which is what replaces the thirty digits.
        assertArrayEquals(
            SignalBackupCrypto.deriveFromAccount(backupKey, salt),
            SignalBackupCrypto.deriveFromAccount(backupKey.copyOf(), salt.copyOf())
        )
    }

    @Test
    fun `every copy gets its own key, because this container is GCM`() {
        // A fixed key across copies would be safe in Signal's CBC-and-HMAC container and is
        // not safe in this one: chunks are numbered from zero behind a four-byte random
        // prefix, so two copies sharing a key could reuse a nonce, which in GCM gives away
        // the authentication key. The copy's own salt is what keeps them apart.
        val other = ByteArray(16) { (it + 99).toByte() }
        assertFalse(
            SignalBackupCrypto.deriveFromAccount(backupKey, salt)
                .contentEquals(SignalBackupCrypto.deriveFromAccount(backupKey, other))
        )
    }

    @Test
    fun `a different account cannot open it`() {
        val plain = "hello".toByteArray()
        val mine = SignalBackupCrypto.deriveFromAccount(backupKey, salt)
        val theirs = SignalBackupCrypto.deriveFromAccount(ByteArray(32) { 3 }, salt)
        assertNotEquals(null, runCatching { opened(sealed(plain, mine), theirs) }.exceptionOrNull())
    }

    @Test
    fun `the two schemes never agree, even on the same bytes`() {
        // Different info strings. A copy locked one way must not be openable by the other
        // reporting success on garbage, and the header says which was used.
        val asDigits = SignalBackupCrypto.derive("123456789012345678901234567890", salt)
        val asAccount = SignalBackupCrypto.deriveFromAccount(backupKey, salt)
        assertFalse(asDigits.contentEquals(asAccount))

        // And the same input treated both ways lands somewhere different again.
        val digitsOfKey = SignalBackupCrypto.derive(backupKey.joinToString("") { "1" }, salt)
        assertFalse(digitsOfKey.contentEquals(asAccount))
    }

    @Test
    fun `an older copy still opens with its digits`() {
        // Copies written before this exist. Nothing about them may change.
        val plain = "written last week".toByteArray()
        val digits = "482913004517229860731145668302"
        val derived = SignalBackupCrypto.derive(digits, salt)
        assertArrayEquals(plain, opened(sealed(plain, derived), derived))
        assertTrue(SignalBackupCrypto.isKey(digits))
    }
}
