package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Random

/**
 * Where attachments are kept, and the move of old plaintext ones into the encrypted form.
 *
 * This store is the only place a received picture exists, so the rule under test throughout is
 * the same one: **at no point is the only copy of an attachment a file nobody has read back**,
 * and nothing is deleted or written in the clear because a key went missing.
 */
class AttachmentVaultTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var sealedDir: File
    private lateinit var legacyDir: File
    private lateinit var secrets: FakeSecret

    /** Stands in for the keystore-sealed secret, with the same two rules as the real one. */
    private class FakeSecret {
        var secret: ByteArray? = null
        var keystoreBroken = false
        var created = 0

        fun forReading(): ByteArray? = if (keystoreBroken) null else secret

        fun forWriting(mayCreate: () -> Boolean): ByteArray {
            check(!keystoreBroken) { "the attachment secret is unavailable" }
            secret?.let { return it }
            check(mayCreate()) { "attachments are already encrypted and their secret is gone" }
            created++
            return ByteArray(32) { (it + created).toByte() }.also { secret = it }
        }
    }

    /** What a power cut looks like to the code: not an Exception it could clean up after. */
    private class PowerCut : Error("power cut")

    @Before
    fun setUp() {
        sealedDir = File(folder.root, "sealed")
        legacyDir = File(folder.root, "legacy").apply { mkdirs() }
        secrets = FakeSecret()
    }

    private fun vault(checkpoint: (AttachmentVault.Step) -> Unit = {}) =
        AttachmentVault(sealedDir, legacyDir, secrets::forReading, secrets::forWriting, checkpoint)

    private fun bytes(n: Int, seed: Long = 1): ByteArray = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun plant(id: String, content: ByteArray) = File(legacyDir, id).writeBytes(content)

    // ---- writing and reading --------------------------------------------------------------

    @Test
    fun `a written attachment reads back and is not on disk in the clear`() {
        val content = bytes(50_000)
        vault().write("a1", ByteArrayInputStream(content))

        assertArrayEquals(content, vault().read("a1"))
        assertTrue(vault().has("a1"))
        assertFalse("nothing new goes to the old directory", File(legacyDir, "a1").exists())
        val stored = File(sealedDir, "a1").readBytes()
        assertEquals(content.size + 32, stored.size)
        assertFalse(stored.copyOfRange(32, stored.size).contentEquals(content))
        assertEquals("no working file is left beside it", listOf("a1"), sealedDir.list()!!.toList())
    }

    @Test
    fun `an old plaintext attachment still reads`() {
        val content = bytes(3000)
        plant("old", content)
        assertArrayEquals(content, vault().read("old"))
        assertTrue(vault().has("old"))
    }

    @Test
    fun `an attachment that is nowhere is null`() {
        assertNull(vault().read("never"))
        assertFalse(vault().has("never"))
    }

    @Test
    fun `forgetting removes an attachment in both forms`() {
        val content = bytes(100)
        plant("both", content)
        vault().write("both", ByteArrayInputStream(content))
        assertTrue(vault().forget("both"))
        assertFalse(File(legacyDir, "both").exists())
        assertFalse(File(sealedDir, "both").exists())
        assertFalse(vault().forget("both"))
    }

    @Test
    fun `the sweep is shown the files of both directories`() {
        plant("p", bytes(10))
        vault().write("s", ByteArrayInputStream(bytes(10)))
        assertEquals(setOf("p", "s"), vault().files().map { it.name }.toSet())
    }

    // ---- the secret going missing ---------------------------------------------------------

    @Test
    fun `without the secret nothing is written, in either form`() {
        secrets.keystoreBroken = true
        val failure = runCatching { vault().write("x", ByteArrayInputStream(bytes(10))) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(File(legacyDir, "x").exists())
        assertTrue(sealedDir.list().isNullOrEmpty())
    }

    @Test
    fun `without the secret an encrypted attachment does not read and is not deleted`() {
        vault().write("x", ByteArrayInputStream(bytes(10)))
        secrets.keystoreBroken = true
        assertNull(vault().read("x"))
        assertTrue(File(sealedDir, "x").isFile)
        // And comes back when the keystore does.
        secrets.keystoreBroken = false
        assertArrayEquals(bytes(10), vault().read("x"))
    }

    @Test
    fun `without the secret an old plaintext attachment still reads`() {
        plant("old", bytes(10))
        secrets.keystoreBroken = true
        assertArrayEquals(bytes(10), vault().read("old"))
    }

    @Test
    fun `a lost secret is not replaced while files encrypted under it exist`() {
        val content = bytes(500)
        vault().write("x", ByteArrayInputStream(content))
        secrets.secret = null // the sealed secret is gone; the file is still there

        val failure = runCatching { vault().write("y", ByteArrayInputStream(bytes(10))) }.exceptionOrNull()
        assertTrue("a new secret would turn the old file to noise", failure is IllegalStateException)
        assertEquals(1, secrets.created)
        assertFalse(vault().has("y"))
        assertTrue(File(sealedDir, "x").isFile)
    }

    @Test
    fun `without the secret the move does nothing and says it stopped`() {
        plant("a", bytes(100, 1))
        plant("b", bytes(100, 2))
        secrets.keystoreBroken = true

        val summary = vault().migrateAll()

        assertEquals(AttachmentVault.Summary(done = 0, left = 2, stoppedEarly = true), summary)
        assertArrayEquals(bytes(100, 1), File(legacyDir, "a").readBytes())
        assertArrayEquals(bytes(100, 2), File(legacyDir, "b").readBytes())
        assertTrue(sealedDir.list().isNullOrEmpty())
    }

    // ---- the move ---------------------------------------------------------------------------

    @Test
    fun `the move encrypts every old attachment and removes the old directory`() {
        val contents = (1..5).associate { "id$it" to bytes(1000 * it, it.toLong()) }
        contents.forEach { (id, c) -> plant(id, c) }

        val summary = vault().migrateAll()

        assertEquals(AttachmentVault.Summary(done = 5, left = 0, stoppedEarly = false), summary)
        contents.forEach { (id, c) ->
            assertArrayEquals(c, vault().read(id))
            assertTrue(File(sealedDir, id).isFile)
        }
        assertFalse("the plaintext directory is gone once it is empty", legacyDir.exists())
        assertEquals(contents.keys, sealedDir.list()!!.toSet())
        // Running it again changes nothing.
        assertEquals(AttachmentVault.Summary(0, 0, false), vault().migrateAll())
    }

    @Test
    fun `an empty attachment moves too`() {
        plant("empty", ByteArray(0))
        assertEquals(AttachmentVault.Moved.DONE, vault().migrateOne("empty"))
        assertArrayEquals(ByteArray(0), vault().read("empty"))
        assertEquals(32L, File(sealedDir, "empty").length())
    }

    @Test
    fun `stopped after any step, the attachment is readable and the next run finishes`() {
        val content = bytes(40_000, 9)
        AttachmentVault.Step.values().forEach { stopAt ->
            sealedDir.deleteRecursively()
            legacyDir.deleteRecursively(); legacyDir.mkdirs()
            secrets = FakeSecret()
            plant("pic", content)

            val cut = runCatching {
                vault { step -> if (step == stopAt) throw PowerCut() }.migrateOne("pic")
            }.exceptionOrNull()
            assertTrue("stopped at $stopAt", cut is PowerCut)

            // The state the disk was left in, read by a fresh process.
            assertArrayEquals("readable after a stop at $stopAt", content, vault().read("pic"))
            val plaintextThere = File(legacyDir, "pic").isFile
            val sealedThere = File(sealedDir, "pic").isFile
            when (stopAt) {
                AttachmentVault.Step.ENCRYPTED, AttachmentVault.Step.VERIFIED -> {
                    assertTrue("the plaintext is untouched until the swap", plaintextThere)
                    assertFalse("an unchecked file is never under the attachment's name", sealedThere)
                }
                AttachmentVault.Step.SWAPPED -> assertTrue(plaintextThere && sealedThere)
                AttachmentVault.Step.PLAINTEXT_REMOVED -> assertTrue(!plaintextThere && sealedThere)
            }

            val summary = vault().migrateAll()

            assertEquals("after a stop at $stopAt", 0, summary.left)
            assertFalse(summary.stoppedEarly)
            assertFalse(File(legacyDir, "pic").exists())
            assertArrayEquals(content, vault().read("pic"))
            assertArrayEquals(
                content,
                AttachmentCipher.decryptingStream(secrets.secret!!, File(sealedDir, "pic")).use { it.readBytes() }
            )
        }
    }

    @Test
    fun `a stop does not hold up the other attachments`() {
        plant("a", bytes(100, 1))
        plant("b", bytes(100, 2))
        var cutOnce = true
        runCatching {
            vault { step ->
                if (cutOnce && step == AttachmentVault.Step.ENCRYPTED) { cutOnce = false; throw PowerCut() }
            }.migrateAll()
        }
        val summary = vault().migrateAll()
        assertEquals(0, summary.left)
        assertArrayEquals(bytes(100, 1), vault().read("a"))
        assertArrayEquals(bytes(100, 2), vault().read("b"))
    }

    @Test
    fun `a half-written working file left by a stop is never taken for an attachment`() {
        val content = bytes(5000)
        plant("pic", content)
        // What a power cut in the middle of step 1 leaves: a secret, and part of a file.
        val secret = secrets.forWriting { true }
        sealedDir.mkdirs()
        val whole = File(folder.root, "whole")
        AttachmentCipher.encryptingStream(secret, whole).use { it.write(content) }
        File(sealedDir, "seal12345.tmp").writeBytes(whole.readBytes().copyOf(700))

        assertArrayEquals(content, vault().read("pic"))
        assertEquals(AttachmentVault.Moved.DONE, vault().migrateOne("pic"))
        assertArrayEquals(content, vault().read("pic"))
        assertEquals(5032L, File(sealedDir, "pic").length())
    }

    @Test
    fun `an attachment deleted while it was being encrypted stays deleted`() {
        plant("gone", bytes(1000))
        val result = vault { step ->
            // A disappearing message's timer runs out between the check and the swap.
            if (step == AttachmentVault.Step.VERIFIED) assertTrue(vault().forget("gone"))
        }.migrateOne("gone")

        assertEquals(AttachmentVault.Moved.GONE, result)
        assertFalse(vault().has("gone"))
        assertTrue("and no working file is left", sealedDir.list().isNullOrEmpty())
    }

    @Test
    fun `two forms that differ are both left alone`() {
        plant("id", bytes(100, 1))
        vault().write("id", ByteArrayInputStream(bytes(100, 2)))

        assertEquals(AttachmentVault.Moved.CONFLICT, vault().migrateOne("id"))

        assertArrayEquals(bytes(100, 1), File(legacyDir, "id").readBytes())
        assertArrayEquals(bytes(100, 2), vault().read("id"))
        assertEquals(1, vault().migrateAll().left)
    }

    @Test
    fun `asked to stop, the move stops between files and loses nothing`() {
        (1..4).forEach { plant("id$it", bytes(200, it.toLong())) }
        var asked = 0
        val summary = vault().migrateAll(shouldStop = { asked++ >= 2 })
        assertEquals(2, summary.done)
        assertEquals(2, summary.left)
        assertTrue(summary.stoppedEarly)
        (1..4).forEach { assertArrayEquals(bytes(200, it.toLong()), vault().read("id$it")) }
    }
}
