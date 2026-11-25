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

    private val self = ServiceId.ACI.from(UUID.fromString("0d0d0d0d-1e1e-4f2f-8a3a-4b4b4b4b4b4b"))
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
        var notes: Int = 0,
        var waitsFor: String? = null,
        /** What it was filed as, when it was processed: the message, or null for nothing. */
        var filed: com.wanderwildwood.kotozute.signal.BridgeMessage? = null,
        /** Applied against its message's note, and kept; see `appliedEarly`. */
        var appliedTimes: Int = 0,
        /** The envelope's own timestamp. */
        val envelopeAt: Long = 1L,
        /** `placeholder_at` is set: the note has been written, by the end of a drain. */
        var noted: Boolean = false
    )

    private class FakeStore(val rows: MutableList<Row>) : KeptCopies.Store {
        override fun due(now: Long) = rows
            .filter { !it.gone && it.waitsFor == null && it.attempts < KeptCopies.MAX_ATTEMPTS && it.nextAt <= now }
            .map { KeptCopies.Kept(it.id, Envelope(clientTimestamp = 1L), 0L, it.copy, it.attempts) }

        override fun all() = rows.filter { !it.gone }
            .map { KeptCopies.Kept(it.id, Envelope(clientTimestamp = it.envelopeAt), 0L, it.copy, it.attempts, it.waitsFor, it.noted) }

        override fun appliedEarly(id: Long, message: com.wanderwildwood.kotozute.signal.BridgeMessage?, target: String) {
            rows.single { it.id == id }.apply { waitsFor = KeptCopies.APPLIED + target; attempts = 0; appliedTimes++ }
        }

        override fun processed(id: Long, message: com.wanderwildwood.kotozute.signal.BridgeMessage?) {
            rows.single { it.id == id }.apply { gone = true; filed = message }
        }

        override fun failedAgain(id: Long, attempts: Int, nextAt: Long) {
            rows.single { it.id == id }.apply { this.attempts = attempts; this.nextAt = nextAt; waitsFor = null }
        }

        override fun gaveUp(id: Long, attempts: Int, note: KeptCopies.Note?, build: Int) {
            rows.single { it.id == id }.apply {
                this.attempts = attempts; this.note = note; gaveUp = true; this.build = build; waitsFor = null
                // `placeholder_at`: set by the first note and never cleared.
                if (note != null && notes == 0) notes = 1
            }
        }

        override fun waitFor(id: Long, target: String) {
            rows.single { it.id == id }.apply { waitsFor = target; attempts = 0; nextAt = 0L }
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
            copies.run(now, process = failing)
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
    fun `nor an old retry receipt, though a sender key stays with its message`() {
        val content = Content(
            dataMessage = DataMessage(body = "hello", timestamp = at),
            senderKeyDistributionMessage = ByteArray(8) { 1 }.toByteString()
        )
        val replayed = KeptCopies.forReplay(content)
        // Harmless processed twice, and the only copy if the first pass threw before taking it.
        assertEquals(content.senderKeyDistributionMessage, replayed.senderKeyDistributionMessage)
        assertEquals("hello", replayed.dataMessage!!.body)
        // A retry receipt is the whole of its content (one of Content's oneof), so nothing is left.
        val receipt = KeptCopies.forReplay(Content(decryptionErrorMessage = ByteArray(8) { 2 }.toByteString()))
        assertNull(receipt.decryptionErrorMessage)
        var seen: Content? = null
        KeptCopies(FakeStore(mutableListOf(Row(1, keptCopyFrom(theirs, Content(decryptionErrorMessage = ByteArray(8) { 2 }.toByteString()))))), BUILD).run(start) { _, replay ->
            seen = replay.content
            KeptCopies.Outcome.Processed(theirs.toString(), null)
        }
        assertNull(seen!!.decryptionErrorMessage)
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

    // --- What points at a kept message waits for it -------------------------------------

    private fun incoming(m: DataMessage) = Content(dataMessage = m)
    private fun keptFrom(who: ServiceId, content: Content) =
        SignalServiceCipherResult(content, metadataFrom(who))
    private fun keptCopyFrom(who: ServiceId, content: Content) =
        SignalReceiver.encodeUnprocessed(keptFrom(who, content))

    private val original = incoming(DataMessage(body = "Lunch at noon?", timestamp = at))
    private val originalId = "$theirs:$at"

    private val withdrawal = incoming(DataMessage(timestamp = at + 10, delete = DataMessage.Delete(targetSentTimestamp = at)))
    private val edit = Content(
        editMessage = org.whispersystems.signalservice.internal.push.EditMessage(
            targetSentTimestamp = at,
            dataMessage = DataMessage(body = "Lunch at one?", timestamp = at + 20)
        )
    )
    private val reaction = incoming(
        DataMessage(
            timestamp = at + 30,
            reaction = DataMessage.Reaction(emoji = "👍", targetAuthorAci = theirs.toString(), targetSentTimestamp = at)
        )
    )

    /** What the process step was handed, by the body or event it carried. */
    private fun describe(replay: SignalServiceCipherResult): String {
        val c = replay.content
        return when {
            c.editMessage != null -> "edit"
            c.dataMessage?.delete != null -> "delete"
            c.dataMessage?.reaction != null -> "reaction"
            else -> "message"
        }
    }

    @Test
    fun `targetOf names the message each kind of event points at`() {
        val meta = metadataFrom(theirs)
        val ours = metadataFrom(self)
        assertEquals(originalId, KeptCopies.targetOf(edit, meta))
        assertEquals(originalId, KeptCopies.targetOf(withdrawal, meta))
        assertEquals(originalId, KeptCopies.targetOf(reaction, meta))
        // The binary twin of the reaction's author works as well as the string.
        assertEquals(
            "$self:$at",
            KeptCopies.targetOf(
                incoming(DataMessage(timestamp = at + 1, reaction = DataMessage.Reaction(
                    emoji = "👍", targetAuthorAciBinary = self.toByteString(), targetSentTimestamp = at
                ))),
                meta
            )
        )
        assertEquals(originalId, KeptCopies.targetOf(incoming(DataMessage(timestamp = at + 2,
            pinMessage = DataMessage.PinMessage(targetAuthorAciBinary = theirs.toByteString(), targetSentTimestamp = at, pinDurationForever = true))), ours))
        assertEquals(originalId, KeptCopies.targetOf(incoming(DataMessage(timestamp = at + 3,
            unpinMessage = DataMessage.UnpinMessage(targetAuthorAciBinary = theirs.toByteString(), targetSentTimestamp = at))), ours))
        assertEquals(originalId, KeptCopies.targetOf(incoming(DataMessage(timestamp = at + 4,
            pollVote = DataMessage.PollVote(targetAuthorAciBinary = theirs.toByteString(), targetSentTimestamp = at))), ours))
        assertEquals(originalId, KeptCopies.targetOf(incoming(DataMessage(timestamp = at + 5,
            pollTerminate = DataMessage.PollTerminate(targetSentTimestamp = at))), meta))
        assertEquals(originalId, KeptCopies.targetOf(incoming(DataMessage(timestamp = at + 6,
            adminDelete = DataMessage.AdminDelete(targetAuthorAciBinary = theirs.toByteString(), targetSentTimestamp = at))), ours))
        // Our own withdrawal and edit, synced from another device: our own message.
        val syncedDelete = Content(syncMessage = SyncMessage(sent = SyncMessage.Sent(
            timestamp = at + 7, message = DataMessage(timestamp = at + 7, delete = DataMessage.Delete(targetSentTimestamp = at))
        )))
        assertEquals("$self:$at", KeptCopies.targetOf(syncedDelete, ours))
        val syncedEdit = Content(syncMessage = SyncMessage(sent = SyncMessage.Sent(
            timestamp = at + 8, editMessage = org.whispersystems.signalservice.internal.push.EditMessage(
                targetSentTimestamp = at, dataMessage = DataMessage(body = "fixed", timestamp = at + 8)
            )
        )))
        assertEquals("$self:$at", KeptCopies.targetOf(syncedEdit, ours))
        // A plain message points at nothing, and lands as itself.
        assertNull(KeptCopies.targetOf(original, meta))
        assertEquals(originalId, KeptCopies.landsAs(keptFrom(theirs, original)))
        assertEquals("$self:$at", KeptCopies.landsAs(SignalServiceCipherResult(transcript(), metadata())))
        // An event lands as nothing anything could point at, and is no missing message.
        assertNull(KeptCopies.landsAs(keptFrom(theirs, reaction)))
        assertNull(KeptCopies.noteFor(keptFrom(theirs, reaction)))
    }

    private fun waitingStore(targetAttempts: Int = 1): FakeStore = FakeStore(mutableListOf(
        Row(1, keptCopyFrom(theirs, original), attempts = targetAttempts, nextAt = start),
        Row(2, keptCopyFrom(theirs, withdrawal), attempts = 0, waitsFor = originalId),
        Row(3, keptCopyFrom(theirs, edit), attempts = 0, waitsFor = originalId),
        Row(4, keptCopyFrom(theirs, reaction), attempts = 0, waitsFor = originalId)
    ))

    @Test
    fun `what an arriving event does depends on where its message is`() {
        // Still being tried: wait.
        KeptCopies(waitingStore(), BUILD).targets().let {
            assertEquals(setOf(originalId), it.wait)
            assertTrue(it.applyAndKeep.isEmpty())
        }
        // Given up, note not written yet: still wait, so the note does not undo it.
        val unnoted = waitingStore(KeptCopies.MAX_ATTEMPTS)
        KeptCopies(unnoted, BUILD).targets().let {
            assertEquals(setOf(originalId), it.wait)
            assertTrue(it.applyAndKeep.isEmpty())
        }
        // Given up and noted: apply to the note now, and keep it for the message.
        val noted = waitingStore(KeptCopies.MAX_ATTEMPTS).also { it.rows[0].noted = true }
        KeptCopies(noted, BUILD).targets().let {
            assertTrue(it.wait.isEmpty())
            assertEquals(setOf(originalId), it.applyAndKeep)
        }
        // Nothing kept: nothing to check, which is the ordinary case.
        assertTrue(KeptCopies(FakeStore(mutableListOf()), BUILD).targets().isEmpty())
        // The events themselves are never targets.
        assertTrue(KeptCopies(FakeStore(waitingStore().rows.drop(1).toMutableList()), BUILD).targets().isEmpty())
    }

    @Test
    fun `a live event kept for a given-up message runs again if it lands, and goes when it is swept`() {
        // As the receiver leaves it: applied to the note, kept as applied, never tried.
        val store = waitingStore(KeptCopies.MAX_ATTEMPTS)
        store.rows[0].apply { gaveUp = true; notes = 1; noted = true; build = BUILD }
        store.rows.drop(1).forEach { it.waitsFor = KeptCopies.APPLIED + originalId }
        val copies = KeptCopies(store, BUILD)
        // While the message stays given up, nothing more.
        copies.afterFiled(start) { _, _ -> error("applied again while still given up") }
        // Swept with the message's copy: the marks the sweep deletes are exactly theirs.
        assertEquals(listOf(KeptCopies.APPLIED + originalId), KeptCopies.appliedFor(listOf(store.rows[0].copy)))
        assertTrue(KeptCopies.appliedFor(listOf(byteArrayOf(9, 9))).isEmpty())
        // A later build lands it instead: then they run after it, in order, and the withdrawn
        // message is not filed.
        val message = ContentNormalizer.normalize(original, metadataFrom(theirs), self.toString(), null)
        val seen = mutableListOf<String>()
        val ok: (KeptCopies.Kept, SignalServiceCipherResult) -> KeptCopies.Outcome = { _, replay ->
            seen += describe(replay)
            KeptCopies.Outcome.Processed(theirs.toString(), if (describe(replay) == "message") message else null)
        }
        val update = KeptCopies(store, BUILD + 1)
        update.run(start + 1, process = ok)
        assertNull(store.rows[0].filed)
        update.afterFiled(start + 1, process = ok)
        assertEquals(listOf("message", "delete", "edit", "reaction"), seen)
        assertTrue(store.rows.all { it.gone })
    }

    @Test
    fun `a withdrawal, an edit and a reaction wait, then follow their message in the order they came`() {
        val store = waitingStore()
        val seen = mutableListOf<String>()
        val ok: (KeptCopies.Kept, SignalServiceCipherResult) -> KeptCopies.Outcome = { _, replay ->
            seen += describe(replay)
            KeptCopies.Outcome.Processed(theirs.toString(), null)
        }
        val copies = KeptCopies(store, BUILD)
        copies.run(start, process = ok)
        // The run itself touches only the message; the events wait for it to be filed.
        assertEquals(listOf("message"), seen)
        assertEquals(3, copies.afterFiled(start, process = ok).size)
        assertEquals(listOf("message", "delete", "edit", "reaction"), seen)
        assertTrue(store.rows.all { it.gone })
        // And nothing is done twice.
        assertEquals(0, copies.afterFiled(start + 1, process = ok).size)
    }

    @Test
    fun `they keep waiting while their message is still being tried`() {
        val store = waitingStore()
        val copies = KeptCopies(store, BUILD)
        copies.run(start) { _, _ -> KeptCopies.Outcome.Failed }
        copies.afterFiled(start) { _, _ -> error("applied before its message") }
        assertEquals(listOf(originalId, originalId, originalId), store.rows.drop(1).map { it.waitsFor })
    }

    @Test
    fun `when their message is given up they wait for its note, then are applied to it and kept`() {
        val store = waitingStore(targetAttempts = KeptCopies.MAX_ATTEMPTS - 1)
        val copies = KeptCopies(store, BUILD)
        val seen = mutableListOf<String>()
        val ok: (KeptCopies.Kept, SignalServiceCipherResult) -> KeptCopies.Outcome = { _, replay ->
            seen += describe(replay); KeptCopies.Outcome.Processed(theirs.toString(), null)
        }
        copies.run(start) { _, replay -> seen += describe(replay); KeptCopies.Outcome.Failed }
        assertTrue(store.rows[0].gaveUp)
        // The note is written at the end of this drain; nothing is applied before it.
        copies.afterFiled(start) { _, _ -> error("applied before the note") }
        assertEquals(listOf(originalId, originalId, originalId), store.rows.drop(1).map { it.waitsFor })
        // Next drain: noted, so applied to the note in the order they came -- and kept.
        store.rows[0].noted = true
        copies.run(start + 1, process = ok)
        copies.afterFiled(start + 1, process = ok)
        assertEquals(listOf("message", "delete", "edit", "reaction"), seen)
        assertTrue(store.rows.drop(1).all { !it.gone && it.waitsFor == KeptCopies.APPLIED + originalId })
        // Not again while the message stays given up.
        copies.afterFiled(start + 2) { _, _ -> error("applied twice to the note") }
    }

    @Test
    fun `if a later build lands the message after all, they are applied again after it, and a withdrawn one stays withdrawn`() {
        val store = waitingStore(targetAttempts = KeptCopies.MAX_ATTEMPTS)
        store.rows[0].apply { gaveUp = true; notes = 1; noted = true; build = BUILD }
        store.rows.drop(1).forEach { it.waitsFor = KeptCopies.APPLIED + originalId }
        val message = ContentNormalizer.normalize(original, metadataFrom(theirs), self.toString(), null)
        val seen = mutableListOf<String>()
        val ok: (KeptCopies.Kept, SignalServiceCipherResult) -> KeptCopies.Outcome = { _, replay ->
            seen += describe(replay)
            KeptCopies.Outcome.Processed(theirs.toString(), if (describe(replay) == "message") message else null)
        }
        val update = KeptCopies(store, BUILD + 1)
        val done = update.run(start, process = ok)
        // Processed, and not filed: it was withdrawn, so it never shows again, not even briefly.
        assertTrue(store.rows[0].gone)
        assertNull(store.rows[0].filed)
        assertNull(done.single().message)
        // Then everything that pointed at it, again, in order, and done with.
        assertEquals(3, update.afterFiled(start, process = ok).size)
        assertEquals(listOf("message", "delete", "edit", "reaction"), seen)
        assertTrue(store.rows.all { it.gone })
        assertEquals(0, update.afterFiled(start + 1, process = ok).size)
    }

    @Test
    fun `a message nobody withdrew is filed as ever when its events wait for it`() {
        val store = FakeStore(mutableListOf(
            Row(1, keptCopyFrom(theirs, original), attempts = 1, nextAt = start),
            Row(3, keptCopyFrom(theirs, edit), attempts = 0, waitsFor = originalId)
        ))
        val message = ContentNormalizer.normalize(original, metadataFrom(theirs), self.toString(), null)
        KeptCopies(store, BUILD).run(start) { _, _ -> KeptCopies.Outcome.Processed(theirs.toString(), message) }
        assertEquals(message, store.rows[0].filed)
    }

    @Test
    fun `a withdrawal too late to honour does not keep somebody else's message out`() {
        val store = FakeStore(mutableListOf(
            Row(1, keptCopyFrom(theirs, original), attempts = 1, nextAt = start),
            Row(2, keptCopyFrom(theirs, withdrawal), attempts = 0, waitsFor = originalId,
                envelopeAt = at + ContentNormalizer.WITHDRAWAL_WINDOW_MS + 1)
        ))
        val message = ContentNormalizer.normalize(original, metadataFrom(theirs), self.toString(), null)
        KeptCopies(store, BUILD).run(start) { _, _ -> KeptCopies.Outcome.Processed(theirs.toString(), message) }
        assertEquals(message, store.rows[0].filed)
    }

    @Test
    fun `what waits outlasts a restart between its message landing and being applied`() {
        val store = waitingStore()
        val ok: (KeptCopies.Kept, SignalServiceCipherResult) -> KeptCopies.Outcome = { _, _ ->
            KeptCopies.Outcome.Processed(theirs.toString(), null)
        }
        KeptCopies(store, BUILD).run(start, process = ok)
        // The process died before afterFiled. A new one, from the same rows, picks it up.
        assertEquals(3, KeptCopies(store, BUILD).afterFiled(start + 60_000, process = ok).size)
        assertTrue(store.rows.all { it.gone })
    }

    @Test
    fun `an event that fails after its release is retried like any kept copy`() {
        val store = waitingStore()
        val copies = KeptCopies(store, BUILD)
        copies.run(start) { _, _ -> KeptCopies.Outcome.Processed(theirs.toString(), null) }
        copies.afterFiled(start) { _, replay ->
            if (describe(replay) == "edit") KeptCopies.Outcome.Failed else KeptCopies.Outcome.Processed(theirs.toString(), null)
        }
        val editRow = store.rows.single { it.id == 3L }
        assertTrue(!editRow.gone)
        assertEquals(1, editRow.attempts)
        assertNull(editRow.waitsFor)
        assertEquals(start + KeptCopies.backoff(1), editRow.nextAt)
    }

    /** The message given up by [BUILD] and noted, as the drain after its give-up leaves it. */
    private fun givenUpAndNoted() = Row(1, keptCopyFrom(theirs, original), attempts = KeptCopies.MAX_ATTEMPTS).apply {
        gaveUp = true; notes = 1; noted = true; build = BUILD
    }

    @Test
    fun `a kept withdrawal applied to a given-up message's note is kept for it too, and the message stays withdrawn`() {
        // The withdrawal failed when it arrived and is kept itself; its message is given up.
        val store = FakeStore(mutableListOf(
            givenUpAndNoted(),
            Row(2, keptCopyFrom(theirs, withdrawal), attempts = 1, nextAt = start)
        ))
        val message = ContentNormalizer.normalize(original, metadataFrom(theirs), self.toString(), null)
        val seen = mutableListOf<String>()
        val ok: (KeptCopies.Kept, SignalServiceCipherResult) -> KeptCopies.Outcome = { _, replay ->
            seen += describe(replay)
            KeptCopies.Outcome.Processed(theirs.toString(), if (describe(replay) == "message") message else null)
        }
        KeptCopies(store, BUILD).run(start, process = ok)
        // Applied to the note, and kept for the message, as a live one is.
        assertEquals(listOf("delete"), seen)
        assertTrue(!store.rows[1].gone)
        assertEquals(KeptCopies.APPLIED + originalId, store.rows[1].waitsFor)
        assertEquals(1, store.rows[1].appliedTimes)
        // A later build lands the message: not filed, and the withdrawal runs again after it.
        val update = KeptCopies(store, BUILD + 1)
        update.run(start + 1, process = ok)
        assertTrue(store.rows[0].gone)
        assertNull(store.rows[0].filed)
        update.afterFiled(start + 1, process = ok)
        assertEquals(listOf("delete", "message", "delete"), seen)
        assertTrue(store.rows.all { it.gone })
    }

    @Test
    fun `an event that fails after its release and then goes through is kept for its message too`() {
        val store = waitingStore(KeptCopies.MAX_ATTEMPTS)
        store.rows[0].apply { gaveUp = true; notes = 1; noted = true; build = BUILD }
        val copies = KeptCopies(store, BUILD)
        // Released to the note; the edit fails and becomes an ordinary kept copy.
        copies.afterFiled(start) { _, replay ->
            if (describe(replay) == "edit") KeptCopies.Outcome.Failed else KeptCopies.Outcome.Processed(theirs.toString(), null)
        }
        val editRow = store.rows.single { it.id == 3L }
        assertNull(editRow.waitsFor)
        // Its retry goes through: applied to the note, and kept like the others.
        copies.run(start + KeptCopies.backoff(1)) { _, _ -> KeptCopies.Outcome.Processed(theirs.toString(), null) }
        assertTrue(!editRow.gone)
        assertTrue(store.rows.drop(1).all { !it.gone && it.waitsFor == KeptCopies.APPLIED + originalId })
    }

    @Test
    fun `a kept event whose given-up message is being tried again by a new build waits for it`() {
        // Both given up by the last build; the new one tries both again.
        val store = FakeStore(mutableListOf(
            givenUpAndNoted(),
            Row(2, keptCopyFrom(theirs, withdrawal), attempts = KeptCopies.MAX_ATTEMPTS).apply { gaveUp = true; build = BUILD }
        ))
        val update = KeptCopies(store, BUILD + 1)
        // The message fails once more: the withdrawal is not spent on its note meanwhile.
        update.run(start) { _, replay ->
            if (describe(replay) == "message") KeptCopies.Outcome.Failed else error("applied before its message")
        }
        assertEquals(originalId, store.rows[1].waitsFor)
        update.afterFiled(start) { _, _ -> error("applied before its message") }
        // It lands at the next try: not filed, and the withdrawal follows it.
        val message = ContentNormalizer.normalize(original, metadataFrom(theirs), self.toString(), null)
        val seen = mutableListOf<String>()
        val ok: (KeptCopies.Kept, SignalServiceCipherResult) -> KeptCopies.Outcome = { _, replay ->
            seen += describe(replay)
            KeptCopies.Outcome.Processed(theirs.toString(), if (describe(replay) == "message") message else null)
        }
        update.run(start + KeptCopies.backoff(2), process = ok)
        assertNull(store.rows[0].filed)
        update.afterFiled(start + KeptCopies.backoff(2), process = ok)
        assertEquals(listOf("message", "delete"), seen)
        assertTrue(store.rows.all { it.gone })
    }

    @Test
    fun `a kept withdrawal keeps its message out even when both are tried in the same run`() {
        val store = FakeStore(mutableListOf(
            Row(1, keptCopyFrom(theirs, original), attempts = 1, nextAt = start),
            Row(2, keptCopyFrom(theirs, withdrawal), attempts = 1, nextAt = start)
        ))
        val message = ContentNormalizer.normalize(original, metadataFrom(theirs), self.toString(), null)
        val copies = KeptCopies(store, BUILD)
        copies.run(start) { _, replay ->
            KeptCopies.Outcome.Processed(theirs.toString(), if (describe(replay) == "message") message else null)
        }
        assertNull(store.rows[0].filed)
    }

    // --- A note written before the copy existed; an identity change mid-run --------------

    @Test
    fun `an early note for our own synced send is taken out, since the message goes to its recipient`() {
        val copy = SignalServiceCipherResult(transcript(), metadata())
        val stale = KeptCopies.staleEarlyNote(self.toString(), null, at, copy, self.toString(), null)
        assertEquals(KeptCopies.EarlyNote("$self:$at", "direct:$self"), stale)
    }

    @Test
    fun `an early note already where the message goes is left for the message to replace`() {
        // Somebody else's one-to-one message.
        assertNull(KeptCopies.staleEarlyNote(theirs.toString(), null, at, keptFrom(theirs, original), self.toString(), null))
        // A group message: filed by the group either way.
        val master = ByteArray(32) { (it + 1).toByte() }
        val group = org.whispersystems.signalservice.internal.push.GroupContextV2(masterKey = master.toByteString(), revision = 0)
        val inGroup = incoming(DataMessage(body = "Running late", timestamp = at, groupV2 = group))
        val groupId = java.util.Base64.getDecoder().decode(ContentNormalizer.groupIdForCheck(master))
        assertNull(KeptCopies.staleEarlyNote(theirs.toString(), groupId, at, keptFrom(theirs, inGroup), self.toString(), null))
        // Our own note to self, synced: Note to Self either way.
        val toSelf = Content(syncMessage = SyncMessage(sent = SyncMessage.Sent(
            destinationServiceIdBinary = self.toByteString(), timestamp = at,
            message = DataMessage(body = "Buy bread", timestamp = at)
        )))
        assertNull(KeptCopies.staleEarlyNote(self.toString(), null, at, SignalServiceCipherResult(toSelf, metadata()), self.toString(), null))
    }

    @Test
    fun `an early note for what turns out not to be a message is taken out`() {
        val readSync = Content(syncMessage = SyncMessage(read = listOf(SyncMessage.Read(senderAciBinary = theirs.toByteString(), timestamp = at))))
        assertEquals(
            KeptCopies.EarlyNote("$self:$at", "direct:$self"),
            KeptCopies.staleEarlyNote(self.toString(), null, at, SignalServiceCipherResult(readSync, metadata()), self.toString(), null)
        )
    }

    @Test
    fun `a copy that changes the account's identity ends the run there`() {
        val store = FakeStore(mutableListOf(
            Row(1, kept(transcript()), attempts = 1, nextAt = start),
            Row(2, kept(transcript()), attempts = 1, nextAt = start)
        ))
        var changed = false
        val tried = mutableListOf<Long>()
        KeptCopies(store, BUILD).run(start, stop = { changed }) { kept, _ ->
            tried += kept.id
            changed = true
            KeptCopies.Outcome.Processed(self.toString(), null)
        }
        assertEquals(listOf(1L), tried)
        // The second waits, untouched, for the new connection.
        assertTrue(!store.rows[1].gone)
        assertEquals(1, store.rows[1].attempts)
    }

    @Test
    fun `and so does an event applied after filing`() {
        val store = waitingStore()
        val ok: (KeptCopies.Kept, SignalServiceCipherResult) -> KeptCopies.Outcome = { _, _ ->
            KeptCopies.Outcome.Processed(theirs.toString(), null)
        }
        val copies = KeptCopies(store, BUILD)
        copies.run(start, process = ok)
        var changed = false
        val done = copies.afterFiled(start, stop = { changed }) { _, _ -> changed = true; KeptCopies.Outcome.Processed(theirs.toString(), null) }
        assertEquals(1, done.size)
        assertEquals(2, store.rows.count { !it.gone })
    }

    private companion object {
        const val BUILD = 10350053
        const val CiphertextMessageType = CiphertextMessage.WHISPER_TYPE
    }
}
