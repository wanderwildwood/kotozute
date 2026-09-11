package com.wanderwildwood.kotozute.signalstore

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Locking a backup with a key the app makes, not one a person thinks up.
 *
 * Signal's own local backup works this way and so does this: thirty digits, generated here,
 * shown once, written down by whoever wants the backup. It is the difference between a
 * secret worth about a hundred bits and one worth twenty, and it is also why the derivation
 * below is a single HKDF pass rather than an argument about how many rounds of Argon2 are
 * enough. Nothing is guessing thirty random digits; the expensive KDFs exist to slow down
 * guessing at passphrases people can remember, which this deliberately is not.
 *
 * The file is written in chunks so it streams on a phone, and each chunk is sealed on its
 * own with the chunk's number and whether it is the last one bound into it. That is what
 * stops a truncated or reordered file from reading as a shorter, plausible backup.
 */
internal object SignalBackupCrypto {

    /** Digits in a key. Signal's own backup key is thirty; the same is plenty. */
    const val KEY_DIGITS = 30

    /** What a chunk holds before it is sealed. */
    const val CHUNK = 64 * 1024

    private const val KEY_BYTES = 32
    private const val TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val PREFIX_BYTES = 4
    private const val INFO = "kotozute backup v1"

    private val random = SecureRandom()

    /** A new key, as digits. Grouped for reading by [group]; stored and typed without spaces. */
    fun newKey(): String = buildString {
        repeat(KEY_DIGITS) { append(random.nextInt(10)) }
    }

    fun newSalt(): ByteArray = ByteArray(16).also(random::nextBytes)

    /** Only the digits matter: spaces and dashes are how it was written down, not part of it. */
    fun normalise(key: String): String = key.filter { it.isDigit() }

    /** Five groups of six, because thirty unbroken digits cannot be copied by hand. */
    fun group(key: String): String = normalise(key).chunked(6).joinToString(" ")

    fun isKey(key: String): Boolean = normalise(key).length == KEY_DIGITS

    /**
     * HKDF-SHA256, extract then expand, as RFC 5869 defines it. Written out rather than
     * taken from a library so the backup's derivation does not move when a library does.
     */
    fun derive(key: String, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(normalise(key).toByteArray())

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(INFO.toByteArray())
        mac.update(1)
        return mac.doFinal().copyOf(KEY_BYTES)
    }

    private fun nonce(prefix: ByteArray, counter: Long): ByteArray {
        val out = ByteArray(NONCE_BYTES)
        prefix.copyInto(out, 0, 0, PREFIX_BYTES)
        for (index in 0 until 8) {
            out[PREFIX_BYTES + index] = (counter ushr (8 * (7 - index))).toByte()
        }
        return out
    }

    /** The chunk's number and whether it ends the file, sealed in with the bytes. */
    private fun aad(counter: Long, last: Boolean): ByteArray =
        ByteArray(9).also { out ->
            for (index in 0 until 8) out[index] = (counter ushr (8 * (7 - index))).toByte()
            out[8] = if (last) 1 else 0
        }

    /**
     * Wraps [out] so that everything written to it is sealed. Closing writes the final chunk,
     * which is the only thing that marks the file complete -- so a stream that is never
     * closed produces a file that will not read back, rather than one that reads back short.
     */
    fun encrypt(out: OutputStream, key: ByteArray): OutputStream = object : OutputStream() {
        private val prefix = ByteArray(PREFIX_BYTES).also(random::nextBytes)
        private val buffer = ByteArray(CHUNK)
        private var held = 0
        private var counter = 0L
        private var closed = false

        init {
            out.write(prefix)
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var left = len
            while (left > 0) {
                val take = minOf(left, CHUNK - held)
                b.copyInto(buffer, held, offset, offset + take)
                held += take
                offset += take
                left -= take
                if (held == CHUNK) seal(last = false)
            }
        }

        private fun seal(last: Boolean) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce(prefix, counter))
            )
            cipher.updateAAD(aad(counter, last))
            val sealed = cipher.doFinal(buffer, 0, held)
            out.write(sealed.size ushr 24)
            out.write(sealed.size ushr 16)
            out.write(sealed.size ushr 8)
            out.write(sealed.size)
            out.write(sealed)
            held = 0
            counter++
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            seal(last = true)
            out.flush()
            out.close()
        }
    }

    /** The other half. Throws rather than returning what it could not authenticate. */
    fun decrypt(input: InputStream, key: ByteArray): InputStream = object : InputStream() {
        private val prefix = ByteArray(PREFIX_BYTES).also { input.readFully(it) }
        private var plain = ByteArray(0)
        private var at = 0
        private var counter = 0L
        private var ended = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at == plain.size && !fill()) return -1
            val take = minOf(len, plain.size - at)
            plain.copyInto(b, off, at, at + take)
            at += take
            return take
        }

        /** True while there is another chunk. The last chunk is the one that says it is. */
        private fun fill(): Boolean {
            if (ended) return false
            val header = ByteArray(4)
            input.readFully(header)
            val size = (header[0].toInt() and 0xff shl 24) or
                    (header[1].toInt() and 0xff shl 16) or
                    (header[2].toInt() and 0xff shl 8) or
                    (header[3].toInt() and 0xff)
            val sealed = ByteArray(size)
            input.readFully(sealed)

            // Which of the two this is decides what was sealed in with it, so a chunk cannot
            // be moved, repeated, or dropped without the tag failing.
            plain = open(sealed, last = false) ?: open(sealed, last = true)?.also { ended = true }
                ?: throw javax.crypto.AEADBadTagException("backup chunk $counter did not open")
            at = 0
            counter++
            return true
        }

        private fun open(sealed: ByteArray, last: Boolean): ByteArray? = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce(prefix, counter))
            )
            cipher.updateAAD(aad(counter, last))
            cipher.doFinal(sealed)
        } catch (e: Exception) {
            null
        }
    }

    private fun InputStream.readFully(into: ByteArray) {
        var read = 0
        while (read < into.size) {
            val n = read(into, read, into.size - read)
            if (n < 0) throw EOFException("backup ended early")
            read += n
        }
    }
}
