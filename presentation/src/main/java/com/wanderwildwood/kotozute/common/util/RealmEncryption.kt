package com.wanderwildwood.kotozute.common.util

import android.content.Context
import android.preference.PreferenceManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.realm.Realm
import io.realm.DynamicRealm
import io.realm.RealmConfiguration
import timber.log.Timber
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the message database encrypted on disk.
 *
 * Everything the app holds lives in one Realm, and until this it sat there in the clear:
 * `strings` over the file returned message bodies, contact names and numbers. Android's own
 * file-based encryption covers it while the phone is locked and the file is app-private, so
 * this is not the difference between safe and exposed -- it is the difference between a
 * rooted or lifted device giving up the messages immediately and not at all. Signal's own
 * client dropped its passphrase when file-based encryption arrived, and Molly exists because
 * people wanted it back; this is the same argument, and the messages here came over Signal.
 *
 * The Realm key is 64 random bytes, generated once. It is not kept in the clear either: it is
 * sealed with an AES-GCM key held in the Android keystore, which never leaves the keystore,
 * and only the sealed form is written to preferences.
 *
 * No user authentication is bound to the keystore key on purpose. The app has to open the
 * database from a boot broadcast and from background workers, with nobody present to
 * authenticate, and a key that could not be used then would take the app offline rather than
 * make it safer.
 */
object RealmEncryption {

    // Not private: [SetAsideDatabases] names set-aside files and keys from these.
    internal const val PREFS = "realm_encryption"
    internal const val PREF_SEALED_KEY = "sealed_key"
    internal const val PREF_IV = "iv"
    private const val PREF_ENCRYPTED = "realm_is_encrypted"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "kotozute_realm_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** Realm's key length. Not negotiable; it wants exactly this many bytes. */
    private const val REALM_KEY_BYTES = 64

    internal const val REALM_NAME = "default.realm"

    /** Kept until the encrypted copy has been opened once, then deleted. */
    private const val ROLLBACK_SUFFIX = ".pre-encryption"


    /**
     * The key to open the Realm with, or null when the keystore will not produce one.
     *
     * Null is returned only when the keystore itself will not co-operate, which happens on
     * devices with a broken or wiped keystore, and sometimes for a while after a system
     * update. It used to mean "carry on without encryption". It does not any more: see
     * [refusal] for what a missing key is allowed to open, which is only a database that was
     * never encrypted in the first place.
     */
    fun keyOrNull(context: Context): ByteArray? = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sealed = prefs.getString(PREF_SEALED_KEY, null)
        val iv = prefs.getString(PREF_IV, null)
        when {
            sealed != null && iv != null -> unseal(sealed, iv)
            else -> newKey().also { seal(context, it) }
        }
    } catch (t: Throwable) {
        Timber.e(t, "realm: the keystore gave no usable encryption key")
        null
    }

    /** Why the message database is not being opened. */
    enum class Refusal {
        /** There is an encrypted database on disk and no key to open it with. */
        NO_KEY_FOR_ENCRYPTED,

        /** There is no database yet, and no key to make an encrypted one with. */
        NO_KEY_FOR_NEW
    }

    /**
     * Whether to refuse to open the database with this key, and why. Null means go ahead.
     *
     * With a key there is nothing to refuse. Without one, three cases:
     *
     *  - **The file on disk is encrypted.** Opening it without its key fails, and what used to
     *    follow was the worst outcome available: the database was discarded and an empty,
     *    unencrypted one took its place. A keystore that is only slow to come back after an
     *    update cost somebody their whole Signal history that way. Refused; nothing is touched.
     *  - **There is no file.** Going ahead would create an unencrypted database. Refused.
     *  - **The file on disk was never encrypted** -- an install from before this class
     *    existed whose keystore has never worked, or one whose conversion declined to run.
     *    Those messages are in the clear already and refusing would only lock their owner out
     *    of them, so that file is still opened as it is. It is converted the first time a key
     *    is available ([encryptExistingRealm]).
     */
    fun refusal(context: Context, key: ByteArray?): Refusal? {
        if (key != null) return null
        val exists = File(context.filesDir, REALM_NAME).exists()
        return when {
            !exists -> Refusal.NO_KEY_FOR_NEW
            isEncrypted(context) -> Refusal.NO_KEY_FOR_ENCRYPTED
            else -> null
        }
    }

    /**
     * Rewrites an existing plaintext database as an encrypted one, once.
     *
     * The order matters more than the cryptography. The original is renamed rather than
     * deleted, the encrypted copy is opened before anything is thrown away, and if that open
     * fails the original goes back. A migration that loses somebody's messages is worse than
     * no migration at all, so every step here is reversible until the last one.
     *
     * @return true when the Realm on disk is encrypted with [key] after this returns.
     */
    fun encryptExistingRealm(context: Context, key: ByteArray, builder: () -> RealmConfiguration.Builder): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_ENCRYPTED, false)) return true

        val dir = context.filesDir
        val realm = File(dir, REALM_NAME)
        if (!realm.exists()) {
            // A fresh install: nothing to convert, and the first open will create it encrypted.
            prefs.edit().putBoolean(PREF_ENCRYPTED, true).apply()
            return true
        }

        val encrypted = File(dir, "$REALM_NAME.encrypting")
        val rollback = File(dir, "$REALM_NAME$ROLLBACK_SUFFIX")
        encrypted.delete()
        rollback.delete()

        try {
            // Written from the plaintext file as it stands, schema migration included: the
            // same configuration the app would have used, only without a key.
            Realm.getInstance(builder().build()).use { it.writeEncryptedCopyTo(encrypted, key) }
        } catch (t: Throwable) {
            Timber.e(t, "realm: could not write an encrypted copy; staying unencrypted")
            encrypted.delete()
            return false
        }

        if (!realm.renameTo(rollback)) {
            Timber.e("realm: could not set the plaintext file aside; staying unencrypted")
            encrypted.delete()
            return false
        }
        if (!encrypted.renameTo(realm)) {
            Timber.e("realm: could not put the encrypted copy in place; rolling back")
            rollback.renameTo(realm)
            return false
        }

        // Prove it opens before anything is thrown away.
        val opened = runCatching {
            Realm.getInstance(builder().encryptionKey(key).build()).use { it.isEmpty }
        }
        if (opened.isFailure) {
            Timber.e(opened.exceptionOrNull(), "realm: encrypted copy will not open; rolling back")
            realm.delete()
            rollback.renameTo(realm)
            return false
        }

        // commit(): on disk before the plaintext copy goes, not some time after.
        prefs.edit().putBoolean(PREF_ENCRYPTED, true).commit()
        rollback.delete()
        Timber.i("realm: database encrypted at rest")
        return true
    }

    /**
     * Whether the Realm on disk is the encrypted one.
     *
     * Read after [encryptExistingRealm] to decide which configuration to install, so that a
     * migration which declined to run does not leave the app opening a plaintext file with a
     * key, which fails, or an encrypted one without, which fails the other way.
     */
    fun isEncrypted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ENCRYPTED, false)

    /**
     * The version Realm itself names when it refuses a file newer than this build, or null.
     *
     * Realm 10.15.0 says, exactly: `Illegal Argument: Provided schema version 27 is less than
     * last set version 28.` Both numbers are in there, so the ordinary case needs no second
     * look at the file at all -- which matters because **opening a realm writes to it**, even
     * read-only through a DynamicRealm. A path whose purpose is to leave a database alone
     * should touch it zero times, not once.
     *
     * ⚠ Matching on a library's message text is brittle, so this is only ever the fast path:
     * when it does not match, [schemaVersionOnDisk] still opens the file and answers properly.
     * Getting that order wrong would be serious -- a missed match falls through to deleting.
     */
    fun versionRefusedAsNewer(failure: Throwable): Long? =
        Regex("schema version (\\d+) is less than last set version (\\d+)")
            .find(failure.message.orEmpty())
            ?.groupValues?.get(2)?.toLongOrNull()

    /**
     * The schema version of the file on disk, or null if it cannot be read at all.
     *
     * ⚠ **The whole point is to tell two failures apart that look identical from outside.** A
     * keystore that lost its key leaves a file nobody can decrypt, and starting over beats an
     * app that will not open. A file written by a *newer* build of this app is not damaged at
     * all -- every byte is intact and the only thing wrong is that this build is behind it.
     * [setAsideUnreadableRealm] starts the app on an empty database, and the Signal history
     * has no second copy anywhere. Putting a perfectly good database out of reach because the
     * check could not tell it from a damaged one is the worst trade in this file.
     *
     * A DynamicRealm is the discriminator because it opens a file without validating it
     * against the model classes -- which is how a migration reads one. If it opens, the key
     * is right and the file is sound, so whatever went wrong was not "unreadable".
     */
    fun schemaVersionOnDisk(key: ByteArray?): Long? = runCatching {
        // ⚠ Its own configuration, deliberately bare: the app's carries compactOnLaunch, and
        // compacting rewrites the file. Measured -- probing with the app's config moved the
        // file's checksum on every attempt. A path whose entire purpose is to leave a database
        // alone must not rewrite it to find out what version it is.
        val probe = RealmConfiguration.Builder()
            .name(REALM_NAME)
            .apply { if (key != null) encryptionKey(key) }
            .build()
        DynamicRealm.getInstance(probe).use { it.version }
    }.onFailure { Timber.w(it, "realm: the file would not open even without its schema") }
        .getOrNull()

    /**
     * Last resort, for a database file that will not open: moves it out of the way so that a
     * new, empty one can be made. **Nothing is deleted.**
     *
     * The file is renamed beside itself (`default.realm.unreadable-<time>`) and stays on the
     * phone. This used to delete it. SMS can be filled again from the telephony provider on
     * the next sync; Signal cannot -- this database holds the only copy of it, and Signal
     * delivers a message to a device once. A file that is kept can still be read by whatever
     * later works out why it would not open; a deleted one cannot.
     *
     * Only Realm's own scratch files (the lock, the note pipe, the management folder) are
     * removed, because a new database of the same name must not find the old one's there.
     *
     * @return false when the file could not be moved; it is then left exactly as it was.
     * @param forgetKey true when the reason is that the key is gone: the sealed key is moved
     *   aside too, under the same suffix, so a new one is made on the next start. False when
     *   the key is fine and the file is what is wrong; the new database then uses the same key
     *   and the file set aside stays readable with it.
     */
    fun setAsideUnreadableRealm(context: Context, forgetKey: Boolean): Boolean {
        val dir = context.filesDir
        val suffix = SetAsideDatabases.MARK + System.currentTimeMillis()
        val realm = File(dir, REALM_NAME)
        if (realm.exists()) {
            val moved = realm.renameTo(File(dir, REALM_NAME + suffix))
            Timber.e("realm: the database would not open; set aside as %s%s (moved: %b)", REALM_NAME, suffix, moved)
            // If it could not even be renamed it stays where it is, untouched, and the next
            // start meets it again. That is the right failure: not opening beats deleting.
            if (!moved) return false
        }
        listOf("$REALM_NAME.lock", "$REALM_NAME.note").forEach { File(dir, it).delete() }
        File(dir, "$REALM_NAME.management").deleteRecursively()
        if (forgetKey) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(PREF_SEALED_KEY + suffix, prefs.getString(PREF_SEALED_KEY, null))
                .putString(PREF_IV + suffix, prefs.getString(PREF_IV, null))
                .remove(PREF_SEALED_KEY).remove(PREF_IV).remove(PREF_ENCRYPTED)
                // commit(): the process is about to be restarted on purpose.
                .commit()
        }
        return true
    }

    // --- key handling ---------------------------------------------------------------------

    private fun newKey(): ByteArray = ByteArray(REALM_KEY_BYTES).also(SecureRandom()::nextBytes)

    private fun seal(context: Context, key: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val sealed = cipher.doFinal(key)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_SEALED_KEY, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            // commit(), not apply(): a database is about to be locked with this key, and a key
            // still waiting in memory when the process dies is a database nobody can open.
            .commit()
            .let { written -> check(written) { "the sealed key was not written" } }
    }

    private fun unseal(sealed: String, iv: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystoreKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
        )
        return cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP))
    }

    private fun keystoreKey(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }
}
