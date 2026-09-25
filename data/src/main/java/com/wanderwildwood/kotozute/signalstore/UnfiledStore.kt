package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.signal.BridgeMessage
import timber.log.Timber

/**
 * Decrypted messages between their envelope going and the message database having them.
 * See [ProtocolStoreSchema.UNFILED].
 */
internal class UnfiledStore(private val db: ProtocolDatabase) {

    /** Swaps an envelope for the message it decrypted to, in one transaction. */
    fun swap(envelopeId: Long, message: BridgeMessage) = withStoreLock(db) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            w.execSQL(
                "INSERT OR REPLACE INTO unfiled (id, message, stored_timestamp) VALUES (?, ?, ?)",
                arrayOf<Any?>(message.id, UnfiledMessage.encode(message), System.currentTimeMillis())
            )
            w.execSQL("DELETE FROM envelope WHERE _id = ?", arrayOf<Any?>(envelopeId))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /**
     * Messages decrypted and not yet filed, oldest first.
     *
     * ⚠ One that will not decode is logged and left in place rather than dropped: it is the
     * only copy, and a fix to the decoder can still read it.
     */
    fun all(): List<BridgeMessage> = withStoreLock(db) {
        db.readableDatabase.rawQuery("SELECT message FROM unfiled ORDER BY stored_timestamp", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }
                .toList()
                .mapNotNull { json ->
                    runCatching { UnfiledMessage.decode(json) }
                        .onFailure { Timber.e(it, "signal receive: an unfiled message will not decode; kept") }
                        .getOrNull()
                }
        }
    }

    /** The message database has these now. */
    fun filed(ids: List<String>) = withStoreLock(db) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            ids.forEach { w.execSQL("DELETE FROM unfiled WHERE id = ?", arrayOf<Any?>(it)) }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }
}
