package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The secret every stored attachment's key is derived from.
 *
 * Signal Android's `AttachmentSecretProvider`: 32 random bytes made once, sealed by a key that
 * lives in the phone's keystore, and the sealed form kept in a private preferences file. Only
 * upstream's "modern" key exists here -- its classic cipher and MAC keys are for files written
 * by Signal builds from before 2018, and this app never had any.
 *
 * The same posture as [ProtocolStoreKey] and for the same reason: **there is no fallback.**
 *
 * - [forReading] never makes a secret. With none, or with one the keystore will not open, the
 *   answer is null and an encrypted attachment stays unread -- and stays on disk.
 * - [forWriting] makes one only when [mayCreate] says nothing has been encrypted yet. A fresh
 *   secret beside files encrypted under a lost one would be worse than failing: the format
 *   carries no authentication, so those files would read back as noise with no error at all.
 *   It throws instead, and the caller does not write the attachment in the clear.
 *
 * Sealing is AES-GCM, so the secret itself cannot come back subtly wrong: it opens whole or
 * not at all.
 */
internal object AttachmentSecret {

    /** The keystore has no entry under [ALIAS] at all, as against one it will not hand over now. */
    private class EntryGone : IllegalStateException("the keystore entry that sealed the attachment secret is gone")

    private const val PREFS = "signal_attachment_secret"
    private const val PREF_SEALED = "sealed_secret"
    private const val PREF_IV = "iv"

    private const val KEYSTORE = "AndroidKeyStore"

    /** Its own alias, so losing one store's key says nothing about another's. */
    private const val ALIAS = "kotozute_attachment_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val SECRET_BYTES = 32

    @Volatile
    private var cached: ByteArray? = null

    /**
     * When the keystore last refused, so a screen of thumbnails that all fail does not ask it
     * again for each one. Short: the refusal is often passing, and the next look should find
     * out.
     */
    @Volatile
    private var refusedAt = 0L
    private const val REFUSAL_REMEMBERED_MS = 10_000L

    /**
     * Whether attachments are encrypted on this phone and cannot be opened.
     *
     * True when the keystore will not open the secret, and also when files are encrypted and
     * no secret is on record at all. False whenever nothing is encrypted yet, with or without
     * a seal on record: there is nothing to be locked out of, and a seal whose keystore entry
     * is gone is replaced the next time an attachment is written (see [forWriting]).
     */
    fun locked(context: Context): Boolean {
        if (SealedFiles.nothingEncrypted(context.filesDir)) return false
        return forReading(context) == null
    }

    /**
     * Whether files are encrypted here under a secret that can never be opened again: none is
     * on record, half of one is, or the keystore entry that sealed it is gone. Not when the
     * keystore only refuses for now, which [locked] also reports and which can pass.
     */
    @Synchronized
    fun lostForGood(context: Context): Boolean {
        if (SealedFiles.nothingEncrypted(context.filesDir)) return false
        if (cached != null) return false
        // ⛔ Locked is not lost: this answer decides whether files are deleted.
        if (com.wanderwildwood.kotozute.lock.AppLock.isLocked(context)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sealed = com.wanderwildwood.kotozute.lock.AppLock.reveal(prefs.getString(PREF_SEALED, null))
        val iv = prefs.getString(PREF_IV, null)
        if (sealed == null || iv == null) return true
        return try {
            unseal(sealed, iv).also { cached = it; refusedAt = 0L }
            false
        } catch (gone: EntryGone) {
            true
        } catch (t: Throwable) {
            // Refused, not gone: the files may open again.
            false
        }
    }

    /**
     * Removes every file encrypted under a secret that is [lostForGood], and the record of
     * that secret, so the next attachment written starts a new one (see [forWriting]).
     *
     * ⚠ Only ever for a secret lost for good: the files it removes could not be read by
     * anything, but they are still deleted, so it does nothing while the keystore may only be
     * refusing for now. Synchronized with [forWriting], so no new secret is made part way.
     *
     * @return true when nothing encrypted is left and a new secret can be made.
     */
    @Synchronized
    fun startOver(context: Context): Boolean {
        if (!lostForGood(context)) return SealedFiles.nothingEncrypted(context.filesDir)
        if (!SealedFiles.removeAll(context.filesDir)) {
            Timber.w("signal attachment: not every unreadable encrypted file could be removed")
            return false
        }
        val cleared = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(PREF_SEALED)
            .remove(PREF_IV)
            .commit()
        cached = null
        refusedAt = 0L
        Timber.w("signal attachment: removed the attachments whose secret was lost; a new one will be made")
        return cleared
    }

    /** True once a secret has been sealed, whether or not it can still be opened. */
    fun exists(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(PREF_SEALED)

    /** The secret, or null when there is none or it cannot be opened. Never creates one. */
    @Synchronized
    fun forReading(context: Context): ByteArray? {
        cached?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Locked: unread for now, and left on disk.
        val sealed = runCatching { com.wanderwildwood.kotozute.lock.AppLock.reveal(prefs.getString(PREF_SEALED, null)) }.getOrNull() ?: return null
        val iv = prefs.getString(PREF_IV, null) ?: return null
        val now = android.os.SystemClock.elapsedRealtime()
        if (refusedAt != 0L && now - refusedAt < REFUSAL_REMEMBERED_MS) return null
        return runCatching { unseal(sealed, iv) }
            .onFailure {
                refusedAt = now
                Timber.w(it, "signal attachment: the attachment secret would not open")
            }
            .getOrNull()
            ?.also { cached = it; refusedAt = 0L }
    }

    /**
     * The secret, made now if there has never been one.
     *
     * @param mayCreate asked only when no secret is on record: whether it is safe to start a
     *   new one, which is to say whether nothing is encrypted yet.
     * @throws IllegalStateException when the secret cannot be had. See the class note.
     */
    @Synchronized
    fun forWriting(context: Context, mayCreate: () -> Boolean): ByteArray {
        cached?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Throws Locked rather than let a new secret be made beside the covered one.
        val sealed = com.wanderwildwood.kotozute.lock.AppLock.reveal(prefs.getString(PREF_SEALED, null))
        val iv = prefs.getString(PREF_IV, null)
        try {
            if (sealed != null && iv != null) {
                try {
                    return unseal(sealed, iv).also { cached = it }
                } catch (gone: EntryGone) {
                    // The keystore entry is not refusing for a moment; it is not there, and
                    // that seal can never open again. With nothing encrypted under it, nothing
                    // is lost by starting over -- and not starting over would refuse every
                    // attachment from now on, received or sent, until the app's data was
                    // cleared. With anything encrypted, it stays a fault to report.
                    check(mayCreate()) { "attachments are already encrypted and the entry that sealed their secret is gone" }
                    Timber.w("signal attachment: the attachment secret's keystore entry is gone and nothing was encrypted under it; making a new one")
                    val fresh = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
                    seal(context, fresh)
                    refusedAt = 0L
                    return fresh.also { cached = it }
                }
            }
            check(sealed == null && iv == null) { "half of the sealed attachment secret is missing" }
            check(mayCreate()) { "attachments are already encrypted and their secret is gone" }
            val fresh = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
            seal(context, fresh)
            return fresh.also { cached = it }
        } catch (t: Throwable) {
            throw IllegalStateException("the attachment secret is unavailable", t)
        }
    }

    private fun seal(context: Context, secret: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.ENCRYPT_MODE, keystoreKey(createIfMissing = true)) }
        val sealed = cipher.doFinal(secret)
        // commit, not apply, and checked: a file may be encrypted under this the moment it is
        // returned, and a secret that had not reached the disk when the process died would
        // take that file with it.
        val written = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_SEALED, com.wanderwildwood.kotozute.lock.AppLock.coverForWrite(context, Base64.encodeToString(sealed, Base64.NO_WRAP)))
            .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
        check(written) { "the sealed attachment secret could not be written" }
    }

    private fun unseal(sealed: String, iv: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                // Never creating one here: a new keystore entry cannot open an old seal.
                keystoreKey(createIfMissing = false),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
            )
        }
        return cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP))
    }

    /**
     * The keystore entry that seals the secret. See [ProtocolStoreKey]'s function of the same
     * name for the two things carried over from it: an entry is never created on the way to
     * opening a seal, and the keystore is asked twice and on its one thread, as Signal's
     * `KeyStoreHelper` does.
     */
    private fun keystoreKey(createIfMissing: Boolean): SecretKey = ProtocolStoreKey.onKeystoreThread {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = try {
            store.getEntry(ALIAS, null)
        } catch (first: java.security.UnrecoverableKeyException) {
            Timber.w(first, "signal attachment: the keystore would not hand over its entry; trying once more")
            store.getEntry(ALIAS, null)
        }
        (existing as? KeyStore.SecretKeyEntry)?.let { return@onKeystoreThread it.secretKey }

        if (!createIfMissing) throw EntryGone()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // Not required: an attachment arrives over a background socket with nobody
                    // there to unlock anything.
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKey()
    }
}
