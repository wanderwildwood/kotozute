package com.wanderwildwood.kotozute.signalstore

/**
 * The schema for Signal's protocol state.
 *
 * Transliterated from signal-cli's stores -- `PreKeyStore`, `SignedPreKeyStore`,
 * `KyberPreKeyStore`, `SessionStore`, `SenderKeyRecordStore` and `IdentityKeyStore` -- which
 * are GPL-3.0, as is this app. The table and column names are kept exactly as they are there,
 * deliberately: when something goes wrong the only useful reference is signal-cli's own code,
 * and a renamed column turns every comparison into a translation exercise.
 *
 * `STRICT` is kept too. SQLite will otherwise accept a string into a BLOB column and hand it
 * back later as something that fails to deserialise, a long way from the mistake.
 *
 * The one table that is **not** from signal-cli is [ACCOUNT] and [ACCOUNT_IDENTITY]. signal-cli
 * keeps the account in a JSON file beside its database, guarded by a process-wide file lock and
 * written after every mutation. Android has process death instead of orderly shutdown, and the
 * pre-key counters must move in the same transaction as the key rows they index -- so the
 * account lives in this database, in these tables, or the two can disagree after a kill.
 *
 * See docs/DECISION-protocol-store.md for why this is a separate database from Realm at all.
 */
internal object ProtocolStoreSchema {

    const val VERSION = 12

    /**
     * One row, enforced. The account is a singleton and a second row would mean two identities
     * in one store, which nothing downstream is prepared to see.
     */
    const val ACCOUNT = """
        CREATE TABLE account (
          _id INTEGER PRIMARY KEY CHECK (_id = 1),
          number TEXT,
          aci TEXT,
          pni TEXT,
          device_id INTEGER NOT NULL DEFAULT 0,
          password TEXT,
          profile_key BLOB
        ) STRICT;
    """

    /**
     * Identity and key bookkeeping, one row per service id type (0 = ACI, 1 = PNI), matching
     * signal-cli's `account_id_type`.
     *
     * The counters sit beside the identity rather than in a table of their own because they are
     * only ever read and written together with it, and because every one of them indexes rows
     * in the key tables -- `next_pre_key_id` and the `pre_key` rows have to move as one, or a
     * crash between the two writes hands out a key id that already exists.
     */
    const val ACCOUNT_IDENTITY = """
        CREATE TABLE account_identity (
          account_id_type INTEGER PRIMARY KEY,
          identity_public BLOB,
          identity_private BLOB,
          registration_id INTEGER NOT NULL DEFAULT 0,
          next_pre_key_id INTEGER NOT NULL DEFAULT 0,
          next_signed_pre_key_id INTEGER NOT NULL DEFAULT 0,
          active_signed_pre_key_id INTEGER NOT NULL DEFAULT -1,
          next_kyber_pre_key_id INTEGER NOT NULL DEFAULT 0,
          active_last_resort_kyber_pre_key_id INTEGER NOT NULL DEFAULT -1
        ) STRICT;
    """

    /**
     * Peer identity keys -- the safety-number database.
     *
     * `address` is UNIQUE and deliberately **not** partitioned by account_id_type, exactly as
     * signal-cli has it: a peer has one identity key, not one per local service id. Wiping this
     * table is not a clean slate; every contact's safety number changes and every one of them
     * gets a warning.
     */
    const val IDENTITY = """
        CREATE TABLE identity (
          _id INTEGER PRIMARY KEY,
          address TEXT UNIQUE NOT NULL,
          identity_key BLOB NOT NULL,
          added_timestamp INTEGER NOT NULL,
          trust_level INTEGER NOT NULL
        ) STRICT;
    """

    /** One-time pre keys, handed out in batches of 100 and deleted as they are consumed. */
    const val PRE_KEY = """
        CREATE TABLE pre_key (
          _id INTEGER PRIMARY KEY,
          account_id_type INTEGER NOT NULL,
          key_id INTEGER NOT NULL,
          public_key BLOB NOT NULL,
          private_key BLOB NOT NULL,
          stale_timestamp INTEGER,
          UNIQUE(account_id_type, key_id)
        ) STRICT;
    """

    /** The signed pre key. One is active at a time; `timestamp` is what drives rotation. */
    const val SIGNED_PRE_KEY = """
        CREATE TABLE signed_pre_key (
          _id INTEGER PRIMARY KEY,
          account_id_type INTEGER NOT NULL,
          key_id INTEGER NOT NULL,
          public_key BLOB NOT NULL,
          private_key BLOB NOT NULL,
          signature BLOB NOT NULL,
          timestamp INTEGER DEFAULT 0,
          UNIQUE(account_id_type, key_id)
        ) STRICT;
    """

    /**
     * Post-quantum pre keys. Both the one-time and last-resort kinds live here; `is_last_resort`
     * is the only thing separating them, and getting it wrong means either handing out the
     * last-resort key as disposable or deleting it after a single use.
     */
    const val KYBER_PRE_KEY = """
        CREATE TABLE kyber_pre_key (
          _id INTEGER PRIMARY KEY,
          account_id_type INTEGER NOT NULL,
          key_id INTEGER NOT NULL,
          serialized BLOB NOT NULL,
          is_last_resort INTEGER NOT NULL,
          stale_timestamp INTEGER,
          timestamp INTEGER DEFAULT 0,
          UNIQUE(account_id_type, key_id)
        ) STRICT;
    """

    /**
     * The double-ratchet state, one row per peer device.
     *
     * The most fragile table here: a stale record written back over a fresher one is silent
     * ratchet corruption, and it shows up later as messages that will not decrypt, pointing
     * nowhere near the cause.
     */
    const val SESSION = """
        CREATE TABLE session (
          _id INTEGER PRIMARY KEY,
          account_id_type INTEGER NOT NULL,
          address TEXT NOT NULL,
          device_id INTEGER NOT NULL,
          record BLOB NOT NULL,
          UNIQUE(account_id_type, address, device_id)
        ) STRICT;
    """

    /** Group sender keys. Every group message from a modern client arrives through one. */
    const val SENDER_KEY = """
        CREATE TABLE sender_key (
          _id INTEGER PRIMARY KEY,
          address TEXT NOT NULL,
          device_id INTEGER NOT NULL,
          distribution_id BLOB NOT NULL,
          record BLOB NOT NULL,
          created_timestamp INTEGER NOT NULL,
          UNIQUE(address, device_id, distribution_id)
        ) STRICT;
    """

    /**
     * Who already has each sender key.
     *
     * Separate from [SENDER_KEY] because it answers a different question: that table holds the
     * keys themselves, this one records which devices have been *given* one. Sending a group
     * message means distributing the key to anyone not in here first -- and a row wrongly
     * present means a device that never receives the key and silently cannot read the group.
     */
    const val SENDER_KEY_SHARED = """
        CREATE TABLE sender_key_shared (
          _id INTEGER PRIMARY KEY,
          address TEXT NOT NULL,
          device_id INTEGER NOT NULL,
          distribution_id BLOB NOT NULL,
          timestamp INTEGER NOT NULL,
          UNIQUE(address, device_id, distribution_id)
        ) STRICT;
    """

    /**
     * Envelopes taken off the socket but not yet handled.
     *
     * Exists because of an ordering rule that is easy to get backwards: **an envelope is
     * written here before it is acknowledged to the server.** The server deletes a message the
     * moment it is acked and will not send it again, so acking first and crashing second loses
     * it permanently -- not delayed, gone, with the sender believing it was delivered.
     *
     * So the sequence is: receive, write here, ack, then decrypt and file at leisure. A crash
     * anywhere after the write costs a repeat of the work, never the message.
     *
     * `server_guid` is unique so that a redelivery -- which happens when the ack itself is
     * lost -- replaces the row instead of queueing the same message twice.
     */
    const val ENVELOPE = """
        CREATE TABLE envelope (
          _id INTEGER PRIMARY KEY,
          server_guid TEXT UNIQUE,
          serialized BLOB NOT NULL,
          server_delivered_timestamp INTEGER NOT NULL,
          stored_timestamp INTEGER NOT NULL,
          -- Why it would not decrypt, when it would not. Null while untried.
          failure TEXT,
          retry_requested INTEGER NOT NULL DEFAULT 0
        ) STRICT;
    """

    /** Order matters only in that account_identity is seeded after account exists. */
    val ALL = listOf(
        ACCOUNT,
        ACCOUNT_IDENTITY,
        IDENTITY,
        PRE_KEY,
        SIGNED_PRE_KEY,
        KYBER_PRE_KEY,
        SESSION,
        SENDER_KEY,
        SENDER_KEY_SHARED,
        ENVELOPE,
        CONTACT,
        BLOCKED,
        BLOCKED_GROUP,
        ACCOUNT_KEYS,
        CDS_STATE,
        CDS_SUBMITTED,
        PNI_ACI,
        RECIPIENT,
        RECIPIENT_E164_INDEX
    )

    /**
     * Names for the people on the other end.
     *
     * Populated from the contacts sync the primary device sends, which is the only way a
     * linked device learns them -- it has no address book of its own and, for a contact known
     * only by ACI, nothing local to match against.
     *
     * `aci` is the key rather than the number because that is what a message carries: under
     * sealed sender the phone number is frequently absent, so a contact table keyed by number
     * would fail to name exactly the conversations that arrive most privately.
     */
    const val CONTACT = """
        CREATE TABLE contact (
          _id INTEGER PRIMARY KEY,
          aci TEXT UNIQUE,
          e164 TEXT,
          name TEXT,
          -- The key that decrypts this person's profile, which is where their name actually
          -- lives. It arrives in the contacts sync; without it a profile fetch returns
          -- ciphertext and nothing else.
          profile_key BLOB,
          updated_timestamp INTEGER NOT NULL
        ) STRICT;
    """

    /**
     * The account's blocked list, as the primary last sent it.
     *
     * Held whole, not as a set of local decisions, because that is the shape Signal syncs:
     * a blocked-list sync carries **every** blocked party, and a device that sends one
     * replaces what the account holds. Blocking somebody from here therefore means editing a
     * list this phone must already have been given -- see [SignalBlockStore], which refuses
     * rather than sending a list it had to guess at.
     */
    const val BLOCKED = """
        CREATE TABLE blocked (
          _id INTEGER PRIMARY KEY,
          aci TEXT UNIQUE,
          e164 TEXT,
          blocked_at INTEGER NOT NULL
        ) STRICT;
    """

    /** Groups are blocked by id, which is not an identifier any person here ever sees. */
    const val BLOCKED_GROUP = """
        CREATE TABLE blocked_group (
          _id INTEGER PRIMARY KEY,
          group_id BLOB UNIQUE
        ) STRICT;
    """

    /**
     * The one key this app needs from the account's own key material.
     *
     * A linked device is not given it at link time; it asks, and the primary answers with a
     * Keys sync carrying the **account entropy pool**. That pool derives a great deal: the
     * master key, and from that the storage service key, the message-backup keys and the
     * registration-recovery material. Signal Android keeps the pool because it needs all of
     * them. This app needs exactly one -- reading the account's contact list -- so the pool
     * is derived from once, in memory, and **only the storage service key is written here**.
     * What cannot be read off this phone cannot be lost with it.
     *
     * ⚠ Even so, this is the most dangerous row in the database: it opens the account's
     * stored state, where every other key here opens one conversation. It lives in the
     * SQLCipher database behind the phone's keystore -- the same posture Signal Android has
     * for the same material -- and it is never logged, never exported, and never written
     * anywhere else.
     */
    const val ACCOUNT_KEYS = """
        CREATE TABLE account_keys (
          _id INTEGER PRIMARY KEY CHECK (_id = 1),
          storage_key BLOB,
          updated_timestamp INTEGER NOT NULL
        ) STRICT;
    """

    /**
     * What contact discovery has already been told, so the next run can be cheap.
     *
     * CDSI is quota'd per account, and the quota is spent on **new** numbers: a run submits
     * the numbers it has asked about before, the ones it has not, and the token from last
     * time, and is charged for the difference. Forgetting either half turns every run into a
     * first run, which is how an account loses discovery for a day.
     *
     * The token is opaque and is only ever handed back to the service.
     */
    const val CDS_STATE = """
        CREATE TABLE cds_state (
          _id INTEGER PRIMARY KEY CHECK (_id = 1),
          token BLOB,
          updated_timestamp INTEGER NOT NULL
        ) STRICT;
    """

    /** Every number already submitted, so the next run asks only about the rest. */
    const val CDS_SUBMITTED = """
        CREATE TABLE cds_submitted (
          e164 TEXT PRIMARY KEY NOT NULL
        ) STRICT;
    """

    /**
     * Which account id a phone-number identity belongs to.
     *
     * A person discovered by phone number arrives as a PNI and nothing else -- that is all
     * CDSI returns for somebody whose ACI/UAK pair the asker does not already hold, which for
     * a linked device is everybody. They can be written to by that PNI, but their replies
     * arrive under their ACI, and without this the two are different people: one conversation
     * in two rows, the same split this app has already been bitten by twice.
     *
     * ⚠ **Filled only from the account's own storage records** -- a ContactRecord carrying
     * both ids, written by the account's own primary and read with the account's own storage
     * key. A pairing asserted by an incoming message is not taken: `Content.pniSignatureMessage`
     * exists for that and carries a signature, and until that signature is actually verified,
     * believing it would let a stranger claim somebody else's phone-number identity and
     * capture their conversation.
     */
    const val PNI_ACI = """
        CREATE TABLE pni_aci (
          pni TEXT PRIMARY KEY NOT NULL,
          aci TEXT NOT NULL,
          updated_timestamp INTEGER NOT NULL
        ) STRICT;
    """

    /**
     * A person, by every name Signal has for them.
     *
     * The shape Signal's own recipient table has, and it is the shape for a reason. The
     * [contact] table this replaces was keyed **by** the service id, which cannot represent
     * the one thing that actually happens: somebody is met first as a phone-number identity
     * and later turns out to be an account already known. Two rows, no way to say they are
     * one person, and nothing that could merge them without changing a primary key.
     *
     * Here the row is a stable `_id` and the ids are columns, so learning a new one about
     * somebody is an update rather than a new person. That is what makes Signal's
     * `processPnpTupleToChangeSet` portable at all -- it works on "the row found by e164, the
     * row found by pni, the row found by aci", which may be three different rows.
     *
     * `aci` and `pni` are unique; `e164` is **not**. Signal can enforce that because its PNP
     * logic resolves every collision; until this app has all of it, two rows claiming one
     * number is possible and a UNIQUE would turn that into a failed write rather than a
     * merge worth doing later.
     */
    const val RECIPIENT = """
        CREATE TABLE recipient (
          _id INTEGER PRIMARY KEY AUTOINCREMENT,
          aci TEXT UNIQUE,
          pni TEXT UNIQUE,
          e164 TEXT,
          name TEXT,
          profile_key BLOB,
          updated_timestamp INTEGER NOT NULL
        );
    """

    const val RECIPIENT_E164_INDEX =
        "CREATE INDEX IF NOT EXISTS recipient_e164 ON recipient (e164);"

    /**
     * Migrations, keyed by the version they upgrade *to*.
     *
     * Explicit and additive. This database holds key material that cannot be refetched -- an
     * identity, sessions, the device's own password -- so there is no "drop and recreate"
     * fallback available here the way there is for the message database. A version with no
     * entry is still an error, deliberately: an unhandled upgrade must be loud rather than
     * leave a half-known schema in place.
     */
    val MIGRATIONS: Map<Int, List<String>> = mapOf(
        // v2 added the envelope queue, so that an envelope can be written down before it is
        // acknowledged to the server. Purely additive: nothing existing is touched.
        2 to listOf(ENVELOPE),
        // v3: names from the primary's contacts sync. Additive.
        3 to listOf(CONTACT),
        // v4: profile keys. Names live in profiles, not in the contact record's
        // legacy name field, which modern clients have largely stopped filling.
        4 to listOf("ALTER TABLE contact ADD COLUMN profile_key BLOB;"),
        // v5: why an envelope would not decrypt. "One message could not be read" is the right
        // thing to show a person and the wrong thing to hand a developer -- a release build
        // logs nothing, so without this the only report from the field is a number.
        5 to listOf("ALTER TABLE envelope ADD COLUMN failure TEXT;"),
        // v6: the account's blocked list, which a linked device has to hold whole before it
        // can change it -- sending a blocked sync replaces the account's list rather than
        // adding to it, so blocking one person without the rest would unblock everyone else.
        6 to listOf(BLOCKED, BLOCKED_GROUP),
        // v7: the storage service key, derived from the pool the primary sends. Additive.
        7 to listOf(ACCOUNT_KEYS),
        // v8: what contact discovery has already asked about. Additive. Empty means the next
        // run is a first run, which is correct but costs quota -- so it is written down.
        8 to listOf(CDS_STATE, CDS_SUBMITTED),
        // v9: which account a phone-number identity belongs to, so the two halves of one
        // person fold together rather than sitting in the inbox as two. Additive.
        9 to listOf(PNI_ACI),
        // v10: people become rows with a stable id, and their service ids become columns.
        //
        // ⚠ The old `contact` table is deliberately NOT dropped. Most of what is in it can be
        // fetched again for nothing, but the rows discovery found cost quota to learn and
        // `cds_submitted` will stop this asking for them a second time -- so if this migration
        // is wrong, those people are gone for good rather than merely re-fetched. Keeping the
        // table costs a few hundred rows and makes a bad migration recoverable.
        10 to listOf(
            RECIPIENT,
            RECIPIENT_E164_INDEX,
            // One row per account id.
            """
            INSERT INTO recipient (aci, name, profile_key, updated_timestamp)
            SELECT aci, NULLIF(name, ''), profile_key, updated_timestamp
            FROM contact WHERE aci NOT LIKE 'PNI:%'
            """.trimIndent(),
            // One row per phone-number identity that is not already somebody here.
            """
            INSERT INTO recipient (pni, name, profile_key, updated_timestamp)
            SELECT c.aci, NULLIF(c.name, ''), c.profile_key, c.updated_timestamp
            FROM contact c
            WHERE c.aci LIKE 'PNI:%'
              AND NOT EXISTS (
                SELECT 1 FROM pni_aci p
                JOIN recipient r ON r.aci = p.aci
                WHERE p.pni = c.aci
              )
            """.trimIndent(),
            // The paired ones fold onto the account they belong to.
            """
            UPDATE recipient SET pni = (
              SELECT p.pni FROM pni_aci p WHERE p.aci = recipient.aci LIMIT 1
            )
            WHERE pni IS NULL
              AND aci IS NOT NULL
              AND EXISTS (SELECT 1 FROM pni_aci p WHERE p.aci = recipient.aci)
            """.trimIndent(),
            // A number from whichever of the two halves had one.
            """
            UPDATE recipient SET e164 = COALESCE(
              (SELECT NULLIF(c.e164, '') FROM contact c WHERE c.aci = recipient.aci),
              (SELECT NULLIF(c.e164, '') FROM contact c WHERE c.aci = recipient.pni)
            )
            """.trimIndent(),
            // And a name and a profile key, where only the folded half had one.
            """
            UPDATE recipient SET name = (
              SELECT NULLIF(c.name, '') FROM contact c WHERE c.aci = recipient.pni
            ) WHERE name IS NULL AND pni IS NOT NULL
            """.trimIndent(),
            """
            UPDATE recipient SET profile_key = (
              SELECT c.profile_key FROM contact c WHERE c.aci = recipient.pni
            ) WHERE profile_key IS NULL AND pni IS NOT NULL
            """.trimIndent()
        ),
        // v11: ask contact discovery again, once.
        //
        // Between the release that could look numbers up and the one that could keep a person
        // known only by a phone-number identity, a lookup returned people this app then threw
        // away -- and recorded the numbers as asked, so it would never ask about them again.
        // They are the exact people the feature exists for: their number is in the phone, they
        // are on Signal, and they cannot be found.
        //
        // Forgetting what was asked costs one quota-charged re-ask and is the only way back:
        // the results were never stored, so there is nothing to recover from, and the record
        // of having asked is the thing standing in the way.
        11 to listOf(
            "DELETE FROM cds_submitted;",
            "DELETE FROM cds_state;"
        ),
        // v12: whether we have already asked the sender to send this one again.
        //
        // An envelope that will not decrypt is kept for a fortnight in case a fix arrives, and
        // every batch re-reads the whole table and tries it again. Asking for a resend each
        // time turns one unreadable message into a fortnight of receipts to that person, each
        // making their client archive its session and resend, each resend failing the same way.
        // Asked once is the whole of the fix.
        12 to listOf("ALTER TABLE envelope ADD COLUMN retry_requested INTEGER NOT NULL DEFAULT 0;")
    )

    /** 0 = ACI, 1 = PNI, as signal-cli numbers them. Both rows exist from the start. */
    val SEED = listOf(
        "INSERT INTO account (_id) VALUES (1);",
        "INSERT INTO account_identity (account_id_type) VALUES (0);",
        "INSERT INTO account_identity (account_id_type) VALUES (1);"
    )
}
