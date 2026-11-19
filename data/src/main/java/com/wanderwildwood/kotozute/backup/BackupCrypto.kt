package com.wanderwildwood.kotozute.backup

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted backup file, plain JVM so it is tested off the phone.
 *
 * ```
 * "KZBK" | version 1 | salt (16) | argon2 iterations (int) | argon2 memory MiB (int) | iv (12) | AES-256-GCM(json) + tag
 * ```
 *
 * The header up to the iv is the GCM associated data, so a file whose parameters were edited
 * fails to open rather than opening under weaker ones. The key is Argon2id over the backup
 * code with this file's own salt ([BackupCode.deriveKey]); the code never appears in a file.
 *
 * A file without the magic is an older plain JSON backup and is read as one.
 */
object BackupCrypto {

    private val MAGIC = byteArrayOf('K'.code.toByte(), 'Z'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val HEADER_BYTES = 4 + 1 + SALT_BYTES + 4 + 4

    data class Params(val salt: ByteArray, val iterations: Int, val memoryMib: Int)

    /** The code did not open this file: wrong code, or the file was changed. */
    class WrongCode : Exception("the backup code does not open this backup")

    private val random = SecureRandom()

    fun newParams(iterations: Int, memoryMib: Int) =
        Params(ByteArray(SALT_BYTES).also(random::nextBytes), iterations, memoryMib)

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun params(file: ByteArray): Params {
        require(isEncrypted(file) && file.size > HEADER_BYTES + IV_BYTES) { "not an encrypted backup" }
        require(file[4] == VERSION) { "an encrypted backup from a newer version of the app" }
        val b = ByteBuffer.wrap(file, 5, HEADER_BYTES - 5)
        val salt = ByteArray(SALT_BYTES).also { b.get(it) }
        return Params(salt, b.int, b.int)
    }

    private fun header(p: Params): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(VERSION).put(p.salt)
            .putInt(p.iterations).putInt(p.memoryMib).array()

    fun encrypt(key: ByteArray, p: Params, plain: ByteArray): ByteArray {
        val head = header(p)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(head)
        return head + iv + cipher.doFinal(plain)
    }

    /** @throws WrongCode when [key] does not open [file]. */
    fun decrypt(key: ByteArray, file: ByteArray): ByteArray {
        params(file) // validates the header
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, file, HEADER_BYTES, IV_BYTES)
        )
        cipher.updateAAD(file, 0, HEADER_BYTES)
        return try {
            cipher.doFinal(file, HEADER_BYTES + IV_BYTES, file.size - HEADER_BYTES - IV_BYTES)
        } catch (e: AEADBadTagException) {
            throw WrongCode()
        }
    }

    // ---- the code itself ----------------------------------------------------------------

    const val CODE_DIGITS = 30

    /** 30 random digits, as Signal's backup passphrase. About 99.6 bits. */
    fun newCode(): String {
        val sb = StringBuilder(CODE_DIGITS)
        repeat(CODE_DIGITS) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    /** Digits only, or null when it is not a whole code. Spaces and dashes as written down are fine. */
    fun normalize(typed: String): String? =
        typed.filter(Char::isDigit).takeIf { it.length == CODE_DIGITS && typed.all { c -> c.isDigit() || c == ' ' || c == '-' } }

    /** Six groups of five, for reading aloud and writing down. */
    fun grouped(code: String): String = code.chunked(5).joinToString(" ")
}
