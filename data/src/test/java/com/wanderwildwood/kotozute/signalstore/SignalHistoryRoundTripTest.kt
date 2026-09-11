package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.signal.BridgeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * What is written out comes back in.
 *
 * These mirror the bridge's round-trip tests, and they are the ones that matter most: an
 * export nobody has read back is a folder of files, not a backup, and every way of losing
 * something here is silent. A conversation reordered, a group of several people flattened
 * into one, two attachments of the same length swapped for each other -- none of it shows
 * until somebody needs the copy, which is exactly when they cannot check it.
 */
class SignalHistoryRoundTripTest {

    @get:Rule val temp = TemporaryFolder()

    private val selfAci = "00000000-0000-4000-8000-000000000000"
    private val ada = "11111111-1111-4111-8111-111111111111"
    private val grace = "22222222-2222-4222-8222-222222222222"

    /** Everything the exporter reads, held in memory. */
    private class Store : SignalHistoryExporter.Source {
        val threads = mutableListOf<SignalHistoryExporter.Source.Thread>()
        val messages = mutableMapOf<String, MutableList<SignalHistoryExporter.Source.Message>>()
        val files = mutableMapOf<String, ByteArray>()

        override fun threads() = threads.toList()

        override fun eachMessage(
            threadKey: String,
            consume: (SignalHistoryExporter.Source.Message) -> Unit
        ) {
            messages[threadKey].orEmpty().sortedBy { it.ts }.forEach(consume)
        }

        override fun attachment(id: String): SignalHistoryExporter.Source.Attachment? =
            files[id]?.let { bytes ->
                SignalHistoryExporter.Source.Attachment(bytes.size.toLong()) {
                    ByteArrayInputStream(bytes)
                }
            }

        fun thread(key: String, title: String = "", number: String = "") {
            threads += SignalHistoryExporter.Source.Thread(key, title, number)
        }

        fun message(
            key: String,
            ts: Long,
            body: String = "",
            sender: String = "",
            outgoing: Boolean = false,
            read: Boolean = true,
            quoteTs: Long = 0,
            expiresAt: Long = 0,
            expiresInSeconds: Long = 0,
            attachmentsJson: String = ""
        ) {
            messages.getOrPut(key) { mutableListOf() } += SignalHistoryExporter.Source.Message(
                ts, sender, outgoing, body, read, quoteTs, expiresAt, expiresInSeconds, attachmentsJson
            )
        }
    }

    /** Everything the importer writes, held in memory. */
    private class Sink(private val groups: Map<String, String> = emptyMap()) :
        SignalHistoryImporter.Sink {
        val inserted = mutableListOf<BridgeMessage>()
        val names = mutableMapOf<String, String>()
        val attachments = mutableMapOf<String, ByteArray>()

        override fun groupThreadsByTitle(): Map<String, String> = groups

        override fun insert(messages: List<BridgeMessage>): Int {
            val held = inserted.map { it.id }.toSet()
            val new = messages.filter { it.id !in held }
            inserted += new
            return new.size
        }

        override fun nameThreadIfUnnamed(threadKey: String, title: String) {
            names.putIfAbsent(threadKey, title)
        }

        override fun storeAttachment(name: String, open: () -> InputStream): String? {
            attachments[name] = open().use { it.readBytes() }
            // As the store does: a name that is already plain is kept, so a copy written by
            // a bridge comes back under the ids its messages already hold.
            return name
        }
    }

    private fun roundTrip(
        store: Store,
        sink: Sink = Sink(),
        now: Long = 1_700_000_000_000
    ): Pair<SignalHistoryExporter.Stats, Sink> {
        val folder = temp.newFolder()
        val exported = SignalHistoryExporter(
            source = store,
            destination = DirectoryExportDestination(folder),
            selfUuid = selfAci
        ).run()

        SignalHistoryImporter(
            source = DirectoryExportSource(folder),
            sink = sink,
            selfUuid = selfAci,
            selfNumber = "+15559998888",
            now = { now }
        ).run()

        return exported to sink
    }

    @Test
    fun `a conversation comes back with both directions and its name`() {
        val store = Store()
        val key = "direct:$ada"
        store.thread(key, title = "Ada Lovelace", number = "+15550001111")
        store.message(key, ts = 1_699_000_000_000, body = "hello", sender = ada)
        store.message(key, ts = 1_699_000_001_000, body = "hi back", sender = selfAci, outgoing = true)

        val (exported, sink) = roundTrip(store)

        assertEquals(2, exported.messages)
        assertEquals(1, exported.threads)
        assertEquals(2, sink.inserted.size)
        assertTrue(sink.inserted.all { it.threadKey == key })
        assertEquals("hello", sink.inserted.first { !it.outgoing }.body)
        assertEquals("hi back", sink.inserted.first { it.outgoing }.body)
        assertEquals("Ada Lovelace", sink.names[key])
    }

    @Test
    fun `an outgoing message is still ours after the round trip`() {
        val store = Store()
        val key = "direct:$ada"
        store.thread(key, title = "Ada")
        store.message(key, ts = 1_699_000_000_000, body = "mine", sender = selfAci, outgoing = true)

        val (_, sink) = roundTrip(store)

        // Written against the self recipient and read back as the account's own, or a
        // history comes back with every sent message attributed to whoever it was sent to.
        assertTrue(sink.inserted.single().outgoing)
        assertEquals(selfAci, sink.inserted.single().senderUuid)
    }

    @Test
    fun `a group keeps each of its senders apart`() {
        val store = Store()
        val key = "group:abc123"
        store.thread(key, title = "Trip")
        store.message(key, ts = 1_699_000_000_000, body = "from ada", sender = ada)
        store.message(key, ts = 1_699_000_001_000, body = "from grace", sender = grace)
        store.message(key, ts = 1_699_000_002_000, body = "from me", sender = selfAci, outgoing = true)

        // The export writes the thread key itself, so the group needs no title match.
        val (_, sink) = roundTrip(store, Sink())

        assertEquals(3, sink.inserted.size)
        assertTrue(sink.inserted.all { it.threadKey == key })
        assertEquals(ada, sink.inserted.first { it.body == "from ada" }.senderUuid)
        assertEquals(grace, sink.inserted.first { it.body == "from grace" }.senderUuid)
        assertTrue(sink.inserted.first { it.body == "from me" }.outgoing)
    }

    @Test
    fun `a group is placed by its own key, not by a title that happens to match`() {
        val store = Store()
        val key = "group:abc123"
        store.thread(key, title = "Trip")
        store.message(key, ts = 1_699_000_000_000, body = "in the group", sender = ada)

        // Nothing in the sink knows a group called "Trip"; the key in the file is enough.
        val (_, sink) = roundTrip(store, Sink(groups = emptyMap()))

        assertEquals(key, sink.inserted.single().threadKey)
    }

    @Test
    fun `a thread keyed on a number survives`() {
        val store = Store()
        val key = "direct:+15550001111"
        store.thread(key, title = "Ada", number = "+15550001111")
        store.message(key, ts = 1_699_000_000_000, body = "no service id here", sender = "")

        val (_, sink) = roundTrip(store)

        // Writing a number into the service-id field loses the conversation: it is refused
        // as unreadable on the way back in, and a thread with neither identifier has nowhere
        // to go.
        assertEquals(key, sink.inserted.single().threadKey)
    }

    @Test
    fun `two attachments of the same length are not swapped for each other`() {
        val store = Store()
        val key = "direct:$ada"
        store.thread(key, title = "Ada")
        store.files["one"] = byteArrayOf(1, 1, 1, 1, 1)
        store.files["two"] = byteArrayOf(2, 2, 2, 2, 2)
        store.message(
            key, ts = 1_699_000_000_000, body = "first", sender = ada,
            attachmentsJson = """[{"id":"one","type":"image/png","filename":"a.png","size":5,"pending":false}]"""
        )
        store.message(
            key, ts = 1_699_000_001_000, body = "second", sender = ada,
            attachmentsJson = """[{"id":"two","type":"image/png","filename":"b.png","size":5,"pending":false}]"""
        )

        val (exported, sink) = roundTrip(store)

        assertEquals(2, exported.attachments)
        // Matched by name, not by size: on size alone both are ambiguous and both are lost.
        assertTrue(sink.attachments["one"]!!.contentEquals(byteArrayOf(1, 1, 1, 1, 1)))
        assertTrue(sink.attachments["two"]!!.contentEquals(byteArrayOf(2, 2, 2, 2, 2)))
        val first = org.json.JSONArray(sink.inserted.first { it.body == "first" }.attachmentsJson)
        assertEquals("one", first.getJSONObject(0).getString("id"))
    }

    @Test
    fun `a message whose attachment is no longer on the phone is still in the copy`() {
        val store = Store()
        val key = "direct:$ada"
        store.thread(key, title = "Ada")
        // A reference with no file: never downloaded, or gone since.
        store.message(
            key, ts = 1_699_000_000_000, sender = ada,
            attachmentsJson = """[{"id":"","type":"image/png","filename":"gone.png","size":9,"pending":true}]"""
        )

        val (exported, sink) = roundTrip(store)

        assertEquals(1, exported.missing)
        // Dropping the reference would leave a message with nothing in it, and an empty
        // message is discarded on the way back in -- so the message itself would vanish from
        // the backup rather than merely its picture.
        assertEquals(1, sink.inserted.size)
        assertNotNull(org.json.JSONArray(sink.inserted.single().attachmentsJson).getJSONObject(0))
    }

    @Test
    fun `an attachment id that is not a plain name is refused rather than followed`() {
        val store = Store()
        val key = "direct:$ada"
        store.thread(key, title = "Ada")
        store.message(
            key, ts = 1_699_000_000_000, body = "look", sender = ada,
            attachmentsJson = """[{"id":"../../shared_prefs/keys.xml","type":"text/xml","filename":"k","size":4}]"""
        )

        val (exported, _) = roundTrip(store)

        // The id comes off the wire in the sender's own pointer, so it is theirs to choose.
        assertEquals(0, exported.attachments)
        assertEquals(1, exported.missing)
    }

    @Test
    fun `a reply keeps what it points at`() {
        val store = Store()
        val key = "direct:$ada"
        store.thread(key, title = "Ada")
        store.message(key, ts = 1_699_000_000_000, body = "the question", sender = ada)
        store.message(key, ts = 1_699_000_001_000, body = "the answer", sender = selfAci,
            outgoing = true, quoteTs = 1_699_000_000_000)

        val (_, sink) = roundTrip(store)

        assertEquals(1_699_000_000_000, sink.inserted.first { it.body == "the answer" }.quoteTs)
    }

    @Test
    fun `an unread message comes back unread`() {
        val store = Store()
        val key = "direct:$ada"
        store.thread(key, title = "Ada")
        store.message(key, ts = 1_699_000_000_000, body = "waiting", sender = ada, read = false)
        store.message(key, ts = 1_699_000_001_000, body = "seen", sender = ada, read = true)

        val (_, sink) = roundTrip(store)

        assertTrue(!sink.inserted.first { it.body == "waiting" }.read)
        assertTrue(sink.inserted.first { it.body == "seen" }.read)
    }

    @Test
    fun `a disappearing message keeps its deadline rather than being renewed`() {
        val store = Store()
        val key = "direct:$ada"
        val now = 1_700_000_000_000
        store.thread(key, title = "Ada")
        store.message(
            key, ts = 1_699_999_000_000, body = "for a day", sender = ada,
            expiresAt = now + 3_600_000, expiresInSeconds = 86_400
        )

        val (_, sink) = roundTrip(store, now = now)

        // Round-tripping must not restart the clock: a backup taken and restored repeatedly
        // would otherwise keep a disappearing message alive for ever.
        assertEquals(now + 3_600_000, sink.inserted.single().expiresAt)
    }

    @Test
    fun `a message with no timestamp does not stop the copy`() {
        val store = Store()
        val key = "direct:$ada"
        store.thread(key, title = "Ada")
        store.message(key, ts = 0, body = "no timestamp", sender = ada)
        store.message(key, ts = 1_699_000_000_000, body = "ordinary", sender = ada)

        val (exported, _) = roundTrip(store)

        assertEquals(2, exported.messages)
    }

    @Test
    fun `messages sharing a timestamp both survive`() {
        val store = Store()
        val key = "group:abc123"
        store.thread(key, title = "Trip")
        // Ordinary in a group: two people writing in the same millisecond. They must not be
        // one message on the way back, which is what happens if both are keyed on the same
        // author and timestamp.
        store.message(key, ts = 1_699_000_000_000, body = "from ada", sender = ada)
        store.message(key, ts = 1_699_000_000_000, body = "from grace", sender = grace)

        val (exported, sink) = roundTrip(store)

        assertEquals(2, exported.messages)
        assertEquals(2, sink.inserted.size)
    }

    @Test
    fun `a long history comes back whole`() {
        val store = Store()
        val key = "direct:$ada"
        store.thread(key, title = "Ada")
        repeat(1_500) { index ->
            store.message(key, ts = 1_699_000_000_000 + index * 1_000L, body = "m$index", sender = ada)
        }

        val (exported, sink) = roundTrip(store)

        assertEquals(1_500, exported.messages)
        assertEquals(1_500, sink.inserted.size)
    }

    /** The same journey, through the lock a copy written by this app carries. */
    private fun lockedRoundTrip(
        store: Store,
        sink: Sink = Sink(),
        key: String = SignalBackupCrypto.newKey(),
        openWith: String = key
    ): Pair<SignalHistoryExporter.Stats, Sink> {
        val folder = temp.newFolder()
        val destination = EncryptedExportDestination(DirectoryExportDestination(folder), key)
        destination.writeMeta()
        val exported = SignalHistoryExporter(store, destination, selfAci).run()

        val plain = DirectoryExportSource(folder)
        val meta = org.json.JSONObject(plain.meta()!!)
        val salt = java.util.Base64.getDecoder().decode(meta.getString("salt"))
        SignalHistoryImporter(
            source = EncryptedExportSource(plain, openWith, salt),
            sink = sink,
            selfUuid = selfAci,
            selfNumber = "+15559998888",
            now = { 1_700_000_000_000 }
        ).run()

        return exported to sink
    }

    private fun oneConversation(): Store = Store().apply {
        val key = "direct:$ada"
        thread(key, title = "Ada Lovelace", number = "+15550001111")
        message(key, ts = 1_699_000_000_000, body = "hello", sender = ada)
        message(key, ts = 1_699_000_001_000, body = "hi back", sender = selfAci, outgoing = true)
        files["one"] = byteArrayOf(9, 8, 7, 6, 5)
        message(
            key, ts = 1_699_000_002_000, body = "a picture", sender = ada,
            attachmentsJson = """[{"id":"one","type":"image/png","filename":"a.png","size":5,"pending":false}]"""
        )
    }

    @Test
    fun `a locked copy comes back whole when it is opened with its key`() {
        val (exported, sink) = lockedRoundTrip(oneConversation())

        assertEquals(3, exported.messages)
        assertEquals(3, sink.inserted.size)
        assertEquals("Ada Lovelace", sink.names["direct:$ada"])
        // Through the lock as well as the format: the attachment's bytes are the bytes.
        assertTrue(sink.attachments["one"]!!.contentEquals(byteArrayOf(9, 8, 7, 6, 5)))
    }

    @Test
    fun `a locked copy is not readable with the wrong key`() {
        val thrown = runCatching {
            lockedRoundTrip(
                oneConversation(),
                key = "111111111111111111111111111111",
                openWith = "222222222222222222222222222222"
            )
        }.exceptionOrNull()

        assertNotNull(thrown)
    }

    @Test
    fun `the records of a locked copy are not readable off the disk`() {
        val folder = temp.newFolder()
        val destination = EncryptedExportDestination(
            DirectoryExportDestination(folder), SignalBackupCrypto.newKey()
        )
        destination.writeMeta()
        SignalHistoryExporter(oneConversation(), destination, selfAci).run()

        val onDisk = java.io.File(folder, "main.jsonl").readBytes().decodeToString()
        // The thing this whole change exists for: a copy in a shared folder is not a
        // readable transcript of somebody's conversations.
        assertFalse(onDisk.contains("hello"))
        assertFalse(onDisk.contains("Ada Lovelace"))
        assertFalse(onDisk.contains("direct:"))
        assertFalse(java.io.File(folder, "files/one").readBytes().contentEquals(byteArrayOf(9, 8, 7, 6, 5)))
    }

    @Test
    fun `a locked copy says what it is without giving anything away`() {
        val folder = temp.newFolder()
        val destination = EncryptedExportDestination(
            DirectoryExportDestination(folder), SignalBackupCrypto.newKey()
        )
        destination.writeMeta()
        SignalHistoryExporter(oneConversation(), destination, selfAci).run()

        // The header is how an importer knows to ask for a key rather than failing at the
        // first line. It carries the salt, which is not a secret, and nothing else.
        val meta = org.json.JSONObject(DirectoryExportSource(folder).meta()!!)
        assertEquals(1, meta.getInt("kotozuteBackup"))
        assertEquals("hkdf-sha256", meta.getString("kdf"))
        assertTrue(meta.getString("salt").isNotBlank())
        assertFalse(meta.has("key"))
    }

    @Test
    fun `a Signal Desktop export is still read without any key at all`() {
        // The other half of the promise: what people arrive with is plaintext, and it has to
        // keep working next to a format that is not.
        val store = oneConversation()
        val (_, sink) = roundTrip(store)

        assertEquals(3, sink.inserted.size)
    }

    @Test
    fun `restoring a copy over what is already here changes nothing`() {
        val store = Store()
        val key = "direct:$ada"
        store.thread(key, title = "Ada")
        store.message(key, ts = 1_699_000_000_000, body = "hello", sender = ada)

        val sink = Sink()
        roundTrip(store, sink)
        val (_, again) = roundTrip(store, sink)

        assertEquals(1, again.inserted.size)
    }
}
