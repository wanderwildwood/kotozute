package com.wanderwildwood.kotozute.signalstore

import timber.log.Timber

/**
 * Who the people on the other end are.
 *
 * A linked device has no address book of its own and no way to resolve an ACI on its own
 * authority, so every name here came from the primary's contacts sync. Without it the inbox
 * shows raw service ids, which is what it did before this existed.
 */
internal class SignalContactStore(private val db: ProtocolDatabase) {

    data class Contact(
        val aci: String,
        val e164: String?,
        val name: String?,
        val profileKey: ByteArray? = null
    )

    /**
     * Upserts on ACI.
     *
     * A name already stored is **kept** when the incoming one is blank. A sync can carry a
     * contact with no name -- someone known only as a number -- and letting that overwrite a
     * good name means a later sync silently un-names people.
     */
    fun store(contacts: List<Contact>) = withStoreLock(db) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            contacts.forEach { c ->
                database.execSQL(
                    """
                    INSERT INTO contact (aci, e164, name, profile_key, updated_timestamp)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(aci) DO UPDATE SET
                      e164 = COALESCE(NULLIF(excluded.e164, ''), contact.e164),
                      name = COALESCE(NULLIF(excluded.name, ''), contact.name),
                      profile_key = COALESCE(excluded.profile_key, contact.profile_key),
                      updated_timestamp = excluded.updated_timestamp
                    """.trimIndent(),
                    arrayOf<Any?>(
                        c.aci, c.e164.orEmpty(), c.name.orEmpty(), c.profileKey, System.currentTimeMillis()
                    )
                )
            }
            database.setTransactionSuccessful()
            Timber.i("signal contacts: stored %d", contacts.size)
        } finally {
            database.endTransaction()
        }
    }

    /** A contact's profile key, or null. Sealed sender needs it; see [SealedSender]. */
    fun profileKeyFor(aci: String): ByteArray? = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT profile_key FROM contact WHERE aci = ?", arrayOf(aci)
        ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
    }

    /** The name for one service id, or null when nobody has told us. */
    fun nameFor(aci: String): String? = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT name FROM contact WHERE aci = ?", arrayOf(aci)
        ).use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
    }

    /** The number for one service id, or null when nothing has ever carried it. */
    fun numberFor(aci: String): String? = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT e164 FROM contact WHERE aci = ?", arrayOf(aci)
        ).use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
    }

    /** The service id known for one number, or null. */
    fun aciForNumber(e164: String): String? = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT aci FROM contact WHERE e164 = ?", arrayOf(e164)
        ).use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
    }

    /** Contacts whose profile could be fetched, and whose name we do not already have. */
    fun needingProfile(): List<Pair<String, ByteArray>> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT aci, profile_key FROM contact WHERE profile_key IS NOT NULL AND (name IS NULL OR name = '')",
            null
        ).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) to c.getBlob(1) else null }.toList()
        }
    }

    /** How many contacts are known, how many have a profile key, and how many have a name. */
    fun counts(): Triple<Int, Int, Int> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            """
            SELECT count(*),
                   sum(CASE WHEN profile_key IS NOT NULL THEN 1 ELSE 0 END),
                   sum(CASE WHEN name IS NOT NULL AND name != '' THEN 1 ELSE 0 END)
            FROM contact
            """.trimIndent(), null
        ).use { c ->
            if (c.moveToFirst()) Triple(c.getInt(0), c.getInt(1), c.getInt(2)) else Triple(0, 0, 0)
        }
    }

    /**
     * Everyone the primary has told us about, named or not.
     *
     * [all] answers a different question -- it is for renaming threads, so it drops anyone
     * without a name. Starting a conversation cannot drop them: an unnamed contact is still
     * a person this account can write to, and on an account where no profile key has ever
     * arrived that is most of them.
     */
    fun everyone(): List<Contact> = withStoreLock(db) {
        // One person, once. A row can be keyed by a phone-number identity while the same
        // person also has a row under their account id -- discovery finds them by number
        // while the account's own records know them properly -- and listing both puts them
        // in the list twice, as two people, one of whom is a worse way to reach them. Where
        // the pairing is known and the account row is itself here, the phone-number row is
        // the one to hide: it is not wrong, it is the same person said less well.
        //
        // Hidden, not deleted. A conversation already held under that PNI is keyed by that
        // row, and removing it would strand those messages.
        db.readableDatabase.rawQuery(
            """
            SELECT aci, e164, name FROM contact
            WHERE aci NOT IN (
              SELECT p.pni FROM pni_aci p
              JOIN contact c ON c.aci = p.aci
            )
            """.trimIndent(),
            null
        ).use { c ->
            generateSequence {
                if (c.moveToNext()) Contact(c.getString(0), c.getString(1), c.getString(2)) else null
            }.toList()
        }
    }

    /**
     * Records that a phone-number identity and an account id are the same person.
     *
     * See the note on the table in [ProtocolStoreSchema.PNI_ACI] for why this is only ever
     * filled from the account's own storage records and never from an incoming message.
     */
    fun pair(pni: String, aci: String) = withStoreLock(db) {
        if (pni.isBlank() || aci.isBlank()) return@withStoreLock
        db.writableDatabase.execSQL(
            """
            INSERT INTO pni_aci (pni, aci, updated_timestamp) VALUES (?, ?, ?)
            ON CONFLICT(pni) DO UPDATE SET
              aci = excluded.aci,
              updated_timestamp = excluded.updated_timestamp
            """.trimIndent(),
            arrayOf<Any?>(pni, aci, System.currentTimeMillis())
        )
    }

    /** The account id a phone-number identity belongs to, where that is known. */
    fun aciForPni(pni: String): String? = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT aci FROM pni_aci WHERE pni = ?", arrayOf(pni)
        ).use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
    }

    /** Every pairing known, for folding conversations in one pass after a sync. */
    fun pairings(): Map<String, String> = withStoreLock(db) {
        db.readableDatabase.rawQuery("SELECT pni, aci FROM pni_aci", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) to c.getString(1) else null }
                .toMap()
        }
    }

    /** Every name known, for renaming threads in one pass after a sync. */
    fun all(): Map<String, String> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT aci, name FROM contact WHERE name IS NOT NULL AND name != ''", null
        ).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) to c.getString(1) else null }.toMap()
        }
    }
}
