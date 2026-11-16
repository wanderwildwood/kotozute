/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.wanderwildwood.kotozute.signalstore

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * How an attachment is written to and read from this phone's storage.
 *
 * Signal Android's `ModernEncryptingPartOutputStream` and `ModernDecryptingPartInputStream`
 * (core/util, `org.signal.core.util.crypto`), carried over into Kotlin in one file. The format
 * is theirs and is not changed: 32 random bytes, a per-file key that is
 * HMAC-SHA256(attachment secret, those bytes), and AES in counter mode from a zero counter.
 *
 * Upstream keeps the 32 bytes either in its attachment table (`data_random`) or at the head of
 * the file (`inline = true`). There is no attachment table here -- a message names its files
 * by id and nothing else -- so this is always the inline form.
 *
 * As upstream says of it: "The on-disk format is very simple, and intentionally no longer
 * includes authentication." A wrong secret or a damaged file therefore reads as wrong bytes
 * rather than as an error; what stands behind the secret being the right one is that it is
 * itself sealed with an authenticated cipher. See [AttachmentSecret].
 */
internal object AttachmentCipher {

    const val PREMATURE_END_ERROR_MESSAGE = "Prematurely reached end of stream!"

    /** The random bytes at the head of every file. */
    const val RANDOM_BYTES = 32

    /** Upstream's `ModernEncryptingPartOutputStream.createFor(secret, file, inline = true)`. */
    @Throws(IOException::class)
    fun encryptingStream(secret: ByteArray, file: File): OutputStream =
        encryptingStream(secret, FileOutputStream(file))

    /** The same over a stream somebody else opened, so the caller can keep hold of the file to sync it. */
    @Throws(IOException::class)
    fun encryptingStream(secret: ByteArray, fileOutputStream: OutputStream): OutputStream {
        val random = ByteArray(RANDOM_BYTES)
        SecureRandom().nextBytes(random)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))

        val iv = ByteArray(16)
        val key = mac.doFinal(random)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

        fileOutputStream.write(random)

        return CipherOutputStream(fileOutputStream, cipher)
    }

    /** Upstream's `ModernEncryptingPartOutputStream.getPlaintextLength`. */
    fun plaintextLength(cipherTextLength: Long): Long = cipherTextLength - RANDOM_BYTES

    /**
     * Upstream's `ModernDecryptingPartInputStream.createFor(secret, file, offset)`: the
     * plaintext from [offset] on, without decrypting what comes before it.
     */
    @Throws(IOException::class)
    fun decryptingStream(secret: ByteArray, file: File, offset: Long = 0): InputStream {
        val inputStream = FileInputStream(file)
        try {
            val random = ByteArray(RANDOM_BYTES)
            readFully(inputStream, random)
            return decryptingStream(secret, random, inputStream, offset)
        } catch (t: Throwable) {
            // Upstream leaves the descriptor to the garbage collector here; closed instead.
            runCatching { inputStream.close() }
            throw t
        }
    }

    @Throws(IOException::class)
    private fun decryptingStream(
        secret: ByteArray,
        random: ByteArray,
        inputStream: InputStream,
        offset: Long
    ): InputStream {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))

        val iv = ByteArray(16)
        val remainder = (offset % 16).toInt()
        longTo4ByteArray(iv, 12, offset / 16)

        val key = mac.doFinal(random)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

        val skipped = inputStream.skip(offset - remainder)

        if (skipped != offset - remainder) {
            throw IOException("Skip failed: " + skipped + " vs " + (offset - remainder))
        }

        val cipherInputStream = CipherInputStream(inputStream, cipher)
        val remainderBuffer = ByteArray(remainder)

        readFully(cipherInputStream, remainderBuffer)

        return cipherInputStream
    }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, buffer: ByteArray) {
        if (buffer.isEmpty()) return
        var offset = 0

        while (true) {
            val read = input.read(buffer, offset, buffer.size - offset)

            if (read == -1) throw IOException(PREMATURE_END_ERROR_MESSAGE)
            else if (read + offset < buffer.size) offset += read
            else return
        }
    }

    /** `org.signal.core.util.Conversions.longTo4ByteArray`. */
    private fun longTo4ByteArray(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset + 3] = value.toByte()
        bytes[offset + 2] = (value shr 8).toByte()
        bytes[offset + 1] = (value shr 16).toByte()
        bytes[offset] = (value shr 24).toByte()
    }
}
