package com.wanderwildwood.kotozute.signalstore

import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Small messages this phone owes *about* other messages, and has not managed to send.
 *
 * A delivery receipt is how a sender's message stops saying nothing at all. It is not a
 * setting and it reveals nothing about the reader -- unlike a read receipt -- so it is sent
 * for everything that arrives, without asking.
 *
 * ⚠ **One attempt was the whole of it.** Upstream answers a failed one with
 * `SendDeliveryReceiptJob`: `setLifespan(TimeUnit.DAYS.toMillis(1))`,
 * `setMaxAttempts(Parameters.UNLIMITED)`, queued per recipient. Here it was sent once and any
 * failure was written to the log and forgotten, so a blip between the message landing and the
 * receipt going left the sender looking at a message that had arrived and would never say so.
 *
 * This is only the record of what is owed. It holds no message and no content: both of these
 * name a message the same way -- by who wrote it and the timestamp they stamped on it -- and
 * that is all there is to keep.
 *
 * Two [Kind]s share the table because they are the same fact with a different audience. A
 * **delivery** receipt goes to the person who wrote the message. A **read sync** goes to this
 * account's own other devices, and is the only thing that stops the primary and Desktop
 * notifying for ever about a conversation already read here.
 */
internal class SignalReceiptStore(private val db: ProtocolDatabase) {

    /** What is owed about a message. The value is what the `kind` column holds. */
    enum class Kind(val value: String) {
        /** Tell the person who wrote it that it arrived here. */
        DELIVERY("delivery"),

        /** Tell this account's own devices it has been read here. */
        READ_SYNC("read-sync")
    }

    /** Somebody still waiting to be told something, and since when. */
    data class Owed(val recipient: String, val sentTimestamp: Long, val since: Long)

    /**
     * Notes that a receipt could not be sent.
     *
     * Only the first failure is dated, because what matters is how long the sender has been
     * waiting rather than when the last attempt was -- that is what upstream's lifespan is
     * measured from.
     */
    fun owe(
        recipient: String,
        timestamps: List<Long>,
        kind: Kind,
        now: Long = System.currentTimeMillis()
    ) = withStoreLock(db) {
        if (recipient.isBlank()) return@withStoreLock
        db.writableDatabase.compileStatement(
            """
            INSERT INTO receipt_owed (recipient, sent_timestamp, owed_since, kind)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(recipient, sent_timestamp, kind) DO NOTHING
            """.trimIndent()
        ).use { statement ->
            timestamps.filter { it > 0 }.forEach { at ->
                statement.clearBindings()
                statement.bindString(1, recipient)
                statement.bindLong(2, at)
                statement.bindLong(3, now)
                statement.bindString(4, kind.value)
                statement.executeInsert()
            }
        }
    }

    /** What is still owed of this kind and still worth sending, grouped by person. */
    fun owed(kind: Kind, now: Long = System.currentTimeMillis()): Map<String, List<Long>> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT recipient, sent_timestamp, owed_since FROM receipt_owed WHERE kind = ? ORDER BY owed_since",
            arrayOf(kind.value)
        ).use { c ->
            generateSequence { if (c.moveToNext()) Owed(c.getString(0), c.getLong(1), c.getLong(2)) else null }
                .filter { stillWorthSending(it.since, now) }
                .groupBy({ it.recipient }, { it.sentTimestamp })
        }
    }

    /** They have been told. */
    fun clear(recipient: String, timestamps: List<Long>, kind: Kind) = withStoreLock(db) {
        if (recipient.isBlank() || timestamps.isEmpty()) return@withStoreLock
        db.writableDatabase.compileStatement(
            "DELETE FROM receipt_owed WHERE recipient = ? AND sent_timestamp = ? AND kind = ?"
        ).use { statement ->
            timestamps.forEach { at ->
                statement.clearBindings()
                statement.bindString(1, recipient)
                statement.bindLong(2, at)
                statement.bindString(3, kind.value)
                statement.executeUpdateDelete()
            }
        }
    }

    /**
     * Stops trying for the ones nobody can be helped by any more, and says how many.
     *
     * Its own step rather than a filter in the query, so that giving up is something this
     * does and can report rather than something that quietly stops happening.
     */
    fun abandonExpired(now: Long = System.currentTimeMillis()): Int = withStoreLock(db) {
        db.writableDatabase.compileStatement(
            "DELETE FROM receipt_owed WHERE owed_since <= ?"
        ).use { statement ->
            statement.bindLong(1, now - RECEIPT_LIFESPAN_MS)
            statement.executeUpdateDelete().also { gone ->
                if (gone > 0) {
                    Timber.w("signal receipt: gave up on %d thing(s) owed about a message", gone)
                }
            }
        }
    }

    internal companion object {
        /**
         * How long a delivery receipt is worth going on trying to send.
         *
         * A day, which is `SendDeliveryReceiptJob`'s own
         * `setLifespan(TimeUnit.DAYS.toMillis(1))`.
         *
         * ⚠ Its own constant rather than one shared with the resend's, which happens to be the
         * same number today. They are two independent decisions upstream made about two
         * different jobs, and folding them into one would hide it the day one of them changes.
         */
        internal val RECEIPT_LIFESPAN_MS = TimeUnit.DAYS.toMillis(1)

        /**
         * Whether a receipt first owed at [since] is still worth sending at [now].
         *
         * Its own function so the boundary can be tested. A retry that quietly stops and one
         * that never stops are both failures, and neither shows up on a healthy account.
         */
        internal fun stillWorthSending(since: Long, now: Long): Boolean =
            since > 0 && now - since < RECEIPT_LIFESPAN_MS
    }
}
