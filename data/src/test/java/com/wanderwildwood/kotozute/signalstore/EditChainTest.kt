package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.model.SignalMessage
import com.wanderwildwood.kotozute.repository.SignalRepository
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.EditMessage

/**
 * Editing a message more than once, and which messages may be edited at all.
 *
 * ⚠ Signal's clients name the revision on screen when they edit, which after the first edit
 * is the *first edit*, not the original. The row here keeps the original's identity, so a
 * second edit that was taken at its word named a row that does not exist and arrived as a new
 * message beneath the old one.
 */
class EditChainTest {

    private val sender = UUID.fromString("d6cd2de6-2397-4bf4-bb68-b29a6228fbbd")
    private val self = UUID.fromString("0f2d5c1a-3b4e-4f60-8a71-92b3c4d5e6f7")
    private val original = 1_790_000_000_000L
    private val firstEdit = original + 60_000
    private val secondEdit = original + 120_000

    private fun edit(target: Long, at: Long, originalOf: (String, Long) -> Long?) = ContentNormalizer.normalize(
        Content(editMessage = EditMessage(targetSentTimestamp = target, dataMessage = DataMessage(body = "v", timestamp = at))),
        EnvelopeMetadata(
            sourceServiceId = ServiceId.ACI.from(sender),
            sourceE164 = null,
            sourceDeviceId = 1,
            sealedSender = false,
            groupId = null,
            destinationServiceId = ServiceId.ACI.from(self),
            ciphertextMessageType = 0
        ),
        self.toString(), null,
        originalOf = originalOf
    )!!

    @Test
    fun `a second edit lands on the original row`() {
        val m = edit(firstEdit, secondEdit) { author, at ->
            if (author == sender.toString() && at == firstEdit) original else null
        }
        assertEquals(ContentNormalizer.messageIdFor(sender.toString(), "", original), m.id)
        assertEquals(original, m.ts)
        assertEquals("the edit records itself", secondEdit, m.revisionTs)
    }

    @Test
    fun `a first edit names the original directly`() {
        val m = edit(original, firstEdit) { _, _ -> null }
        assertEquals(ContentNormalizer.messageIdFor(sender.toString(), "", original), m.id)
        assertEquals(firstEdit, m.revisionTs)
    }

    @Test
    fun `only our own sent text inside the window is editable`() {
        val now = original + 60_000
        assertTrue(SignalRepository.canEdit(true, original, false, false, SignalMessage.SEND_SENT, now))
        assertFalse("somebody else's", SignalRepository.canEdit(false, original, false, false, SignalMessage.SEND_SENT, now))
        assertFalse("view-once", SignalRepository.canEdit(true, original, true, false, SignalMessage.SEND_SENT, now))
        assertFalse("carries an attachment", SignalRepository.canEdit(true, original, false, true, SignalMessage.SEND_SENT, now))
        assertFalse("never went", SignalRepository.canEdit(true, original, false, false, SignalMessage.SEND_FAILED, now))
        assertFalse("past the window",
            SignalRepository.canEdit(true, original, false, false, SignalMessage.SEND_SENT, original + SignalRepository.WITHDRAW_WINDOW_MS))
    }
}
