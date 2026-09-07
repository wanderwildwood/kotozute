package com.wanderwildwood.kotozute.common.util

import android.content.Context
import android.preference.PreferenceManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.realm.Realm
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

    private const val PREFS = "realm_encryption"
    private const val PREF_SEALED_KEY = "sealed_key"
    private const val PREF_IV = "iv"
    private const val PREF_ENCRYPTED = "realm_is_encrypted"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "kotozute_realm_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** Realm's key length. Not negotiable; it wants exactly this many bytes. */
    private const val REALM_KEY_BYTES = 64

    private const val REALM_NAME = "default.realm"

    /** Kept until the encrypted copy has been opened once, then deleted. */
    private const val ROLLBACK_SUFFIX = ".pre-encryption"

    /** Owned by Preferences; a test asserts these still match what it declares. */
    internal const val PREF_SIGNAL_CURSOR = "signalCursor"
    internal const val PREF_SIGNAL_BRIDGE_INSTANCE = "signalBridgeInstance"

    /**
     * The key to open the Realm with, or null to carry on without one.
     *
     * Null is returned only when the keystore itself will not co-operate, which happens on
     * devices with a broken or wiped keystore. Refusing to start would be the wrong answer to
     * that: an unencrypted database the user can read beats an encrypted one nobody can.
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
        Timber.e(t, "realm: no usable encryption key; continuing unencrypted")
        null
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

        prefs.edit().putBoolean(PREF_ENCRYPTED, true).apply()
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
     * Last resort, for a database that exists, claims to be encrypted, and will not open --
     * a keystore that lost its key, which some devices do on a firmware update.
     *
     * Both rails can be filled again from somewhere else: SMS from the telephony provider on
     * the next sync, Signal from the bridge, which keeps its own copy. What does not come
     * back is what only ever lived here -- drafts, pins, and which conversations were
     * archived. That is a real loss and this says so in the log rather than quietly starting
     * over as though nothing had happened.
     *
     * The Signal cursor goes with the database. It is a high-water mark into the bridge's
     * sequence, and leaving it behind would point past every message the new database does
     * not have: the bridge would answer "nothing since then" and the rail would stay empty
     * for good. Reset, the next sync draws the history down again.
     */
    fun discardUnreadableRealm(context: Context) {
        val dir = context.filesDir
        Timber.e("realm: cannot open the encrypted database; starting a new one")
        listOf(REALM_NAME, "$REALM_NAME.lock", "$REALM_NAME.note").forEach { File(dir, it).delete() }
        File(dir, "$REALM_NAME.management").deleteRecursively()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(PREF_SEALED_KEY).remove(PREF_IV).remove(PREF_ENCRYPTED).apply()
        // Named rather than injected: this runs before the graph is usable, and the two
        // names are checked by a test so a rename cannot quietly strand the rail.
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putLong(PREF_SIGNAL_CURSOR, 0L)
            .putString(PREF_SIGNAL_BRIDGE_INSTANCE, "")
            .apply()
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
            .apply()
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
