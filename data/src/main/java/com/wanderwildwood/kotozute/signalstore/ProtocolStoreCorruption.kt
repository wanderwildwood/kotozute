package com.wanderwildwood.kotozute.signalstore

import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What to do when SQLCipher says the protocol store is corrupt.
 *
 * ⚠ **Not the default, which deletes the file.** `SQLiteOpenHelper` was given `null` here, and
 * SQLCipher's default handler responds to a corruption report by removing the database and
 * letting the next open create an empty one. For most databases that is a reasonable trade.
 * For this one it is the worst possible outcome: it holds the ACI and PNI identity key pairs,
 * every session, every peer's identity key, the device's own password and the storage-service
 * key. Delete it and the device is not degraded, it is gone -- it cannot decrypt anything sent
 * to it, cannot prove it is itself, and the only remedy is re-linking, which takes every
 * message with it. All of that from a single false-positive corruption report.
 *
 * Signal reserves the deleting handler (`SqlCipherDeletingErrorHandler`) for jobs, logs and
 * metrics -- things that can be rebuilt -- and gives everything else `SqlCipherErrorHandler`,
 * which diagnoses, logs, and **throws**. Crashing is the right answer: it is loud, it is
 * recoverable, and it leaves the file on disk for somebody to look at.
 *
 * The diagnostics are the two pragmas upstream runs. `integrity_check` asks whether the pages
 * make sense; `cipher_integrity_check` asks whether they decrypt. Which of the two fails says
 * whether this is a damaged file or a wrong key, and that is the difference between "restore
 * the backup" and "the key store has moved under us" -- worth knowing before anybody decides
 * what to do next.
 */
internal class ProtocolStoreCorruption(private val databaseName: String) : DatabaseErrorHandler {

    override fun onCorruption(db: SQLiteDatabase, exception: android.database.sqlite.SQLiteException?) {
        // A corruption report can arrive on several connections at once, and the diagnostics
        // below query the database that has just been called corrupt. Upstream guards this the
        // same way, for the same reason.
        if (inProgress.getAndSet(true)) {
            Timber.e("signal store: %s reported corrupt again while being examined", databaseName)
            return
        }
        try {
            Timber.e("signal store: %s REPORTED CORRUPT -- %s", databaseName, exception?.message.orEmpty())
            val pages = check(db, "PRAGMA integrity_check")
            val cipher = check(db, "PRAGMA cipher_integrity_check")
            Timber.e(
                "signal store: integrity_check=%s cipher_integrity_check=%s",
                pages ?: "could not run", cipher ?: "could not run"
            )

            // ⚠ Thrown, never deleted. This is the whole point of the class.
            throw ProtocolStoreCorrupt(
                "$databaseName reported corrupt: ${exception?.message}; " +
                    "integrity_check=$pages cipher_integrity_check=$cipher"
            )
        } finally {
            inProgress.set(false)
        }
    }

    /** Runs one diagnostic pragma, or null if even that will not go. */
    private fun check(db: SQLiteDatabase, pragma: String): String? = runCatching {
        db.rawQuery(pragma, null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }
                .take(MAX_DIAGNOSTIC_ROWS)
                .joinToString("; ")
        }
    }.getOrNull()

    /** Raised instead of deleting the store. Named so a crash report says what happened. */
    internal class ProtocolStoreCorrupt(message: String) : IllegalStateException(message)

    private companion object {
        val inProgress = AtomicBoolean(false)

        /** Enough to tell what kind of damage it is; `integrity_check` can return thousands. */
        const val MAX_DIAGNOSTIC_ROWS = 20
    }
}
