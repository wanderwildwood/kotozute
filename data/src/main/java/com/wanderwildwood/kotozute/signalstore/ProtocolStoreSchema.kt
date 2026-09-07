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

    const val VERSION = 1

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
        SENDER_KEY_SHARED
    )

    /** 0 = ACI, 1 = PNI, as signal-cli numbers them. Both rows exist from the start. */
    val SEED = listOf(
        "INSERT INTO account (_id) VALUES (1);",
        "INSERT INTO account_identity (account_id_type) VALUES (0);",
        "INSERT INTO account_identity (account_id_type) VALUES (1);"
    )
}
