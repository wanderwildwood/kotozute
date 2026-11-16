package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Random

/**
 * The format attachments are stored in: Signal Android's, carried over.
 *
 * What is checked is what the rest of the app leans on -- that what goes in comes out, at any
 * length and from any offset -- and what the format does *not* promise, written down as tests
 * so nobody builds on a promise it never made: it carries no authentication, so a wrong secret
 * and a cut-off file both read without an error.
 */
class AttachmentCipherTest {

    @get:Rule val folder = TemporaryFolder()

    private val secret = ByteArray(32) { (it * 7 + 1).toByte() }
    private val otherSecret = ByteArray(32) { (it * 5 + 3).toByte() }

    private fun bytes(n: Int, seed: Long = 42): ByteArray = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun encrypt(plain: ByteArray, name: String = "f"): File {
        val file = File(folder.root, name)
        AttachmentCipher.encryptingStream(secret, file).use { it.write(plain) }
        return file
    }

    private fun decrypt(file: File, with: ByteArray = secret, offset: Long = 0): ByteArray =
        AttachmentCipher.decryptingStream(with, file, offset).use { it.readBytes() }

    @Test
    fun `what is written is what is read, at every awkward length`() {
        // Around the cipher's 16-byte block and well past any buffer.
        listOf(0, 1, 15, 16, 17, 31, 32, 33, 4095, 4096, 4097, 300_000).forEach { n ->
            val plain = bytes(n, seed = n.toLong())
            val file = encrypt(plain, "len-$n")
            assertArrayEquals("length $n", plain, decrypt(file))
        }
    }

    @Test
    fun `the file is the plaintext's length and thirty-two bytes more`() {
        val file = encrypt(bytes(1000))
        assertEquals(1032L, file.length())
        assertEquals(1000L, AttachmentCipher.plaintextLength(file.length()))
    }

    @Test
    fun `the plaintext is not in the file`() {
        val plain = "Ada Whitlock sent a photo of the harbor at dusk. ".repeat(40).toByteArray()
        val stored = encrypt(plain).readBytes()
        val needle = "harbor".toByteArray()
        val found = (0..stored.size - needle.size).any { i ->
            needle.indices.all { j -> stored[i + j] == needle[j] }
        }
        assertFalse("a word of the plaintext is readable in the stored file", found)
    }

    @Test
    fun `the same bytes stored twice are two different files`() {
        val plain = bytes(500)
        val a = encrypt(plain, "a").readBytes()
        val b = encrypt(plain, "b").readBytes()
        assertFalse("each file has its own random bytes, so its own key", a.contentEquals(b))
        assertFalse(
            "and not only a different head",
            a.copyOfRange(32, a.size).contentEquals(b.copyOfRange(32, b.size))
        )
    }

    @Test
    fun `a wrong secret reads the wrong bytes, without an error`() {
        // The format has no authentication, as upstream says of it. What makes the secret the
        // right one is its own seal; see AttachmentSecret.
        val plain = bytes(2000)
        val file = encrypt(plain)
        val read = decrypt(file, with = otherSecret)
        assertEquals(plain.size, read.size)
        assertFalse(plain.contentEquals(read))
    }

    @Test
    fun `a file too short to hold its random bytes is refused`() {
        listOf(0, 1, 31).forEach { n ->
            val file = File(folder.root, "short-$n").apply { writeBytes(ByteArray(n)) }
            val e = runCatching { decrypt(file) }.exceptionOrNull()
            assertTrue("a $n-byte file must not read", e is IOException)
            assertEquals(AttachmentCipher.PREMATURE_END_ERROR_MESSAGE, e?.message)
        }
    }

    @Test
    fun `a cut-off file reads as the start of the plaintext`() {
        // Not detected by the format: the reason a file is only ever put under its name whole
        // (AttachmentVault writes beside it and renames).
        val plain = bytes(5000)
        val file = encrypt(plain)
        val cut = File(folder.root, "cut").apply { writeBytes(file.readBytes().copyOf(32 + 1234)) }
        assertArrayEquals(plain.copyOf(1234), decrypt(cut))
    }

    @Test
    fun `reading from an offset gives the plaintext from that offset`() {
        val plain = bytes(10_000)
        val file = encrypt(plain)
        listOf(0, 1, 15, 16, 17, 255, 256, 4096, 4099, 9_999, 10_000).forEach { offset ->
            assertArrayEquals(
                "offset $offset",
                plain.copyOfRange(offset, plain.size),
                decrypt(file, offset = offset.toLong())
            )
        }
    }

    @Test
    fun `the head of the file is the random bytes and they are not all zero`() {
        val head = encrypt(bytes(10)).readBytes().copyOf(AttachmentCipher.RANDOM_BYTES)
        assertTrue(head.any { it != 0.toByte() })
    }
}
