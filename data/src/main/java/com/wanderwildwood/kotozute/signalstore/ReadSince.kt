package com.wanderwildwood.kotozute.signalstore

/**
 * Which rows a read sync from another of the account's devices marks read.
 *
 * Kept apart from the database so the rule can be tested on its own; the Realm side is
 * `SignalRepositoryImpl.markReadRows`.
 */
object ReadSince {

    /** What the rule needs to know about one stored message. */
    class Row(
        val id: String,
        val threadKey: String,
        val date: Long,
        val senderUuid: String,
        val revisionTs: Long,
        val outgoing: Boolean,
        val read: Boolean
    )

    /** The message database, as the rule reads it. */
    interface Table {
        fun byId(id: String): Row?

        /** The row an edit by [author] sent at [revisionTs] rewrote, if any. */
        fun byRevision(author: String, revisionTs: Long): Row?

        /** Incoming rows of [threadKey] not read yet, dated at or before [upTo]. */
        fun unreadIncoming(threadKey: String, upTo: Long): List<Row>
    }

    /**
     * [present]: the sync ids that name a row on file (read now or before), so a sync for a
     * message not here yet can be kept. [toMark]: the ids of the rows to set read.
     */
    class Plan(val present: Set<String>, val toMark: Set<String>)

    fun plan(syncIds: Collection<String>, table: Table): Plan {
        val present = mutableSetOf<String>()
        val toMark = linkedSetOf<String>()
        // Per conversation, the newest message a sync named.
        val readUpTo = mutableMapOf<String, Long>()
        syncIds.forEach { id ->
            val row = find(id, table) ?: return@forEach
            present += id
            if (!row.read) toMark += row.id
            readUpTo[row.threadKey] = maxOf(readUpTo[row.threadKey] ?: Long.MIN_VALUE, row.date)
        }
        // ⚠ Not only the messages named. A read sync names the messages the other device had
        // unread, and nothing else: a missed call, a message that would not open here, an
        // edit filed under its original -- none is ever named, and each kept the conversation
        // unread here for ever while its notification was already gone (issue 9, second
        // report). Signal marks the conversation read up to the newest message a sync names
        // (`SyncMessageProcessor.handleSynchronizeReadMessage` → `ThreadTable.setReadSince`
        // → `MessageTable.setMessagesReadSince`), and so does this. Upstream dates by
        // arrival; rows here have only the sent time, which is the order the other device
        // shows them in.
        readUpTo.forEach { (threadKey, upTo) ->
            table.unreadIncoming(threadKey, upTo).forEach { toMark += it.id }
        }
        return Plan(present, toMark)
    }

    /**
     * What a batch just filed answers to, for a read sync kept waiting for it: each new
     * incoming message's id, and each incoming edit's own "author:edit time" -- the name a
     * device that read the edited message gives it, and not a new row here.
     */
    fun filedKeys(
        fresh: List<com.wanderwildwood.kotozute.signal.BridgeMessage>,
        all: List<com.wanderwildwood.kotozute.signal.BridgeMessage>
    ): List<String> =
        fresh.filter { !it.outgoing }.map { it.id } +
            all.filter { !it.outgoing && it.revisionTs > 0L && it.senderUuid.isNotBlank() }
                .map { "${it.senderUuid}:${it.revisionTs}" }

    /**
     * The row a sync's "author:timestamp" names. Signal finds it by sent time among every
     * revision; here an edit rewrites the original's row, so a sync naming the edit (what a
     * device that read the edited message sends) is matched on the row's newest revision.
     */
    private fun find(id: String, table: Table): Row? {
        table.byId(id)?.let { return it }
        val author = id.substringBeforeLast(':', "")
        val at = id.substringAfterLast(':', "").toLongOrNull() ?: return null
        if (author.isBlank() || at <= 0L) return null
        return table.byRevision(author, at)
    }
}
