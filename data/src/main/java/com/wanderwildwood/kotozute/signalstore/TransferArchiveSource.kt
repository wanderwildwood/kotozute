package com.wanderwildwood.kotozute.signalstore

import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.signal.archive.proto.ChatItem
import org.signal.archive.proto.Frame
import org.signal.archive.proto.Recipient
import java.io.InputStream
import java.util.Base64

/**
 * A transferred history, offered to [SignalHistoryImporter] as if it were a Signal export.
 *
 * It nearly is one. A Signal Desktop "export chat history" folder is the same backup format
 * written as JSON, one frame per line, and the importer already reads that -- with every rule it
 * learned against real exports: an edit filed under its first timestamp, an expired message left
 * out, a group placed by its master key, a missing attachment kept as one not downloaded. So the
 * archive's frames are written here as the lines an export would hold, and the importer runs
 * unchanged. One importer means one insert path and one id per message: a later Desktop import
 * of the same history finds every message the transfer brought already there.
 *
 * Only the fields the importer reads are written. A frame it has no use for -- account data,
 * sticker packs, call links, folders -- produces no line at all. A chat item of a kind it does
 * not import (stickers, contact cards, view-once, polls, payments, story replies, and the old SMS
 * a primary may still hold) produces a line with no content, which the importer counts as
 * skipped.
 *
 * There are no attachment files: the archive carries pointers, not media. Each attachment
 * therefore arrives the way the importer already shows one whose file an export did not include
 * -- kept, with no bytes, drawn as not downloaded.
 */
internal class TransferArchiveSource(
    private val reader: TransferArchiveReader
) : SignalExportSource {

    /** The archive's header said a version this build does not read. */
    class NewerVersion(val version: Long) : Exception("archive version $version")

    override fun isExport(): Boolean = true

    override fun <T> readLines(consume: (Sequence<String>) -> T): T =
        reader.read { header, frames ->
            if (header == null) throw TransferArchiveReader.Unusable("archive has no header")
            if (header.version > TransferArchiveReader.VERSION) throw NewerVersion(header.version)
            consume(frames.mapNotNull(::line))
        }

    override fun attachments(): List<SignalExportSource.Entry> = emptyList()

    /** Never asked for: [readLines] is overridden, and nothing else reads the records file. */
    override fun openMain(): InputStream =
        throw UnsupportedOperationException("a transfer archive has no records file")

    override fun meta(): String? = null

    companion object {

        /** The export line for [frame], or null for a frame the importer has no use for. */
        fun line(frame: Frame): String? {
            frame.recipient?.let { return JSONObject().put("recipient", recipient(it)).toString() }
            frame.chat?.let { chat ->
                return JSONObject().put(
                    "chat",
                    JSONObject()
                        .put("id", chat.id.toString())
                        .put("recipientId", chat.recipientId.toString())
                ).toString()
            }
            frame.chatItem?.let { return JSONObject().put("chatItem", chatItem(it)).toString() }
            return null
        }

        private fun recipient(recipient: Recipient): JSONObject {
            val out = JSONObject().put("id", recipient.id.toString())
            recipient.contact?.let { contact ->
                out.put(
                    "contact",
                    JSONObject().apply {
                        contact.aci?.let { put("aci", it.base64()) }
                        contact.pni?.let { put("pni", it.base64()) }
                        // As an export writes a uint64: digits, as a string, no plus sign. The
                        // importer files a number-only contact the same way either way, and
                        // keeping the export's spelling is what keeps the two in agreement.
                        contact.e164?.let { put("e164", it.toString()) }
                        put("systemGivenName", contact.systemGivenName)
                        put("systemFamilyName", contact.systemFamilyName)
                        contact.profileGivenName?.let { put("profileGivenName", it) }
                        contact.profileFamilyName?.let { put("profileFamilyName", it) }
                    }
                )
            }
            recipient.group?.let { group ->
                out.put(
                    "group",
                    JSONObject().apply {
                        if (group.masterKey.size > 0) put("masterKey", group.masterKey.base64())
                        group.snapshot?.title?.title?.let { title ->
                            put("snapshot", JSONObject().put("title", JSONObject().put("title", title)))
                        }
                    }
                )
            }
            if (recipient.self != null) out.put("self", JSONObject())
            return out
        }

        private fun chatItem(item: ChatItem): JSONObject {
            val out = JSONObject()
                .put("chatId", item.chatId.toString())
                .put("authorId", item.authorId.toString())
                .put("dateSent", item.dateSent.toString())
            item.expireStartDate?.let { out.put("expireStartDate", it.toString()) }
            item.expiresInMs?.let { out.put("expiresInMs", it.toString()) }
            if (item.revisions.isNotEmpty()) {
                out.put(
                    "revisions",
                    JSONArray().apply {
                        item.revisions.forEach { put(JSONObject().put("dateSent", it.dateSent.toString())) }
                    }
                )
            }
            item.incoming?.let { out.put("incoming", JSONObject().put("read", it.read)) }
            item.outgoing?.let { out.put("outgoing", JSONObject()) }
            item.directionless?.let { out.put("directionless", JSONObject()) }

            // An SMS the primary still holds from before Signal stopped carrying them. It is
            // not Signal history, and this phone keeps its own SMS; it is left out, counted.
            if (item.sms) return out

            when {
                item.standardMessage != null -> out.put("standardMessage", standard(item.standardMessage!!))
                // Both are a message deleted for everyone: nothing to bring across.
                item.remoteDeletedMessage != null || item.adminDeletedMessage != null ->
                    out.put("remoteDeletedMessage", JSONObject())
                // Group changes, timer changes, calls, safety-number changes: structured
                // events this app has no words for here, which the importer counts and skips.
                item.updateMessage != null -> out.put("updateMessage", JSONObject())
            }
            return out
        }

        private fun standard(message: org.signal.archive.proto.StandardMessage): JSONObject {
            val out = JSONObject()
            message.text?.let { out.put("text", JSONObject().put("body", it.body)) }
            message.quote?.targetSentTimestamp?.let {
                out.put("quote", JSONObject().put("targetSentTimestamp", it.toString()))
            }
            if (message.attachments.isNotEmpty()) {
                out.put(
                    "attachments",
                    JSONArray().apply {
                        message.attachments.forEach { attachment ->
                            val pointer = attachment.pointer ?: return@forEach
                            put(
                                JSONObject()
                                    .put(
                                        "pointer",
                                        JSONObject().apply {
                                            pointer.contentType?.let { put("contentType", it) }
                                            pointer.fileName?.let { put("fileName", it) }
                                            // Always written, even as 0: the importer passes
                                            // over a pointer with no locator, and an
                                            // attachment-only message would then vanish.
                                            put(
                                                "locatorInfo",
                                                JSONObject().put("size", (pointer.locatorInfo?.size ?: 0).toLong())
                                            )
                                        }
                                    )
                                    // The backup enum's number, which the importer compares
                                    // by equality -- see [SignalHistoryImporter.isVoiceMessageFlag].
                                    .put("flag", attachment.flag.value)
                            )
                        }
                    }
                )
            }
            return out
        }

        private fun ByteString.base64(): String = Base64.getEncoder().encodeToString(toByteArray())
    }
}
