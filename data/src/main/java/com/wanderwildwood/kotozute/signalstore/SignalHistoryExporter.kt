package com.wanderwildwood.kotozute.signalstore

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Writing this phone's Signal messages back out as the JSONL an import reads.
 *
 * Symmetry is the point. A linked device is given no history by Signal, so once these
 * messages are here this is the only copy there is: the phone's own database, which goes
 * when the phone goes. What comes out here goes back in through [SignalHistoryImporter], so
 * a history can be kept somewhere else, moved to another phone, or put back after the thing
 * that lost it.
 *
 * This is not Signal's own export format and does not pretend to be. It is the subset of
 * that shape the importer actually reads, written so the importer can read it -- plus two
 * fields of our own that Signal's format has no room for and that make a restore exact
 * rather than a match on titles and file sizes.
 */
internal class SignalHistoryExporter(
    private val source: Source,
    private val destination: SignalExportDestination,
    private val selfUuid: String
) {

    /** What there is to write. Kept behind an interface so the shape can be tested. */
    internal interface Source {
        data class Thread(val key: String, val title: String, val number: String)

        data class Message(
            val ts: Long,
            val senderUuid: String,
            val outgoing: Boolean,
            val body: String,
            val read: Boolean,
            val quoteTs: Long,
            val expiresAt: Long,
            val expiresInSeconds: Long,
            val attachmentsJson: String
        )

        data class Attachment(val size: Long, val open: () -> InputStream)

        fun threads(): List<Thread>

        /**
         * Every message in a thread, oldest first, each exactly once. A callback rather than
         * a list: a history is thousands of messages and this runs on a phone.
         */
        fun eachMessage(threadKey: String, consume: (Message) -> Unit)

        /** An attachment's bytes, or null when the file is no longer on the phone. */
        fun attachment(id: String): Attachment?
    }

    data class Stats(
        val threads: Int = 0,
        val messages: Int = 0,
        val attachments: Int = 0,
        /** References the phone has no file for. The reference is written anyway; see below. */
        val missing: Int = 0,
        val folder: String = ""
    )

    fun run(onProgress: (Int) -> Unit = {}): Stats {
        var threads = 0
        var messages = 0
        var attachments = 0
        var missing = 0

        destination.main().use { out ->
            // Recipient ids are ours to choose; the importer only needs them to be
            // consistent within the file. Numbering from 2 leaves 1 for self, as Signal's
            // own export does.
            out.writeRecord(JSONObject().put("recipient", JSONObject().put("id", SELF_ID).put("self", JSONObject())))

            val all = source.threads()

            // Every distinct sender, written before anything refers to one. In a group the
            // author of a message is a member, not the thread's other party, and mapping
            // them to the group's recipient leaves them with no identity at all -- which
            // silently dropped every incoming group message on the way back in.
            var next = 2
            val senders = mutableMapOf<String, String>()
            all.forEach { thread ->
                source.eachMessage(thread.key) { message ->
                    val uuid = message.senderUuid
                    if (uuid.isNotBlank() && uuid != selfUuid && senders[uuid] == null) {
                        val id = next.toString()
                        next++
                        senders[uuid] = id
                        out.writeRecord(
                            JSONObject().put(
                                "recipient",
                                JSONObject().put("id", id).put("contact", JSONObject().put("aci", uuid))
                            )
                        )
                    }
                }
            }

            all.forEach { thread ->
                var recipientId = next.toString()
                val chatId = next.toString()
                next++

                when {
                    thread.key.startsWith("group:") -> {
                        // The thread key itself, which Signal's own export cannot carry: it
                        // holds a group's master key, and the id this phone knows the group
                        // by is derived from that. Writing the derived id makes a restore
                        // exact rather than dependent on a thread with a matching title
                        // already existing. Importing Signal's own export still falls back
                        // to the title.
                        out.writeRecord(
                            JSONObject().put(
                                "recipient",
                                JSONObject()
                                    .put("id", recipientId)
                                    .put("kotozuteThreadKey", thread.key)
                                    .put(
                                        "group",
                                        JSONObject().put(
                                            "snapshot",
                                            JSONObject().put("title", JSONObject().put("title", thread.title))
                                        )
                                    )
                            )
                        )
                    }
                    thread.key.startsWith("direct:") -> {
                        // The same person may already have a recipient from the sender pass;
                        // naming them again would be two ids for one identity. Reuse it, and
                        // let this record carry the name and number the sender pass had not.
                        val ident = thread.key.removePrefix("direct:")
                        senders[ident]?.let { recipientId = it }
                        // A direct thread is keyed on the counterpart's service id where one
                        // is known and on their number where it is not. Writing a number
                        // into the "aci" field loses the whole conversation on the way back:
                        // it is refused there as unreadable, e164 is empty, and a thread with
                        // neither identifier has nowhere to go. So the key goes to whichever
                        // field it actually is.
                        val looksLikeNumber = ident.startsWith("+")
                        out.writeRecord(
                            JSONObject().put(
                                "recipient",
                                JSONObject()
                                    .put("id", recipientId)
                                    .put(
                                        "contact",
                                        JSONObject()
                                            .put("aci", if (looksLikeNumber) "" else ident)
                                            .put("e164", if (looksLikeNumber) ident else thread.number)
                                            .put("systemGivenName", thread.title)
                                    )
                            )
                        )
                    }
                    else -> return@forEach
                }

                out.writeRecord(
                    JSONObject().put("chat", JSONObject().put("id", chatId).put("recipientId", recipientId))
                )
                threads++

                val threadRecipientId = recipientId
                source.eachMessage(thread.key) { message ->
                    val item = JSONObject()
                        .put("chatId", chatId)
                        .put("authorId", authorId(message, threadRecipientId, senders))
                        // As strings, because that is how Signal's export writes them and how
                        // the importer reads them.
                        .put("dateSent", message.ts.toString())
                    if (message.outgoing) {
                        item.put("outgoing", JSONObject().put("dateReceived", message.ts.toString()))
                    } else {
                        item.put(
                            "incoming",
                            JSONObject().put("dateReceived", message.ts.toString()).put("read", message.read)
                        )
                    }
                    if (message.expiresAt > 0 && message.expiresInSeconds > 0) {
                        val inMs = message.expiresInSeconds * 1000
                        item.put("expiresInMs", inMs.toString())
                        item.put("expireStartDate", (message.expiresAt - inMs).toString())
                    }

                    val standard = JSONObject()
                    if (message.body.isNotEmpty()) {
                        standard.put("text", JSONObject().put("body", message.body))
                    }
                    // The reply link. Signal's own export nests a whole quoted message here;
                    // this has the timestamp it points at, which is all the importer needs.
                    if (message.quoteTs != 0L) {
                        standard.put("quote", JSONObject().put("targetSentTimestamp", message.quoteTs.toString()))
                    }
                    val written = writeAttachments(message)
                    if (written.array.length() > 0) standard.put("attachments", written.array)
                    attachments += written.kept
                    missing += written.missing
                    item.put("standardMessage", standard)

                    out.writeRecord(JSONObject().put("chatItem", item))
                    messages++
                    if (messages % PROGRESS_EVERY == 0) onProgress(messages)
                }
            }
        }

        onProgress(messages)
        return Stats(threads, messages, attachments, missing, destination.name())
    }

    private class Written(val array: JSONArray, val kept: Int, val missing: Int)

    private fun writeAttachments(message: Source.Message): Written {
        val array = JSONArray()
        var kept = 0
        var missing = 0
        if (message.attachmentsJson.isNotBlank()) {
            val entries = runCatching { JSONArray(message.attachmentsJson) }.getOrNull()
            for (index in 0 until (entries?.length() ?: 0)) {
                val entry = entries?.optJSONObject(index) ?: continue
                val id = entry.optString("id")
                val pointer = JSONObject()
                    .put("contentType", entry.optString("type"))
                    .put("fileName", entry.optString("filename"))
                    .put("locatorInfo", JSONObject().put("size", entry.optLong("size")))

                // An id that is not a plain name is refused rather than followed. It comes
                // off the wire in the sender's own attachment pointer, so it is theirs to
                // choose, and a name with a path in it would read and write outside the two
                // folders this is allowed to touch.
                val file = id.takeIf { it.isNotBlank() && PLAIN_NAME.matches(it) }
                    ?.let { source.attachment(it) }

                if (file == null) {
                    // A reference with no file behind it -- never downloaded, or gone from
                    // the phone since. It is still written, with no name: dropping it makes
                    // a message whose only content was the attachment look empty, and an
                    // empty message is discarded on the way back in. So the message itself
                    // would disappear from the backup, not merely its picture. An entry with
                    // no file reads as "attachment, not downloaded", which is what it is.
                    missing++
                    array.put(JSONObject().put("pointer", pointer))
                    continue
                }

                val copied = destination.file(id)?.use { out ->
                    runCatching { file.open().use { it.copyTo(out) } }.isSuccess
                } ?: false
                if (!copied) {
                    missing++
                    array.put(JSONObject().put("pointer", pointer))
                    continue
                }
                pointer.put("locatorInfo", JSONObject().put("size", file.size))
                array.put(
                    JSONObject()
                        .put("pointer", pointer)
                        // Our own exports name the file outright. Matching on size alone
                        // loses BOTH of any two attachments that share a length -- two
                        // screenshots, two voice notes, the same file sent twice -- and with
                        // them any message whose only content was the attachment. Signal's
                        // export cannot carry this, so the importer still falls back to size.
                        .put("kotozuteFileName", id)
                )
                kept++
            }
        }
        return Written(array, kept, missing)
    }

    /**
     * Which recipient wrote a message. In a one-to-one thread the sender is the thread's
     * other party; in a group it is whichever member wrote it, which is why senders are
     * written as recipients of their own.
     */
    private fun authorId(
        message: Source.Message,
        threadRecipientId: String,
        senders: Map<String, String>
    ): String = when {
        message.outgoing -> SELF_ID
        else -> senders[message.senderUuid] ?: threadRecipientId
    }

    private fun OutputStream.writeRecord(record: JSONObject) {
        write(record.toString().toByteArray())
        write('\n'.code)
    }

    companion object {
        private const val SELF_ID = "1"
        private const val PROGRESS_EVERY = 200

        /** What the attachment store will serve: a filename and nothing that walks out of it. */
        private val PLAIN_NAME = Regex("[A-Za-z0-9_.-]+")
    }
}
