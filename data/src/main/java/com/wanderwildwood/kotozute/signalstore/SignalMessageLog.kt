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
        sentTimestamp: Long,
        content: Content,
        urgent: Boolean,
        groupId: ByteArray?
    ) = withStoreLock(db) {
        if (recipient.isBlank() || sentTimestamp <= 0) return@withStoreLock
        db.writableDatabase.execSQL(
            """
            INSERT INTO message_log (recipient, sent_timestamp, content, urgent, group_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                recipient,
                sentTimestamp,
                content.encode(),
                if (urgent) 1 else 0,
                groupId,
                System.currentTimeMillis()
            )
        )
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

    private companion object {
        /**
         * How long a sent message is worth keeping in case somebody asks for it again.
         *
         * A day, which is Signal's own window. A retry receipt follows the failure by minutes;
         * past that this is plaintext kept for a resend that is never going to be asked for.
         */
        private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(1)
    }
}
