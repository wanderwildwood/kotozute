package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock

/**
 * Signal's protocol state, in its own encrypted database.
 *
 * Separate from Realm on purpose, and the reasoning is in docs/DECISION-protocol-store.md. The
 * short of it: libsignal's store methods are `@CalledFromNative`, so the Rust core re-enters
 * this code synchronously in the middle of a cipher operation, from whichever thread it happens
 * to be on. Realm cannot survive that; SQLite can.
 *
 * Encrypted with the pattern the message database has used since v1.11.2 -- a random key sealed
 * by a keystore key. The limit is the same and worth restating: the keystore key cannot require
 * user authentication, because this database has to open from a boot broadcast and a background
 * socket with nobody present. It protects a lifted file, not code running as this app.
 *
 * ⚠ Losing this key is **not** recoverable the way the message database's is. That one can be
 * discarded and refilled from the bridge. This one holds the device's identity, and losing it
 * means the phone is no longer a device on the account -- the only repair is to link again.
 */
class ProtocolDatabase(
    private val context: Context,
    private val passphrase: ByteArray
) : SQLiteOpenHelper(
    context,
    NAME,
    passphrase,
    null,
    ProtocolStoreSchema.VERSION,
    0,
    // ⚠ Not null. Null here is SQLCipher's default handler, which answers a corruption report
    // by deleting the database -- and this one cannot be recreated. See
    // [ProtocolStoreCorruption]: it diagnoses, logs, and throws instead.
    ProtocolStoreCorruption(NAME),
    // Still null, and deliberately. Signal passes a hook here that sets kdf_iter = 1 and
    // cipher_compatibility = 3, which is right for a random 32-byte key -- but it applies from
    // the moment a database is created, and adding it to a store that already exists would
    // change how the key is derived and leave the file unopenable -- taking the identity keys,
    // the sessions and the device password with it. Doing it properly means opening with the
    // old parameters, `sqlcipher_export`ing to a new file with the new ones and swapping: a
    // migration with a real failure mode, spent to save PBKDF2 time on cold opens. Not a
    // constructor argument, and not worth it until that latency is measured to matter.
    null,
    false
) {

    /**
     * One lock for the whole store, following signal-cli's `ReentrantSignalSessionLock`.
     *
     * Deliberately not one lock per table. `SignalProtocolStore.archiveSession()` reaches into
     * the sender-key store from inside a libsignal callback that already holds the session
     * lock; independent locks taken in different orders deadlock, and they would do it on the
     * receive thread in the field rather than in a test. Reentrant because the same thread will
     * come back through here while it is already inside.
     */
    val lock = ReentrantLock()

    override fun onConfigure(db: SQLiteDatabase) {
        // Write-ahead logging, because the receive path writes sessions while the UI reads.
        db.enableWriteAheadLogging()
    }

    override fun onOpen(db: SQLiteDatabase) {
        // ⚠ The supported call, not a raw pragma, and not in onConfigure.
        //
        // `PRAGMA foreign_keys = ON` applies to the one connection it runs on; with
        // write-ahead logging there is a pool of them, so the setting held for whichever
        // connection happened to configure the database and for none of the others. The
        // helper's own method reconfigures the pool, which is why Signal calls
        // `setForeignKeyConstraintsEnabled(true)` in `onOpen` rather than writing the pragma.
        //
        // ⚠ And the comment that used to be here was false: it said the schema declares
        // references between the account and its identities. It declares none -- there is not
        // one REFERENCES clause in `ProtocolStoreSchema` -- so the setting has been inert all
        // along. Kept, correctly done, so that a migration which adds a real reference gets
        // the cascade it expects instead of discovering this.
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        Timber.i("signal store: creating protocol database v%d", ProtocolStoreSchema.VERSION)
        ProtocolStoreSchema.ALL.forEach(db::execSQL)
        ProtocolStoreSchema.SEED.forEach(db::execSQL)
    }

    /**
     * Steps forward one version at a time, and refuses to guess.
     *
     * A missing step still throws. This database holds key material that cannot be refetched
     * -- the identity, the sessions, the device's own password -- so there is no "drop and
     * recreate" available here the way there is for the message database. An unhandled upgrade
     * must be loud rather than leave a half-known schema behind.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        ((oldVersion + 1)..newVersion).forEach { version ->
            val steps = ProtocolStoreSchema.MIGRATIONS[version]
                ?: throw IllegalStateException("no migration for protocol store v$version")
            Timber.i("signal store: migrating protocol database to v%d", version)
            steps.forEach(db::execSQL)
        }
    }

    companion object {
        const val NAME = "signal-protocol.db"

        /** As signal-cli numbers them, and the numbers are stored, so they cannot drift. */
        const val ACCOUNT_ID_TYPE_ACI = 0
        const val ACCOUNT_ID_TYPE_PNI = 1

        init {
            // SQLCipher's native library. Loading it here means a failure surfaces when the
            // class is first touched rather than at some later query.
            System.loadLibrary("sqlcipher")
        }
    }
}

/**
 * Takes the store's one lock for the duration of [body].
 *
 * Every store method goes through here. The lock is reentrant because libsignal will call back
 * into a store from inside an operation that already holds it -- see [ProtocolDatabase.lock].
 */
internal inline fun <T> withStoreLock(db: ProtocolDatabase, body: () -> T): T {
    db.lock.lock()
    try {
        return body()
    } finally {
        db.lock.unlock()
    }
}

/**
 * A UUID as 16 raw bytes, big-endian, matching signal-cli's `UuidUtil.toByteArray`.
 *
 * Distribution ids are stored as BLOBs, and the text form would simply never match a lookup --
 * surfacing as group messages that fail to decrypt rather than as an error pointing here.
 */
internal fun java.util.UUID.toByteArray(): ByteArray =
    java.nio.ByteBuffer.allocate(16)
        .putLong(mostSignificantBits)
        .putLong(leastSignificantBits)
        .array()

/**
 * The same 16 bytes as a SQL blob literal, `x'...'`, for use inline in a query.
 *
 * Necessary, not stylistic. `execSQL` binds a `ByteArray` argument as a blob, but `rawQuery`
 * binds it as its `toString()` -- `[B@1f2e3d` -- so `WHERE distribution_id = ?` with a byte
 * array silently matches nothing. Nothing throws; the row is simply never found. Measured on
 * device: the same lookup returns 2 rows as a literal and 0 as a bound argument.
 *
 * That failure is invisible in exactly the tests one writes first. A lookup for a key that was
 * never stored returns null either way, so a store can pass its round of checks and still be
 * unable to find anything it wrote. Downstream it surfaces as group messages that will not
 * decrypt, a long way from here.
 *
 * Safe to interpolate: the output is 32 hex characters derived from a UUID's own bytes, so
 * there is no path by which caller data reaches the SQL.
 */
internal fun java.util.UUID.toSqlBlobLiteral(): String =
    toByteArray().joinToString(separator = "", prefix = "x'", postfix = "'") { "%02x".format(it) }
