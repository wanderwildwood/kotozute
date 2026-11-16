package com.wanderwildwood.kotozute.signalstore

import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Where attachment files are, encrypted, and the one way to read them.
 *
 * Two directories and no other record. [sealedDir] holds files in [AttachmentCipher]'s format;
 * [legacyDir] holds what older builds wrote, in the clear. An id is a file name in one or the
 * other, so where a file is says what it is, and a move from one to the other is finished or
 * not started -- there is no state to keep in step with the disk, and nothing to resume from
 * but the disk itself.
 *
 * Nothing new is ever written to [legacyDir]. [migrateAll] empties it, a file at a time.
 *
 * No Android in here, so the whole of it runs in a unit test against a temporary folder.
 *
 * @param readSecret the attachment secret, or null when it cannot be had. Must never create one.
 * @param writeSecret the attachment secret for writing, created if `mayCreate` allows; throws
 *   when it cannot be had. See [AttachmentSecret].
 * @param checkpoint called after each step of a move. For tests, which stop a move there.
 */
internal class AttachmentVault(
    private val sealedDir: File,
    private val legacyDir: File,
    private val readSecret: () -> ByteArray?,
    private val writeSecret: (mayCreate: () -> Boolean) -> ByteArray,
    private val checkpoint: (Step) -> Unit = {}
) {

    /** The steps of moving one file, in order. */
    enum class Step { ENCRYPTED, VERIFIED, SWAPPED, PLAINTEXT_REMOVED }

    enum class Moved {
        /** Encrypted, read back, and the plaintext removed. */
        DONE,
        /** Nothing to do: the plaintext was not there, or went while this was working. */
        GONE,
        /** Encrypted and read back, but the plaintext would not delete. Tried again next time. */
        PLAINTEXT_KEPT,
        /** An encrypted file of this name exists and is not this plaintext. Both are left. */
        CONFLICT
    }

    data class Summary(val done: Int, val left: Int, val stoppedEarly: Boolean)

    fun has(id: String): Boolean = sealed(id).isFile || legacy(id).isFile

    /**
     * The bytes of an attachment in whichever form it is in, or null.
     *
     * Null also when the file is encrypted and the secret cannot be had: it is not readable,
     * and it is not deleted.
     */
    fun read(id: String): ByteArray? {
        readSealed(id)?.let { return it }
        val plain = legacy(id)
        runCatching { if (plain.isFile) plain.readBytes() else null }.getOrNull()?.let { return it }
        // Asked once more: a move finishing between the two looks above takes the file out of
        // the second place after this had already found it missing from the first.
        return readSealed(id)
    }

    private fun readSealed(id: String): ByteArray? {
        val file = sealed(id)
        if (!file.isFile) return null
        val secret = readSecret() ?: run {
            Timber.w("signal attachment: an encrypted attachment cannot be read; its secret is unavailable")
            return null
        }
        return try {
            AttachmentCipher.decryptingStream(secret, file).use { it.readBytes() }
        } catch (e: IOException) {
            Timber.w(e, "signal attachment: an encrypted attachment would not read")
            null
        }
    }

    /**
     * Writes an attachment, encrypted. It appears under its name whole or not at all: written
     * beside it under another name, synced, then renamed.
     *
     * @throws IllegalStateException when the secret cannot be had. Nothing is written.
     * @throws IOException when the bytes could not be written.
     */
    fun write(id: String, source: InputStream) {
        val secret = secretForWriting()
        sealedDir.mkdirs()
        val temp = File.createTempFile("seal", null, sealedDir)
        try {
            encryptTo(secret, source, temp)
            if (!temp.renameTo(sealed(id))) throw IOException("an attachment could not be put in place")
        } finally {
            temp.delete()
        }
    }

    /** Removes an attachment in every form it is in. */
    fun forget(id: String): Boolean = synchronized(SWAP) {
        val a = sealed(id).let { it.isFile && it.delete() }
        val b = legacy(id).let { it.isFile && it.delete() }
        a || b
    }

    /** Every file in either directory, for the abandoned-file sweep. */
    fun files(): Array<File> =
        (sealedDir.listFiles().orEmpty().asList() + legacyDir.listFiles().orEmpty().asList())
            .filter { it.isFile }
            .toTypedArray()

    /** How many attachments are still in the clear. */
    fun plaintextCount(): Int = legacyDir.listFiles()?.count { it.isFile } ?: 0

    /**
     * Encrypts every attachment an older build left in the clear.
     *
     * One file at a time, so it needs room for one more copy of the largest of them and no
     * more. Safe to stop anywhere and to run again: see [migrateOne].
     *
     * Stops at the first file if the secret cannot be had -- every other would fail the same
     * way -- and leaves everything as it was.
     */
    fun migrateAll(shouldStop: () -> Boolean = { false }): Summary {
        val names = legacyDir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
        var done = 0
        var stopped = false
        for (name in names) {
            if (shouldStop()) { stopped = true; break }
            try {
                if (migrateOne(name) == Moved.DONE) done++
            } catch (e: IllegalStateException) {
                Timber.w(e, "signal attachment: not encrypting stored attachments; the secret is unavailable")
                stopped = true
                break
            } catch (e: Exception) {
                // No room, most likely. The plaintext is untouched and is tried again next time.
                Timber.w(e, "signal attachment: one stored attachment could not be encrypted yet")
            }
        }
        val left = plaintextCount()
        if (left == 0 && legacyDir.isDirectory && legacyDir.list().isNullOrEmpty()) legacyDir.delete()
        return Summary(done, left, stopped)
    }

    /**
     * Moves one attachment from the clear into the encrypted form.
     *
     * 1. Encrypt it to a file of another name, hashing the plaintext on the way past.
     * 2. Decrypt that file from the disk and compare: same length, same hash. If not, stop;
     *    the plaintext has not been touched.
     * 3. Rename it into place. From here a reader finds the encrypted one first.
     * 4. Delete the plaintext.
     *
     * The plaintext is the last thing to go and only after its replacement was read back, so
     * there is no moment at which the only copy is a file nobody has checked. Stopped after
     * 1 or 2, an unnamed file is left for the sweep and the move starts over. Stopped after 3,
     * both exist; the next run compares them and finishes with step 4.
     *
     * @throws IllegalStateException when the secret cannot be had.
     */
    fun migrateOne(id: String): Moved {
        val plain = legacy(id)
        if (!plain.isFile) return Moved.GONE
        val dest = sealed(id)
        val secret = secretForWriting()

        if (dest.isFile) {
            val same = plain.inputStream().use { sha256(it) }
                .contentEquals(AttachmentCipher.decryptingStream(secret, dest).use { sha256(it) })
            if (!same) {
                Timber.w("signal attachment: a stored attachment exists in two forms that differ; leaving both")
                return Moved.CONFLICT
            }
            return removePlaintext(plain)
        }

        sealedDir.mkdirs()
        val temp = File.createTempFile("seal", null, sealedDir)
        try {
            val length = plain.length()
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(plain.inputStream(), digest).use { encryptTo(secret, it, temp) }
            val expected = digest.digest()
            checkpoint(Step.ENCRYPTED)

            if (AttachmentCipher.plaintextLength(temp.length()) != length ||
                !AttachmentCipher.decryptingStream(secret, temp).use { sha256(it) }.contentEquals(expected)
            ) {
                throw IOException("an encrypted attachment did not read back as it was written")
            }
            checkpoint(Step.VERIFIED)

            synchronized(SWAP) {
                // Deleted meanwhile -- a disappearing message's timer, say. Putting the
                // encrypted copy in place now would bring back a file that was meant to go.
                if (!plain.isFile) {
                    temp.delete()
                    return Moved.GONE
                }
                if (!temp.renameTo(dest)) throw IOException("an encrypted attachment could not be put in place")
            }
            checkpoint(Step.SWAPPED)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        return removePlaintext(plain)
    }

    private fun removePlaintext(plain: File): Moved =
        if (plain.delete() || !plain.exists()) {
            checkpoint(Step.PLAINTEXT_REMOVED)
            Moved.DONE
        } else {
            Timber.w("signal attachment: an encrypted attachment's plaintext would not delete")
            Moved.PLAINTEXT_KEPT
        }

    /**
     * A new secret may be started only while nothing is encrypted. See [AttachmentSecret]: one
     * made beside files written under a lost one would turn them to noise without an error.
     */
    private fun secretForWriting(): ByteArray =
        writeSecret { sealedDir.listFiles().isNullOrEmpty() }

    private fun encryptTo(secret: ByteArray, source: InputStream, file: File) {
        FileOutputStream(file).use { out ->
            // Closing the cipher stream is what writes its last block; the file has to stay
            // open past that to be synced, so the close stops short of it.
            val keepOpen = object : FilterOutputStream(out) {
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() = flush()
            }
            AttachmentCipher.encryptingStream(secret, keepOpen).use { source.copyTo(it) }
            out.fd.sync()
        }
    }

    private fun sha256(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest()
    }

    private fun sealed(id: String) = File(sealedDir, id)
    private fun legacy(id: String) = File(legacyDir, id)

    private companion object {
        /**
         * Held for the moment a file changes form, and by [forget]. One for the process: this
         * class is made afresh for each call and they must still exclude each other.
         */
        val SWAP = Any()
    }
}
