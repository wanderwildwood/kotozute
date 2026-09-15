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
internal class SignalContactStore(
    private val db: ProtocolDatabase,
    /**
     * Told when somebody this account already had a number for now has a different one.
     *
     * Not when a number is learned for the first time -- see [noteworthyNumberChange].
     */
    private val onNumberChanged: (aci: String, from: String, to: String) -> Unit = { _, _, _ -> }
) {

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
        val username: String? = null,
        /**
         * Hidden by the account owner, and when the account noticed they had left Signal.
         *
         * Both mean "do not offer this person", and both were being decoded and thrown away.
         * Null where the source does not say -- a contacts sync does not carry either -- so a
         * storage read can set them without a sync clearing them again.
         */
        val hidden: Boolean? = null,
        val unregisteredAt: Long? = null
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
                upsert(
                    database, aci, pni, c.e164, c.name, c.profileKey, c.username,
                    c.hidden, c.unregisteredAt
                )
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
        username: String?,
        /** Null where the source does not carry it; see [Contact.hidden]. */
        hidden: Boolean?,
        unregisteredAt: Long?
    ) {
        val now = System.currentTimeMillis()
        val byAci = aci?.let { candidateFor(database, "aci", it) }
        val byPni = pni?.let { candidateFor(database, "pni", it) }
        // By number too. One person can be here three times over -- found by number from
        // discovery, by phone-number identity from a group, by account id from the account's
        // own records -- learned months apart from sources that never mention each other.
        val byE164 = e164.orNull()?.let { candidateForNumber(database, it) }

        // The one decision worth stating on its own; see [RecipientMerge].
        val existing = when (val plan = RecipientMerge.plan(aci, byAci, byPni, byE164)) {
            is RecipientMerge.Plan.Insert -> null

            is RecipientMerge.Plan.InsertAfterSteal -> {
                // Every row that answered belongs to somebody else -- a recycled number, or a
                // phone-number identity that has moved on. Take the stale identifiers off them
                // and give this person a row of their own, rather than writing this person's
                // name over the previous owner's.
                plan.steal.forEach { steal(database, keep = null, from = it.from, held = it.held) }
                null
            }
            is RecipientMerge.Plan.Update -> plan.id
            is RecipientMerge.Plan.Merge -> {
                // Rows that hold no account id of their own: safe to fold in and remove.
                plan.absorb.forEach { absorb(database, keep = plan.keep, absorb = it) }
                // Rows that hold a different one: a different person. Take back the identifier
                // that pointed at them and leave everything else of theirs alone.
                plan.steal.forEach { steal(database, keep = plan.keep, from = it.from, held = it.held) }
                if (plan.absorb.isNotEmpty()) {
                    Timber.i(
                        "signal contacts: %d row(s) turned out to be one person",
                        plan.absorb.size + 1
                    )
                }
                plan.keep
            }
        }

        if (existing == null) {
            database.execSQL(
                "INSERT INTO recipient " +
                    "(aci, pni, e164, name, profile_key, username, hidden, unregistered_at, updated_timestamp) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    aci, pni, e164.orNull(), name.orNull(), profileKey, username.orNull(),
                    // Nothing said means the column's default, not null: these are NOT NULL.
                    if (hidden == true) 1 else 0, unregisteredAt ?: 0L,
                    now
                )
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
        // ⚠ Read before the write, because `e164 = COALESCE(?, e164)` below replaces it and
        // afterwards nothing says what it used to be. In this app a number is not decoration:
        // one person is one row across two rails, so their number changing re-pairs the Signal
        // half of that row with a different text conversation. Upstream notes a number change
        // in the conversation for its own reasons (`RecipientTable` -> `insertNumberChangeMessages`);
        // here there is a second reason on top of theirs.
        val numberBefore = if (e164 != null && aci != null) {
            db.readableDatabase.rawQuery(
                "SELECT e164 FROM recipient WHERE aci = ?", arrayOf(aci)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } else {
            null
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
              -- Fill-only, like the rest: null means "this source does not carry it". A
              -- contacts sync says nothing about either, so it must not clear what a storage
              -- read set.
              hidden = COALESCE(?, hidden),
              unregistered_at = COALESCE(?, unregistered_at),
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
                hidden?.let { if (it) 1 else 0 }, unregisteredAt,
                profileKey, profileKey, profileKey, profileKey, now, existing
            )
        )
        // After the write, so a note never claims a change the store did not take.
        if (aci != null && e164 != null && noteworthyNumberChange(numberBefore, e164)) {
            runCatching { onNumberChanged(aci, numberBefore.orEmpty(), e164) }
                .onFailure { Timber.w(it, "signal contacts: could not note a number change") }
        }
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

    /** As [rowIdForNumber], with the account id the row holds. See [candidateFor]. */
    private fun candidateForNumber(
        database: net.zetetic.database.sqlcipher.SQLiteDatabase,
        e164: String
    ): RecipientMerge.Candidate? = database.rawQuery(
        "SELECT _id, aci FROM recipient WHERE e164 = ? ORDER BY aci IS NULL LIMIT 1", arrayOf(e164)
    ).use { c ->
        if (c.moveToFirst()) {
            RecipientMerge.Candidate(c.getLong(0), c.getString(1)?.takeIf { it.isNotBlank() })
        } else {
            null
        }
    }

    private fun rowIdFor(
        database: net.zetetic.database.sqlcipher.SQLiteDatabase,
        column: String,
        value: String
    ): Long? = database.rawQuery("SELECT _id FROM recipient WHERE $column = ?", arrayOf(value))
        .use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /**
     * The row that answers to [value], with the account id it already holds.
     *
     * ⚠ The account id is what decides whether that row may be deleted. A merge used to be
     * planned from row ids alone, which cannot tell a row holding nothing but a phone number
     * from a second real person -- see [RecipientMerge].
     */
    private fun candidateFor(
        database: net.zetetic.database.sqlcipher.SQLiteDatabase,
        column: String,
        value: String
    ): RecipientMerge.Candidate? =
        database.rawQuery("SELECT _id, aci FROM recipient WHERE $column = ?", arrayOf(value))
            .use { c ->
                if (c.moveToFirst()) {
                    RecipientMerge.Candidate(c.getLong(0), c.getString(1)?.takeIf { it.isNotBlank() })
                } else {
                    null
                }
            }

    /**
     * Take one identifier off a row that is **not** being deleted, and give it to the keeper.
     *
     * Signal's `RemovePni` then `SetPni`, in that order and for a concrete reason: `aci` and
     * `pni` are both UNIQUE here, so setting the keeper first would collide with the value the
     * loser still holds.
     */
    private fun steal(
        database: net.zetetic.database.sqlcipher.SQLiteDatabase,
        /** The row to give it to, or null when the row that will get it does not exist yet. */
        keep: Long?,
        from: Long,
        held: RecipientMerge.Held
    ) {
        val column = when (held) {
            RecipientMerge.Held.PNI -> "pni"
            RecipientMerge.Held.E164 -> "e164"
        }
        val value = database
            .rawQuery("SELECT $column FROM recipient WHERE _id = ?", arrayOf(from.toString()))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: return

        database.execSQL("UPDATE recipient SET $column = NULL WHERE _id = ?", arrayOf<Any?>(from))
        if (keep != null) {
            database.execSQL(
                "UPDATE recipient SET $column = COALESCE($column, ?) WHERE _id = ?",
                arrayOf<Any?>(value, keep)
            )
        }
        // With no keeper the identifier is simply released: the insert that follows carries it
        // in its own INSERT, and the UNIQUE columns would refuse it while the old row held it.
        Timber.i(
            "signal contacts: moved a %s off a row with a different account id, and kept that row",
            column
        )
    }

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
     * Whether the service has said this person is no longer on Signal.
     *
     * Read before retrying anything at them. Upstream's jobs draw the same line from the other
     * end -- `onShouldRetry` returns true for a `PushNetworkException` and **false** for a
     * `ServerRejectedException` -- so a refusal ends the work where a dropped connection does
     * not. Somebody who has left is a refusal that will be repeated every time.
     */
    fun isUnregistered(serviceId: String): Boolean = withStoreLock(db) {
        if (serviceId.isBlank()) return@withStoreLock false
        db.readableDatabase.rawQuery(
            "SELECT unregistered_at FROM recipient WHERE aci = ? OR pni = ? LIMIT 1",
            arrayOf(serviceId, serviceId)
        ).use { c -> c.moveToFirst() && c.getLong(0) > 0 }
    }

    /**
     * Notes that the service says this person is not on Signal.
     *
     * ⚠ A send that comes back "not registered" is the account telling us something, and it
     * was being thrown away. The column existed and was only ever written from a storage
     * record -- so somebody who had left Signal went on being offered in the picker, and every
     * message to them failed the same way, until their primary noticed and wrote a record
     * saying so.
     *
     * Signal marks them on exactly this signal: `SignalDatabase.recipients().markUnregistered`
     * from the send result, and its contact search then excludes them. On the one-to-one path
     * it also refreshes contact discovery, which is the authority; that is deliberately not
     * done here, because discovery on this account is rate-limited and a send failure is not
     * worth spending the window on. The mark is local and a storage read or a later discovery
     * pass clears it.
     */
    fun markUnregistered(serviceId: String, at: Long = System.currentTimeMillis()) = withStoreLock(db) {
        db.writableDatabase.execSQL(
            "UPDATE recipient SET unregistered_at = ? WHERE aci = ? OR pni = ?",
            arrayOf<Any?>(at, serviceId, serviceId)
        )
    }

    /**
     * Whether this address is a phone-number identity with no account identity behind it.
     *
     * Sealed sender cannot reach such a recipient: the access key is checked against the
     * account, and there is no account here yet. Signal forces the mode to DISABLED for
     * exactly this case rather than letting the stored mode speak --
     * `SealedSenderAccessUtil.getEffectiveSealedSenderAccessMode` on the record path
     * (`aci == null && pni != null`) and `Recipient.sealedSenderAccessMode` on the other
     * (`pni.isPresent && pni == serviceId`). Two spellings of one rule.
     *
     * A PNI nobody has told us anything about counts too: no row means no account id, which
     * is the state this is asking about.
     */
    fun addressedOnlyByPni(serviceId: String): Boolean {
        if (!isPni(serviceId)) return false
        return byServiceId(serviceId, "aci") { it.getString(0)?.takeIf { s -> s.isNotBlank() } } == null
    }

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
     * Numbers held by more than one row, which no row should be.
     *
     * The invariant [RecipientMerge] maintains at write time: a number arriving for somebody
     * already holding it is an absorb or a steal, never a second row. Nineteen cases cover that
     * decision, and none of them can speak for rows written *before* it existed -- which is the
     * whole reason upstream ships `DuplicateE164MigrationJob` rather than trusting its own
     * merge logic.
     *
     * Reported rather than repaired. Upstream's migration repairs, but it is repairing data its
     * own old versions wrote; here the question is whether any exists at all, and the answer on
     * a healthy store is none. If it is ever not none, the rows are worth looking at before
     * something automatic touches them -- a wrong merge points a conversation at a stranger.
     *
     * @return each duplicated number and how many rows hold it.
     */
    fun duplicateNumbers(): List<Pair<String, Int>> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            """
            SELECT e164, count(*) FROM recipient
            WHERE e164 IS NOT NULL AND e164 != ''
            GROUP BY e164 HAVING count(*) > 1
            """.trimIndent(),
            null
        ).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) to c.getInt(1) else null }.toList()
        }
    }

    /**
     * Numbers that are not shaped like numbers.
     *
     * The rule is `BadE164MigrationJob`'s, copied rather than invented, including the part that
     * reads as a mistake and is not -- upstream's own comment says *"A number with exactly 7
     * chars (strange but true -- neither shortcodes nor longcodes can be 7 chars long)"*.
     *
     * A malformed number is not cosmetic: every lookup by number is an equality match, so a row
     * holding `(704) 555-0148` can never be found by the `+17045550148` anyone would search
     * with, and the person quietly has two half-rows or none.
     *
     * Reported, not repaired, for the reason [duplicateNumbers] is. Upstream has three tiers of
     * repair because it is fixing what its own old versions wrote; the question here is whether
     * this app has ever written one.
     *
     * @return how many rows hold a number of that shape.
     */
    fun malformedNumbers(): Int = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            """
            SELECT count(*) FROM recipient
            WHERE e164 IS NOT NULL AND (
              e164 GLOB '*[^+0-9]*'
                OR (LENGTH(e164) > 7 AND e164 NOT GLOB '+[0-9]*')
                OR (LENGTH(e164) == 7)
                OR (LENGTH(e164) < 7 AND e164 NOT GLOB '[0-9]*')
            )
            """.trimIndent(),
            null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /**
     * How many people are known, how many have a profile key, and how many have a name.
     *
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
                "WHERE (aci IS NOT NULL OR pni IS NOT NULL) " +
                // ⚠ The account said twice that these people should not be offered, and both
                // were ignored. Signal's contact search does the same two exclusions --
                // `FILTER_HIDDEN` is " AND hidden = ?" with 0, and its SIGNAL_CONTACT clause
                // requires REGISTERED, which it clears whenever the unregistered timestamp is
                // set. Somebody hidden came back at every read; somebody who had left Signal
                // was offered like anyone else, and picking them starts a conversation that
                // cannot deliver.
                "AND hidden = 0 AND unregistered_at = 0",
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
        val pniCandidate = candidateFor(database, "pni", pni)
        val aciCandidate = candidateFor(database, "aci", aci)
        val pniRow = pniCandidate?.id
        val aciRow = aciCandidate?.id

        when (val plan = RecipientMerge.plan(aci, aciCandidate, pniCandidate)) {
            is RecipientMerge.Plan.InsertAfterSteal -> {
                // The phone-number identity has moved to somebody else. Take it off them; the
                // account row, if there is one, keeps everything else it had.
                plan.steal.forEach { steal(database, keep = aciRow, from = it.from, held = it.held) }
                return
            }
            is RecipientMerge.Plan.Merge -> {
                plan.absorb.forEach { absorb(database, keep = plan.keep, absorb = it) }
                plan.steal.forEach { steal(database, keep = plan.keep, from = it.from, held = it.held) }
                if (plan.absorb.isNotEmpty()) {
                    Timber.i("signal contacts: two halves of one person became one row")
                }
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
        remapDependents(database, keep = keep, absorb = absorb)
        database.execSQL("DELETE FROM recipient WHERE _id = ?", arrayOf<Any?>(absorb))
    }

    /**
     * Moves what other tables hold under the absorbed row's names onto the surviving one.
     *
     * ⚠ **Two tables key on a service id and neither followed a merge.** The resend log and the
     * owed-receipt list are written with whatever address a message was sent to, so everything
     * sent to somebody while they were known only by their phone-number identity stayed filed
     * under that identity after they became an account. Their client's retry request arrives
     * naming the account, the lookup misses, and a message that could have been resent cannot
     * be -- which is exactly the case the log exists for.
     *
     * Upstream does the same thing for the same reason (`MessageSendLogTables.remapRecipient`,
     * one of fourteen tables that implement `remapRecipient`), though it is forced to: its rows
     * hold a row id that stops existing. Ours hold a string that stays valid and merely stops
     * being the one anybody asks by, which is why this could go unnoticed.
     *
     * `UPDATE OR REPLACE` on the receipts, because `(recipient, sent_timestamp, kind)` is its
     * primary key and both halves of one person can owe the same receipt. Collapsing the two
     * into one is right: it was always one receipt.
     */
    private fun remapDependents(
        database: net.zetetic.database.sqlcipher.SQLiteDatabase,
        keep: Long,
        absorb: Long
    ) {
        val canonical = database.rawQuery(
            "SELECT COALESCE(aci, pni) FROM recipient WHERE _id = ?", arrayOf(keep.toString())
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return

        val (absorbedAci, absorbedPni) = database.rawQuery(
            "SELECT aci, pni FROM recipient WHERE _id = ?", arrayOf(absorb.toString())
        ).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getString(1) else null to null
        }

        idsToRemap(canonical, absorbedAci, absorbedPni).forEach { was ->
            database.execSQL(
                "UPDATE message_log SET recipient = ? WHERE recipient = ?",
                arrayOf<Any?>(canonical, was)
            )
            database.execSQL(
                "UPDATE OR REPLACE receipt_owed SET recipient = ? WHERE recipient = ?",
                arrayOf<Any?>(canonical, was)
            )
        }
    }

    /**
     * Notes that this person knows us by number and not yet by account.
     *
     * Set when a message from them arrives addressed to this account's **phone-number
     * identity**. That is them saying which of our two identities they hold -- and until they
     * are shown the two are one person, their client keeps a second, separate conversation for
     * us. Signal marks the sender at exactly this moment
     * (`MessageDecryptor`, `RecipientTable.markNeedsPniSignature`).
     *
     * ⚠ The proof is the thing this app was sending to **everybody**: the `includePniSignature`
     * argument on the one-to-one send was hardcoded true. That hands this account's
     * phone-number identity to people who only ever knew its account id -- which is the
     * opposite of what the sealed-sender certificate here is chosen to avoid.
     */
    fun markNeedsPniSignature(serviceId: String) = withStoreLock(db) {
        if (serviceId.isBlank()) return@withStoreLock
        db.writableDatabase.execSQL(
            "UPDATE recipient SET needs_pni_signature = 1 WHERE aci = ? OR pni = ?",
            arrayOf<Any?>(serviceId, serviceId)
        )
    }

    /** Whether this person is still owed the proof. Unknown counts as no: see [clearNeedsPniSignature]. */
    fun needsPniSignature(serviceId: String): Boolean = withStoreLock(db) {
        if (serviceId.isBlank()) return@withStoreLock false
        db.readableDatabase.rawQuery(
            "SELECT needs_pni_signature FROM recipient WHERE aci = ? OR pni = ? LIMIT 1",
            arrayOf(serviceId, serviceId)
        ).use { c -> c.moveToFirst() && c.getInt(0) != 0 }
    }

    /**
     * Notes that the proof has gone.
     *
     * ⚠ Cleared on a **successful send**, where upstream waits for delivery
     * (`PendingPniSignatureMessageTable`, which holds the pending sends and clears the flag
     * when all of them are delivered). The difference is deliberate and the costs are not the
     * same size: clearing early costs one correspondent a proof they may have to learn another
     * way, and never clearing costs every message this account sends carrying it for ever --
     * which is the state this replaces.
     */
    fun clearNeedsPniSignature(serviceId: String) = withStoreLock(db) {
        if (serviceId.isBlank()) return@withStoreLock
        db.writableDatabase.execSQL(
            "UPDATE recipient SET needs_pni_signature = 0 WHERE aci = ? OR pni = ?",
            arrayOf<Any?>(serviceId, serviceId)
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

    /**
     * Records whether the account shares its profile with somebody.
     *
     * ContactRecord's `whitelisted`, and the thing Signal's `PushSendJob.getProfileKey` tests
     * before attaching this account's profile key to a message. Written from the account's own
     * records rather than decided here -- turning sharing off is something a person does on
     * whichever device they happen to be holding, and this is how it reaches the others.
     *
     * ⚠ Not a rotation: this only records what the account said. Nothing here rotates a
     * storage id, because applying what the account told us is not a local change.
     */
    fun setWhitelisted(serviceId: String, whitelisted: Boolean) = withStoreLock(db) {
        if (serviceId.isBlank()) return@withStoreLock
        db.writableDatabase.execSQL(
            "UPDATE recipient SET whitelisted = ? WHERE aci = ? OR pni = ?",
            arrayOf<Any?>(if (whitelisted) 1 else 0, serviceId, serviceId)
        )
    }

    /**
     * Whether to give somebody this account's profile key.
     *
     * ⚠ Unknown counts as yes. A row that has never been touched by a storage read defaults to
     * whitelisted, so nobody already being talked to loses their profile key -- and with it
     * this account's name and avatar -- because of a column that arrived after them. Only an
     * explicit "no" from the account's own records withholds it.
     */
    fun isWhitelisted(serviceId: String): Boolean = withStoreLock(db) {
        if (serviceId.isBlank()) return@withStoreLock true
        db.readableDatabase.rawQuery(
            "SELECT whitelisted FROM recipient WHERE aci = ? OR pni = ? LIMIT 1",
            arrayOf(serviceId, serviceId)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) != 0 else true }
    }

    /**
     * Every account id this phone holds a profile key for, paired with that key.
     *
     * What contact discovery has to send if it wants account ids back. CDSI answers with an
     * ACI only where the asker can already prove it knows that person -- it takes these pairs,
     * turns them into aci/uak pairs, and returns the ACI for the ones that check out. Sent
     * nothing, it can only ever answer with phone-number identities.
     *
     * A port of `RecipientTable.getAllServiceIdProfileKeyPairs`, down to the where clause.
     */
    fun serviceIdProfileKeyPairs(): Map<org.signal.core.models.ServiceId, org.signal.libsignal.zkgroup.profiles.ProfileKey> =
        withStoreLock(db) {
            val pairs = mutableMapOf<
                org.signal.core.models.ServiceId,
                org.signal.libsignal.zkgroup.profiles.ProfileKey
                >()
            db.readableDatabase.rawQuery(
                "SELECT aci, profile_key FROM recipient WHERE aci IS NOT NULL AND profile_key IS NOT NULL",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val aci = org.signal.core.models.ServiceId.ACI.parseOrNull(c.getString(0))
                    val key = runCatching {
                        org.signal.libsignal.zkgroup.profiles.ProfileKey(c.getBlob(1))
                    }.getOrNull()
                    if (aci != null && key != null) pairs[aci] = key
                }
            }
            pairs
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

    internal companion object {
        /** How a phone-number identity writes itself as a service id. */
        private const val PNI_PREFIX = "PNI:"

        /**
         * Whether a number arriving is a *change* worth telling the reader about.
         *
         * The same shape as [SignalProfiles.noteworthyNameChange] and for the same reasons.
         * The first number ever learned is not a change -- a contact discovered by account id
         * has no number until one is found, and without this every one of those would announce
         * a change the moment discovery ran. A number going away is not one either: the write
         * below is fill-only for blanks, so the old number stays, and saying it changed to
         * nothing would describe this app's own gap as the contact's decision.
         *
         * Upstream notes a number change for its own reasons (`RecipientTable`'s
         * `ChangeNumberInsert` -> `insertNumberChangeMessages`). There is a second reason here:
         * one person is one row across two rails, so their number changing re-pairs the Signal
         * half of that row with a different text conversation.
         */
        internal fun noteworthyNumberChange(held: String?, arriving: String): Boolean =
            !held.isNullOrBlank() && arriving.isNotBlank() && held != arriving

        /**
         * Which of an absorbed row's names other tables should stop being filed under.
         *
         * Pure because this is where the mistake would be silent: remapping onto the name a
         * row already has does nothing and looks like it worked, and skipping a name leaves a
         * message unresendable with no sign of it either way. The SQL around it is two plain
         * UPDATE statements.
         */
        internal fun idsToRemap(
            canonical: String?,
            absorbedAci: String?,
            absorbedPni: String?
        ): List<String> {
            if (canonical.isNullOrBlank()) return emptyList()
            return listOfNotNull(absorbedAci, absorbedPni)
                .filter { it.isNotBlank() && it != canonical }
                .distinct()
        }

        /**
         * Whether a stored number is shaped like one, as `BadE164MigrationJob` judges it.
         *
         * The same four rules as the query in [malformedNumbers], as a pure function so they
         * can be tested against upstream's own documented cases. A rule copied from another
         * codebase is worth pinning: if it is ever edited into something subtly different, the
         * query and this drift apart silently and the check starts answering a question nobody
         * asked.
         */
        internal fun looksLikeANumber(e164: String): Boolean = when {
            e164.any { it != '+' && !it.isDigit() } -> false
            e164.length == 7 -> false
            e164.length > 7 -> e164.startsWith("+") && e164.drop(1).all { it.isDigit() }
            else -> e164.all { it.isDigit() }
        }
    }
}
