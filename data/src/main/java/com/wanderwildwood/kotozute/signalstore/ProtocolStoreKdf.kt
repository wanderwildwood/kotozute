package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import timber.log.Timber
import java.io.File
import java.security.SecureRandom

/**
 * The protocol store's key derivation, moved to Signal's: one iteration rather than SQLCipher's
 * default 256,000.
 *
 * The key is 32 random bytes sealed by the keystore (see [ProtocolStoreKey]), so stretching it
 * buys nothing -- stretching is for passwords people choose. It costs a great deal: about 0.87 s
 * per connection on a Kompakt, twice per open because write-ahead logging keeps a pool, on every
 * cold start and every boot (measured 2026-10-02). Signal's `SqlCipherDatabaseHook` sets
 * `cipher_compatibility = 3` and `kdf_iter = 1` for exactly this reason; [HOOK] is its `postKey`.
 *
 * Its `preKey` is left out on purpose. That changes SQLCipher's process-wide *defaults*, and
 * the migration below reads the old file through those defaults.
 *
 * ⚠ The file holds the device's identity, which cannot be recreated -- losing it means linking
 * again. So the old file is never altered in place:
 *
 *  1. The old store is checkpointed, and its tables counted.
 *  2. A new file is made with [HOOK] and the old one exported into it (`sqlcipher_export`).
 *  3. The new file is opened afresh, integrity-checked, and every table's count compared.
 *  4. Only then are the files swapped: old to `.prekdf` (kept), new into place, marker written.
 *
 * Anything failing before step 4 deletes the new file and the store opens the old way, exactly
 * as before. Each step of the swap can be resumed from what is on disk, so a phone that dies in
 * the middle comes back to one complete store or the other. And before any of it touches the
 * real store, the same migration is run on a throwaway store in [rehearse]; if that fails, the
 * real one is left alone.
 */
internal object ProtocolStoreKdf {

    /** Signal's `SqlCipherDatabaseHook.postKey`. Per connection, so nothing else is affected. */
    val HOOK: SQLiteDatabaseHook = object : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) = Unit
        override fun postKey(connection: SQLiteConnection) {
            connection.execute("PRAGMA cipher_compatibility = 3;", null, null)
            connection.execute("PRAGMA kdf_iter = '1';", null, null)
            connection.execute("PRAGMA cipher_page_size = 4096;", null, null)
        }
    }

    private const val MARKER_SUFFIX = ".kdf1"
    private const val BACKUP_SUFFIX = ".prekdf"
    private const val FRESH_SUFFIX = ".kdf1-new"
    private val SIDECARS = listOf("", "-wal", "-shm", "-journal")

    /** How long the old file is kept after a migration, for a hand recovery. */
    private const val KEEP_BACKUP_MS = 14L * 24 * 60 * 60 * 1000

    /**
     * Whether the store called [name] is to be opened with [HOOK], migrating it first where it
     * can. Called before anything opens the store, under the same lock that opens it.
     */
    fun prepare(context: Context, name: String, key: ByteArray): Boolean {
        val db = context.getDatabasePath(name)
        val marker = File(db.path + MARKER_SUFFIX)
        val backup = File(db.path + BACKUP_SUFFIX)
        val fresh = File(db.path + FRESH_SUFFIX)

        if (marker.exists()) {
            pruneBackup(backup, marker)
            return true
        }

        // A swap that was interrupted. The backup is only made after the new file has been
        // verified, so a backup on disk means a verified new file exists, in one place or other.
        if (backup.exists()) {
            if (!db.exists() && fresh.exists()) moveAll(fresh, db)
            if (db.exists()) {
                marker.createNewFile()
                Timber.i("signal store: finished an interrupted switch to Signal's key derivation")
                return true
            }
            moveAll(backup, db)
            Timber.w("signal store: put the old file back after an interrupted switch")
        }

        if (!db.exists()) {
            // Nothing to move: the store is about to be created, and is created the new way.
            db.parentFile?.mkdirs()
            marker.createNewFile()
            return true
        }

        deleteAll(fresh)
        if (!rehearse(context)) return false
        return try {
            migrate(db, fresh, backup, marker, key)
            true
        } catch (t: Throwable) {
            Timber.w(t, "signal store: did not switch key derivation; opening it as before")
            deleteAll(fresh)
            false
        }
    }

    private fun migrate(db: File, fresh: File, backup: File, marker: File, key: ByteArray) {
        val started = System.currentTimeMillis()

        // 1. Everything into the main file, and what is in it.
        val (counts, version) = SQLiteDatabase.openDatabase(db.path, key, null, SQLiteDatabase.OPEN_READWRITE, null, null).use { old ->
            old.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            tableCounts(old) to old.version
        }

        // 2. Exported into a new file made the new way.
        SQLiteDatabase.openDatabase(
            fresh.path, key, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null, HOOK
        ).use { target ->
            target.execSQL("ATTACH DATABASE ? AS old KEY ?", arrayOf<Any>(db.path, key))
            target.rawQuery("SELECT sqlcipher_export('main', 'old')", null).use { it.moveToFirst() }
            target.execSQL("DETACH DATABASE old")
            target.version = version
        }

        // 3. Opened again from cold, the way the app will open it, and checked.
        SQLiteDatabase.openDatabase(fresh.path, key, null, SQLiteDatabase.OPEN_READONLY, null, HOOK).use { check ->
            check.rawQuery("PRAGMA cipher_integrity_check", null).use {
                require(!it.moveToFirst()) { "integrity check reported ${it.getString(0)}" }
            }
            check.rawQuery("PRAGMA quick_check", null).use {
                require(it.moveToFirst() && it.getString(0) == "ok") { "quick check failed" }
            }
            require(check.version == version) { "schema version ${check.version}, expected $version" }
            val copied = tableCounts(check)
            require(copied == counts) { "row counts differ: $copied against $counts" }
        }

        // 4. The swap, in an order every step of which [prepare] can resume from.
        moveAll(db, backup)
        moveAll(fresh, db)
        marker.createNewFile()
        Timber.i(
            "signal store: switched to Signal's key derivation in %d ms (%d tables, %d rows); old file kept",
            System.currentTimeMillis() - started, counts.size, counts.values.sum()
        )
    }

    /**
     * The same migration on a throwaway store, first. A SQLCipher build that cannot attach by
     * key, or export, finds out here rather than on the store that cannot be replaced.
     */
    private fun rehearse(context: Context): Boolean {
        val name = "kdf-rehearsal-${System.nanoTime()}.db"
        val db = context.getDatabasePath(name)
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        return try {
            ProtocolDatabase(context.withDatabaseName(name), key).use { helper ->
                val w = helper.writableDatabase
                w.execSQL("CREATE TABLE rehearsal (n INTEGER, b BLOB)")
                w.beginTransaction()
                try {
                    repeat(500) { w.execSQL("INSERT INTO rehearsal VALUES (?, ?)", arrayOf<Any>(it, ByteArray(64) { b -> b.toByte() })) }
                    w.setTransactionSuccessful()
                } finally {
                    w.endTransaction()
                }
            }
            migrate(db, File(db.path + FRESH_SUFFIX), File(db.path + BACKUP_SUFFIX), File(db.path + MARKER_SUFFIX), key)
            // And the result opens the way the app will open it.
            ProtocolDatabase(context.withDatabaseName(name), key, fastKdf = true).use { helper ->
                helper.readableDatabase.rawQuery("SELECT count(*) FROM rehearsal", null).use {
                    require(it.moveToFirst() && it.getInt(0) == 500) { "rehearsal rows missing" }
                }
            }
            true
        } catch (t: Throwable) {
            Timber.w(t, "signal store: the key derivation rehearsal failed; leaving the real store alone")
            false
        } finally {
            listOf("", BACKUP_SUFFIX, FRESH_SUFFIX, MARKER_SUFFIX).forEach { deleteAll(File(db.path + it)) }
        }
    }

    private fun tableCounts(db: SQLiteDatabase): Map<String, Long> {
        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null
        ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        return tables.associateWith { table ->
            db.rawQuery("SELECT count(*) FROM \"$table\"", null).use { if (it.moveToFirst()) it.getLong(0) else -1L }
        }
    }

    /** Moves a database and whichever of its sidecar files exist. */
    private fun moveAll(from: File, to: File) {
        SIDECARS.forEach { suffix ->
            val source = File(from.path + suffix)
            val target = File(to.path + suffix)
            if (source.exists()) {
                target.delete()
                check(source.renameTo(target)) { "could not move ${source.name} to ${target.name}" }
            } else if (suffix.isNotEmpty()) {
                // A sidecar left from the other file must not be read against this one.
                target.delete()
            }
        }
    }

    /** The helper names its own file, so point it at a throwaway one (as the self-check does). */
    private fun Context.withDatabaseName(name: String): Context =
        object : android.content.ContextWrapper(this) {
            override fun getDatabasePath(unused: String) = super.getDatabasePath(name)
        }

    private fun deleteAll(file: File) = SIDECARS.forEach { File(file.path + it).delete() }

    private fun pruneBackup(backup: File, marker: File) {
        if (backup.exists() && System.currentTimeMillis() - marker.lastModified() > KEEP_BACKUP_MS) {
            deleteAll(backup)
            Timber.i("signal store: removed the copy kept from before the key derivation switch")
        }
    }
}
