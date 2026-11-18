package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The outbox encrypted, and the move of what an older build queued in the clear.
 *
 * An unsent message is the one thing here that is acted on by itself, after a restart, with
 * nobody watching. So the rule is about what [SignalOutbox.load] answers at every point of the
 * move: **the same attachments, each once, in order** -- one short is a message sent without
 * its picture, and one twice is a picture sent twice -- and never "gone" for something that
 * is still on the phone.
 */
class SignalOutboxAtRestTest {

    @get:Rule val folder = TemporaryFolder()

    private val id = "0f2d-aci:1790000000000"
    private val dirName = "0f2d-aci_1790000000000"
    private val uris = (0 until 12).map { "data:image/jpeg;base64,picture-number-$it-" + "A".repeat(200 * (it + 1)) }

    private var secret: ByteArray? = ByteArray(32) { (it + 5).toByte() }
    private var keystoreBroken = false

    private class PowerCut : Error("power cut")

    private fun outbox(checkpoint: (AttachmentVault.Step) -> Unit = {}) = SignalOutbox(
        root = folder.root,
        readSecret = { if (keystoreBroken) null else secret },
        writeSecret = {
            check(!keystoreBroken) { "the attachment secret is unavailable" }
            secret ?: error("no secret")
        },
        checkpoint = checkpoint
    )

    /** What a build before this one left for a message: one plaintext file per attachment. */
    private fun plantOldEntry(messageDir: String = dirName, attachments: List<String> = uris) {
        val dir = File(folder.root, messageDir).apply { mkdirs() }
        attachments.forEachIndexed { i, uri -> File(dir, "$i").writeText(uri) }
    }

    private fun everyFileUnder(dir: File): List<File> = dir.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `what is saved is not on disk in the clear`() {
        outbox().save(id, uris)

        val files = everyFileUnder(folder.root)
        assertEquals(uris.size, files.size)
        files.forEach { file ->
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            assertFalse("${file.name} holds readable text", text.contains("picture-number") || text.contains("data:image"))
        }
        assertEquals(uris, outbox().load(id, hadAttachments = true))
    }

    @Test
    fun `an entry an older build queued in the clear still loads`() {
        plantOldEntry()
        assertEquals(uris, outbox().load(id, hadAttachments = true))
    }

    @Test
    fun `the move encrypts a queued entry and it loads the same`() {
        plantOldEntry()

        assertEquals(0, outbox().encryptStored())

        assertEquals(uris, outbox().load(id, hadAttachments = true))
        everyFileUnder(folder.root).forEach { file ->
            assertEquals("everything left is in the sealed folder", "sealed", file.parentFile!!.name)
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("picture-number"))
        }
        // And again changes nothing.
        assertEquals(0, outbox().encryptStored())
        assertEquals(uris, outbox().load(id, hadAttachments = true))
    }

    @Test
    fun `stopped at any step of any attachment, the message loads whole, once, in order`() {
        AttachmentVault.Step.values().forEach { stopAt ->
            // Stopped on the first attachment moved, on one in the middle, and on the last.
            listOf(1, 6, uris.size).forEach { stopOnNth ->
                folder.root.listFiles()!!.forEach { it.deleteRecursively() }
                plantOldEntry()
                var seen = 0
                val cut = runCatching {
                    outbox { step ->
                        if (step == stopAt && ++seen == stopOnNth) throw PowerCut()
                    }.encryptStored()
                }.exceptionOrNull()
                assertTrue("stopped at $stopAt of attachment $stopOnNth", cut is PowerCut)

                assertEquals(
                    "after a stop at $stopAt of attachment $stopOnNth",
                    uris, outbox().load(id, hadAttachments = true)
                )

                assertEquals(0, outbox().encryptStored())
                assertEquals(uris, outbox().load(id, hadAttachments = true))
                assertTrue(
                    "no plaintext is left once the move has finished",
                    File(folder.root, dirName).listFiles()!!.none { it.isFile }
                )
            }
        }
    }

    @Test
    fun `the working file of a stopped move is not taken for an attachment`() {
        outbox().save(id, uris.take(2))
        File(File(File(folder.root, dirName), "sealed"), "seal4821.tmp").writeBytes(ByteArray(900) { 1 })
        File(File(folder.root, dirName), "seal77.tmp").writeBytes(ByteArray(10))

        assertEquals(uris.take(2), outbox().load(id, hadAttachments = true))
    }

    @Test
    fun `every queued message is moved, and one is not another's`() {
        plantOldEntry(dirName, uris.take(3))
        plantOldEntry("0f2d-aci_1790000000001", listOf("data:b"))

        assertEquals(0, outbox().encryptStored())

        assertEquals(uris.take(3), outbox().load(id, hadAttachments = true))
        assertEquals(listOf("data:b"), outbox().load("0f2d-aci:1790000000001", hadAttachments = true))
    }

    @Test
    fun `without the secret a queued entry is unreadable, not gone, and nothing is deleted`() {
        outbox().save(id, uris)
        val before = everyFileUnder(folder.root).map { it.path to it.length() }.toSet()
        keystoreBroken = true

        val failure = runCatching { outbox().load(id, hadAttachments = true) }.exceptionOrNull()

        assertTrue("null would mean 'gone' and end the message", failure is SignalOutbox.Unreadable)
        assertEquals(before, everyFileUnder(folder.root).map { it.path to it.length() }.toSet())
        // And it loads again when the keystore does.
        keystoreBroken = false
        assertEquals(uris, outbox().load(id, hadAttachments = true))
    }

    @Test
    fun `without the secret an old plaintext entry still loads and is not moved`() {
        plantOldEntry()
        keystoreBroken = true

        assertEquals(uris, outbox().load(id, hadAttachments = true))
        assertEquals(uris.size, outbox().encryptStored())
        assertEquals(uris, outbox().load(id, hadAttachments = true))
        assertTrue(File(File(folder.root, dirName), "0").isFile)
    }

    @Test
    fun `without the secret nothing is saved, in either form`() {
        keystoreBroken = true
        val failure = runCatching { outbox().save(id, uris) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(everyFileUnder(folder.root).isEmpty())
        keystoreBroken = false
        assertNull(outbox().load(id, hadAttachments = true))
    }

    @Test
    fun `clearing a message removes it in both forms`() {
        plantOldEntry()
        runCatching {
            outbox { step -> if (step == AttachmentVault.Step.SWAPPED) throw PowerCut() }.encryptStored()
        }
        outbox().clear(id)
        assertFalse(File(folder.root, dirName).exists())
        assertNull(outbox().load(id, hadAttachments = true))
    }

    @Test
    fun `the sweep still takes a sent message's folder, encrypted or not`() {
        outbox().save(id, uris.take(1))
        plantOldEntry("0f2d-aci_1790000000001", listOf("data:b"))
        assertEquals(1, outbox().sweep(setOf(id)))
        assertEquals(uris.take(1), outbox().load(id, hadAttachments = true))
        assertFalse(File(folder.root, "0f2d-aci_1790000000001").exists())
    }
}
