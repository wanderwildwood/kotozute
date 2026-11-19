package com.wanderwildwood.kotozute.lock

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The plain-JVM half of the passphrase lock: AES-GCM wrapping, and the outer layer put on a
 * sealed secret while the lock is on. No Android, no keystore, so it is tested off the phone.
 *
 * A covered value is `L1:` + base64(iv || ciphertext+tag). Anything without the prefix is a
 * value the lock never touched and passes through unchanged -- which is what lets a phone
 * that dies half-way through turning the lock on or off still open everything: each value
 * says for itself which form it is in.
 */
object LockCrypto {

    const val PREFIX = "L1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    const val KEY_BYTES = 32

    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)
    fun newSalt(): ByteArray = ByteArray(16).also(random::nextBytes)

    /** iv || ciphertext+tag. */
    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    /** The plaintext, or null when the key is wrong or the bytes were altered. */
    fun open(key: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < IV_BYTES + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES)
            )
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (e: AEADBadTagException) {
            null
        }
    }

    fun isCovered(stored: String?): Boolean = stored?.startsWith(PREFIX) == true

    fun cover(key: ByteArray, plain: String, b64: Base64Codec): String =
        PREFIX + b64.encode(seal(key, plain.toByteArray(Charsets.UTF_8)))

    /**
     * The value under the cover. Null only if [stored] is covered and will not open under
     * [key] -- a different lock key, or a damaged value. Never guesses.
     */
    fun uncover(key: ByteArray, stored: String, b64: Base64Codec): String? {
        if (!isCovered(stored)) return stored
        val bytes = runCatching { b64.decode(stored.removePrefix(PREFIX)) }.getOrNull() ?: return null
        return open(key, bytes)?.toString(Charsets.UTF_8)
    }

    /** Base64 without tying this file to android.util (tests run on the JVM). */
    interface Base64Codec {
        fun encode(bytes: ByteArray): String
        fun decode(text: String): ByteArray
    }

    object JavaBase64 : Base64Codec {
        override fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
        override fun decode(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
    }
}
