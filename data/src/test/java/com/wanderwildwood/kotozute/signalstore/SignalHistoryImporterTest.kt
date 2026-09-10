package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.signal.BridgeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

/**
 * Reading a Signal export.
 *
 * These mirror the bridge's `import_test.go` case for case. The rules were arrived at against
 * real exports, and each one is a message that would otherwise be lost, duplicated, or put in
 * the wrong conversation -- none of which announces itself. Now that the import runs on the
 * phone rather than on a machine with an operator at it, there is nobody watching a count go
 * past to catch it.
 */
class SignalHistoryImporterTest {

    @get:Rule val temp = TemporaryFolder()

    private val selfAci = "00000000-0000-4000-8000-000000000000"
    private val selfNumber = "+15559998888"
    private val theirAci = "11111111-1111-4111-8111-111111111111"

    /** The same identity as [theirAci], written the way an export writes it. */
    private val theirAciBase64 = java.util.Base64.getEncoder().encodeToString(
        theirAci.replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    )

    private class Sink(
        private val groups: Map<String, String> = emptyMap(),
        private val held: MutableSet<String> = mutableSetOf()
    ) : SignalHistoryImporter.Sink {
        val inserted = mutableListOf<BridgeMessage>()
        val names = mutableMapOf<String, String>()
        val attachments = mutableMapOf<String, ByteArray>()

        override fun groupThreadsByTitle(): Map<String, String> = groups

        override fun insert(messages: List<BridgeMessage>): Int {
            var new = 0
            messages.forEach { message ->
                if (held.add(message.id)) {
                    inserted += message
                    new++
                }
            }
            return new
        }

        override fun nameThreadIfUnnamed(threadKey: String, title: String) {
            names.putIfAbsent(threadKey, title)
        }

        override fun storeAttachment(name: String, open: () -> InputStream): String? {
            attachments["import-$name"] = open().use { it.readBytes() }
            return "import-$name"
        }
    }

    private fun export(vararg lines: String): File {
        val dir = temp.newFolder()
        File(dir, "main.jsonl").writeText(lines.joinToString("\n") + "\n")
        return dir
    }

    private fun import(
        dir: File,
        sink: Sink = Sink(),
        now: Long = 1_700_000_000_000
    ): Pair<SignalHistoryImporter.Stats, Sink> {
        val stats = SignalHistoryImporter(
            source = DirectoryExportSource(dir),
            sink = sink,
            selfUuid = selfAci,
            selfNumber = selfNumber,
            now = { now }
        ).run()
        return stats to sink
    }

    private fun minimalExport(): File = export(
        """{"account":{"givenName":"Me"}}""",
        """{"recipient":{"id":"1","self":{}}}""",
        """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64","e164":"+15550001111",""" +
            """"systemGivenName":"Ada","systemFamilyName":"Lovelace"}}}""",
        """{"chat":{"id":"10","recipientId":"2"}}""",
        """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1699000000000",""" +
            """"incoming":{"dateReceived":"1699000000100","read":true},""" +
            """"standardMessage":{"text":{"body":"hello"}}}}""",
        """{"chatItem":{"chatId":"10","authorId":"1","dateSent":"1699000001000",""" +
            """"outgoing":{"dateReceived":"1699000001100"},""" +
            """"standardMessage":{"text":{"body":"hi back"}}}}""",
        // Not messages: an event, and a tombstone for something deleted for everyone.
        """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1699000002000",""" +
            """"updateMessage":{"simpleUpdate":{"type":"IDENTITY_UPDATE"}}}}""",
        """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1699000003000",""" +
            """"remoteDeletedMessage":{}}}"""
    )

    @Test
    fun `the export's identifier is the one the rest of the app uses`() {
        assertEquals(theirAci, SignalHistoryImporter.canonicalUuid(theirAciBase64))
        assertEquals(theirAci, SignalHistoryImporter.canonicalUuid(theirAci))
        // Unreadable comes back empty so the caller falls back to the number rather than
        // keying a thread on a string it cannot read.
        assertEquals("", SignalHistoryImporter.canonicalUuid("not-an-identifier"))
        assertEquals("", SignalHistoryImporter.canonicalUuid(
            java.util.Base64.getEncoder().encodeToString("short".toByteArray())
        ))
    }

    @Test
    fun `both directions are read, and non-messages are refused`() {
        val (stats, sink) = import(minimalExport())

        assertEquals(2, stats.messages)
        assertEquals(1, stats.skippedDeleted)
        assertEquals(1, stats.skippedEvents)

        val incoming = sink.inserted.first { !it.outgoing }
        assertEquals("direct:$theirAci", incoming.threadKey)
        assertEquals("hello", incoming.body)
        assertEquals(theirAci, incoming.senderUuid)
        assertEquals("import", incoming.source)

        val outgoing = sink.inserted.first { it.outgoing }
        assertEquals("direct:$theirAci", outgoing.threadKey)
        assertEquals(selfAci, outgoing.senderUuid)
    }

    @Test
    fun `an imported thread is named from the export`() {
        val (_, sink) = import(minimalExport())
        assertEquals("Ada Lovelace", sink.names["direct:$theirAci"])
    }

    @Test
    fun `a chat nobody said anything in does not become a conversation`() {
        val (_, sink) = import(
            export(
                """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64","systemGivenName":"Ada"}}}""",
                """{"chat":{"id":"10","recipientId":"2"}}"""
            )
        )
        // A real export carries a chat for every contact ever messaged. Naming those creates
        // them, and an import then adds a screenful of empty conversations above the real ones.
        assertTrue(sink.names.isEmpty())
    }

    @Test
    fun `a message keys the same way a live copy of it would`() {
        val (_, sink) = import(minimalExport())
        val incoming = sink.inserted.first { !it.outgoing }
        // author:timestamp -- the id the live rail builds. Anything else and an import next
        // to live data duplicates every message the two have in common.
        assertEquals("$theirAci:1699000000000", incoming.id)
    }

    @Test
    fun `importing twice imports nothing twice`() {
        val dir = minimalExport()
        val sink = Sink()
        val (first, _) = import(dir, sink)
        val (second, _) = import(dir, sink)

        assertEquals(2, first.messages)
        assertEquals(0, second.messages)
        assertEquals(2, second.alreadyPresent)
        assertEquals(2, sink.inserted.size)
    }

    @Test
    fun `a message whose timer has already run out is not history`() {
        val dir = export(
            """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64"}}}""",
            """{"chat":{"id":"10","recipientId":"2"}}""",
            """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1698000000000",""" +
                """"expireStartDate":"1698000000000","expiresInMs":"86400000",""" +
                """"standardMessage":{"text":{"body":"gone by now"}}}}"""
        )
        val (stats, sink) = import(dir, now = 1_700_000_000_000)

        assertEquals(0, stats.messages)
        assertEquals(1, stats.skippedExpired)
        assertTrue(sink.inserted.isEmpty())
    }

    @Test
    fun `a disappearing message whose timer never started gets one`() {
        val now = 1_700_000_000_000
        val dir = export(
            """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64"}}}""",
            """{"chat":{"id":"10","recipientId":"2"}}""",
            """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1699999999000",""" +
                """"expiresInMs":"86400000",""" +
                """"standardMessage":{"text":{"body":"still ticking"}}}}"""
        )
        val (stats, sink) = import(dir, now = now)

        assertEquals(1, stats.messages)
        // Importing it as "never expires" would turn a disappearing message into a permanent
        // record, which is the outcome this rule exists to avoid.
        assertEquals(now + 86_400_000, sink.inserted.single().expiresAt)
        assertEquals(86_400, sink.inserted.single().expiresInSeconds)
    }

    @Test
    fun `a folder that is not an export says so rather than importing nothing`() {
        val dir = temp.newFolder()
        File(dir, "something-else.txt").writeText("not an export")

        val thrown = runCatching {
            SignalHistoryImporter(DirectoryExportSource(dir), Sink(), selfAci, selfNumber).run()
        }.exceptionOrNull()

        assertTrue(thrown is SignalHistoryImporter.NotAnExport)
    }

    @Test
    fun `a group with no thread on this phone is skipped, not guessed at`() {
        val dir = export(
            """{"recipient":{"id":"3","group":{"masterKey":"AAAA","snapshot":{"title":{"title":"Trip"}}}}}""",
            """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64"}}}""",
            """{"chat":{"id":"11","recipientId":"3"}}""",
            """{"chatItem":{"chatId":"11","authorId":"2","dateSent":"1699000000000",""" +
                """"standardMessage":{"text":{"body":"in the group"}}}}"""
        )
        val (stats, sink) = import(dir)

        assertEquals(0, stats.messages)
        assertEquals(1, stats.skippedUnknownGroup)
        assertTrue(sink.inserted.isEmpty())
    }

    @Test
    fun `a group this phone already has takes the thread it already has`() {
        val dir = export(
            """{"recipient":{"id":"3","group":{"masterKey":"AAAA","snapshot":{"title":{"title":"Trip"}}}}}""",
            """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64"}}}""",
            """{"chat":{"id":"11","recipientId":"3"}}""",
            """{"chatItem":{"chatId":"11","authorId":"2","dateSent":"1699000000000",""" +
                """"standardMessage":{"text":{"body":"in the group"}}}}"""
        )
        val (stats, sink) = import(dir, Sink(groups = mapOf("trip" to "group:abc123")))

        assertEquals(1, stats.messages)
        val message = sink.inserted.single()
        assertEquals("group:abc123", message.threadKey)
        assertEquals("abc123", message.groupId)
    }

    @Test
    fun `an attachment is matched by size and kept`() {
        val dir = temp.newFolder()
        File(dir, "main.jsonl").writeText(
            """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64"}}}""" + "\n" +
                """{"chat":{"id":"10","recipientId":"2"}}""" + "\n" +
                """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1699000000000",""" +
                """"standardMessage":{"attachments":[{"pointer":{"contentType":"image/jpeg",""" +
                """"fileName":"beach.jpg","locatorInfo":{"size":5}}}]}}}""" + "\n"
        )
        File(dir, "files").mkdirs()
        File(dir, "files/ab12cd").writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        val (stats, sink) = import(dir)

        assertEquals(1, stats.messages)
        assertEquals(1, stats.attachments)
        assertNotNull(sink.attachments["import-ab12cd"])
        val recorded = org.json.JSONArray(sink.inserted.single().attachmentsJson).getJSONObject(0)
        assertEquals("import-ab12cd", recorded.getString("id"))
        assertEquals("image/jpeg", recorded.getString("type"))
        assertEquals("beach.jpg", recorded.getString("filename"))
        assertFalse(recorded.getBoolean("pending"))
    }

    @Test
    fun `two attachments of the same length are not guessed between`() {
        val dir = temp.newFolder()
        File(dir, "main.jsonl").writeText(
            """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64"}}}""" + "\n" +
                """{"chat":{"id":"10","recipientId":"2"}}""" + "\n" +
                """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1699000000000",""" +
                """"standardMessage":{"text":{"body":"look"},"attachments":[{"pointer":""" +
                """{"contentType":"image/jpeg","fileName":"a.jpg","locatorInfo":{"size":5}}}]}}}""" + "\n"
        )
        File(dir, "files").mkdirs()
        File(dir, "files/one").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        File(dir, "files/two").writeBytes(byteArrayOf(5, 4, 3, 2, 1))

        val (stats, sink) = import(dir)

        // Neither file is a safe match, so the reference is kept with no id -- which draws as
        // not downloaded. Dropping it instead would take the message with it.
        assertEquals(1, stats.messages)
        assertEquals(0, stats.attachments)
        assertEquals(1, stats.attachmentsLost)
        val recorded = org.json.JSONArray(sink.inserted.single().attachmentsJson).getJSONObject(0)
        assertEquals("", recorded.getString("id"))
        assertTrue(recorded.getBoolean("pending"))
    }

    @Test
    fun `a message whose only content was an unmatched attachment still arrives`() {
        val dir = export(
            """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64"}}}""",
            """{"chat":{"id":"10","recipientId":"2"}}""",
            """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1699000000000",""" +
                """"standardMessage":{"attachments":[{"pointer":{"contentType":"image/jpeg",""" +
                """"fileName":"gone.jpg","locatorInfo":{"size":99}}}]}}}"""
        )
        val (stats, sink) = import(dir)

        assertEquals(1, stats.messages)
        assertEquals(1, stats.attachmentsLost)
        assertEquals("", sink.inserted.single().body)
    }

    @Test
    fun `an item with neither text nor attachment is not a message`() {
        val dir = export(
            """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64"}}}""",
            """{"chat":{"id":"10","recipientId":"2"}}""",
            """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1699000000000",""" +
                """"standardMessage":{}}}"""
        )
        val (stats, _) = import(dir)
        assertEquals(0, stats.messages)
    }

    @Test
    fun `history arrives read, and only an explicit false waits`() {
        val dir = export(
            """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64"}}}""",
            """{"chat":{"id":"10","recipientId":"2"}}""",
            // Signal's own archive carries no incoming object on most items. Reading the zero
            // value there would mark years of history unread: a notification storm.
            """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1699000000000",""" +
                """"standardMessage":{"text":{"body":"old"}}}}""",
            """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1699000001000",""" +
                """"incoming":{"read":false},"standardMessage":{"text":{"body":"new"}}}}"""
        )
        val (_, sink) = import(dir)

        assertTrue(sink.inserted.first { it.body == "old" }.read)
        assertFalse(sink.inserted.first { it.body == "new" }.read)
    }

    @Test
    fun `a quote keeps what it points at`() {
        val dir = export(
            """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64"}}}""",
            """{"chat":{"id":"10","recipientId":"2"}}""",
            """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1699000002000",""" +
                """"standardMessage":{"text":{"body":"agreed"},""" +
                """"quote":{"targetSentTimestamp":"1699000000000"}}}}"""
        )
        val (_, sink) = import(dir)
        assertEquals(1699000000000, sink.inserted.single().quoteTs)
    }

    @Test
    fun `a contact with no aci is still reachable by number`() {
        val dir = export(
            """{"recipient":{"id":"2","contact":{"e164":"+15550001111","systemGivenName":"Ada"}}}""",
            """{"chat":{"id":"10","recipientId":"2"}}""",
            """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1699000000000",""" +
                """"standardMessage":{"text":{"body":"hello"}}}}"""
        )
        val (stats, sink) = import(dir)

        assertEquals(1, stats.messages)
        assertEquals("direct:+15550001111", sink.inserted.single().threadKey)
    }

    @Test
    fun `a message from nobody at all is dropped rather than filed under an empty author`() {
        val dir = export(
            """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64"}}}""",
            """{"chat":{"id":"10","recipientId":"2"}}""",
            """{"chatItem":{"chatId":"10","authorId":"99","dateSent":"1699000000000",""" +
                """"standardMessage":{"text":{"body":"from nowhere"}}}}"""
        )
        val (stats, _) = import(dir)

        assertEquals(0, stats.messages)
        assertEquals(1, stats.skippedNoAuthor)
    }

    @Test
    fun `the profile name stands in when the address book had none`() {
        val dir = export(
            """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64",""" +
                """"profileGivenName":"Ada","profileFamilyName":"L"}}}""",
            """{"chat":{"id":"10","recipientId":"2"}}""",
            """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1699000000000",""" +
                """"standardMessage":{"text":{"body":"hello"}}}}"""
        )
        val (_, sink) = import(dir)
        assertEquals("Ada L", sink.names["direct:$theirAci"])
    }

    @Test
    fun `an empty export is not an error`() {
        val dir = export("")
        val (stats, sink) = import(dir)

        assertEquals(0, stats.messages)
        assertTrue(sink.inserted.isEmpty())
    }

    @Test
    fun `a line that is not JSON at all is stepped over`() {
        val dir = export(
            """{"recipient":{"id":"2","contact":{"aci":"$theirAciBase64"}}}""",
            """{"chat":{"id":"10","recipientId":"2"}}""",
            """not json at all""",
            """{"chatItem":{"chatId":"10","authorId":"2","dateSent":"1699000000000",""" +
                """"standardMessage":{"text":{"body":"after the bad line"}}}}"""
        )
        val (stats, _) = import(dir)
        assertEquals(1, stats.messages)
    }

    @Test
    fun `the timestamps an export writes as strings are read as numbers`() {
        val (_, sink) = import(minimalExport())
        // Read as a number, a quoted timestamp comes back 0 and every message in a history
        // lands at the epoch, in one heap, in the wrong order.
        assertTrue(sink.inserted.all { it.ts > 1_600_000_000_000 })
    }

}
