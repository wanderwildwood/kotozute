package com.wanderwildwood.kotozute.signalstore

/**
 * Read syncs that named a message this phone did not have yet, kept until it arrives.
 *
 * ⚠ The case this exists for: a batch from the server can hold a message *and* the read sync
 * for it (read on Desktop or iOS before this phone woke up). The receive loop decrypts the
 * whole batch first and files the messages afterwards, so the sync is applied while the row
 * is still missing. It used to be dropped, and the message then sat unread with a
 * notification on the phone for ever (GitHub issue 9).
 *
 * Signal Android does the same with `EarlyMessageCache`: a read sync that finds no message
 * (`SyncMessageProcessor.handleSynchronizeReadMessage`, the `unhandled` list) is stored under
 * the sender and timestamp, and replayed when that message is inserted. Here the replay is
 * [filed], called once the message database has the rows.
 *
 * One lock covers "look, then keep" in [applied] and "claim" in [filed], so a sync landing
 * on one thread while the batch is filed on another is applied by exactly one of them.
 */
class ReadSyncLedger(
    private val rows: Rows,
    private val lifespanMs: Long = DAY_MS,
    private val capacity: Int = 1000
) {
    /** The message database, as the ledger needs it. */
    interface Rows {
        /**
         * Marks each id read where it is held and still unread. Returns the ids that exist at
         * all (changed or already read), so the ledger knows which to keep waiting for.
         */
        fun markRead(ids: Collection<String>, readAt: Long): Set<String>
    }

    private class Held(val readAt: Long, val keptAt: Long)

    private val held = LinkedHashMap<String, Held>()

    /** A read sync arrived for [ids]; whatever is not on file yet is kept for [filed]. */
    @Synchronized
    fun applied(ids: Collection<String>, readAt: Long, now: Long = System.currentTimeMillis()) {
        if (ids.isEmpty()) return
        val present = rows.markRead(ids, readAt)
        ids.filter { it !in present }.forEach { held[it] = Held(readAt, now) }
        trim(now)
    }

    /**
     * Messages with these [ids] have just been filed. Applies any read sync kept for them and
     * returns the ids that are now read, so the caller does not announce them.
     */
    @Synchronized
    fun filed(ids: Collection<String>, now: Long = System.currentTimeMillis()): Set<String> {
        trim(now)
        if (held.isEmpty()) return emptySet()
        val claimed = ids.filter { it in held }
        if (claimed.isEmpty()) return emptySet()
        val done = mutableSetOf<String>()
        // One call per distinct time, since each sync dated its own reading.
        claimed.groupBy { held[it]!!.readAt }.forEach { (readAt, group) ->
            rows.markRead(group, readAt)
            done += group
        }
        done.forEach { held.remove(it) }
        return done
    }

    @Synchronized
    fun waiting(): Int = held.size

    private fun trim(now: Long) {
        val it = held.entries.iterator()
        while (it.hasNext()) if (now - it.next().value.keptAt > lifespanMs) it.remove()
        while (held.size > capacity) {
            val first = held.entries.iterator()
            first.next(); first.remove()
        }
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
