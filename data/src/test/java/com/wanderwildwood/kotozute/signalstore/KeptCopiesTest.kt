package com.wanderwildwood.kotozute.signalstore

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.core.models.ServiceId
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.api.crypto.SignalServiceCipherResult
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.push.SyncMessage
import java.util.UUID

/**
 * The loop that processes kept copies again: a message that decrypted and then failed to
 * process. See [KeptCopies] for why it exists and [UnprocessedCopyTest] for the copy itself.
 *
 * Run against a small in-memory store standing in for the envelope table, with the same
 * columns: the copy, the tries so far, when the next is due, and whether a note is owed.
 */
class KeptCopiesTest {

    private val self = ServiceId.ACI.from(UUID.fromString("26960253-cf31-4b8b-8f1c-bb1c14f62b32"))
    private val theirs = ServiceId.ACI.from(UUID.fromString("11111111-2222-4333-8444-555555555555"))
    private val at = 1789646040389L
    private val start = 1_800_000_000_000L

    private class Row(
        val id: Long,
        val copy: ByteArray,
        var attempts: Int = 1,
        var nextAt: Long = 0L,
        var gone: Boolean = false,
        var note: KeptCopies.Note? = null,
        var gaveUp: Boolean = false
    )

    private class FakeStore(val rows: MutableList<Row>) : KeptCopies.Store {
        override fun due(now: Long) = rows
            .filter { !it.gone && it.attempts < KeptCopies.MAX_ATTEMPTS && it.nextAt <= now }
            .map { KeptCopies.Kept(it.id, Envelope(clientTimestamp = 1L), 0L, it.copy, it.attempts) }

        override fun processed(id: Long, message: com.wanderwildwood.kotozute.signal.BridgeMessage?) {
            rows.single { it.id == id }.gone = true
        }

        override fun failedAgain(id: Long, attempts: Int, nextAt: Long) {
            rows.single { it.id == id }.apply { this.attempts = attempts; this.nextAt = nextAt }
        }

        override fun gaveUp(id: Long, attempts: Int, note: KeptCopies.Note?) {
            rows.single { it.id == id }.apply { this.attempts = attempts; this.note = note; gaveUp = true }
        }
    }

    private fun metadata() = EnvelopeMetadata(
        self, null, 3, false, null, self, CiphertextMessageType
    )

    private fun kept(content: Content) =
        SignalReceiver.encodeUnprocessed(SignalServiceCipherResult(content, metadata()))

    private fun transcript(profileKey: ByteArray? = null) = Content(
        syncMessage = SyncMessage(
            sent = SyncMessage.Sent(
                destinationServiceIdBinary = theirs.toByteString(),
                timestamp = at,
                message = DataMessage(
                    body = "See you at six, Ada",
                    timestamp = at,
                    profileKey = profileKey?.toByteString()
                )
            )
        )
    )

    @Test
    fun `a copy that processes is cleared and handed back to be filed`() {
        val store = FakeStore(mutableListOf(Row(1, kept(transcript()))))
        val done = KeptCopies(store).run(start) { _, _ -> KeptCopies.Outcome.Processed(self.toString(), null) }
        assertEquals(1, done.size)
        assertTrue(store.rows.single().gone)
        // And nothing is left to try.
        assertEquals(0, KeptCopies(store).run(start + 1) { _, _ -> error("not again") }.size)
    }

    @Test
    fun `a copy that keeps failing backs off, then is given up with a note`() {
        val row = Row(1, kept(transcript()), attempts = 1, nextAt = start + KeptCopies.backoff(1))
        val store = FakeStore(mutableListOf(row))
        val copies = KeptCopies(store)
        var tries = 0
        val failing: (KeptCopies.Kept, SignalServiceCipherResult) -> KeptCopies.Outcome = { _, _ ->
            tries++
            KeptCopies.Outcome.Failed
        }

        // Not before it is due.
        copies.run(start) { _, _ -> error("tried before it was due") }
        assertEquals(0, tries)

        var now = start
        val waits = mutableListOf<Long>()
        while (!row.gaveUp) {
            now = row.nextAt
            val before = row.nextAt
            copies.run(now, failing)
            if (!row.gaveUp) waits += row.nextAt - before
            assertTrue("never more than the cap", row.attempts <= KeptCopies.MAX_ATTEMPTS)
        }
        // The first try was the one that failed in the decryption pass.
        assertEquals(KeptCopies.MAX_ATTEMPTS - 1, tries)
        assertEquals(KeptCopies.MAX_ATTEMPTS, row.attempts)
        assertEquals(waits.sorted(), waits)
        assertTrue(waits.first() < waits.last())

        // The note goes where the old path puts one: under whoever sent it.
        assertNotNull(row.note)
        assertEquals(self.toString(), row.note!!.sender)
        assertEquals(3, row.note!!.deviceId)

        // And once given up it is never tried again.
        copies.run(now + 365L * 24 * 3600 * 1000) { _, _ -> error("tried after giving up") }
    }

    @Test
    fun `a throw while processing again counts as a failure`() {
        val row = Row(1, kept(transcript()))
        KeptCopies(FakeStore(mutableListOf(row))).run(start) { _, _ -> throw IllegalStateException("again") }
        assertEquals(2, row.attempts)
        assertTrue(row.nextAt > start)
    }

    @Test
    fun `a copy that cannot be read is given up at once, with no note`() {
        val row = Row(1, byteArrayOf(9, 9, 9))
        KeptCopies(FakeStore(mutableListOf(row))).run(start) { _, _ -> error("processed garbage") }
        assertTrue(row.gaveUp)
        assertNull(row.note)
    }

    @Test
    fun `an old blocked list is not applied again`() {
        val blocked = Content(
            syncMessage = SyncMessage(blocked = SyncMessage.Blocked(acis = listOf(theirs.toString())))
        )
        var seen: Content? = null
        KeptCopies(FakeStore(mutableListOf(Row(1, kept(blocked))))).run(start) { _, replay ->
            seen = replay.content
            KeptCopies.Outcome.Processed(self.toString(), null)
        }
        assertNotNull(seen)
        assertNull(seen!!.syncMessage?.blocked)
    }

    @Test
    fun `nor an old profile key, though the message it rode on is processed`() {
        var seen: Content? = null
        KeptCopies(FakeStore(mutableListOf(Row(1, kept(transcript(ByteArray(32) { 7 })))))).run(start) { _, replay ->
            seen = replay.content
            KeptCopies.Outcome.Processed(self.toString(), null)
        }
        val message = seen!!.syncMessage!!.sent!!.message!!
        assertNull(message.profileKey)
        assertEquals("See you at six, Ada", message.body)
        assertEquals(theirs.toString(), ContentNormalizer.destinationServiceIdOf(seen!!.syncMessage!!.sent!!))
    }

    @Test
    fun `the other state syncs are dropped too`() {
        listOf(
            SyncMessage(contacts = SyncMessage.Contacts()),
            SyncMessage(configuration = SyncMessage.Configuration(readReceipts = true)),
            SyncMessage(keys = SyncMessage.Keys()),
            SyncMessage(verified = org.whispersystems.signalservice.internal.push.Verified()),
            SyncMessage(messageRequestResponse = SyncMessage.MessageRequestResponse())
        ).forEach { sync ->
            val replayed = KeptCopies.forReplay(Content(syncMessage = sync)).syncMessage!!
            assertNull(replayed.contacts)
            assertNull(replayed.configuration)
            assertNull(replayed.keys)
            assertNull(replayed.verified)
            assertNull(replayed.messageRequestResponse)
        }
        val incoming = Content(dataMessage = DataMessage(body = "hello", profileKey = ByteArray(32).toByteString()))
        assertNull(KeptCopies.forReplay(incoming).dataMessage!!.profileKey)
        assertEquals("hello", KeptCopies.forReplay(incoming).dataMessage!!.body)
    }

    private companion object {
        const val CiphertextMessageType = CiphertextMessage.WHISPER_TYPE
    }
}
