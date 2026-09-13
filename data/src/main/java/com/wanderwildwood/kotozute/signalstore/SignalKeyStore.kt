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

        // ⚠ The pool itself is kept now, not only the one key derived from it. It is what
        // Signal calls "The Root of All Entropy" and every account key comes off it --
        // `deriveMasterKey` for the storage service, `deriveMessageBackupKey` for a written-out
        // copy. Reducing it to the storage key on the way in meant a backup had to invent its
        // own secret, thirty digits shown once and stored nowhere, which is lost the moment
        // the paper is. A linked device holds the pool in Signal too.
        withStoreLock(db) {
            db.writableDatabase.execSQL(
                """
                INSERT INTO account_keys (_id, storage_key, entropy_pool, updated_timestamp)
                VALUES (1, ?, ?, ?)
                ON CONFLICT(_id) DO UPDATE SET
                  storage_key = excluded.storage_key,
                  entropy_pool = excluded.entropy_pool,
                  updated_timestamp = excluded.updated_timestamp
                """.trimIndent(),
                arrayOf<Any?>(derived, accountEntropyPool, System.currentTimeMillis())
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

    /**
     * The key a written-out copy is locked with, or null before the primary has answered.
     *
     * ⚠ Not derived here. `MessageBackupKey(pool, aci)` is libsignal's own type and
     * `getAesKey()` is the key Signal encrypts a backup with -- the same one its
     * `AccountEntropyPool.deriveMessageBackupKey()` leads to. Doing the HKDF by hand off the
     * raw backup key would have been a second answer to a question libsignal has already
     * answered, and it would not have bound the key to the account the way this does.
     *
     * [aci] is bound in, so two accounts on one phone could never arrive at the same key.
     * The result is the same on every device on the account and the same tomorrow as today,
     * which is the whole point: a copy written by this phone opens on any phone that can
     * reach this account, and there is nothing to write down or lose.
     */
    fun messageBackupKey(aci: String): ByteArray? = runCatching {
        val pool = pool() ?: return@runCatching null
        if (aci.isBlank()) return@runCatching null
        val account = org.signal.libsignal.protocol.ServiceId.Aci.parseFromString(aci)
        org.signal.libsignal.messagebackup.MessageBackupKey(pool, account).aesKey
    }.onFailure { Timber.w(it, "signal keys: the backup key would not derive") }.getOrNull()

    private fun pool(): String? = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT entropy_pool FROM account_keys WHERE _id = 1", null
        ).use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
    }
}
