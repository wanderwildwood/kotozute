package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.signal.BridgeMessage
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.archive.proto.AccountData
import org.signal.archive.proto.BackupInfo
import org.signal.archive.proto.Chat
import org.signal.archive.proto.ChatItem
import org.signal.archive.proto.ChatUpdateMessage
import org.signal.archive.proto.Contact
import org.signal.archive.proto.FilePointer
import org.signal.archive.proto.Frame
import org.signal.archive.proto.Group
import org.signal.archive.proto.MessageAttachment
import org.signal.archive.proto.Quote
import org.signal.archive.proto.Recipient
import org.signal.archive.proto.RemoteDeletedMessage
import org.signal.archive.proto.Self
import org.signal.archive.proto.StandardMessage
import org.signal.archive.proto.Text
import org.signal.archive.proto.ViewOnceMessage
import org.signal.core.models.ServiceId
import java.io.File
import java.io.InputStream
import java.security.SecureRandom

/**
 * The history a primary transfers at linking: read, decrypted, and filed through the Desktop
 * importer.
 *
 * The archive is built by [TransferArchiveFixture], the way upstream's writer builds one.
 * Everyone in it is made up.
 */
class TransferArchiveTest {

    @get:Rule val temp = TemporaryFolder()

    private val selfAci = "00000000-0000-4000-8000-000000000001"
    private val adaAci = "11111111-1111-4111-8111-111111111111"
    private val tomasAci = "22222222-2222-4222-8222-222222222222"
    private val masterKey = ByteArray(32) { (it + 7).toByte() }
    private val groupKey = "group:" + ContentNormalizer.groupIdForCheck(masterKey)

    private val ephemeralKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val aci = ServiceId.ACI.parseOrThrow(selfAci)

    private fun uuidBytes(uuid: String) =
        uuid.replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray().toByteString()

    // --- the people and conversations ---------------------------------------------------

    private val recipients = listOf(
        Frame(account = AccountData()),
        Frame(recipient = Recipient(id = 1, self = Self())),
        Frame(
            recipient = Recipient(
                id = 2,
                contact = Contact(
                    aci = uuidBytes(adaAci),
                    e164 = 15550100001,
                    profileGivenName = "Ada",
                    profileFamilyName = "Whitlock"
                )
            )
        ),
        Frame(
            recipient = Recipient(
                id = 3,
                contact = Contact(aci = uuidBytes(tomasAci), e164 = 15550100002, systemGivenName = "Tomas", systemFamilyName = "Reyes")
            )
        ),
        Frame(
            recipient = Recipient(
                id = 4,
                group = Group(
                    masterKey = masterKey.toByteString(),
                    snapshot = Group.GroupSnapshot(title = Group.GroupAttributeBlob(title = "Allotment"))
                )
            )
        ),
        Frame(chat = Chat(id = 10, recipientId = 2)),
        Frame(chat = Chat(id = 11, recipientId = 4))
    )

    private fun text(body: String) = StandardMessage(text = Text(body = body))

    private fun incoming(chat: Long, author: Long, sent: Long, message: StandardMessage, read: Boolean = true) =
        Frame(
            chatItem = ChatItem(
                chatId = chat, authorId = author, dateSent = sent,
                incoming = ChatItem.IncomingMessageDetails(dateReceived = sent + 1, read = read),
                standardMessage = message
            )
        )

    private fun outgoing(chat: Long, sent: Long, message: StandardMessage) =
        Frame(
            chatItem = ChatItem(
                chatId = chat, authorId = 1, dateSent = sent,
                outgoing = ChatItem.OutgoingMessageDetails(dateReceived = sent),
                standardMessage = message
            )
        )

    private val items = listOf(
        incoming(10, 2, 1_000, text("Are the seedlings in?")),
        outgoing(10, 2_000, StandardMessage(text = Text(body = "This weekend"), quote = Quote(targetSentTimestamp = 1_000, authorId = 2))),
        // An edit: filed under the first timestamp, with the latest text.
        Frame(
            chatItem = ChatItem(
                chatId = 10, authorId = 2, dateSent = 3_500,
                incoming = ChatItem.IncomingMessageDetails(read = false),
                standardMessage = text("Bring the trowel, please"),
                revisions = listOf(
                    ChatItem(chatId = 10, authorId = 2, dateSent = 3_000, incoming = ChatItem.IncomingMessageDetails(), standardMessage = text("Bring the trowel"))
                )
            )
        ),
        // An attachment with nothing behind it: kept, not downloaded.
        incoming(
            11, 3, 4_000,
            StandardMessage(
                attachments = listOf(
                    MessageAttachment(
                        pointer = FilePointer(
                            contentType = "image/jpeg",
                            fileName = "beds.jpg",
                            locatorInfo = FilePointer.LocatorInfo(size = 2048)
                        )
                    )
                )
            )
        ),
        incoming(11, 3, 5_000, text("Water at six")),
        // Skipped, each counted.
        Frame(chatItem = ChatItem(chatId = 11, authorId = 3, dateSent = 6_000, incoming = ChatItem.IncomingMessageDetails(), remoteDeletedMessage = RemoteDeletedMessage())),
        Frame(chatItem = ChatItem(chatId = 11, authorId = 3, dateSent = 7_000, directionless = ChatItem.DirectionlessMessageDetails(), updateMessage = ChatUpdateMessage())),
        Frame(chatItem = ChatItem(chatId = 10, authorId = 2, dateSent = 8_000, incoming = ChatItem.IncomingMessageDetails(), viewOnceMessage = ViewOnceMessage())),
        Frame(chatItem = ChatItem(chatId = 10, authorId = 2, dateSent = 9_000, sms = true, incoming = ChatItem.IncomingMessageDetails(), standardMessage = text("An old SMS")))
    )

    // --- the archive ------------------------------------------------------------------

    private fun archive(
        frames: List<Frame> = recipients + items,
        key: ByteArray = ephemeralKey,
        header: BackupInfo = BackupInfo(version = 1, backupTimeMs = 10_000)
    ): File = TransferArchiveFixture.write(temp.newFile(), key, aci, frames, header)

    private fun reader(file: File, key: ByteArray = ephemeralKey) =
        TransferArchiveReader.forLinking(key, aci, file)

    // --- the sink, as SignalHistoryImporterTest's ----------------------------------------

    private class Sink(private val held: MutableSet<String> = mutableSetOf()) : SignalHistoryImporter.Sink {
        val inserted = mutableListOf<BridgeMessage>()
        val names = mutableMapOf<String, String>()

        override fun groupThreadsByTitle(): Map<String, String> = emptyMap()

        override fun insert(messages: List<BridgeMessage>): Int {
            var new = 0
            messages.forEach { if (held.add(it.id)) { inserted += it; new++ } }
            return new
        }

        override fun nameThreadIfUnnamed(threadKey: String, title: String) {
            names.putIfAbsent(threadKey, title)
        }

        override fun storeAttachment(name: String, open: () -> InputStream): String? = null
    }

    private fun import(file: File, sink: Sink = Sink()): Pair<SignalHistoryImporter.Stats, Sink> {
        val r = reader(file).also { it.verify() }
        val stats = SignalHistoryImporter(
            source = TransferArchiveSource(r),
            sink = sink,
            selfUuid = selfAci,
            selfNumber = "+15550100000",
            now = { 1_700_000_000_000 }
        ).run()
        return stats to sink
    }

    // --- tests ------------------------------------------------------------------------

    @Test
    fun `the frames come back as they were written`() {
        val frames = reader(archive()).also { it.verify() }.read { header, frames ->
            assertEquals(1L, header?.version)
            frames.toList()
        }
        assertEquals(recipients + items, frames)
    }

    @Test
    fun `another key does not open it`() {
        val other = ByteArray(32) { 9 }
        try {
            reader(archive(), key = other).verify()
            fail("a wrong key passed the MAC")
        } catch (expected: TransferArchiveReader.Unusable) {
        }
    }

    @Test
    fun `one changed byte fails the MAC`() {
        val file = archive()
        val bytes = file.readBytes()
        bytes[40] = (bytes[40].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        try {
            reader(file).verify()
            fail("a damaged archive passed the MAC")
        } catch (expected: TransferArchiveReader.Unusable) {
        }
    }

    @Test
    fun `a file too short to be an archive is unusable, not a crash`() {
        val file = temp.newFile().apply { writeBytes(ByteArray(20)) }
        try {
            reader(file).verify()
            fail("twenty bytes passed")
        } catch (expected: TransferArchiveReader.Unusable) {
        }
    }

    @Test
    fun `a newer format is refused by version`() {
        val file = archive(header = BackupInfo(version = 2))
        try {
            TransferArchiveSource(reader(file)).readLines { it.toList() }
            fail("version 2 was read")
        } catch (expected: TransferArchiveSource.NewerVersion) {
            assertEquals(2L, expected.version)
        }
    }

    @Test
    fun `one to one messages land in the contact's thread, both directions`() {
        val (_, sink) = import(archive())
        val direct = sink.inserted.filter { it.threadKey == "direct:$adaAci" }.associateBy { it.ts }

        val asked = direct.getValue(1_000)
        assertEquals("Are the seedlings in?", asked.body)
        assertEquals(adaAci, asked.senderUuid)
        assertFalse(asked.outgoing)
        assertEquals("$adaAci:1000", asked.id)
        assertTrue(asked.read)

        val answered = direct.getValue(2_000)
        assertTrue(answered.outgoing)
        // The id a live send of it would have carried, so the two never double.
        assertEquals("$selfAci:2000", answered.id)
        assertEquals(1_000L, answered.quoteTs)
    }

    @Test
    fun `an edit is filed under its first timestamp with its latest text`() {
        val (_, sink) = import(archive())
        val edited = sink.inserted.single { it.body.startsWith("Bring the trowel") }
        assertEquals(3_000L, edited.ts)
        assertEquals("Bring the trowel, please", edited.body)
        assertFalse("unread on the primary, unread here", edited.read)
    }

    @Test
    fun `group messages land in the group the master key derives`() {
        val (_, sink) = import(archive())
        val group = sink.inserted.filter { it.threadKey == groupKey }
        assertEquals(setOf(4_000L, 5_000L), group.map { it.ts }.toSet())
        assertTrue(group.all { it.senderUuid == tomasAci })
        assertTrue(group.all { it.groupMasterKey?.contentEquals(masterKey) == true })
        assertEquals("Allotment", sink.names[groupKey])
        assertEquals("Ada Whitlock", sink.names["direct:$adaAci"])
    }

    @Test
    fun `an attachment arrives as the Desktop import shows a missing one`() {
        val (stats, sink) = import(archive())
        val picture = sink.inserted.single { it.ts == 4_000L }
        val entry = org.json.JSONArray(picture.attachmentsJson).getJSONObject(0)
        assertEquals("", entry.getString("id"))
        assertTrue(entry.getBoolean("pending"))
        assertEquals("image/jpeg", entry.getString("type"))
        assertEquals("beds.jpg", entry.getString("filename"))
        assertEquals(2048L, entry.getLong("size"))
        assertEquals(1, stats.attachmentsLost)
        assertEquals(0, stats.attachments)
    }

    @Test
    fun `what is not a text message is counted, not imported`() {
        val (stats, sink) = import(archive())
        assertEquals(5, stats.messages)
        assertEquals(5, sink.inserted.size)
        assertEquals(1, stats.skippedDeleted)
        // The group change, the view-once message and the old SMS.
        assertEquals(3, stats.skippedEvents)
        assertTrue(sink.inserted.none { it.body == "An old SMS" })
    }

    @Test
    fun `a second import of the same history adds nothing`() {
        val file = archive()
        val held = mutableSetOf<String>()
        import(file, Sink(held))
        val (again, sink) = import(file, Sink(held))
        assertEquals(0, again.messages)
        assertEquals(5, again.alreadyPresent)
        assertTrue(sink.inserted.isEmpty())
    }

    @Test
    fun `frames the importer has no use for make no line`() {
        assertNull(TransferArchiveSource.line(Frame(account = AccountData())))
        assertNull(TransferArchiveSource.line(Frame()))
    }
}
