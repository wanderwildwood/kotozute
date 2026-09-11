package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.AccountEntropyPool
import org.signal.core.models.storageservice.StorageKey
import timber.log.Timber

/**
 * The key the account's stored state is read with.
 *
 * A linked device is not given it when it links. It asks -- `SyncMessage.Request` of type KEYS
 * -- and the primary answers with the **account entropy pool**. That pool derives the master
 * key, and from that the storage service key, the message-backup keys, and the material that
 * recovers the account.
 *
 * **Only the storage service key is kept.** The pool is derived from once, in memory, and
 * dropped. This app reads the account's contact list and nothing else; holding the material
 * for backups and account recovery would be holding what it cannot use and can only lose.
 * Signal Android keeps the pool because it needs all of it — keeping less here is not a
 * departure from that, it is the same posture applied to a smaller job.
 *
 * ⚠ What is kept still opens the account's stored state, where every other key in this
 * database opens one conversation. It is never logged, never written into an export this app
 * makes, and never leaves this database.
 */
internal class SignalKeyStore(private val db: ProtocolDatabase) {

    /**
     * Derives from the pool the primary sent and keeps only what is needed.
     *
     * Returns whether it worked, so a caller can ask again rather than believe it has a key.
     */
    fun store(accountEntropyPool: String): Boolean {
        if (accountEntropyPool.isBlank()) return false
        val derived = runCatching {
            AccountEntropyPool.parseOrNull(accountEntropyPool)
                ?.deriveMasterKey()
                ?.deriveStorageServiceKey()
                ?.key
        }.onFailure { Timber.w(it, "signal keys: the pool would not derive") }.getOrNull()
            ?: return false

        withStoreLock(db) {
            db.writableDatabase.execSQL(
                """
                INSERT INTO account_keys (_id, storage_key, updated_timestamp)
                VALUES (1, ?, ?)
                ON CONFLICT(_id) DO UPDATE SET
                  storage_key = excluded.storage_key,
                  updated_timestamp = excluded.updated_timestamp
                """.trimIndent(),
                arrayOf<Any?>(derived, System.currentTimeMillis())
            )
        }
        // Says only that it happened: not the pool, not the key, not a prefix of either.
        Timber.i("signal keys: the storage service key is now known")
        return true
    }

    private fun stored(): ByteArray? = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT storage_key FROM account_keys WHERE _id = 1", null
        ).use { c -> if (c.moveToFirst()) c.getBlob(0)?.takeIf { it.isNotEmpty() } else null }
    }

    /** Whether the primary has answered yet. */
    fun known(): Boolean = runCatching { stored() != null }.getOrDefault(false)

    /** The key itself, or null while it is unknown -- a reason to ask again, not to fail. */
    fun storageKey(): StorageKey? = runCatching { stored()?.let { StorageKey(it) } }
        .onFailure { Timber.w(it, "signal keys: the stored key would not load") }
        .getOrNull()
}
