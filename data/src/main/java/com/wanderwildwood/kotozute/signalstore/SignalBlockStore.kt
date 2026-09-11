package com.wanderwildwood.kotozute.signalstore

import timber.log.Timber

/**
 * Who this account has blocked, as the primary last said.
 *
 * Blocking is not a local decision. Signal keeps one blocked list per account and syncs it
 * whole: a device that wants to change it sends **the entire list**, and whatever it sends
 * becomes the list. So a linked device that blocks somebody without knowing the rest would
 * quietly unblock everyone else on it -- which is the one failure here that nobody would
 * notice until the wrong person got through.
 *
 * Hence [known]: until a list has arrived from the primary, this refuses to produce one, and
 * the repository asks for it rather than inventing it.
 */
internal class SignalBlockStore(private val db: ProtocolDatabase) {

    data class Blocked(val aci: String?, val e164: String?, val blockedAt: Long)

    /**
     * Whether the primary has ever told this device what the list is.
     *
     * An empty list is a real answer -- most accounts block nobody -- so this cannot be read
     * off the rows. [store] writes a marker beside them, and its presence is the answer.
     */
    fun known(): Boolean = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM blocked WHERE aci = '' AND e164 = ''", null
        ).use { it.moveToFirst() }
    }

    /** Replaces everything: this is what a blocked sync carries, and it arrives whole. */
    fun store(individuals: List<Blocked>, groups: List<ByteArray>) = withStoreLock(db) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            database.execSQL("DELETE FROM blocked")
            database.execSQL("DELETE FROM blocked_group")
            // The marker row, so that "no one is blocked" can be told from "nobody has said".
            database.execSQL(
                "INSERT INTO blocked (aci, e164, blocked_at) VALUES ('', '', ?)",
                arrayOf<Any?>(System.currentTimeMillis())
            )
            individuals.forEach { one ->
                database.execSQL(
                    "INSERT OR REPLACE INTO blocked (aci, e164, blocked_at) VALUES (?, ?, ?)",
                    arrayOf<Any?>(one.aci.orEmpty(), one.e164.orEmpty(), one.blockedAt)
                )
            }
            groups.forEach { id ->
                database.execSQL(
                    "INSERT OR REPLACE INTO blocked_group (group_id) VALUES (?)", arrayOf<Any?>(id)
                )
            }
            database.setTransactionSuccessful()
            Timber.i("signal blocked: stored %d individual(s), %d group(s)", individuals.size, groups.size)
        } finally {
            database.endTransaction()
        }
    }

    /** The list as it stands, without the marker row. */
    fun individuals(): List<Blocked> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT aci, e164, blocked_at FROM blocked WHERE aci != '' OR e164 != ''", null
        ).use { c ->
            generateSequence {
                if (c.moveToNext()) {
                    Blocked(
                        c.getString(0).takeIf { it.isNotBlank() },
                        c.getString(1).takeIf { it.isNotBlank() },
                        c.getLong(2)
                    )
                } else null
            }.toList()
        }
    }

    fun groups(): List<ByteArray> = withStoreLock(db) {
        db.readableDatabase.rawQuery("SELECT group_id FROM blocked_group", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getBlob(0) else null }.toList()
        }
    }

    fun isBlocked(aci: String): Boolean =
        individuals().any { it.aci.equals(aci, ignoreCase = true) }
}
