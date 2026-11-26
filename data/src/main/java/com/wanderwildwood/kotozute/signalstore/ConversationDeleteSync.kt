package com.wanderwildwood.kotozute.signalstore

/**
 * What a conversation delete from another of the account's devices removes here.
 *
 * ⚠ **It is not "empty the conversation".** `SyncMessage.DeleteForMe.ConversationDelete` is
 * sent for two different things, and names the newest messages it covers either way:
 *
 *  - the account deleted the conversation (`isFullDelete = true`);
 *  - the other device **trimmed** it: Signal's "Keep messages" and "Conversation length limit"
 *    settings remove the old end of every conversation and tell linked devices
 *    (`ThreadTable.trimThread` -> `enqueueThreadDeletes(isFullDelete = false)`).
 *
 * Upstream (`SyncMessageProcessor.handleSynchronizeConversationDeletes`) finds the newest of
 * the named messages it holds in that conversation and removes what is as old or older; when
 * it holds none of them it removes **nothing**. This app used to ignore the names and empty
 * the whole conversation, so a trim on the main device took every message here, the ones the
 * main device kept included (forum t/15210, post 212).
 */
object ConversationDeleteSync {

    /**
     * One conversation named by a delete. [recent] and [recentNonExpiring] are author and sent
     * time, newest messages the sender covered. [whole] is the message-request delete, which
     * names nothing and means the conversation itself (`deleteConversation(threadId,
     * syncThreadDelete = false)` at upstream's `SyncMessageProcessor:1235`).
     */
    data class Request(
        val threadKey: String,
        val recent: List<Pair<String, Long>> = emptyList(),
        val recentNonExpiring: List<Pair<String, Long>> = emptyList(),
        val full: Boolean = false,
        val whole: Boolean = false
    )

    /**
     * A stored row of that conversation. [local] is a row no other device has under this id:
     * a call line, a note this app wrote, an update row.
     */
    data class Row(val id: String, val date: Long, val local: Boolean)

    /** Whether a row with this id and update flag exists on this phone only. */
    fun isLocal(id: String, update: Boolean): Boolean =
        update || id.startsWith("call:") || id.startsWith("groupcall:") || id.startsWith("local:")

    /** The ids of the rows in [rows], all of one conversation, that [request] removes. */
    fun doomed(request: Request, rows: List<Row>): Set<String> {
        if (request.whole) return rows.mapTo(mutableSetOf()) { it.id }
        val cutoff = newestHeld(request.recent, rows)
            ?: newestHeld(request.recentNonExpiring, rows)
            ?: return emptySet()
        val gone = rows.filter { it.date <= cutoff }.mapTo(mutableSetOf()) { it.id }
        // Upstream's `deleteConversationIfContainsOnlyLocal`: a deleted conversation does not
        // stay behind as a list of call lines.
        if (request.full) {
            val left = rows.filter { it.id !in gone }
            if (left.all { it.local }) left.mapTo(gone) { it.id }
        }
        return gone
    }

    private fun newestHeld(named: List<Pair<String, Long>>, rows: List<Row>): Long? {
        if (named.isEmpty()) return null
        val ids = named.mapTo(mutableSetOf()) { (author, at) -> "$author:$at" }
        return rows.filter { it.id in ids }.maxOfOrNull { it.date }
    }
}
