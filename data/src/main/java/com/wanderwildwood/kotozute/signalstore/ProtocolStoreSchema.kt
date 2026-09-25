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

    const val VERSION = 37

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
          last_pni_change_timestamp INTEGER NOT NULL DEFAULT 0,
          profile_key BLOB,
          auth_credential_salt BLOB,
          -- Key transparency's "distinguished" tree head: the last point in the public log
          -- this device has checked against. It belongs to the account rather than to any
          -- contact, which is why it sits here and the per-person data sits on `recipient`.
          distinguished_head BLOB
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
     * Every use of a last-resort Kyber key, so the same use cannot be replayed.
     *
     * ⚠ This is replay protection for the protocol, not bookkeeping.
     *
     * A one-time Kyber key is deleted the moment it is used, and that deletion is what stops
     * the message that used it being replayed. A **last-resort** key is deliberately not
     * deleted -- it is the fallback when the one-time keys have run out, and deleting it would
     * leave the account with nothing to fall back to. So nothing stopped a
     * `PreKeySignalMessage` built against it being sent again, and again: libsignal asks the
     * store to flag the reuse, the store said nothing, and the same tuple re-established a
     * session every time.
     *
     * What makes a use unique is the triple libsignal hands over: which last-resort key, which
     * signed pre key, and the sender's base key. Seeing that triple twice is a replay, and the
     * UNIQUE constraint is what notices -- the insert fails, and the failure is turned into the
     * `ReusedBaseKeyException` libsignal is waiting for.
     *
     * Signal's `last_resort_key_tuple`, column for column. The reference is to the key's row
     * rather than to its key id, so that rotating a last-resort key takes its history with it.
     * It cascades, and this database does now enforce that -- see `ProtocolDatabase.onOpen`,
     * whose comment anticipated the first migration to declare a real reference.
     */
    const val LAST_RESORT_KEY_TUPLE = """
        CREATE TABLE last_resort_key_tuple (
          _id INTEGER PRIMARY KEY,
          kyber_prekey_id INTEGER NOT NULL REFERENCES kyber_pre_key (_id) ON DELETE CASCADE,
          signed_key_id INTEGER NOT NULL,
          public_key BLOB NOT NULL,
          UNIQUE(kyber_prekey_id, signed_key_id, public_key)
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
          retry_requested INTEGER NOT NULL DEFAULT 0,
          -- Who sent it, once the failure has said so. ⚠ Not readable from the envelope
          -- itself: a sealed-sender envelope carries no source, and the sender's name only
          -- appears in the protocol exception after unsealing fails. Recorded at the moment
          -- the resend is asked for, which is the only moment it is known. See v29.
          retry_sender TEXT,
          retry_device INTEGER NOT NULL DEFAULT 0,
          -- The group it was sent to, where it was a group. Same reason.
          retry_group BLOB,
          -- When this app gave up waiting and said so in the conversation. Null until then.
          placeholder_at INTEGER
        ) STRICT;
    """

    /**
     * Delivery receipts this phone owes and has not managed to send.
     *
     * A receipt is what stops a sender's message saying nothing at all. Upstream treats one
     * that fails as worth a day of unlimited retries -- `SendDeliveryReceiptJob` is
     * `setLifespan(TimeUnit.DAYS.toMillis(1))`, `setMaxAttempts(Parameters.UNLIMITED)`, queued
     * per recipient -- and this app sent it once and wrote a log line, so a blip left the
     * sender looking at a message that had arrived and would never say so.
     *
     * Keyed on the pair that identifies the message: who sent it, and the timestamp they
     * stamped on it. That is the whole of what a receipt carries.
     */
    const val RECEIPT_OWED = """
        CREATE TABLE receipt_owed (
          recipient TEXT NOT NULL,
          sent_timestamp INTEGER NOT NULL,
          owed_since INTEGER NOT NULL,
          -- Which small message is owed about this one. See v28.
          kind TEXT NOT NULL DEFAULT 'delivery',
          PRIMARY KEY (recipient, sent_timestamp, kind)
        ) STRICT;
    """

    /**
     * Messages decrypted and not yet filed.
     *
     * ⚠ The gap this closes: an envelope's row was deleted the moment it decrypted, and its
     * message was held in memory until the whole batch was filed into the message database.
     * A process killed in between, or a message database that could not be written, lost the
     * message for good -- acknowledged to the server, gone from the queue, and impossible to
     * decrypt again because the ratchet had moved on. Upstream decrypts and inserts in one
     * transaction (`IncomingMessageObserver`); with the messages in Realm and the keys here
     * that is not available, so the decrypted message is written here instead, in the same
     * transaction that deletes its envelope, and removed only once it has been filed.
     */
    const val UNFILED = """
        CREATE TABLE unfiled (
          id TEXT PRIMARY KEY,
          message TEXT NOT NULL,
          stored_timestamp INTEGER NOT NULL
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
        // After KYBER_PRE_KEY, which it references.
        LAST_RESORT_KEY_TUPLE,
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
        RECIPIENT_E164_INDEX,
        RECIPIENT_GROUP_ID_INDEX,
        MESSAGE_LOG,
        MESSAGE_LOG_INDEX,
        RECEIPT_OWED,
        UNFILED
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
          -- The account entropy pool the primary sent, kept rather than derived-from and
          -- discarded. It is the root every other account key comes off -- Signal calls it
          -- "The Root of All Entropy" -- and a linked device holds it in Signal too
          -- (`SignalStore.account.accountEntropyPool`). Keeping it is what lets a written-out
          -- copy be locked with a key derived from the account rather than thirty digits
          -- somebody has to copy onto paper and never lose.
          entropy_pool TEXT,
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
          -- When the service will answer again, after it has said the quota is spent. Signal
          -- keeps the same value as `cdsBlockedUtil`, taken from the refusal's own
          -- retryAfterSeconds -- the only thing that says how long, and it arrives once.
          blocked_until INTEGER NOT NULL DEFAULT 0,
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
     * Filled from two sources, both of which prove the pairing rather than assert it:
     *
     * - a **ContactRecord** carrying both ids, written by the account's own primary and read
     *   with the account's own storage key;
     * - a **`Content.pniSignatureMessage`** whose signature actually checks out against the
     *   identity keys already on file -- see `SignalReceiver.rememberVerifiedPni`, ported from
     *   `MessageDecryptor.handlePniSignatureMessage`.
     *
     * ⚠ An unverified claim is never taken. Believing one would let a stranger assert somebody
     * else's phone-number identity and capture their conversation. (This note used to say the
     * signature was not checked at all, which stopped being true and would now argue against
     * a check that exists.)
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
          -- The name they gave their own profile, and only that. `name` is what to *call*
          -- them, and for anybody in the address book that is the address book's. See v36.
          profile_name TEXT,
          profile_key BLOB,
          -- Whether this person accepts sealed sender, and on what terms. Signal's
          -- SealedSenderAccessMode: 0 unknown, 1 disabled, 2 enabled, 3 unrestricted. Learned
          -- from how our sends to them actually go, which is the only way to learn it.
          sealed_sender_mode INTEGER NOT NULL DEFAULT 0,
          -- The @name they chose, where they have one. The last thing Signal will show
          -- somebody by before giving up and calling them Unknown.
          username TEXT,
          -- Hidden by the account owner, and when the account noticed they had left Signal.
          -- Both come from the account's own contact record and both mean "do not offer this
          -- person"; see the v25 migration.
          hidden INTEGER NOT NULL DEFAULT 0,
          unregistered_at INTEGER NOT NULL DEFAULT 0,
          -- What key transparency last verified about this person, opaque to everything here.
          key_transparency_data BLOB,
          -- This row's id in the account's storage service, base64 of sixteen random bytes.
          -- ⚠ Rotated on every **local** change and on nothing else: the rotation IS the
          -- record of "this differs from what the account holds". See [SignalContactStore.
          -- rotateStorageId]. Null until the row has ever been pushed or marked.
          storage_id TEXT,
          -- The account's own copy of this row's storage record, exactly as it arrived, so a
          -- write can put back fields this build does not understand. See v34.
          storage_record BLOB,
          -- The id the account's manifest holds for this row, as against `storage_id` which
          -- is ours and rotates locally. What a write deletes when it replaces it. See v35.
          remote_storage_id TEXT,
          -- A group's row has this and nothing else -- no aci, no pni, no number. Signal's
          -- RecipientTable holds groups the same way (`getOrInsertFromGroupId`), so that one
          -- dirty flag covers a muted group as well as a renamed person. Base64 of the group
          -- id, which is what a thread key already carries.
          group_id TEXT DEFAULT NULL,
          -- Whether this person has yet to be shown that this account's phone-number identity
          -- and its account id are one person. Set when a message from them arrives addressed
          -- to the PNI -- which is them saying they know us by number, not by account -- and
          -- cleared once a message carrying the proof has gone. See v30.
          needs_pni_signature INTEGER NOT NULL DEFAULT 0,
          -- Whether the account shares its profile with this person -- ContactRecord's
          -- `whitelisted`. Signal will not attach this account's profile key to a message for
          -- somebody who is neither a system contact nor whitelisted, which is what makes
          -- blocking-then-unblocking, or turning sharing off elsewhere, actually mean
          -- something. Defaults to 1 so nobody already known loses their profile key before
          -- the first storage read says otherwise.
          whitelisted INTEGER NOT NULL DEFAULT 1,
          -- When this person's profile was last fetched, which is NOT the same question as
          -- when the row was last written. Signal keeps them apart for exactly this reason
          -- (`RecipientTable.LAST_PROFILE_FETCH`), and conflating them here meant profiles
          -- were never refreshed at all -- see the v18 note below.
          last_profile_fetch INTEGER NOT NULL DEFAULT 0,
          updated_timestamp INTEGER NOT NULL
        );
    """

    const val RECIPIENT_E164_INDEX =
        "CREATE INDEX IF NOT EXISTS recipient_e164 ON recipient (e164);"

    /**
     * ⚠ A unique **index**, not a UNIQUE column, because SQLite will not add one.
     * `ALTER TABLE ... ADD COLUMN` refuses a UNIQUE or PRIMARY KEY constraint outright, so a
     * migration written that way throws -- and this database holds the identity keys, the
     * sessions and the device's own password, none of which can be recreated. The index gives
     * the same guarantee and is what `ON CONFLICT(group_id)` needs; SQLite allows any number
     * of NULLs under it, which is every row that is a person rather than a group.
     *
     * Signal writes indexes over a mostly-empty column as partial ones -- `CREATE INDEX ... ON
     * attachment (attachment_uuid) WHERE attachment_uuid IS NOT NULL` -- but that form makes
     * the upsert in `rotateStorageIdForGroup` need a matching `ON CONFLICT(group_id) WHERE
     * group_id IS NOT NULL` target, and it saves nothing on a table with a couple of hundred
     * rows. Its own storage-id migration (`V323_AddStickerPackStorageSync`) adds the column as
     * a plain `TEXT DEFAULT NULL`, which is the part that matters here.
     */
    const val RECIPIENT_GROUP_ID_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS recipient_group_id ON recipient (group_id);"

    /**
     * What this device has recently sent, so it can send it again if asked.
     *
     * Signal's message log, and the thing that makes `ContentHint.RESENDABLE` an honest
     * promise: a recipient whose client cannot decrypt one of our messages asks for it, and
     * without this there was nothing to answer with -- the session could be repaired but the
     * message itself was gone.
     *
     * The content stored is the exact `Content` the send returned, not a reconstruction.
     *
     * Short-lived on purpose. A retry receipt arrives within minutes of the failure; keeping
     * sent plaintext any longer than it can be useful is keeping it for nothing.
     */
    const val MESSAGE_LOG = """
        CREATE TABLE message_log (
          _id INTEGER PRIMARY KEY AUTOINCREMENT,
          recipient TEXT NOT NULL,
          -- Which of that person's devices this copy is for. A message goes to every device
          -- somebody has and each acknowledges separately, so a delivery receipt clears one
          -- row and leaves the others -- which is the whole reason Signal's
          -- `deleteEntryForRecipient` takes a device id.
          device_id INTEGER NOT NULL DEFAULT 0,
          sent_timestamp INTEGER NOT NULL,
          content BLOB NOT NULL,
          urgent INTEGER NOT NULL DEFAULT 1,
          group_id BLOB,
          created_at INTEGER NOT NULL,
          -- What `ContentHint` this copy went out under, as its wire value. Kept so a resend
          -- asserts what the original asserted rather than a constant. See v31.
          content_hint INTEGER NOT NULL DEFAULT 1,
          -- When a resend was first owed for this copy, and null while none is. See v26.
          resend_owed_since INTEGER
        );
    """

    const val MESSAGE_LOG_INDEX =
        "CREATE INDEX IF NOT EXISTS message_log_lookup ON message_log (recipient, sent_timestamp);"

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
            RECIPIENT_GROUP_ID_INDEX,
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
        12 to listOf("ALTER TABLE envelope ADD COLUMN retry_requested INTEGER NOT NULL DEFAULT 0;"),
        // v13: what was recently sent, so a retry receipt can actually be answered.
        13 to listOf(MESSAGE_LOG, MESSAGE_LOG_INDEX),
        // v14: when this device last applied a change of the account's own phone number.
        //
        // The server redelivers, so without somewhere to remember this a replayed change --
        // or an old one arriving late -- would be applied again over a newer one, replacing
        // live PNI identity key material with superseded key material. Signal keeps the same
        // watermark for the same reason.
        14 to listOf(
            "ALTER TABLE account ADD COLUMN last_pni_change_timestamp INTEGER NOT NULL DEFAULT 0;"
        ),
        // v15: what we have learned about each person's willingness to be sent to sealed.
        //
        // Starting everyone at unknown is right: unknown means "try it and see", which is
        // exactly what this device should do the first time, and is how it learns anything at
        // all. Signal calls the same column sealed_sender_mode and seeds it the same way.
        15 to listOf(
            "ALTER TABLE recipient ADD COLUMN sealed_sender_mode INTEGER NOT NULL DEFAULT 0;"
        ),
        // v16: the username, the last name Signal has for somebody before "Unknown".
        //
        // A contact record carries one and it was not being read, so a person the account
        // knows only by their username -- no name, no number -- had nothing to be shown as
        // but a fragment of their service id.
        16 to listOf("ALTER TABLE recipient ADD COLUMN username TEXT;"),
        // v17: somewhere to record that a row differs from what the account holds.
        //
        // Signal gives every recipient a storage-service id and rotates it on every local
        // change -- `rotateStorageId` fires from 31 places in its RecipientTable. That
        // rotation is the dirty flag the sync diffs against; there is no separate "needs
        // push" column. Nothing here writes to the storage service yet, so this only
        // records. See docs/DECISION-storage-write.md for the order the rest goes in.
        17 to listOf("ALTER TABLE recipient ADD COLUMN storage_id TEXT;"),
        // v18: when a profile was last fetched, separately from when the row was last written.
        //
        // ⚠ The profile refresh asked for contacts whose `updated_timestamp` was old. But that
        // column is bumped by **every** write to the row, and a storage read writes all of
        // them -- so after any app launch nothing was stale and the refresh fetched nobody
        // except people with no name at all. A contact who changed their name never updated,
        // and shortening the staleness window did nothing whatsoever, because the window was
        // never reached.
        //
        // Signal has `last_profile_fetch` as its own column and resets it to 0 when a profile
        // key changes, so a new key forces a refetch. Same here.
        18 to listOf(
            "ALTER TABLE recipient ADD COLUMN last_profile_fetch INTEGER NOT NULL DEFAULT 0;"
        ),
        // v19: somewhere for a group to be marked, which it had nowhere to be.
        //
        // Muting or archiving a group is a local change the account's storage service holds --
        // on a GroupV2Record rather than a ContactRecord -- and there was no row here to rotate
        // a storage id on, so those changes were silently not recorded as needing a push. The
        // dirty flag existed for people and not for groups.
        //
        // Signal does not have a second table for this: `RecipientTable` holds groups too.
        // `getOrInsertFromGroupId` inserts a row with `group_id` set, no service id and no
        // number, and gives it a `storage_service_id` there and then. One table, one dirty
        // flag, whatever kind of conversation it is.
        19 to listOf(
            "ALTER TABLE recipient ADD COLUMN group_id TEXT DEFAULT NULL;",
            RECIPIENT_GROUP_ID_INDEX
        ),
        // v20: keep the account entropy pool, instead of deriving one key from it and
        // throwing the rest away.
        //
        // A written-out copy was locked with thirty digits generated at the moment of
        // writing, shown once and stored nowhere -- so losing the paper lost the backup, and
        // there was no second chance to read them. Signal does not do that: a backup key is
        // `AccountEntropyPool.deriveMessageBackupKey()`, derived from the pool the account
        // already has, so any device that can link to the account can open any copy.
        //
        // The pool arrives in the KEYS sync we already ask for and was being reduced to the
        // storage-service key on the way in. Now it is kept.
        20 to listOf("ALTER TABLE account_keys ADD COLUMN entropy_pool TEXT;"),
        // v21: which device a remembered send was for, so a delivery receipt can clear it.
        //
        // The log holds the **plaintext** Content of everything sent, so it can be sent again
        // if somebody's device asks. Signal deletes an entry the moment delivery is confirmed
        // and keeps the age trim only as a backstop; this kept every copy for the full
        // fourteen days even when the recipient acknowledged it seconds later.
        //
        // It has to be per-device or the deletion is wrong: the first device to acknowledge
        // would take the copy the others still need.
        //
        // ⚠ Rows written before this get device 0, which no receipt names, so they age out on
        // the sweep as they do today. Only new sends are cleared early.
        21 to listOf("ALTER TABLE message_log ADD COLUMN device_id INTEGER NOT NULL DEFAULT 0;"),
        // v22: whether the account shares its profile with somebody.
        //
        // A profile key was attached to every outgoing message and every reaction, with no
        // per-recipient test. Signal's `PushSendJob.getProfileKey` returns nothing unless the
        // recipient `isSystemContact || isProfileSharing`, so anyone the account has
        // un-whitelisted -- blocked and then unblocked, or sharing turned off on another
        // device -- was being handed a durable key to this account's profile on the next
        // message. The flag rides ContactRecord.whitelisted and is read with the rest.
        22 to listOf("ALTER TABLE recipient ADD COLUMN whitelisted INTEGER NOT NULL DEFAULT 1;"),
        // v23: when contact discovery will answer again.
        //
        // A spent quota arrives with a retryAfterSeconds and that number was being thrown
        // away, so nothing stopped a reader retrying a lookup that could not succeed and the
        // app could not say how long remained. Signal persists it as `cdsBlockedUtil`.
        23 to listOf("ALTER TABLE cds_state ADD COLUMN blocked_until INTEGER NOT NULL DEFAULT 0;"),
        // v24: replay protection for the last-resort Kyber key. A new table, so nothing
        // existing is touched and there is nothing to back-fill -- an empty seen-set is the
        // right starting point, because a use nobody recorded cannot be shown to be a replay.
        24 to listOf(LAST_RESORT_KEY_TUPLE),
        // v25: the two things the account's records say about somebody that mean "do not
        // offer this person". `hidden` is a deliberate choice by the account owner;
        // `unregistered_at` is the account's note that they have left Signal. Both were being
        // decoded and dropped, so a hidden contact came back at every storage read and
        // somebody who had left was offered as an ordinary contact -- and picking them starts
        // a conversation that can never deliver. Signal keeps both columns and excludes them
        // from contact search (`FILTER_HIDDEN`, and SIGNAL_CONTACT requiring REGISTERED).
        25 to listOf(
            "ALTER TABLE recipient ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0;",
            "ALTER TABLE recipient ADD COLUMN unregistered_at INTEGER NOT NULL DEFAULT 0;"
        ),
        // v26: when a resend was first owed to somebody, null while none is. A retry receipt
        // asks for a message again and this app answered with **one attempt** -- upstream's
        // `ResendMessageJob` is `setLifespan(1 day)` and `setMaxAttempts(UNLIMITED)`, because
        // the failure that matters is the network going away, not the send being refused. The
        // plaintext is already kept here for a fortnight; all that was missing was a note that
        // somebody is still waiting for it.
        //
        // Nullable and no default: a row with nothing owed says so by holding nothing, which
        // is also what every existing row gets.
        26 to listOf("ALTER TABLE message_log ADD COLUMN resend_owed_since INTEGER;"),
        // v27: delivery receipts owed. The mirror of v26 on the receiving side, and the same
        // reasoning -- upstream answers a failed one with a job retried for a day, this app
        // answered with a single attempt. Purely additive.
        27 to listOf(RECEIPT_OWED),
        // v28: which small message is owed. The same table serves two of them, because both
        // are "something owed *about* a message" and a message is named the same way in each:
        // by who wrote it and the timestamp they stamped on it.
        //
        //   'delivery'  -- tell the sender their message arrived here.
        //   'read-sync' -- tell this account's *own* devices it has been read here, which is
        //                  the only thing that stops the primary and Desktop notifying about a
        //                  conversation already read on this phone. `MultiDeviceReadUpdateJob`,
        //                  and upstream gives it the same day of unlimited attempts.
        //
        // The primary key has to grow with it: one message can owe both at once. SQLite cannot
        // add a column to a primary key in place, so the table is rebuilt -- additive in
        // effect, and every existing row is a delivery receipt, which is what it defaults to.
        28 to listOf(
            "ALTER TABLE receipt_owed RENAME TO receipt_owed_old;",
            RECEIPT_OWED,
            """
            INSERT INTO receipt_owed (recipient, sent_timestamp, owed_since, kind)
            SELECT recipient, sent_timestamp, owed_since, 'delivery' FROM receipt_owed_old;
            """,
            "DROP TABLE receipt_owed_old;"
        ),

        /**
         * What key transparency remembers between checks.
         *
         * Two opaque blobs, both written and read only by libsignal: the account-wide
         * *distinguished tree head* -- the last point in the public log this device verified
         * against -- and, per person, the account data that proves their identifiers were in
         * the log when we last looked. `org.signal.libsignal.keytrans.Store` is the interface,
         * and its four methods are exactly these two columns.
         *
         * ⚠ Opaque on purpose. Nothing here parses them; a client that second-guesses the
         * contents is a client that can be argued into accepting a substituted key, which is
         * the whole thing key transparency exists to prevent.
         */
        33 to listOf(
            "ALTER TABLE account ADD COLUMN distinguished_head BLOB;",
            "ALTER TABLE recipient ADD COLUMN key_transparency_data BLOB;"
        ),

        /**
         * The account's own copy of this row's storage record, exactly as it arrived.
         *
         * ⛔ **Step 0 of `docs/DECISION-storage-write.md`, and the reason it comes first.** A
         * storage record can carry fields this build has never heard of, written by a newer
         * Signal client. Reading and discarding them costs nothing while nothing is written
         * back -- but the moment a write exists, re-encoding a record from only the fields
         * this app understands **destroys another client's data on the account**, for every
         * device on it. Signal keeps the unknown fields per record and has a migration for
         * the period when it did not (`ApplyUnknownFieldsToSelfMigrationJob`).
         *
         * ⚠ The whole decrypted record is kept, not a filtered "unknown fields" blob. Wire
         * keeps unknown fields on the decoded message, so a write starts from these bytes,
         * sets the handful of fields this app actually decides, and re-encodes -- everything
         * else, known or not, rides through untouched. Storing only what we failed to parse
         * would mean re-deriving the rest, which is the same mistake in a smaller box.
         *
         * Null until this row has been seen in a storage read. A row with no remote record
         * has never been on the account, so there is nothing of anyone else's to preserve.
         */
        34 to listOf(
            "ALTER TABLE recipient ADD COLUMN storage_record BLOB;"
        ),

        /**
         * The id the account's own manifest holds for this row.
         *
         * ⛔ **This is what makes a write's deletes safe, and it is not the same column as
         * `storage_id`.** `storage_id` is *ours*: it rotates on every local change and is the
         * dirty flag. This one is the account's, set only when a record is read, and it says
         * which manifest entry this row is replacing.
         *
         * ⚠ The reason it exists rather than computing deletes as `remote ids - local ids`:
         * **this app models only contacts and groups.** An account's manifest also names
         * story distribution lists, call links, chat folders, sticker packs, notification
         * profiles and the account record itself, none of which have a row here. A set
         * difference would put every one of them in the delete list and **erase them for
         * every device on the account** -- exactly the damage this whole staged plan exists
         * to avoid. Signal can do the subtraction only because it keeps
         * `unknownStorageIds.allUnknownIds` and folds them back into its local set
         * (`StorageSyncJob.getAllLocalStorageIds`).
         *
         * So a write here deletes **only the specific id a row is replacing** and leaves every
         * other manifest entry exactly where it is. The cost is that a genuinely orphaned
         * remote record is never tidied up, which is the conservative direction to be wrong in.
         */
        35 to listOf(
            "ALTER TABLE recipient ADD COLUMN remote_storage_id TEXT;"
        ),

        /**
         * The profile name on its own, apart from the name the reader knows them by.
         *
         * ⚠ **Without it, anybody in the address book was announced as renamed every day.**
         * `name` holds whichever of nickname, address book and profile wins, so for a saved
         * contact it holds the address-book name. The daily profile fetch compared its answer
         * against *that*, found them different, wrote "X is now called Y" and stored Y; the
         * next contact or storage sync put X back; the next day it happened again. Signal
         * compares against `recipient.profileName` (`RetrieveProfileJob`), a column of its
         * own, and so does this now.
         *
         * Starts empty. The first fetch after this fills it without a note, the same as any
         * first name learned -- so upgrading does not write one into every conversation.
         */
        /** Decrypted messages survive until filed. See [UNFILED]. */
        37 to listOf(UNFILED),

        36 to listOf(
            "ALTER TABLE recipient ADD COLUMN profile_name TEXT;"
        ),

        /**
         * The salt an account without a phone number signs group credentials with.
         *
         * `GroupsV2Api.getGroupsV2AuthorizationString` takes it as its third argument and uses
         * it only when the account has no PNI: with one it calls
         * `receiveAuthCredentialWithPniAsServiceId`, without one
         * `receiveAuthCredentialWithoutPni`, which needs the salt and throws on a null.
         *
         * ⚠ The primary sends it in the provisioning message, field 19, which this app did not
         * read before -- the prebuilt service jar predated the field. **A device linked before
         * this version has no salt and cannot be given one without re-linking**, which is
         * harmless for an account that has a phone number and total for one that does not.
         */
        32 to listOf(
            "ALTER TABLE account ADD COLUMN auth_credential_salt BLOB;"
        ),

        /**
         * The hint a message was sent under, so a resend can claim the same one.
         *
         * ⚠ A resend used to assert `RESENDABLE` whatever the original said. The hint tells a
         * recipient what to do when they cannot read a message -- show an error now, show
         * nothing and wait for a resend, or need no error at all -- so replaying the wrong one
         * tells somebody to wait for something after first telling them not to worry.
         *
         * Defaulting to `RESENDABLE` leaves every existing row saying exactly what the resend
         * path already assumed, so the migration changes nothing that is already recorded.
         */
        31 to listOf(
            "ALTER TABLE message_log ADD COLUMN content_hint INTEGER NOT NULL DEFAULT 1;"
        ),

        /**
         * Whether this person still has to be shown that our two identities are one person.
         *
         * Set when a message arrives addressed to this account's **phone-number identity**,
         * which is the sender saying they know us by number and not by account. Cleared once a
         * message carrying the proof has gone to them. See
         * [SignalContactStore.markNeedsPniSignature].
         */
        30 to listOf(
            "ALTER TABLE recipient ADD COLUMN needs_pni_signature INTEGER NOT NULL DEFAULT 0;"
        ),

        /**
         * What a message that would not open was, so the conversation can say one is missing.
         *
         * ⚠ A sealed-sender envelope carries no source. Who sent it is known only inside the
         * protocol exception, at the moment the resend is asked for -- so it has to be written
         * down then or it is gone.
         *
         * Four plain columns rather than a table of its own, because the envelope row already
         * *is* the pending record: upstream keeps a separate `PendingRetryReceiptCache` only
         * because its envelope is discarded by then, and this app keeps its envelope so a later
         * session repair can still open it. Keeping them together means the record cannot
         * outlive the thing it describes.
         */
        29 to listOf(
            "ALTER TABLE envelope ADD COLUMN retry_sender TEXT;",
            "ALTER TABLE envelope ADD COLUMN retry_device INTEGER NOT NULL DEFAULT 0;",
            "ALTER TABLE envelope ADD COLUMN retry_group BLOB;",
            "ALTER TABLE envelope ADD COLUMN placeholder_at INTEGER;"
        )
    )

    /** 0 = ACI, 1 = PNI, as signal-cli numbers them. Both rows exist from the start. */
    val SEED = listOf(
        "INSERT INTO account (_id) VALUES (1);",
        "INSERT INTO account_identity (account_id_type) VALUES (0);",
        "INSERT INTO account_identity (account_id_type) VALUES (1);"
    )
}
