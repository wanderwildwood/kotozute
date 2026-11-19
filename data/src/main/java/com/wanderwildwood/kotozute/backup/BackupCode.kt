package com.wanderwildwood.kotozute.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.wanderwildwood.kotozute.lock.AppLock
import org.signal.argon2.Argon2
import org.signal.argon2.MemoryCost
import org.signal.argon2.Type
import org.signal.argon2.Version
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The backup code: 30 digits made once on this phone (chosen 2026-11-19: the
 * Signal-style code), shown for writing down, and used for every backup after.
 *
 * Kept on the phone so automatic backups can run with nobody there: sealed by its own
 * keystore key, like the app's other secrets, and under the passphrase lock's cover when the
 * lock is on ([AppLock]) -- so a locked phone gives up neither the messages nor the means to
 * read its backups.
 *
 * Restoring on a new phone: the code from the old phone is typed in once ([set]); it then
 * opens those backups and is used for this phone's backups too.
 */
object BackupCode {

    private const val PREFS = "backup_code"
    // Named in AppLock's covered list; change both together.
    const val PREF_SEALED = "sealed_code"
    private const val PREF_IV = "iv"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "kotozute_backup_code"

    /** Argon2id cost per backup file. Fixed, and written into each file's header. */
    private const val ITERATIONS = 3
    private const val MEMORY_MIB = 32

    class Needed : Exception("no backup code on this phone")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun exists(context: Context): Boolean = prefs(context).contains(PREF_SEALED)

    /** The code, or null when none is set up. @throws AppLock.Locked while locked. */
    fun get(context: Context): String? {
        val p = prefs(context)
        val sealed = AppLock.reveal(p.getString(PREF_SEALED, null)) ?: return null
        val iv = p.getString(PREF_IV, null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    /** Makes and keeps a new code, replacing any other. Returns it for showing once. */
    fun create(context: Context): String = BackupCrypto.newCode().also { set(context, it) }

    /** Keeps [code] (already normalized: 30 digits). */
    fun set(context: Context, code: String) {
        require(code.length == BackupCrypto.CODE_DIGITS && code.all(Char::isDigit))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val sealed = Base64.encodeToString(cipher.doFinal(code.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(
            prefs(context).edit()
                .putString(PREF_SEALED, AppLock.coverForWrite(context, sealed))
                .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
        ) { "could not keep the backup code" }
    }

    fun newParams(): BackupCrypto.Params = BackupCrypto.newParams(ITERATIONS, MEMORY_MIB)

    fun deriveKey(code: String, p: BackupCrypto.Params): ByteArray =
        Argon2.Builder(Version.V13)
            .type(Type.Argon2id)
            .memoryCost(MemoryCost.MiB(p.memoryMib))
            .parallelism(1)
            .iterations(p.iterations)
            .build()
            .hash(code.toByteArray(Charsets.UTF_8), p.salt)
            .hash

    /** The backup's bytes encrypted under this phone's code. @throws Needed when none is set up. */
    fun encrypt(context: Context, json: ByteArray): ByteArray {
        val code = get(context) ?: throw Needed()
        val p = newParams()
        return BackupCrypto.encrypt(deriveKey(code, p), p, json)
    }

    /**
     * The JSON inside [file]: as is for an older plain backup, decrypted for an encrypted one.
     * @throws Needed when it is encrypted and no code is set up; [BackupCrypto.WrongCode] when
     *   this phone's code does not open it.
     */
    fun open(context: Context, file: ByteArray): ByteArray {
        if (!BackupCrypto.isEncrypted(file)) return file
        val code = get(context) ?: throw Needed()
        return BackupCrypto.decrypt(deriveKey(code, BackupCrypto.params(file)), file)
    }

    private fun key(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "the keystore entry for the backup code is gone" }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
