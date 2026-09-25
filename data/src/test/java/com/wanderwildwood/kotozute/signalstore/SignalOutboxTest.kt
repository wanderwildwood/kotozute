package com.wanderwildwood.kotozute.signalstore

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an outgoing message carries, kept until it has gone, and which pending sends are sent
 * again after a restart.
 *
 * ⚠ The failure that matters is quiet: a resend that goes without the photo somebody attached
 * looks like a successful send to the person who wrote it. So a message that had attachments
 * and has lost them must come back as "cannot", never as "none".
 */
class SignalOutboxTest {

    private val root: File = Files.createTempDirectory("outbox").toFile()
    private val outbox = SignalOutbox(root)
    private val id = "0f2d-aci:1790000000000"

    @Test
    fun `attachments come back in the order they were given`() {
        val uris = (0 until 12).map { "data:image/jpeg;base64,$it" }
        outbox.save(id, uris)
        // Twelve, so that "10" sorting before "2" as text would show.
        assertEquals(uris, outbox.load(id, hadAttachments = true))
    }

    @Test
    fun `a message that lost its attachments cannot be sent again`() {
        assertNull(outbox.load(id, hadAttachments = true))
        outbox.save(id, listOf("data:audio/aac;base64,AAAA"))
        outbox.clear(id)
        assertNull(outbox.load(id, hadAttachments = true))
    }

    @Test
    fun `a message with no attachments needs none`() {
        assertEquals(emptyList<String>(), outbox.load(id, hadAttachments = false))
    }

    @Test
    fun `one message's attachments are not another's`() {
        outbox.save(id, listOf("data:a"))
        outbox.save("0f2d-aci:1790000000001", listOf("data:b"))
        outbox.clear(id)
        assertEquals(listOf("data:b"), outbox.load("0f2d-aci:1790000000001", hadAttachments = true))
    }

    @Test
    fun `a pending send from the last day goes again and an older one does not`() {
        val now = 1_790_000_000_000L
        val day = SignalOutbox.RESEND_WINDOW_MS
        assertTrue(SignalOutbox.worthResending(now - 1, now))
        assertTrue(SignalOutbox.worthResending(now - day + 1, now))
        assertFalse("exactly a day is outside, as upstream's > is", SignalOutbox.worthResending(now - day, now))
        assertFalse("a clock that has gone backwards", SignalOutbox.worthResending(now + 60_000, now))
    }

    @Test
    fun `the sweep keeps what has not gone and removes what has no message`() {
        outbox.save(id, listOf("data:unsent"))
        outbox.save("0f2d-aci:1790000000009", listOf("data:orphan"))
        assertEquals(1, outbox.sweep(setOf(id)))
        assertEquals(listOf("data:unsent"), outbox.load(id, hadAttachments = true))
        assertNull(outbox.load("0f2d-aci:1790000000009", hadAttachments = true))
    }
}

