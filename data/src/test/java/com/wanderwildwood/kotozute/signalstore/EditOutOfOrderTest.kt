package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.core.models.ServiceId
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.EditMessage
import java.util.UUID

/**
 * An edit that arrives before its message, and the message after it: the row keeps the edit.
 * See [ContentNormalizer.keepsLaterEdit], which the message store asks before rewriting a row.
 */
class EditOutOfOrderTest {

    private val self = ServiceId.ACI.from(UUID.fromString("26960253-cf31-4b8b-8f1c-bb1c14f62b32"))
    private val theirs = ServiceId.ACI.from(UUID.fromString("11111111-2222-4333-8444-555555555555"))
    private val at = 1789646040389L
    private val meta = EnvelopeMetadata(theirs, null, 2, false, null, self, CiphertextMessage.WHISPER_TYPE)

    private fun normalize(content: Content) =
        ContentNormalizer.normalize(content, meta, self.toString(), null)!!

    @Test
    fun `the original arriving after its edit does not take the text back`() {
        val edit = normalize(Content(editMessage = EditMessage(
            targetSentTimestamp = at, dataMessage = DataMessage(body = "Lunch at one?", timestamp = at + 20)
        )))
        val original = normalize(Content(dataMessage = DataMessage(body = "Lunch at noon?", timestamp = at)))
        // The same row: the edit is filed under its original.
        assertEquals(original.id, edit.id)
        assertTrue(ContentNormalizer.keepsLaterEdit(edit.revisionTs, original.revisionTs))
        // The other way round is the ordinary case, and the edit is applied.
        assertFalse(ContentNormalizer.keepsLaterEdit(original.revisionTs, edit.revisionTs))
    }

    @Test
    fun `an earlier edit arriving after a later one is kept out too`() {
        assertTrue(ContentNormalizer.keepsLaterEdit(at + 30, at + 20))
        assertFalse(ContentNormalizer.keepsLaterEdit(at + 20, at + 30))
    }

    @Test
    fun `a redelivery and a note being replaced are left alone`() {
        // Same version again (a redelivery), and a never-edited row such as a missing-message
        // note being replaced by its message: both go ahead as before.
        assertFalse(ContentNormalizer.keepsLaterEdit(0L, 0L))
        assertFalse(ContentNormalizer.keepsLaterEdit(at + 20, at + 20))
    }
}
