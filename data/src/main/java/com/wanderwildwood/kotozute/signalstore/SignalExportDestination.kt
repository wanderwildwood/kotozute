package com.wanderwildwood.kotozute.signalstore

import org.json.JSONObject
import java.io.File
import java.io.OutputStream

/**
 * Where an export is written: `main.jsonl` beside a `files/` directory, the same shape the
 * importer reads. On the phone that is a folder the reader picked; in a test it is a
 * directory.
 */
internal interface SignalExportDestination {

    /** The records file, truncated. */
    fun main(): OutputStream

    /**
     * A file under `files/`, or -- when [under] is null -- beside `main.jsonl`. Null if it
     * could not be created.
     */
    fun file(name: String, under: String? = DirectoryExportSource.FILES): OutputStream?

    /** What to call the folder in a sentence, once the writing is done. */
    fun name(): String
}

/**
 * A destination whose files are sealed on the way out.
 *
 * It wraps another destination rather than replacing it: what is written is the same export,
 * and only the bytes on the way to disk differ. The one plaintext thing is [META], which
 * carries the salt and says what the folder is -- an importer has to be able to tell an
 * encrypted backup from a Signal Desktop export before it can ask for a key, and a salt is
 * not a secret.
 */
internal class EncryptedExportDestination(
    private val inner: SignalExportDestination,
    lock: Lock
) : SignalExportDestination {

    /** What a copy is locked with. */
    internal sealed interface Lock {
        /**
         * The account's own backup key, which is what Signal does -- a copy opens on any
         * device that can reach this account and there is nothing to write down.
         */
        data class Account(val backupKey: ByteArray) : Lock

        /** Thirty digits, shown once. Kept so copies written before now still open. */
        data class Digits(val key: String) : Lock
    }

    private val salt = SignalBackupCrypto.newSalt()
    private val derived = when (lock) {
        is Lock.Account -> SignalBackupCrypto.deriveFromAccount(lock.backupKey, salt)
        is Lock.Digits -> SignalBackupCrypto.derive(lock.key, salt)
    }
    private val lockName = when (lock) {
        is Lock.Account -> ACCOUNT
        is Lock.Digits -> DIGITS
    }

    /** Written first, so a folder half-written is still recognisably ours. */
    fun writeMeta() {
        inner.file(META, under = null)?.use { out ->
            out.write(
                JSONObject()
                    .put("kotozuteBackup", VERSION)
                    .put("kdf", "hkdf-sha256")
                    .put("cipher", "aes-256-gcm")
                    // ⚠ New, and absent from every copy written before this. A header with no
                    // `key` is a copy locked with digits -- which is why the reader treats a
                    // missing field as [DIGITS] rather than as a fault. The structure is
                    // unchanged, so the version is not bumped: only what the key came from is.
                    .put("key", lockName)
                    .put("salt", java.util.Base64.getEncoder().encodeToString(salt))
                    .toString()
                    .toByteArray()
            )
        }
    }

    override fun main(): OutputStream = SignalBackupCrypto.encrypt(inner.main(), derived)

    override fun file(name: String, under: String?): OutputStream? =
        inner.file(name, under)?.let { SignalBackupCrypto.encrypt(it, derived) }

    override fun name(): String = inner.name()

    companion object {
        const val META = "backup.meta"
        const val VERSION = 1

        /** Header values for `key`. A header without one was written before the account key. */
        const val ACCOUNT = "account"
        const val DIGITS = "digits"
    }
}

/** An export written into an ordinary directory. */
internal class DirectoryExportDestination(private val dir: File) : SignalExportDestination {

    private val files = File(dir, DirectoryExportSource.FILES)

    override fun main(): OutputStream {
        dir.mkdirs()
        return File(dir, DirectoryExportSource.MAIN).outputStream()
    }

    override fun file(name: String, under: String?): OutputStream? {
        val parent = if (under == null) dir else File(dir, under)
        parent.mkdirs()
        return File(parent, name).outputStream()
    }

    override fun name(): String = dir.name
}
