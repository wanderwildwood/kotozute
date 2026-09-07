package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * The passphrase for [ProtocolDatabase] -- a random key sealed by a keystore key.
 *
 * The same shape as the message database's key, with one deliberate difference: **there is no
 * fallback.** `RealmEncryption.keyOrNull` returns null when the keystore will not co-operate,
 * on the reasoning that an unencrypted database the user can read beats an encrypted one
 * nobody can. That reasoning does not carry here. This database holds the account's identity
 * private keys, and the two ways of "carrying on" are both worse than stopping:
 *
 * - Unencrypted would leave the private keys of a Signal identity in a plain file.
 * - A fresh key would open an empty store, which reads as a device that was never linked --
 *   so the repair looks like "link again", quietly abandoning a device that is still on the
 *   account and still holds sessions nobody can now decrypt.
 *
 * So this throws instead, and the caller is expected to say so rather than paper over it.
 *
 * The keystore key cannot require user authentication: this database has to open from a boot
 * broadcast and a background socket with nobody present. It protects a file lifted off the
 * device, not code running as this app.
 */
object ProtocolStoreKey {

    private const val PREFS = "signal_protocol_store"
    private const val PREF_SEALED_KEY = "sealed_key"
    private const val PREF_IV = "iv"

    private const val KEYSTORE = "AndroidKeyStore"

    /** Its own alias. Sharing the Realm key's would tie the two databases' fates together. */
    private const val ALIAS = "kotozute_protocol_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** SQLCipher takes raw key bytes here rather than a passphrase to derive from. */
    private const val KEY_BYTES = 32

    /**
     * The key, creating and sealing one on first call.
     *
     * @throws IllegalStateException if the keystore will not produce it. See the class note:
     *   failing is the correct outcome, because both ways of continuing lose more.
     */
    fun require(context: Context): ByteArray = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sealed = prefs.getString(PREF_SEALED_KEY, null)
        val iv = prefs.getString(PREF_IV, null)
        if (sealed != null && iv != null) unseal(sealed, iv) else newKey().also { seal(context, it) }
    } catch (t: Throwable) {
        throw IllegalStateException("the protocol store key is unavailable", t)
    }

    /** True once a key exists, without creating one. Lets a caller ask whether to offer linking. */
    fun exists(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(PREF_SEALED_KEY)

    private fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)

    private fun seal(context: Context, key: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        val sealed = cipher.doFinal(key)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_SEALED_KEY, android.util.Base64.encodeToString(sealed, android.util.Base64.NO_WRAP))
            .putString(PREF_IV, android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            .apply()
    }

    private fun unseal(sealed: String, iv: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                keystoreKey(),
                GCMParameterSpec(GCM_TAG_BITS, android.util.Base64.decode(iv, android.util.Base64.NO_WRAP))
            )
        }
        return cipher.doFinal(android.util.Base64.decode(sealed, android.util.Base64.NO_WRAP))
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
                    // Deliberately not required: this opens from a boot broadcast with nobody
                    // present. See the class note.
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKey()
    }
}
