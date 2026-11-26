package com.wanderwildwood.kotozute.signalstore

import org.signal.archive.proto.BackupInfo
import org.signal.archive.proto.Frame
import org.signal.core.models.ServiceId
import org.signal.core.models.backup.MessageBackupKey
import org.signal.core.util.readNBytesOrThrow
import org.signal.core.util.readVarInt32
import org.signal.core.util.skipNBytesOrThrow
import org.signal.core.util.stream.LimitedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads the archive a primary uploads for a newly linked device: the "Transfer message history"
 * option on Signal's own link screen.
 *
 * The format is Signal's backup format, and this follows upstream's reader for it
 * (`lib/archive/.../stream/EncryptedBackupReader.kt`, `createForLocalOrLinking`) step for step:
 *
 *  1. Keys: `MessageBackupKey(ephemeralKey).deriveBackupSecrets(aci, forwardSecrecyToken = null)`
 *     -- the key the primary put in the provisioning message, bound to the account's ACI.
 *  2. An optional forward-secrecy prefix (`SBACKUP` 0x01, a varint length, that many bytes) that
 *     only archive-CDN backups carry. A link archive has none; it is skipped if present, as
 *     upstream skips it.
 *  3. HMAC-SHA256 over everything after the prefix except the last 32 bytes, which are the MAC.
 *     Checked over the whole file **before** a byte is decrypted.
 *  4. A 16-byte IV, then AES-256-CBC with PKCS#5 padding, then gzip (with zero padding after
 *     the gzip trailer, which `GZIPInputStream` ignores).
 *  5. Varint-length-delimited protobufs: one `BackupInfo`, then `Frame`s until the end.
 *
 * Two departures, neither in the format. The MAC is computed in a streaming loop rather than by
 * reading the archive into one array, because a history has no fixed size and this runs on a
 * phone. And guava's counting stream is not used: nothing here reports bytes read.
 */
internal class TransferArchiveReader(
    private val macKey: ByteArray,
    private val aesKey: ByteArray,
    private val length: Long,
    private val open: () -> InputStream
) {

    /** The archive cannot be read with this key, or is not an archive. Not worth retrying. */
    class Unusable(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** The size of the forward-secrecy prefix, 0 when there is none (a link archive). */
    private val prefixLength: Long = open().use { prefixLengthOf(it) }

    /**
     * Throws [Unusable] unless the MAC matches. Upstream's `validateMac`, in the constructor
     * there; a method here so the caller can tell "wrong key or damaged" from a read failure
     * further in.
     */
    fun verify() {
        val encryptedLength = length - prefixLength
        if (encryptedLength < IV_SIZE + MAC_SIZE) throw Unusable("archive too short: $length bytes")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }
        val expected = openAfterPrefix().use { input ->
            val body = LimitedInputStream(input, encryptedLength - MAC_SIZE)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = body.read(buffer)
                if (read < 0) break
                mac.update(buffer, 0, read)
            }
            runCatching { input.readNBytesOrThrow(MAC_SIZE) }
                .getOrElse { throw Unusable("archive ends before its MAC", it) }
        }
        if (!MessageDigest.isEqual(mac.doFinal(), expected)) throw Unusable("archive MAC does not match")
    }

    /**
     * Decrypts and hands [consume] the header and the frames, streamed. Call [verify] first.
     *
     * A frame that will not decode is skipped, up to [MAX_READ_RETRIES] in a row, as upstream
     * does; past that the stream is taken to be damaged and this throws.
     */
    fun <T> read(consume: (BackupInfo?, Sequence<Frame>) -> T): T {
        val encryptedLength = length - prefixLength
        return openAfterPrefix().use { input ->
            val iv = input.readNBytesOrThrow(IV_SIZE)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            }
            val stream = GZIPInputStream(
                CipherInputStream(LimitedInputStream(input, encryptedLength - IV_SIZE - MAC_SIZE), cipher)
            )
            val header = readHeader(stream)
            consume(header, generateSequence { readFrame(stream) })
        }
    }

    private fun openAfterPrefix(): InputStream =
        open().also { if (prefixLength > 0) it.skipNBytesOrThrow(prefixLength) }

    private fun readHeader(stream: InputStream): BackupInfo? = try {
        val size = stream.readVarInt32().takeIf { it >= 0 }
        when {
            size == null -> null
            size > MAX_FRAME_LENGTH -> throw Unusable("header length $size exceeds the maximum")
            else -> BackupInfo.ADAPTER.decode(stream.readNBytesOrThrow(size))
        }
    } catch (e: EOFException) {
        null
    }

    private fun readFrame(stream: InputStream): Frame? {
        var attempts = 0
        while (attempts < MAX_READ_RETRIES) {
            val bytes = try {
                val size = stream.readVarInt32().takeIf { it >= 0 } ?: return null
                if (size > MAX_FRAME_LENGTH) {
                    stream.skipNBytesOrThrow(size.toLong())
                    continue
                }
                stream.readNBytesOrThrow(size)
            } catch (e: EOFException) {
                return null
            }
            try {
                return Frame.ADAPTER.decode(bytes)
            } catch (e: IOException) {
                attempts++
            }
        }
        throw IOException("archive: $MAX_READ_RETRIES frames in a row would not decode")
    }

    companion object {
        const val MAC_SIZE = 32
        const val IV_SIZE = 16

        /** Upstream's `MAX_FRAME_LENGTH`: anything larger is taken as corruption. */
        private const val MAX_FRAME_LENGTH = 25 * 1024 * 1024

        /** Upstream's `MAX_READ_RETRIES`. */
        private const val MAX_READ_RETRIES = 3

        private const val MAX_FORWARD_SECRECY_METADATA_SIZE = 16 * 1024

        /** `EncryptedBackupWriter.MAGIC_NUMBER`. */
        val MAGIC_NUMBER = "SBACKUP".toByteArray(Charsets.UTF_8) + 0x01

        /** The newest `BackupInfo.version` this reader understands: upstream's `BackupRepository.VERSION`. */
        const val VERSION = 1L

        /** A reader for [file], keyed as the primary keyed it for this account. */
        fun forLinking(ephemeralKey: ByteArray, aci: ServiceId.ACI, file: File): TransferArchiveReader {
            val material = MessageBackupKey(ephemeralKey).deriveBackupSecrets(aci, null)
            return TransferArchiveReader(material.macKey, material.aesKey, file.length()) { file.inputStream().buffered() }
        }

        /** Upstream's `getForwardSecrecyPrefixDataLength`. */
        private fun prefixLengthOf(stream: InputStream): Long {
            val magic = ByteArray(MAGIC_NUMBER.size)
            var filled = 0
            while (filled < magic.size) {
                val read = stream.read(magic, filled, magic.size - filled)
                if (read < 0) return 0
                filled += read
            }
            if (!MAGIC_NUMBER.contentEquals(magic)) return 0
            val metadataLength = stream.readVarInt32()
            if (metadataLength < 0 || metadataLength > MAX_FORWARD_SECRECY_METADATA_SIZE) {
                throw Unusable("forward secrecy metadata length $metadataLength")
            }
            return (MAGIC_NUMBER.size + varIntSize(metadataLength) + metadataLength).toLong()
        }

        private fun varIntSize(value: Int): Int {
            var size = 1
            var rest = value ushr 7
            while (rest != 0) {
                size++
                rest = rest ushr 7
            }
            return size
        }
    }
}
