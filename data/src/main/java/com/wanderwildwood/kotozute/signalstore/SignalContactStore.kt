package com.wanderwildwood.kotozute.signalstore

import timber.log.Timber

/** Sixteen random bytes, as Signal's `StorageSyncHelper.KEY_GENERATOR` makes them. */
private const val STORAGE_ID_BYTES = 16

/** Signal's `SealedSenderAccessMode`, by the same numbers it stores them under. */
internal const val SEALED_SENDER_UNKNOWN = 0
internal const val SEALED_SENDER_DISABLED = 1
internal const val SEALED_SENDER_ENABLED = 2
internal const val SEALED_SENDER_UNRESTRICTED = 3

/**
 * Who the people on the other end are.
 *
 * A linked device has no address book of its own and no way to resolve an id on its own
 * authority, so everything here came from somewhere else: the account's stored records, a
 * shared group, contact discovery, or a message from the person themselves.
 *
 * **A person is a row, and their ids are columns.** This used to be keyed *by* the service id,
 * which could not represent what actually happens -- somebody met first as a phone-number
 * identity and later turning out to be an account already known. That was two rows with no way
 * to say they were one person. See [ProtocolStoreSchema.RECIPIENT].
 *
 * The method names still speak of an `aci` because that is what nearly every caller has: the
 * thing a conversation is keyed by. Where a person is known only by their phone-number
 * identity, that *is* their service id and these answer for it too.
 */
internal class SignalContactStore(private val db: ProtocolDatabase) {

    /**
     * One person as some source knows them.
     *
     * [serviceId] is how a conversation with them is keyed -- their account id where there is
     * one, their phone-number identity otherwise. [pni] is set separately only when a source
     * knew both at once, which is the moment two halves can be joined.
     */
    data class Contact(
        val serviceId: String,
        val e164: String? = null,
        val name: String? = null,
        val profileKey: ByteArray? = null,
        val pni: String? = null,
        /** The @name they chose, where the source knew one. */
        val username: String? = null
    )

    /** Whether a service id is a phone-number identity rather than an account. */
    private fun isPni(serviceId: String) = serviceId.startsWith(PNI_PREFIX)

    /**
     * Writes people down, joining halves rather than duplicating them.
     *
     * The rule that matters: **a value already known is never overwritten by a blank one.** A
     * sync can carry somebody with no name -- known only as a number -- and letting that win
     * means a later sync silently un-names people. That was true of the old table and is true
     * of this one.
     *
     * Where a contact names both an account and a phone-number identity, the two are the same
     * person by definition, so an existing row for either is filled in rather than joined by a
     * second row. This is the narrow, safe part of what Signal's `processPnpTupleToChangeSet`
     * does; the full set of cases -- a number moving between people, an account
     * re-registering -- is still ahead.
     */
    fun store(contacts: List<Contact>) = withStoreLock(db) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            contacts.forEach { c ->
                if (c.serviceId.isBlank()) return@forEach
                val aci = if (isPni(c.serviceId)) null else c.serviceId
                val pni = c.pni ?: c.serviceId.takeIf { isPni(it) }
                upsert(database, aci, pni, c.e164, c.name, c.profileKey, c.username)
            }
            database.setTransactionSuccessful()
            Timber.i("signal contacts: stored %d", contacts.size)
        } finally {
            database.endTransaction()
        }
    }

    /**
     * One person, found by whichever id is already on file.
     *
     * The account id is looked for first. A row found by phone-number identity that has no
     * account id yet is then *given* one -- which is the merge, and the reason this table
     * exists: the person met as a PNI and the person met as an ACI become one row rather than
     * two, without a primary key having to change.
     */
    private fun upsert(
        database: net.zetetic.database.sqlcipher.SQLiteDatabase,
        aci: String?,
        pni: String?,
        e164: String?,
        name: String?,
        profileKey: ByteArray?,
        username: String?
    ) {
        val now = System.currentTimeMillis()
        val byAci = aci?.let { rowIdFor(database, "aci", it) }
        val byPni = pni?.let { rowIdFor(database, "pni", it) }
        // By number too. One person can be here three times over -- found by number from
        // discovery, by phone-number identity from a group, by account id from the account's
        // own records -- learned months apart from sources that never mention each other.
        val byE164 = e164.orNull()?.let { rowIdForNumber(database, it) }

        // The one decision worth stating on its own; see [RecipientMerge].
        val existing = when (val plan = RecipientMerge.plan(byAci, byPni, byE164)) {
            is RecipientMerge.Plan.Insert -> null
            is RecipientMerge.Plan.Update -> plan.id
            is RecipientMerge.Plan.Merge -> {
                // Several rows, one person, and this contact is what proved it.
                plan.absorb.forEach { absorb(database, keep = plan.keep, absorb = it) }
                Timber.i("signal contacts: %d row(s) turned out to be one person", plan.absorb.size + 1)
                plan.keep
            }
        }

        if (existing == null) {
            database.execSQL(
                "INSERT INTO recipient (aci, pni, e164, name, profile_key, username, updated_timestamp) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(aci, pni, e164.orNull(), name.orNull(), profileKey, username.orNull(), now)
            )
            return
        }

        // A new value wins where there is one; a blank never overwrites. Those are two
        // different rules and both matter.
        //
        // ⚠ This briefly had it as "what is already here always wins", which quietly made the
        // whole table write-once: somebody who changed their profile name, or moved to a new
        // number, kept the old one for ever, and a profile key that had been rotated could
        // never be replaced. The rule that was being preserved is only the second one -- a
        // sync can carry somebody with no name, and letting that land would un-name people.
        //
        // The two ids stay fill-only. A contact record naming a *different* account for a
        // phone-number identity we already hold is not an update, it is two people or a
        // person who has moved, and quietly overwriting one with the other is how a
        // conversation ends up pointing at a stranger. Resolving that properly is the rest of
        // Signal's PNP logic; until then it is left alone and said out loud.
        if (aci != null && byPni != null && byAci == null) {
            db.readableDatabase.rawQuery(
                "SELECT aci FROM recipient WHERE _id = ?", arrayOf(byPni.toString())
            ).use { c ->
                val held = if (c.moveToFirst()) c.getString(0) else null
                if (held != null && held != aci) {
                    Timber.w("signal contacts: a phone-number identity now names a different account; left alone")
                }
            }
        }
        database.execSQL(
            """
            UPDATE recipient SET
              aci = COALESCE(aci, ?),
              pni = COALESCE(pni, ?),
              e164 = COALESCE(?, e164),
              name = COALESCE(?, name),
              profile_key = COALESCE(?, profile_key),
              username = COALESCE(?, username),
              -- A new profile key means the name we hold was decrypted with the old one.
              -- Signal zeroes last_profile_fetch on every profile key write for this reason.
              last_profile_fetch = CASE
                WHEN ? IS NOT NULL AND (profile_key IS NULL OR profile_key != ?) THEN 0
                ELSE last_profile_fetch
              END,
              -- A new profile key makes everything learned about their sealed sender stale:
              -- what we knew was learned without it, or with the old one, and "they refused"
              -- may only have meant "we were guessing". Signal resets the mode on every
              -- profile key write for exactly this reason -- without it, one failed guess
              -- would rule out sealed sender to that person permanently, including after
              -- they shared the key that would have worked.
              sealed_sender_mode = CASE
                WHEN ? IS NOT NULL AND (profile_key IS NULL OR profile_key != ?)
                  THEN $SEALED_SENDER_UNKNOWN
                ELSE sealed_sender_mode
              END,
              updated_timestamp = ?
            WHERE _id = ?
            """.trimIndent(),
            arrayOf<Any?>(
                aci, pni, e164.orNull(), name.orNull(), profileKey, username.orNull(),
                profileKey, profileKey, profileKey, profileKey, now, existing
            )
        )
    }

    private fun String?.orNull(): String? = this?.takeIf { it.isNotBlank() }

    /**
     * The row that answers to a phone number.
     *
     * A number is not unique here, so this can face more than one row: the account row is
     * preferred, since that is the one a conversation is keyed by. The others are candidates
     * to be folded in, which is what the merge above is for.
     */
    private fun rowIdForNumber(
        database: net.zetetic.database.sqlcipher.SQLiteDatabase,
        e164: String
    ): Long? = database.rawQuery(
        "SELECT _id FROM recipient WHERE e164 = ? ORDER BY aci IS NULL LIMIT 1", arrayOf(e164)
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun rowIdFor(
        database: net.zetetic.database.sqlcipher.SQLiteDatabase,
        column: String,
        value: String
    ): Long? = database.rawQuery("SELECT _id FROM recipient WHERE $column = ?", arrayOf(value))
        .use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** One column of the row that answers to [serviceId], by either of its ids. */
    private fun <T> byServiceId(serviceId: String, column: String, read: (android.database.Cursor) -> T?): T? =
        withStoreLock(db) {
            db.readableDatabase.rawQuery(
                "SELECT $column FROM recipient WHERE aci = ? OR pni = ? LIMIT 1",
                arrayOf(serviceId, serviceId)
            ).use { c -> if (c.moveToFirst()) read(c) else null }
        }

    /**
     * Notes that this person's profile has just been asked for.
     *
     * Written whether or not a name came back: a fetch that returned nothing has still been
     * paid for, and repeating it every batch would spend the whole per-pass budget on the same
     * handful of people who have no readable profile.
     */
    fun markProfileFetched(serviceId: String) = withStoreLock(db) {
        db.writableDatabase.execSQL(
            "UPDATE recipient SET last_profile_fetch = ? WHERE aci = ? OR pni = ?",
            arrayOf<Any?>(System.currentTimeMillis(), serviceId, serviceId)
        )
    }

    /** A contact's profile key, or null. Sealed sender needs it; see [SealedSender]. */
    fun profileKeyFor(aci: String): ByteArray? = byServiceId(aci, "profile_key") { it.getBlob(0) }

    /**
     * What we have learned about whether this person accepts sealed sender.
     *
     * Unknown for anyone never sent to, which is the right starting point: it means try, and
     * trying is the only way the answer is ever learned.
     */
    fun sealedSenderModeFor(serviceId: String): Int =
        byServiceId(serviceId, "sealed_sender_mode") { it.getInt(0) } ?: SEALED_SENDER_UNKNOWN

    /**
     * Write down what a send taught us.
     *
     * Only ever called with the outcome of a real send, so it cannot drift: if they stop
     * accepting sealed sender the next send says so and this follows it back down.
     */
    fun setSealedSenderMode(serviceId: String, mode: Int) = withStoreLock(db) {
        db.writableDatabase.execSQL(
            "UPDATE recipient SET sealed_sender_mode = ? WHERE aci = ? OR pni = ?",
            arrayOf<Any?>(mode, serviceId, serviceId)
        )
    }

    /** The name for one service id, or null when nobody has told us. */
    fun nameFor(aci: String): String? =
        byServiceId(aci, "name") { it.getString(0)?.takeIf { s -> s.isNotBlank() } }

    /** The number for one service id, or null when nothing has ever carried it. */
    fun numberFor(aci: String): String? =
        byServiceId(aci, "e164") { it.getString(0)?.takeIf { s -> s.isNotBlank() } }

    /**
     * The service id known for a number, or null.
     *
     * An account id in preference to a phone-number identity: both address the same person,
     * and the account is the one everything else is keyed by. `e164` is not unique here, so
     * this genuinely can face two rows.
     */
    fun aciForNumber(e164: String): String? = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT COALESCE(aci, pni) FROM recipient WHERE e164 = ? ORDER BY aci IS NULL LIMIT 1",
            arrayOf(e164)
        ).use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
    }

    /**
     * Contacts whose profile is worth fetching.
     *
     * Anyone with no name, and anyone whose profile has not been read for a while. The second
     * half is what lets a name change: eligibility used to be "has no name", so a name learned
     * once could never be corrected -- somebody who changed what they call themselves kept the
     * old name here for ever.
     *
     * Bounded per pass, because this is a network round trip each and it runs after a batch.
     */
    fun needingProfile(staleBefore: Long, limit: Int): List<Pair<String, ByteArray>> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            """
            SELECT COALESCE(aci, pni), profile_key FROM recipient
            WHERE profile_key IS NOT NULL
              AND (name IS NULL OR name = '' OR last_profile_fetch < ?)
            ORDER BY (name IS NULL OR name = '') DESC, last_profile_fetch ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(staleBefore.toString(), limit.toString())
        ).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) to c.getBlob(1) else null }.toList()
        }
    }

    /** How many people are known, how many have a profile key, and how many have a name. */
    /**
     * How the address book stands, in the terms that decide what a person actually sees.
     *
     * [nameless] is the number that matters and the one this used not to report: a contact
     * with no name and no number shows as eight characters of a service id, which is the
     * complaint this whole rail started from. "201 contacts, 125 named" reads like a healthy
     * directory with a few gaps; it does not say that 76 rows can only be shown as noise.
     */
    data class Counts(
        val known: Int,
        val withProfileKey: Int,
        val named: Int,
        val withNumber: Int,
        /** Neither a name, a number, nor a username: nothing to show but a fragment of an id. */
        val nameless: Int,
        val withUsername: Int
    )

    /**
     * ⚠ `WHERE group_id IS NULL`, because a group has a row in this table too (v19) and a
     * group is not a contact. Without it the settings line would count every marked group as
     * a contact -- and, having no name, number or username, as one with nothing to show but
     * an id. Signal spells the same filter out as `FILTER_GROUPS = " AND group_id IS NULL"`
     * and pairs it with the service-id test wherever it asks about people.
     */
    fun counts(): Counts = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            """
            SELECT count(*),
                   sum(CASE WHEN profile_key IS NOT NULL THEN 1 ELSE 0 END),
                   sum(CASE WHEN name IS NOT NULL AND name != '' THEN 1 ELSE 0 END),
                   sum(CASE WHEN e164 IS NOT NULL AND e164 != '' THEN 1 ELSE 0 END),
                   sum(CASE WHEN (name IS NULL OR name = '') AND (e164 IS NULL OR e164 = '')
                                 AND (username IS NULL OR username = '')
                            THEN 1 ELSE 0 END),
                   sum(CASE WHEN username IS NOT NULL AND username != '' THEN 1 ELSE 0 END)
            FROM recipient
            WHERE group_id IS NULL
            """.trimIndent(), null
        ).use { c ->
            if (c.moveToFirst()) {
                Counts(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5))
            } else {
                Counts(0, 0, 0, 0, 0, 0)
            }
        }
    }

    /**
     * Everyone this account can write to, named or not, once each.
     *
     * [all] answers a different question -- it is for renaming threads, so it drops anyone
     * without a name. Starting a conversation cannot drop them: an unnamed contact is still a
     * person this account can write to, and on an account where no profile key has ever
     * arrived that is most of them.
     *
     * One row is one person now, so the duplicate that the old table could produce -- somebody
     * listed once by account and once by phone-number identity -- cannot occur here.
     */
    fun everyone(): List<Contact> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT COALESCE(aci, pni), e164, name, pni, username FROM recipient " +
                "WHERE aci IS NOT NULL OR pni IS NOT NULL",
            null
        ).use { c ->
            generateSequence {
                if (c.moveToNext()) {
                    Contact(
                        serviceId = c.getString(0),
                        e164 = c.getString(1),
                        name = c.getString(2),
                        pni = c.getString(3),
                        username = c.getString(4)
                    )
                } else {
                    null
                }
            }.toList()
        }
    }

    /**
     * Records that a phone-number identity and an account id are the same person.
     *
     * Two things, not one: the pairing is written down so a conversation already held under
     * the PNI can be folded later, and the two rows -- if they are two -- become one.
     *
     * ⚠ Only ever called with a pairing the account itself stated, or one the person proved
     * with a signature. See the note on [ProtocolStoreSchema.PNI_ACI].
     */
    fun pair(pni: String, aci: String) = withStoreLock(db) {
        if (pni.isBlank() || aci.isBlank()) return@withStoreLock
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            database.execSQL(
                """
                INSERT INTO pni_aci (pni, aci, updated_timestamp) VALUES (?, ?, ?)
                ON CONFLICT(pni) DO UPDATE SET
                  aci = excluded.aci,
                  updated_timestamp = excluded.updated_timestamp
                """.trimIndent(),
                arrayOf<Any?>(pni, aci, System.currentTimeMillis())
            )
            join(database, pni, aci)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Makes the two halves of one person into one row.
     *
     * The account row wins, because it is what conversations are keyed by. Anything the
     * phone-number row knew that the account row did not is carried across first, and only
     * then is it removed -- losing a name or a number to a merge would be a worse bug than
     * the duplicate it fixes.
     */
    private fun join(database: net.zetetic.database.sqlcipher.SQLiteDatabase, pni: String, aci: String) {
        val pniRow = rowIdFor(database, "pni", pni)
        val aciRow = rowIdFor(database, "aci", aci)

        when (val plan = RecipientMerge.plan(aciRow, pniRow)) {
            is RecipientMerge.Plan.Merge -> {
                plan.absorb.forEach { absorb(database, keep = plan.keep, absorb = it) }
                Timber.i("signal contacts: two halves of one person became one row")
                return
            }
            else -> Unit
        }

        if (pniRow == null) {
            // Nothing to join. Record the phone-number identity on the account's row so the
            // next lookup by it finds the right person.
            if (aciRow != null) {
                database.execSQL(
                    "UPDATE recipient SET pni = COALESCE(pni, ?) WHERE _id = ?",
                    arrayOf<Any?>(pni, aciRow)
                )
            }
            return
        }
        if (aciRow == null) {
            // Known only as a phone-number identity until now: this is the moment it becomes
            // an account, and it is an update, not a new person.
            database.execSQL(
                "UPDATE recipient SET aci = COALESCE(aci, ?) WHERE _id = ?",
                arrayOf<Any?>(aci, pniRow)
            )
            return
        }
    }

    /**
     * Folds one row into another and removes it.
     *
     * Everything the absorbed row knew is carried across first, and only where the kept row
     * has a hole -- what is already on the surviving row is what conversations have been using,
     * so it wins. Then the other goes, in the same transaction: a half-done merge is two rows
     * that now disagree.
     */
    private fun absorb(database: net.zetetic.database.sqlcipher.SQLiteDatabase, keep: Long, absorb: Long) {
        database.execSQL(
            """
            UPDATE recipient SET
              aci = COALESCE(aci, (SELECT aci FROM recipient WHERE _id = ?)),
              pni = COALESCE(pni, (SELECT pni FROM recipient WHERE _id = ?)),
              e164 = COALESCE(e164, (SELECT e164 FROM recipient WHERE _id = ?)),
              name = COALESCE(name, (SELECT name FROM recipient WHERE _id = ?)),
              profile_key = COALESCE(profile_key, (SELECT profile_key FROM recipient WHERE _id = ?))
            WHERE _id = ?
            """.trimIndent(),
            arrayOf<Any?>(absorb, absorb, absorb, absorb, absorb, keep)
        )
        database.execSQL("DELETE FROM recipient WHERE _id = ?", arrayOf<Any?>(absorb))
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
            """
            SELECT COALESCE(aci, pni), name FROM recipient
            WHERE name IS NOT NULL AND name != ''
            """.trimIndent(), null
        ).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) to c.getString(1) else null }.toMap()
        }
    }

    /**
     * Marks this row as differing from what the account's records hold.
     *
     * A port of Signal's `rotateStorageId`: sixteen random bytes, base64 with padding. The new
     * value is not meaningful in itself -- what matters is that it no longer matches the id in
     * the manifest, which is how a sync finds what to push. Signal keeps no separate "dirty"
     * column for the same reason.
     *
     * ⚠ **Local changes only.** Applying what the account just told us is not a local change,
     * and rotating there would make this device permanently believe it had something to send --
     * a loop between two devices each undoing the other, which is what Signal's
     * `StorageSyncLoopDetector` exists to catch.
     *
     * Nothing writes to the storage service yet. This records; see
     * `docs/DECISION-storage-write.md`.
     */
    fun rotateStorageId(serviceId: String) = withStoreLock(db) {
        val id = ByteArray(STORAGE_ID_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        db.writableDatabase.execSQL(
            "UPDATE recipient SET storage_id = ? WHERE aci = ? OR pni = ?",
            arrayOf<Any?>(
                android.util.Base64.encodeToString(id, android.util.Base64.NO_WRAP),
                serviceId,
                serviceId
            )
        )
    }

    /**
     * The same mark, on a group.
     *
     * A group's state lives on a GroupV2Record rather than a ContactRecord, but the dirty flag
     * does not move with it: Signal's `RecipientTable` holds groups as rows of its own
     * (`getOrInsertFromGroupId`, which gives the new row a storage id there and then), so one
     * flag in one table covers a muted group as well as a renamed person. Before this there
     * was no row to mark and muting or archiving a group went unrecorded.
     *
     * [groupId] is base64 of the group id, which is what a `group:` thread key already carries.
     */
    fun rotateStorageIdForGroup(groupId: String) = withStoreLock(db) {
        val id = ByteArray(STORAGE_ID_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val storageId = android.util.Base64.encodeToString(id, android.util.Base64.NO_WRAP)
        // Insert-or-update in one statement: a group has no row until something about it is
        // changed here, and the first change is also the row's reason to exist.
        db.writableDatabase.execSQL(
            """
            INSERT INTO recipient (group_id, storage_id, updated_timestamp)
            VALUES (?, ?, ?)
            ON CONFLICT(group_id) DO UPDATE SET storage_id = excluded.storage_id
            """.trimIndent(),
            arrayOf<Any?>(groupId, storageId, System.currentTimeMillis())
        )
    }

    /**
     * One row the account has not been told about.
     *
     * Either a person or a group, never both -- which is which is what decides whether it
     * would go up as a ContactRecord or a GroupV2Record.
     */
    data class Pending(
        val serviceId: String?,
        val groupId: String?,
        val name: String?,
        val hasProfileKey: Boolean
    )

    /** Rows the account has not been told about, for the diff that will one day be a write. */
    fun needingStoragePush(): List<Pending> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            """
            SELECT COALESCE(aci, pni), group_id, name, profile_key IS NOT NULL
            FROM recipient WHERE storage_id IS NOT NULL
            """.trimIndent(), null
        ).use { c ->
            generateSequence {
                if (!c.moveToNext()) null
                else Pending(
                    serviceId = c.getString(0),
                    groupId = c.getString(1),
                    name = c.getString(2),
                    hasProfileKey = c.getInt(3) != 0
                )
            }.toList()
        }
    }

    /** Every username known, for the picker's last fallback before a bare id. */
    fun usernames(): Map<String, String> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            """
            SELECT COALESCE(aci, pni), username FROM recipient
            WHERE username IS NOT NULL AND username != ''
            """.trimIndent(), null
        ).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) to c.getString(1) else null }.toMap()
        }
    }

    private companion object {
        /** How a phone-number identity writes itself as a service id. */
        private const val PNI_PREFIX = "PNI:"
    }
}
