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
) : SQLiteOpenHelper(context, NAME, passphrase, null, ProtocolStoreSchema.VERSION, 0, null, null, false) {

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
        // Not on by default in SQLite, and the schema declares references between the account
        // and its identities.
        db.execSQL("PRAGMA foreign_keys = ON;")
    }

    override fun onCreate(db: SQLiteDatabase) {
        Timber.i("signal store: creating protocol database v%d", ProtocolStoreSchema.VERSION)
        ProtocolStoreSchema.ALL.forEach(db::execSQL)
        ProtocolStoreSchema.SEED.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Nothing to migrate yet, and a silent no-op would be the wrong default here: this
        // database holds key material that cannot be refetched, so an unhandled upgrade must
        // be loud rather than leave a half-known schema in place.
        throw IllegalStateException(
            "no migration from protocol store v$oldVersion to v$newVersion"
        )
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
