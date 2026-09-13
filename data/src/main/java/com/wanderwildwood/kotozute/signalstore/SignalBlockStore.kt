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

    /**
     * Whether this person is blocked, by whichever of their names the account used.
     *
     * ⚠ Matching the account id alone was half a block. The primary stores a block by phone
     * number too -- `blocked.blockedE164s` and `blocked.numbers` arrive as rows with no account
     * id at all -- and those rows were never consulted, so somebody blocked by number went on
     * arriving here, raising a notification and, worse, being sent a delivery receipt telling
     * them this phone was on and had received them.
     */
    fun isBlocked(serviceId: String?, e164: String? = null): Boolean =
        matches(individuals(), serviceId, e164)

    companion object {
        /**
         * Whether any of [blocked] names this person, by either of the names they have.
         *
         * ⚠ Both names, and either is enough. The account can hold a block against a phone
         * number with no account id at all -- `blocked.blockedE164s` -- and a block against an
         * account id with no number. Testing one of them is half a block, and the half that is
         * missed goes on arriving: decrypted, filed, shown, notified, and answered with a
         * delivery receipt telling the blocked person the phone is on and reading them.
         *
         * Pure, so it can be asked without a database. The hard part was never this test; it
         * was the caller having a number to give it -- see the receive path, which resolves it
         * from the recipient row because a modern envelope does not carry one.
         */
        internal fun matches(
            blocked: List<Blocked>,
            serviceId: String?,
            e164: String?
        ): Boolean {
            if (serviceId.isNullOrBlank() && e164.isNullOrBlank()) return false
            return blocked.any { one ->
                (!serviceId.isNullOrBlank() && one.aci.equals(serviceId, ignoreCase = true)) ||
                    (!e164.isNullOrBlank() && one.e164.equals(e164, ignoreCase = true))
            }
        }
    }

    /**
     * Whether this group is blocked.
     *
     * A blocked group's messages arrive from members who are not themselves blocked, so the
     * per-person check never sees them. Blocking a group and still being shown it is the same
     * failure as blocking a person and still being shown them.
     */
    fun isGroupBlocked(groupId: ByteArray?): Boolean {
        if (groupId == null || groupId.isEmpty()) return false
        return groups().any { it.contentEquals(groupId) }
    }
}
