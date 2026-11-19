package com.wanderwildwood.kotozute.lock

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.signal.argon2.Argon2
import org.signal.argon2.MemoryCost
import org.signal.argon2.Type
import org.signal.argon2.Version
import timber.log.Timber
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The optional passphrase lock (Molly's "data encryption at rest", chosen by David
 * 2026-11-19 as a setting, OFF by default).
 *
 * ## What it locks
 *
 * The app keeps three secrets, each sealed by its own keystore key with no user
 * authentication: the message database key ([RealmEncryption] in presentation), the Signal
 * protocol store key ([com.wanderwildwood.kotozute.signalstore.ProtocolStoreKey]) and the
 * attachment secret ([com.wanderwildwood.kotozute.signalstore.AttachmentSecret]). While the
 * lock is on, each stored sealed value gets one more layer ([LockCrypto.cover]) under a random
 * **lock key**, and the lock key itself is kept only wrapped under the passphrase. The
 * keystore seal underneath is untouched, so a lifted file still needs this phone's keystore
 * too.
 *
 * ## The passphrase key
 *
 * Molly's `PassphraseBasedKdf`: Argon2id over the passphrase, then HMAC-SHA256 with a key
 * that lives in the keystore and never leaves it. The second step is the point: a copy of the
 * files cannot be guessed against off the phone, every guess has to go through this
 * device's keystore, and each one costs a full Argon2 run (sized to about a second here).
 *
 * ## Locked
 *
 * The lock key is only ever in this process's memory. Locking ends the process (see the
 * presentation-side `LockController`), so "locked" is simply "the lock is on and this process
 * has not been given the passphrase". Nothing in here writes the lock key or the passphrase
 * anywhere.
 *
 * ⚠ Forgetting the passphrase loses everything it covers. There is no recovery by design:
 * any recovery path would be a way in for someone else.
 */
object AppLock {

    /** Asked for a covered secret while locked. Never caught and papered over as "no key". */
    class Locked : IllegalStateException("Messaging is locked")

    private const val PREFS = "app_lock"
    private const val PREF_ON = "on"
    private const val PREF_SALT = "salt"
    private const val PREF_ITERATIONS = "iterations"
    private const val PREF_MEMORY_MIB = "memory_mib"
    private const val PREF_WRAPPED = "wrapped_key"
    private const val PREF_AFTER_MINUTES = "after_minutes"
    private const val PREF_WIPE = "wipe_memory"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val HMAC_ALIAS = "kotozute_lock_hmac"

    private const val MEMORY_MIB = 32
    private const val MIN_ITERATIONS = 2
    private const val MAX_ITERATIONS = 20
    private const val TARGET_MS = 1_000L

    /** Lock as soon as the screen goes off. Stored in [PREF_AFTER_MINUTES]. */
    const val ON_SCREEN_OFF = 0
    /** Never on a timer; only "Lock now" or a restart. */
    const val MANUAL_ONLY = -1
    const val DEFAULT_AFTER_MINUTES = 5

    /**
     * Every sealed secret the lock covers: the preferences file and the key prefix. A prefix,
     * not one key, because [RealmEncryption] keeps set-aside copies under suffixed names, and
     * a copy left uncovered would be the one way round the lock.
     */
    private val COVERED = listOf(
        "realm_encryption" to "sealed_key",
        "signal_protocol_store" to "sealed_key",
        "signal_attachment_secret" to "sealed_secret",
    )

    @Volatile private var lockKey: ByteArray? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(PREF_ON, false)

    /** On, and this process has not been given the passphrase. */
    fun isLocked(context: Context): Boolean = isOn(context) && lockKey == null

    var Context.lockAfterMinutes: Int
        get() = prefs(this).getInt(PREF_AFTER_MINUTES, DEFAULT_AFTER_MINUTES)
        set(value) { prefs(this).edit().putInt(PREF_AFTER_MINUTES, value).apply() }

    var Context.wipeMemoryOnLock: Boolean
        get() = prefs(this).getBoolean(PREF_WIPE, true)
        set(value) { prefs(this).edit().putBoolean(PREF_WIPE, value).apply() }

    // ---- read and write paths of the three secrets -------------------------------------

    /**
     * What a secret's read path calls on the stored sealed value before unsealing it.
     * Uncovered values (lock off, or a value from before the lock) come back unchanged.
     *
     * @throws Locked when the value is covered and the passphrase has not been given.
     */
    fun reveal(stored: String?): String? {
        if (stored == null || !LockCrypto.isCovered(stored)) return stored
        val key = lockKey ?: throw Locked()
        return LockCrypto.uncover(key, stored, LockCrypto.JavaBase64)
            ?: throw IllegalStateException("a covered secret would not open under the lock key")
    }

    /**
     * What a secret's write path calls on a freshly sealed value before storing it, so a
     * secret first made while the lock is on is covered like the others.
     *
     * @throws Locked if the lock is on and the passphrase has not been given -- a new secret
     *   must not be written in a form the lock does not cover.
     */
    fun coverForWrite(context: Context, sealed: String): String {
        if (!isOn(context)) return sealed
        val key = lockKey ?: throw Locked()
        return LockCrypto.cover(key, sealed, LockCrypto.JavaBase64)
    }

    // ---- passphrase ------------------------------------------------------------------

    /** True when [passphrase] opens the lock; this process then holds the lock key. */
    @Synchronized
    fun unlock(context: Context, passphrase: CharArray): Boolean {
        if (!isOn(context)) return true
        val key = unwrap(context, passphrase) ?: return false
        lockKey = key
        return true
    }

    /**
     * Turns the lock on with [passphrase]. Must be called with the app running normally
     * (lock off). Covers every sealed secret before returning; each value is written with
     * commit(), so a process killed half-way leaves a mix that [reveal] still opens.
     */
    @Synchronized
    fun turnOn(context: Context, passphrase: CharArray) {
        check(!isOn(context)) { "the lock is already on" }
        val key = LockCrypto.newKey()
        val salt = LockCrypto.newSalt()
        val iterations = benchmark(passphrase, salt)
        val wrapped = LockCrypto.seal(passphraseKey(context, passphrase, salt, iterations, MEMORY_MIB, create = true), key)
        // The wrapped key first, and on: from here a covered value can be opened.
        check(
            prefs(context).edit()
                .putString(PREF_SALT, LockCrypto.JavaBase64.encode(salt))
                .putInt(PREF_ITERATIONS, iterations)
                .putInt(PREF_MEMORY_MIB, MEMORY_MIB)
                .putString(PREF_WRAPPED, LockCrypto.JavaBase64.encode(wrapped))
                .putBoolean(PREF_ON, true)
                .commit()
        ) { "could not save the lock" }
        lockKey = key
        rewriteCovered(context) { LockCrypto.cover(key, it, LockCrypto.JavaBase64) }
        Timber.i("lock: turned on (argon2id %d MiB x %d)", MEMORY_MIB, iterations)
    }

    /** Turns the lock off if [passphrase] is right. Uncovers every secret first. */
    @Synchronized
    fun turnOff(context: Context, passphrase: CharArray): Boolean {
        if (!isOn(context)) return true
        val key = unwrap(context, passphrase) ?: return false
        lockKey = key
        rewriteCovered(context) { LockCrypto.uncover(key, it, LockCrypto.JavaBase64) ?: it }
        check(prefs(context).edit().putBoolean(PREF_ON, false).remove(PREF_WRAPPED).remove(PREF_SALT).commit())
        lockKey = null
        runCatching { keystore().deleteEntry(HMAC_ALIAS) }
        Timber.i("lock: turned off")
        return true
    }

    /** New passphrase, same lock key: nothing covered needs rewriting. */
    @Synchronized
    fun change(context: Context, old: CharArray, new: CharArray): Boolean {
        val key = unwrap(context, old) ?: return false
        val salt = LockCrypto.newSalt()
        val iterations = benchmark(new, salt)
        val wrapped = LockCrypto.seal(passphraseKey(context, new, salt, iterations, MEMORY_MIB, create = false), key)
        check(
            prefs(context).edit()
                .putString(PREF_SALT, LockCrypto.JavaBase64.encode(salt))
                .putInt(PREF_ITERATIONS, iterations)
                .putInt(PREF_MEMORY_MIB, MEMORY_MIB)
                .putString(PREF_WRAPPED, LockCrypto.JavaBase64.encode(wrapped))
                .commit()
        )
        lockKey = key
        return true
    }

    /** Overwrites the lock key in memory. The process ends right after; this is belt and braces. */
    fun forget() {
        lockKey?.let { Arrays.fill(it, 0) }
        lockKey = null
    }

    // ---- internals -------------------------------------------------------------------

    private fun unwrap(context: Context, passphrase: CharArray): ByteArray? {
        val p = prefs(context)
        val salt = p.getString(PREF_SALT, null)?.let(LockCrypto.JavaBase64::decode) ?: return null
        val wrapped = p.getString(PREF_WRAPPED, null)?.let(LockCrypto.JavaBase64::decode) ?: return null
        val pk = passphraseKey(
            context, passphrase, salt,
            p.getInt(PREF_ITERATIONS, MIN_ITERATIONS), p.getInt(PREF_MEMORY_MIB, MEMORY_MIB), create = false
        )
        return LockCrypto.open(pk, wrapped).also { Arrays.fill(pk, 0) }
    }

    private fun rewriteCovered(context: Context, transform: (String) -> String) {
        for ((file, prefix) in COVERED) {
            val p = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)
            val edit = p.edit()
            var changed = false
            for ((k, v) in p.all) {
                if (k.startsWith(prefix) && v is String) {
                    val next = transform(v)
                    if (next != v) { edit.putString(k, next); changed = true }
                }
            }
            if (changed) check(edit.commit()) { "could not rewrite $file" }
        }
    }

    private fun argon(passphrase: CharArray, salt: ByteArray, iterations: Int, memoryMib: Int): ByteArray {
        val bytes = String(passphrase).toByteArray(Charsets.UTF_8)
        try {
            return Argon2.Builder(Version.V13)
                .type(Type.Argon2id)
                .memoryCost(MemoryCost.MiB(memoryMib))
                .parallelism(1)
                .iterations(iterations)
                .build()
                .hash(bytes, salt)
                .hash
        } finally {
            Arrays.fill(bytes, 0)
        }
    }

    private fun passphraseKey(
        context: Context, passphrase: CharArray, salt: ByteArray, iterations: Int, memoryMib: Int, create: Boolean
    ): ByteArray {
        val stretched = argon(passphrase, salt, iterations, memoryMib)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(hmacKey(create))
            return mac.doFinal(stretched)
        } finally {
            Arrays.fill(stretched, 0)
        }
    }

    /** Iterations so one unlock costs about [TARGET_MS] on this phone. */
    private fun benchmark(passphrase: CharArray, salt: ByteArray): Int {
        val start = SystemClock.elapsedRealtime()
        argon(passphrase, salt, MIN_ITERATIONS, MEMORY_MIB)
        val per = ((SystemClock.elapsedRealtime() - start) / MIN_ITERATIONS).coerceAtLeast(1)
        return (TARGET_MS / per).toInt().coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /**
     * ⚠ Created only when the lock is turned on. Missing at unlock means the passphrase can
     * never be checked again; making a new one would not help, so it is reported, not filled.
     */
    private fun hmacKey(create: Boolean): SecretKey {
        (keystore().getKey(HMAC_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "the keystore entry for the passphrase lock is gone" }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
        gen.init(KeyGenParameterSpec.Builder(HMAC_ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return gen.generateKey()
    }
}
