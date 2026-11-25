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
        var gaveUp: Boolean = false,
        var build: Int = 0,
        /** Notes written for it, as `announceGivenUpEnvelopes` writes them: once. */
        var notes: Int = 0
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

        override fun gaveUp(id: Long, attempts: Int, note: KeptCopies.Note?, build: Int) {
            rows.single { it.id == id }.apply {
                this.attempts = attempts; this.note = note; gaveUp = true; this.build = build
                // `placeholder_at`: set by the first note and never cleared.
                if (note != null && notes == 0) notes = 1
            }
        }

        override fun freshTries(build: Int, now: Long) {
            rows.filter { !it.gone && it.attempts >= KeptCopies.MAX_ATTEMPTS && it.build != build }.forEach {
                it.attempts = 1; it.nextAt = now; it.build = build; it.gaveUp = false
            }
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
        val done = KeptCopies(store, BUILD).run(start) { _, _ -> KeptCopies.Outcome.Processed(self.toString(), null) }
        assertEquals(1, done.size)
        assertTrue(store.rows.single().gone)
        // And nothing is left to try.
        assertEquals(0, KeptCopies(store, BUILD).run(start + 1) { _, _ -> error("not again") }.size)
    }

    @Test
    fun `a copy that keeps failing backs off, then is given up with a note`() {
        val row = Row(1, kept(transcript()), attempts = 1, nextAt = start + KeptCopies.backoff(1))
        val store = FakeStore(mutableListOf(row))
        val copies = KeptCopies(store, BUILD)
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

        // Our own message, so filed under this account, in the conversation it went to.
        assertNotNull(row.note)
        assertEquals(self.toString(), row.note!!.sender)
        assertEquals(3, row.note!!.deviceId)
        assertEquals(theirs.toString(), row.note!!.sentTo)

        // And once given up it is never tried again.
        copies.run(now + 365L * 24 * 3600 * 1000) { _, _ -> error("tried after giving up") }
    }

    @Test
    fun `a throw while processing again counts as a failure`() {
        val row = Row(1, kept(transcript()))
        KeptCopies(FakeStore(mutableListOf(row)), BUILD).run(start) { _, _ -> throw IllegalStateException("again") }
        assertEquals(2, row.attempts)
        assertTrue(row.nextAt > start)
    }

    @Test
    fun `a copy that cannot be read is given up at once, with no note`() {
        val row = Row(1, byteArrayOf(9, 9, 9))
        KeptCopies(FakeStore(mutableListOf(row)), BUILD).run(start) { _, _ -> error("processed garbage") }
        assertTrue(row.gaveUp)
        assertNull(row.note)
    }

    @Test
    fun `an old blocked list is not applied again`() {
        val blocked = Content(
            syncMessage = SyncMessage(blocked = SyncMessage.Blocked(acis = listOf(theirs.toString())))
        )
        var seen: Content? = null
        KeptCopies(FakeStore(mutableListOf(Row(1, kept(blocked)))), BUILD).run(start) { _, replay ->
            seen = replay.content
            KeptCopies.Outcome.Processed(self.toString(), null)
        }
        assertNotNull(seen)
        assertNull(seen!!.syncMessage?.blocked)
    }

    @Test
    fun `nor an old profile key, though the message it rode on is processed`() {
        var seen: Content? = null
        KeptCopies(FakeStore(mutableListOf(Row(1, kept(transcript(ByteArray(32) { 7 }))))), BUILD).run(start) { _, replay ->
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

    private fun metadataFrom(who: ServiceId) = EnvelopeMetadata(
        who, null, 2, false, null, self, CiphertextMessageType
    )

    /** The note's conversation and id, and the message's, for the same content. */
    private fun noteAndMessage(content: Content, from: ServiceId = self): Pair<Pair<String?, String>, Pair<String, String>> {
        val metadata = metadataFrom(from)
        val note = KeptCopies.noteFor(SignalServiceCipherResult(content, metadata))!!
        val group = note.groupId?.let { java.util.Base64.getEncoder().encodeToString(it) }.orEmpty()
        val noteKey = ContentNormalizer.noteThreadKey(
            note.sender, group, note.sentTo, note.sentToNumber, self.toString(), null
        )
        val noteId = ContentNormalizer.messageIdFor(note.sender, "", note.sentAt ?: at)
        val message = ContentNormalizer.normalize(content, metadata, self.toString(), null)!!
        return (noteKey to noteId) to (message.threadKey to message.id)
    }

    @Test
    fun `a one-to-one message sent from another device is noted in the conversation it went to`() {
        val (note, message) = noteAndMessage(transcript())
        assertEquals("direct:$theirs", note.first)
        // The same conversation and the same id, so the message replaces the note if it lands.
        assertEquals(message, note.first!! to note.second)
    }

    @Test
    fun `a note to self sent from another device stays in Note to Self`() {
        val toSelf = Content(
            syncMessage = SyncMessage(
                sent = SyncMessage.Sent(
                    destinationServiceIdBinary = self.toByteString(),
                    timestamp = at,
                    message = DataMessage(body = "Buy bread", timestamp = at)
                )
            )
        )
        val (note, message) = noteAndMessage(toSelf)
        assertEquals("direct:$self", note.first)
        assertEquals(message, note.first!! to note.second)
    }

    @Test
    fun `a group message sent from another device is noted in the group`() {
        val group = org.whispersystems.signalservice.internal.push.GroupContextV2(
            masterKey = ByteArray(32) { (it + 1).toByte() }.toByteString(), revision = 0
        )
        val inGroup = Content(
            syncMessage = SyncMessage(
                sent = SyncMessage.Sent(
                    timestamp = at,
                    message = DataMessage(body = "Running late", timestamp = at, groupV2 = group)
                )
            )
        )
        val (note, message) = noteAndMessage(inGroup)
        assertTrue(note.first!!.startsWith("group:"))
        assertEquals(message, note.first!! to note.second)
    }

    @Test
    fun `somebody else's message is noted under them, as before`() {
        val incoming = Content(dataMessage = DataMessage(body = "On my way", timestamp = at))
        val decoded = SignalServiceCipherResult(incoming, metadataFrom(theirs))
        val note = KeptCopies.noteFor(decoded)!!
        assertNull(note.sentTo)
        assertEquals(theirs.toString(), note.sender)
        val (keyAndId, message) = noteAndMessage(incoming, from = theirs)
        assertEquals("direct:$theirs", keyAndId.first)
        assertEquals(message, keyAndId.first!! to keyAndId.second)
    }

    @Test
    fun `what is not a message is given up without a note`() {
        val readSync = Content(
            syncMessage = SyncMessage(read = listOf(SyncMessage.Read(senderAciBinary = theirs.toByteString(), timestamp = at)))
        )
        assertNull(KeptCopies.noteFor(SignalServiceCipherResult(readSync, metadata())))
        val update = Content(
            syncMessage = SyncMessage(
                sent = SyncMessage.Sent(
                    timestamp = at, isRecipientUpdate = true,
                    message = DataMessage(timestamp = at)
                )
            )
        )
        assertNull(KeptCopies.noteFor(SignalServiceCipherResult(update, metadata())))

        val row = Row(1, kept(readSync), attempts = KeptCopies.MAX_ATTEMPTS - 1)
        KeptCopies(FakeStore(mutableListOf(row)), BUILD).run(start) { _, _ -> KeptCopies.Outcome.Failed }
        assertTrue(row.gaveUp)
        assertNull(row.note)
    }

    @Test
    fun `a copy given up by one build gets a fresh set of tries from the next, and one note`() {
        val row = Row(1, kept(transcript()), attempts = 1, nextAt = start)
        val store = FakeStore(mutableListOf(row))
        var now = start
        while (!row.gaveUp) {
            KeptCopies(store, BUILD).run(now) { _, _ -> KeptCopies.Outcome.Failed }
            now = maxOf(now, row.nextAt) + 1
        }
        assertEquals(1, row.notes)
        assertEquals(BUILD, row.build)

        // The same build again: nothing.
        KeptCopies(store, BUILD).run(now + 365L * 24 * 3600 * 1000) { _, _ -> error("same build tried it again") }

        // An update: tried at once, and again with backoff, then given up again -- one note still.
        var tries = 0
        while (true) {
            KeptCopies(store, BUILD + 1).run(now) { _, _ -> tries++; KeptCopies.Outcome.Failed }
            if (row.gaveUp) break
            now = row.nextAt
        }
        assertEquals(KeptCopies.MAX_ATTEMPTS - 1, tries)
        assertEquals(BUILD + 1, row.build)
        assertEquals(1, row.notes)
        KeptCopies(store, BUILD + 1).run(now + 1) { _, _ -> error("tried twice by one build") }

        // And the build after that, with the fault fixed: processed, cleared.
        val done = KeptCopies(store, BUILD + 2).run(now + 2) { _, _ ->
            KeptCopies.Outcome.Processed(self.toString(), null)
        }
        assertEquals(1, done.size)
        assertTrue(row.gone)
    }

    @Test
    fun `a copy still being tried is not reset by an update`() {
        val row = Row(1, kept(transcript()), attempts = 3, nextAt = start + 10_000)
        KeptCopies(FakeStore(mutableListOf(row)), BUILD + 1).run(start) { _, _ -> error("not due yet") }
        assertEquals(3, row.attempts)
        assertEquals(start + 10_000, row.nextAt)
    }

    private companion object {
        const val BUILD = 10350053
        const val CiphertextMessageType = CiphertextMessage.WHISPER_TYPE
    }
}
