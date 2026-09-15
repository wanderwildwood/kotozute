package com.wanderwildwood.kotozute.signalstore

import org.whispersystems.signalservice.internal.push.Content
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * What this device has recently sent, so it can send it again if asked.
 *
 * Signal's message log. It is what makes `ContentHint.RESENDABLE` an honest promise: a
 * recipient whose client could not decrypt one of our messages asks for it, and their client
 * shows nothing meanwhile because it expects the resend. Without a log there was nothing to
 * answer with -- the session could be repaired, but that message was gone, and the recipient
 * was left waiting for something that would never come.
 *
 * What is stored is the exact [Content] the send returned, not a rebuild of it.
 *
 * ⚠ This is sent plaintext at rest. It is in the same SQLCipher database as the protocol
 * stores, and it is kept for [MAX_AGE_MS] and no longer -- a retry receipt arrives within
 * minutes of the failure, so anything older cannot be useful and is only a copy nobody needs.
 */
internal class SignalMessageLog(private val db: ProtocolDatabase) {

    /** One thing sent, as it was sent. */
    data class Entry(val content: Content, val urgent: Boolean, val groupId: ByteArray?)

    /**
     * Writes down one send, per recipient.
     *
     * A group send is one row per member, because a retry receipt comes from one of them and
     * names only the timestamp -- there is no way back to "the group send" without it.
     */
    fun remember(
        recipient: String,
        deviceId: Int,
        sentTimestamp: Long,
        content: Content,
        urgent: Boolean,
        groupId: ByteArray?
    ) = withStoreLock(db) {
        if (recipient.isBlank() || sentTimestamp <= 0) return@withStoreLock
        db.writableDatabase.execSQL(
            """
            INSERT INTO message_log (recipient, device_id, sent_timestamp, content, urgent, group_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                recipient,
                deviceId,
                sentTimestamp,
                content.encode(),
                if (urgent) 1 else 0,
                groupId,
                System.currentTimeMillis()
            )
        )
    }

    /**
     * Forgets the copy kept for one device, because that device has acknowledged the message.
     *
     * The plaintext is held so it can be sent again if somebody's device asks; once a device
     * says it has the message, that device will never ask, and the copy is only a copy.
     * Signal deletes at both receipt points the moment delivery is confirmed --
     * `IncomingMessageObserver.processReceipt` for the server's, and
     * `ReceiptMessageProcessor.handleDeliveryReceipt` for the recipient's -- and keeps the age
     * trim as a backstop rather than as the way entries normally go.
     *
     * ⚠ One device, not the person. A message goes to every device somebody has and each
     * acknowledges separately; clearing the lot on the first receipt would take the copy the
     * others still need, which is why upstream's `deleteEntryForRecipient` is keyed by device.
     */
    fun delivered(recipient: String, deviceId: Int, sentTimestamp: Long): Int = withStoreLock(db) {
        if (recipient.isBlank() || sentTimestamp <= 0) return@withStoreLock 0
        db.writableDatabase.compileStatement(
            "DELETE FROM message_log WHERE recipient = ? AND device_id = ? AND sent_timestamp = ?"
        ).use { statement ->
            statement.bindString(1, recipient)
            statement.bindLong(2, deviceId.toLong())
            statement.bindLong(3, sentTimestamp)
            statement.executeUpdateDelete().also { gone ->
                // Said out loud when it actually removes something, because a delete that
                // matches nothing and a delete that works look identical otherwise -- and the
                // whole point of this is that plaintext stops sitting on the disk. Nobody is
                // named: the count is the fact.
                if (gone > 0) {
                    Timber.i("signal message log: cleared %d delivered copy(ies)", gone)
                }
            }
        }
    }

    /**
     * Forgets every copy of one message this device sent, whoever it went to.
     *
     * For a message that has been taken back. The log exists so a message can be sent *again*
     * when somebody's device says it could not read it -- and a withdrawn message is precisely
     * one that must never be sent again. Left in place, a retry receipt arriving any time in
     * the next fortnight would have this device deliver the message the user was told had been
     * unsent, to the one person it was taken back from.
     *
     * Signal removes the payloads in the same transaction as the delete:
     * `messageLog.deleteAllRelatedToMessage(messageId)` sits beside the row being blanked.
     * A message is named here by the timestamp it was sent with, which is what the log is
     * keyed on and what makes one of ours unique.
     */
    fun forgetSent(sentTimestamp: Long): Int = withStoreLock(db) {
        if (sentTimestamp <= 0) return@withStoreLock 0
        db.writableDatabase.compileStatement(
            "DELETE FROM message_log WHERE sent_timestamp = ?"
        ).use { statement ->
            statement.bindLong(1, sentTimestamp)
            statement.executeUpdateDelete().also { gone ->
                if (gone > 0) {
                    Timber.i("signal message log: dropped %d copy(ies) of a withdrawn message", gone)
                }
            }
        }
    }

    /**
     * Notes that somebody asked for a message again and this phone could not send it.
     *
     * ⚠ **One attempt was the whole of the answer.** Upstream's `ResendMessageJob` is
     * `setLifespan(1 day)` with `setMaxAttempts(UNLIMITED)`, queued per recipient, because the
     * failure that matters here is not the send being refused -- it is the network going away
     * between their asking and our answering. Logged and dropped, the message stays lost after
     * `ContentHint.RESENDABLE` told their client to sit and wait for it.
     *
     * Only the first failure is dated. What matters is how long they have been waiting, not
     * when the last attempt was, because that is what upstream's lifespan is measured from.
     */
    fun markResendOwed(
        recipient: String,
        sentTimestamp: Long,
        now: Long = System.currentTimeMillis()
    ): Int = withStoreLock(db) {
        db.writableDatabase.compileStatement(
            """
            UPDATE message_log SET resend_owed_since = ?
            WHERE recipient = ? AND sent_timestamp = ? AND resend_owed_since IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.bindLong(1, now)
            statement.bindString(2, recipient)
            statement.bindLong(3, sentTimestamp)
            statement.executeUpdateDelete()
        }
    }

    /** Somebody is no longer waiting: the message reached them, or it never will. */
    fun clearResendOwed(recipient: String, sentTimestamp: Long): Int = withStoreLock(db) {
        db.writableDatabase.compileStatement(
            "UPDATE message_log SET resend_owed_since = NULL WHERE recipient = ? AND sent_timestamp = ?"
        ).use { statement ->
            statement.bindString(1, recipient)
            statement.bindLong(2, sentTimestamp)
            statement.executeUpdateDelete()
        }
    }

    /** Somebody still waiting for a message, and since when. */
    data class Owed(val recipient: String, val sentTimestamp: Long, val since: Long)

    /**
     * Who is still owed a resend and still worth trying, newest request first.
     *
     * One row per person per message, not per device: [resend] addresses a person and the
     * library decides which of their devices to reach.
     */
    fun owedResends(now: Long = System.currentTimeMillis()): List<Owed> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            """
            SELECT recipient, sent_timestamp, MIN(resend_owed_since)
            FROM message_log
            WHERE resend_owed_since IS NOT NULL
            GROUP BY recipient, sent_timestamp
            ORDER BY MIN(resend_owed_since) DESC
            """.trimIndent(),
            null
        ).use { c ->
            generateSequence { if (c.moveToNext()) Owed(c.getString(0), c.getLong(1), c.getLong(2)) else null }
                .filter { stillWorthResending(it.since, now) }
                .toList()
        }
    }

    /**
     * Stops trying for the ones nobody can be helped by any more, and says how many.
     *
     * Kept as its own step rather than folded into the query so that giving up is something
     * the log *does* and can report, not something that quietly stops happening.
     */
    fun abandonExpiredResends(now: Long = System.currentTimeMillis()): Int = withStoreLock(db) {
        db.writableDatabase.compileStatement(
            "UPDATE message_log SET resend_owed_since = NULL WHERE resend_owed_since IS NOT NULL AND resend_owed_since <= ?"
        ).use { statement ->
            statement.bindLong(1, now - RESEND_LIFESPAN_MS)
            statement.executeUpdateDelete().also { gone ->
                if (gone > 0) {
                    Timber.w(
                        "signal retry: gave up resending %d copy(ies); nobody was reached in a day",
                        gone
                    )
                }
            }
        }
    }

    /** What was sent to somebody at that moment, or null when it is no longer held. */
    fun recall(recipient: String, sentTimestamp: Long): Entry? = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            """
            SELECT content, urgent, group_id FROM message_log
            WHERE recipient = ? AND sent_timestamp = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(recipient, sentTimestamp.toString())
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            runCatching {
                Entry(
                    content = Content.ADAPTER.decode(c.getBlob(0)),
                    urgent = c.getInt(1) != 0,
                    groupId = c.getBlob(2)
                )
            }.onFailure { Timber.w(it, "signal message log: an entry would not decode") }.getOrNull()
        }
    }

    /**
     * Forgets everything kept for one person.
     *
     * ⚠ Called when their identity key changes, which is what `IdentityUtil.saveIdentity` does
     * (`messageLog().deleteAllForRecipient`). Everything held for them was encrypted to the
     * identity they have just stopped having: resending it answers a retry receipt with
     * ciphertext they still cannot read, so the same receipt comes back and the exchange
     * repeats with nothing ever arriving. Keeping it is worse than having nothing, because
     * having nothing at least ends the loop.
     */
    fun forget(recipient: String): Int = withStoreLock(db) {
        val gone = db.writableDatabase.compileStatement(
            "DELETE FROM message_log WHERE recipient = ?"
        ).use { statement ->
            statement.bindString(1, recipient)
            statement.executeUpdateDelete()
        }
        if (gone > 0) Timber.i("signal message log: dropped %d entry(ies) for a changed identity", gone)
        gone
    }

    /**
     * Drops the lot.
     *
     * For "delete Signal data", where every message row goes at once and there is nothing
     * left to enumerate timestamps from. Upstream reaches the same state by its trigger firing
     * once per deleted row; there is no row here for a trigger to fire on, so it is said
     * outright.
     */
    fun forgetEverything(): Int = withStoreLock(db) {
        val gone = db.writableDatabase.compileStatement("DELETE FROM message_log")
            .use { it.executeUpdateDelete() }
        Timber.i("signal message log: cleared %d entry(ies) with the account's messages", gone)
        gone
    }

    /**
     * Drops everything older than [MAX_AGE_MS].
     *
     * Called on the same pass that sweeps undecryptable envelopes, so there is one place that
     * decides what this database stops holding on to.
     */
    fun sweep(): Int = withStoreLock(db) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val gone = db.writableDatabase.compileStatement(
            "DELETE FROM message_log WHERE created_at < ?"
        ).use { statement ->
            statement.bindLong(1, cutoff)
            statement.executeUpdateDelete()
        }
        if (gone > 0) Timber.i("signal message log: forgot %d sent message(s)", gone)
        gone
    }

    internal companion object {
        /**
         * How long a sent message is worth keeping in case somebody asks for it again.
         *
         * Fourteen days, which is Signal's `android.retryRespondMaxAge` default and what
         * `MessageSendLogTables.trimOldMessages` is given.
         *
         * ⚠ This was one day, on the reasoning that "a retry receipt arrives within minutes of
         * the failure". That reasoning was invented and it is wrong in the case the log exists
         * for: the recipient's device is *away*. It comes back after a week, finds a message it
         * cannot read, and asks -- and a one-day log has nothing to answer with, so the message
         * is lost precisely when the promise in ContentHint.RESENDABLE mattered most.
         *
         * It also now matches [SignalReceiver.UNDECRYPTABLE_RETENTION_MS], which is the same
         * fourteen days for the mirror-image case: an envelope *we* could not read, kept in
         * case a fix arrives. Keeping one side for a fortnight and the other for a day was the
         * asymmetry that gave this away.
         */
        private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(14)

        /**
         * How long a resend somebody asked for is worth going on trying.
         *
         * A day, which is `ResendMessageJob`'s `setLifespan(TimeUnit.DAYS.toMillis(1))`. Not
         * the fortnight above: that is how long the *material* is kept, so that somebody whose
         * phone was away can still ask. This is how long to keep answering one asking, and a
         * request nobody could be reached about in a day is one to stop waking the radio for.
         */
        internal val RESEND_LIFESPAN_MS = TimeUnit.DAYS.toMillis(1)

        /**
         * Whether a resend first owed at [since] is still worth attempting at [now].
         *
         * Its own function so the boundary can be tested. A retry that quietly stops happening
         * and a retry that never stops are both failures, and neither shows up on a healthy
         * account.
         */
        internal fun stillWorthResending(since: Long, now: Long): Boolean =
            since > 0 && now - since < RESEND_LIFESPAN_MS
    }
}
