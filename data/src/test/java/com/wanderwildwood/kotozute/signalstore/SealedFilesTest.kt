package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Whether a new attachment secret may be made: only while nothing is encrypted anywhere.
 *
 * Getting it wrong in the permissive direction makes a second secret beside files written
 * under the first, and the format has no way to say they no longer read.
 */
class SealedFilesTest {

    @get:Rule val folder = TemporaryFolder()

    private fun file(vararg path: String): File =
        path.fold(folder.root) { dir, name -> File(dir, name) }.apply { parentFile!!.mkdirs(); writeText("x") }

    @Test
    fun `a phone with nothing on it has nothing encrypted`() {
        assertTrue(SealedFiles.nothingEncrypted(folder.root))
    }

    @Test
    fun `plaintext from an older build is not something encrypted`() {
        file(SealedFiles.ATTACHMENTS_LEGACY, "abc")
        file(SealedFiles.OUTBOX, "msg_1", "0")
        File(folder.root, SealedFiles.ATTACHMENTS_SEALED).mkdirs()
        File(File(File(folder.root, SealedFiles.OUTBOX), "msg_1"), SealedFiles.OUTBOX_SEALED).mkdirs()
        assertTrue(SealedFiles.nothingEncrypted(folder.root))
    }

    @Test
    fun `an encrypted attachment counts`() {
        file(SealedFiles.ATTACHMENTS_SEALED, "abc")
        assertFalse(SealedFiles.nothingEncrypted(folder.root))
    }

    @Test
    fun `an encrypted unsent attachment counts, with the attachment store empty`() {
        file(SealedFiles.OUTBOX, "msg_1", "0")
        file(SealedFiles.OUTBOX, "msg_2", SealedFiles.OUTBOX_SEALED, "0")
        assertFalse(SealedFiles.nothingEncrypted(folder.root))
    }

    @Test
    fun `count is both places together`() {
        file(SealedFiles.ATTACHMENTS_SEALED, "abc")
        file(SealedFiles.ATTACHMENTS_SEALED, "def")
        file(SealedFiles.OUTBOX, "msg_1", SealedFiles.OUTBOX_SEALED, "0")
        file(SealedFiles.ATTACHMENTS_LEGACY, "old")
        file(SealedFiles.OUTBOX, "msg_2", "0")
        assertEquals(3, SealedFiles.count(folder.root))
    }

    /** What can never be read goes; what an older build kept in the clear stays. */
    @Test
    fun `removing all leaves nothing encrypted and keeps the plaintext`() {
        file(SealedFiles.ATTACHMENTS_SEALED, "abc")
        file(SealedFiles.OUTBOX, "msg_1", SealedFiles.OUTBOX_SEALED, "0")
        val legacy = file(SealedFiles.ATTACHMENTS_LEGACY, "old")
        val unsentPlain = file(SealedFiles.OUTBOX, "msg_2", "0")
        assertTrue(SealedFiles.removeAll(folder.root))
        assertTrue(SealedFiles.nothingEncrypted(folder.root))
        assertEquals(0, SealedFiles.count(folder.root))
        assertTrue(legacy.isFile)
        assertTrue(unsentPlain.isFile)
    }
}
