package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.signal.BridgeMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale

/**
 * Reading a Signal Desktop "export chat history" folder into this phone.
 *
 * A linked device receives no history at all: threads begin on the day it is linked and fill
 * forward. This is the only way back to what came before, and until now it ran on the
 * computer holding the bridge -- which meant history was reachable only by people willing to
 * run a daemon. The rules are a port of that importer, kept case for case, because they were
 * arrived at against real exports and each one is a message that would otherwise be lost or
 * duplicated.
 *
 * What it will not do is invent. A group whose thread this phone does not already have is
 * skipped rather than guessed at: the export identifies a group by its master key and Signal
 * identifies it by a value derived from that, and matching them on the title is a guess that
 * would put someone's messages in the wrong conversation.
 */
internal class SignalHistoryImporter(
    private val source: SignalExportSource,
    private val sink: Sink,
    private val selfUuid: String,
    private val selfNumber: String,
    private val now: () -> Long = System::currentTimeMillis
) {

    /** Where the imported messages go, and what the phone already knows. */
    internal interface Sink {
        /** Existing group threads by lowercased title, the only way to place a group. */
        fun groupThreadsByTitle(): Map<String, String>

        /** Insert-only: a message already held wins, whether it arrived live or by import. */
        fun insert(messages: List<BridgeMessage>): Int

        /** Name a thread that has no name of its own. Never overwrites one that has. */
        fun nameThreadIfUnnamed(threadKey: String, title: String)

        /** Keep an attachment's bytes and return the id a message can record, or null. */
        fun storeAttachment(name: String, open: () -> InputStream): String?
    }

    /** What the import did, in the operator's terms, for a screen to report. */
    data class Stats(
        val messages: Int = 0,
        val alreadyPresent: Int = 0,
        val attachments: Int = 0,
        val attachmentsLost: Int = 0,
        val skippedEvents: Int = 0,
        val skippedDeleted: Int = 0,
        val skippedExpired: Int = 0,
        val skippedNoThread: Int = 0,
        val skippedNoAuthor: Int = 0,
        val skippedUnknownGroup: Int = 0
    )

    private data class Person(
        val uuid: String,
        val number: String,
        val name: String,
        val isSelf: Boolean = false
    )

    /**
     * The thread key for a group, derived from the master key the export carries.
     *
     * The same derivation a live message goes through -- [ContentNormalizer.groupIdForCheck]
     * -- so an imported group lands in the conversation it belongs to rather than beside it.
     */
    private fun threadKeyFromMasterKey(encoded: String): String? {
        if (encoded.isBlank()) return null
        return runCatching {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            if (bytes.size != GROUP_MASTER_KEY_SIZE) return null
            "group:" + ContentNormalizer.groupIdForCheck(bytes).ifBlank { return null }
        }.getOrNull()
    }

    class NotAnExport : Exception("no ${DirectoryExportSource.MAIN} in the chosen folder")

    /**
     * [onProgress] is called with the running count of messages taken in, sparsely enough to
     * be drawn on a screen that repaints in full.
     */
    fun run(onProgress: (Int) -> Unit = {}): Stats {
        if (!source.isExport()) throw NotAnExport()

        val people = mutableMapOf<String, Person>()
        val groupTitles = mutableMapOf<String, String>()
        val groupThreadKeys = mutableMapOf<String, String>()
        val chats = mutableMapOf<String, String>()

        // First pass: who and where. The export does not promise that a recipient appears
        // before the messages that name it, so the mapping has to be complete first.
        source.readLines { lines ->
            lines.forEach { line ->
                val record = parse(line) ?: return@forEach
                record.optJSONObject("recipient")?.let { recipient ->
                    val id = recipient.optString("id")
                    val contact = recipient.optJSONObject("contact")
                    val group = recipient.optJSONObject("group")
                    when {
                        contact != null -> {
                            // ACI first, because that is what a live message keys on and what
                            // makes an imported thread merge with one that already exists. A
                            // pni-only contact still gets a thread; the alternative is
                            // dropping them.
                            val uuid = canonicalUuid(contact.optString("aci"))
                                .ifBlank { canonicalUuid(contact.optString("pni")) }
                            people[id] = Person(uuid, contact.optString("e164"), contactName(contact))
                        }
                        group != null -> {
                            recipient.optString("kotozuteThreadKey")
                                .takeIf { it.isNotBlank() }
                                ?.let { groupThreadKeys[id] = it }
                            // The master key, which Signal's own export carries and from
                            // which the thread key is *derived* rather than guessed. This is
                            // the answer the title match was standing in for.
                            threadKeyFromMasterKey(group.optString("masterKey"))
                                ?.let { groupThreadKeys[id] = it }
                            group.optJSONObject("snapshot")
                                ?.optJSONObject("title")
                                ?.optString("title")
                                ?.takeIf { it.isNotBlank() }
                                ?.let { groupTitles[id] = it }
                        }
                        recipient.has("self") -> people[id] = Person(selfUuid, selfNumber, "", isSelf = true)
                    }
                }
                record.optJSONObject("chat")?.let { chat ->
                    chats[chat.optString("id")] = chat.optString("recipientId")
                }
            }
        }

        // How a group in the export becomes a conversation on this phone, best answer first:
        //
        //  1. its master key, which Signal's own export carries -- the thread key is derived
        //     from it exactly as a live message's is, so this needs nothing to match against;
        //  2. `kotozuteThreadKey`, written by our own exporter, which is the same answer said
        //     directly;
        //  3. failing both, the title.
        //
        // The title is a guess and is now treated as one. Two groups can share a name, and a
        // name is one rename away from matching a different conversation entirely -- so a
        // title that is not unique on *both* sides is not used at all, and those messages are
        // counted as an unknown group rather than filed somewhere plausible. Putting
        // somebody's group messages in the wrong conversation is worse than not importing
        // them: the import reports a number either way, and only one of those is recoverable.
        val known = sink.groupThreadsByTitle()
        val groupKeys = mutableMapOf<String, String>()
        val ambiguous = groupTitles.values
            .groupingBy { it.lowercase(Locale.ROOT) }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        groupTitles.forEach { (recipientId, title) ->
            val key = title.lowercase(Locale.ROOT)
            if (key in ambiguous) return@forEach
            known[key]?.let { groupKeys[recipientId] = it }
        }
        groupKeys.putAll(groupThreadKeys)

        val files = FileIndex(source.attachments())
        var stats = Stats()
        val landed = mutableSetOf<String>()
        val batch = mutableListOf<BridgeMessage>()

        fun flush() {
            if (batch.isEmpty()) return
            val inserted = sink.insert(batch)
            stats = stats.copy(
                messages = stats.messages + inserted,
                alreadyPresent = stats.alreadyPresent + (batch.size - inserted)
            )
            batch.clear()
            onProgress(stats.messages)
        }

        // Second pass: the messages.
        source.readLines { lines ->
            lines.forEach { line ->
                val item = parse(line)?.optJSONObject("chatItem") ?: return@forEach

                // Events, not messages: "X joined the group", a timer change, a profile change.
                if (item.has("updateMessage")) {
                    stats = stats.copy(skippedEvents = stats.skippedEvents + 1)
                    return@forEach
                }
                // A tombstone for something already deleted for everyone. There is no content
                // to import, and a row for it would resurrect a message as an empty bubble.
                if (item.has("remoteDeletedMessage")) {
                    stats = stats.copy(skippedDeleted = stats.skippedDeleted + 1)
                    return@forEach
                }
                val standard = item.optJSONObject("standardMessage")
                if (standard == null) {
                    stats = stats.copy(skippedEvents = stats.skippedEvents + 1)
                    return@forEach
                }

                val expireStart = item.optLongString("expireStartDate")
                val expiresIn = item.optLongString("expiresInMs")
                // A message whose disappearing deadline has already passed is not history; it
                // is something Signal would have removed. Importing it undoes the sender's
                // choice.
                if (expireStart > 0 && expiresIn > 0 && expireStart + expiresIn <= now()) {
                    stats = stats.copy(skippedExpired = stats.skippedExpired + 1)
                    return@forEach
                }

                val chatId = item.optString("chatId")
                val recipientId = chats[chatId]
                if (recipientId == null) {
                    stats = stats.copy(skippedNoThread = stats.skippedNoThread + 1)
                    return@forEach
                }
                // A group this phone has no thread for. Named apart from the other skips
                // because it is the one a person can do something about: open the group once
                // on this phone, and a later import will find it.
                if (groupTitles.containsKey(recipientId) && !groupKeys.containsKey(recipientId)) {
                    stats = stats.copy(skippedUnknownGroup = stats.skippedUnknownGroup + 1)
                    return@forEach
                }

                val threadKey = groupKeys[recipientId]
                    ?: people[recipientId]?.let { person ->
                        person.uuid.ifBlank { person.number }.takeIf { it.isNotBlank() }
                            ?.let { "direct:$it" }
                    }
                if (threadKey == null) {
                    stats = stats.copy(skippedNoThread = stats.skippedNoThread + 1)
                    return@forEach
                }
                val groupId = if (threadKey.startsWith("group:")) threadKey.removePrefix("group:") else ""

                val author = people[item.optString("authorId")] ?: Person("", "", "")
                // The id must be the one a live copy of this message would carry, or an
                // import next to live data duplicates every message the two have in common.
                val idAuthor = author.uuid.ifBlank { author.number }
                if (idAuthor.isBlank()) {
                    stats = stats.copy(skippedNoAuthor = stats.skippedNoAuthor + 1)
                    return@forEach
                }

                // An edited message. Signal's archive files it under the *latest* edit: the
                // item carries the current text and the newest timestamp, and `revisions`
                // holds the older versions, oldest first.
                //
                // This app keeps an edit as a rewrite of the original row -- the id is the
                // author and the timestamp the message was *first* sent, which is also what
                // every quote of it points at. Taking the item's own timestamp therefore
                // imported a second copy of a message already held, under an id nothing else
                // refers to, and left the quote pointing at a message the import had not
                // created. The text is the latest one; only the timestamp comes from the
                // original.
                val ts = originalTimestamp(item)
                val body = standard.optJSONObject("text")?.optString("body").orEmpty()
                val attachments = JSONArray()
                var kept = 0
                var lost = 0
                standard.optJSONArray("attachments")?.let { array ->
                    for (index in 0 until array.length()) {
                        val entry = array.optJSONObject(index) ?: continue
                        val pointer = entry.optJSONObject("pointer") ?: continue
                        val size = pointer.optJSONObject("locatorInfo")?.optLong("size") ?: continue
                        val id = files.take(entry.optString("kotozuteFileName"), size, sink)
                        // No file behind the reference: the export named one it did not
                        // write, or it was pruned. The reference is kept with no id, which
                        // the app draws as not downloaded -- dropping it instead would leave
                        // an attachment-only message with nothing in it, and empty messages
                        // are discarded below, so the message itself would vanish.
                        attachments.put(
                            JSONObject()
                                .put("id", id.orEmpty())
                                .put("type", pointer.optString("contentType"))
                                .put("filename", pointer.optString("fileName"))
                                .put("size", size)
                                .put("pending", id == null)
                        )
                        if (id == null) lost++ else kept++
                    }
                }

                if (body.isBlank() && attachments.length() == 0) {
                    stats = stats.copy(skippedEvents = stats.skippedEvents + 1)
                    return@forEach
                }
                stats = stats.copy(
                    attachments = stats.attachments + kept,
                    attachmentsLost = stats.attachmentsLost + lost
                )

                val outgoing = item.has("outgoing") || author.isSelf
                batch += BridgeMessage(
                    id = "$idAuthor:$ts",
                    seq = 0,
                    threadKey = threadKey,
                    ts = ts,
                    senderUuid = author.uuid,
                    senderNumber = author.number,
                    outgoing = outgoing,
                    body = body,
                    groupId = groupId,
                    quoteTs = standard.optJSONObject("quote")?.optLongString("targetSentTimestamp") ?: 0L,
                    read = isRead(item),
                    source = "import",
                    attachmentsJson = if (attachments.length() == 0) "" else attachments.toString(),
                    expiresInSeconds = if (expiresIn > 0) expiresIn / 1000 else 0,
                    // Signal writes a start only once the timer has actually started, so an
                    // unread disappearing message carries a duration and no start. Treating
                    // that as "never expires" would turn every one of them into a permanent
                    // record, which is the outcome this importer exists to avoid. Start the
                    // clock now: no later than Signal would, which is the safe direction.
                    expiresAt = when {
                        expiresIn <= 0 -> 0L
                        expireStart > 0 -> expireStart + expiresIn
                        else -> now() + expiresIn
                    }
                )
                landed += threadKey
                if (batch.size >= BATCH) flush()
            }
        }
        flush()

        // Names, so an imported conversation is not a row of service ids. After the messages,
        // so there is a thread to name -- and only for threads a message actually landed in:
        // a real export carries a chat for every contact ever messaged, and naming those
        // creates them, which is a screenful of empty conversations above the real ones.
        chats.forEach { (_, recipientId) ->
            val key = groupKeys[recipientId]
                ?: people[recipientId]?.let { person ->
                    person.uuid.ifBlank { person.number }.takeIf { it.isNotBlank() }?.let { "direct:$it" }
                }
                ?: return@forEach
            if (key !in landed) return@forEach
            val title = people[recipientId]?.name?.takeIf { it.isNotBlank() }
                ?: groupTitles[recipientId]?.takeIf { it.isNotBlank() }
                ?: return@forEach
            sink.nameThreadIfUnnamed(key, title)
        }

        return stats
    }

    /**
     * Resolving an attachment reference to a file.
     *
     * The export names its files by a derived media name that appears nowhere in the
     * records, so size is what the two sides share. A size matching exactly one file is a
     * safe match; a size matching none or several is not a match at all. Our own exports
     * carry the filename and are matched on that instead.
     */
    private class FileIndex(entries: List<SignalExportSource.Entry>) {
        private val bySize = entries.groupBy { it.size }
        private val byName = entries.associateBy { it.name }
        private val taken = mutableMapOf<String, String>()

        fun take(name: String, size: Long, sink: Sink): String? {
            val entry = name.takeIf { it.isNotBlank() }
                ?.let { byName[it.substringAfterLast('/')] }
                ?: bySize[size]?.singleOrNull()
                ?: return null
            // The same file referenced twice is kept once.
            taken[entry.name]?.let { return it }
            return sink.storeAttachment(entry.name, entry::open)?.also { taken[entry.name] = it }
        }
    }

    companion object {
        /** Rows per transaction: enough to be worth a write, small enough to report progress. */
        private const val BATCH = 200

        /** A group master key is 32 bytes; anything else is not one, whatever it decodes to. */
        private const val GROUP_MASTER_KEY_SIZE = 32

        /**
         * When a message was first sent, edits and all.
         *
         * `revisions` is ordered oldest first, so the first entry is the original -- but the
         * order is the exporter's promise rather than the format's guarantee, and the earliest
         * timestamp is the same answer without having to trust it.
         */
        fun originalTimestamp(item: JSONObject): Long {
            val own = item.optLongString("dateSent")
            val revisions = item.optJSONArray("revisions") ?: return own
            var earliest = own
            for (index in 0 until revisions.length()) {
                val at = revisions.optJSONObject(index)?.optLongString("dateSent") ?: continue
                if (at in 1 until earliest) earliest = at
            }
            return earliest
        }

        private fun parse(line: String): JSONObject? =
            if (line.isBlank()) null else runCatching { JSONObject(line) }.getOrNull()

        /**
         * Whether an imported message has already been seen.
         *
         * An outgoing one always has. An incoming one carries the flag in our own exports;
         * Signal's own archive has no `incoming` object on most items, and the zero value
         * there would mark years of history unread -- a notification storm and a wrong
         * inbox. Absence means read; only an explicit false means waiting.
         */
        fun isRead(item: JSONObject): Boolean {
            val incoming = item.optJSONObject("incoming") ?: return true
            return incoming.optBoolean("read", false)
        }

        /**
         * The name this account's own address book had is the one the reader recognises; the
         * profile name is what the other person chose to publish, and is the fallback.
         */
        fun contactName(contact: JSONObject): String {
            val system = listOf("systemGivenName", "systemFamilyName")
                .joinToString(" ") { contact.optString(it) }.trim()
            if (system.isNotBlank()) return system
            return listOf("profileGivenName", "profileFamilyName")
                .joinToString(" ") { contact.optString(it) }.trim()
        }

        /**
         * The export's identifier in the form the rest of the app uses.
         *
         * An export writes an ACI as base64 of the raw 16 bytes; everything else here says
         * the same identity as a hyphenated UUID. They are equal and they do not compare
         * equal, so without this every contact gets a second thread and every message a
         * second copy -- silently, and visibly only once the history is already doubled.
         *
         * Anything unreadable comes back empty so the caller falls back to the number rather
         * than keying a thread on a string it cannot read.
         */
        fun canonicalUuid(value: String): String {
            if (value.isBlank()) return ""
            if (value.length == 36 && value.count { it == '-' } == 4) return value.lowercase(Locale.ROOT)
            // java.util.Base64, not android.util: this rule is worth testing off a device,
            // and the android one is a stub in a JVM test that decodes everything to nothing.
            val raw = runCatching { java.util.Base64.getDecoder().decode(value) }
                .getOrNull() ?: return ""
            if (raw.size != 16) return ""
            val hex = raw.joinToString("") { "%02x".format(it) }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                    "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        }

        /**
         * A timestamp the export writes as a JSON string, not a number. Reading it as a
         * number returns 0 and every message lands at the epoch.
         */
        private fun JSONObject.optLongString(name: String): Long {
            val value = opt(name) ?: return 0L
            return when (value) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull() ?: 0L
                else -> 0L
            }
        }
    }
}
