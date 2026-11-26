package com.wanderwildwood.kotozute.signalstore

import org.signal.archive.proto.BackupInfo
import org.signal.archive.proto.Frame
import org.signal.core.models.ServiceId
import org.signal.core.models.backup.MessageBackupKey
import org.signal.core.util.writeVarInt32
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Writes a transfer archive the way upstream's `EncryptedBackupWriter.createForLocalOrLinking`
 * does: IV, AES-CBC over gzip, zeros after the gzip trailer as its padded stream adds, and
 * HMAC-SHA256 over IV and ciphertext at the end, keyed by `MessageBackupKey.deriveBackupSecrets`.
 */
internal object TransferArchiveFixture {

    fun write(
        file: File,
        key: ByteArray,
        aci: ServiceId.ACI,
        frames: List<Frame>,
        header: BackupInfo = BackupInfo(version = 1, backupTimeMs = 10_000)
    ): File {
        val material = MessageBackupKey(key).deriveBackupSecrets(aci, null)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(material.aesKey, "AES"), IvParameterSpec(iv))
        }
        val ciphertext = cipher.doFinal(plaintext(header, frames))
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(material.macKey, "HmacSHA256"))
            update(iv)
            update(ciphertext)
        }.doFinal()
        file.writeBytes(iv + ciphertext + mac)
        return file
    }

    private fun plaintext(header: BackupInfo, frames: List<Frame>): ByteArray {
        val raw = ByteArrayOutputStream()
        GZIPOutputStream(raw).use { gzip ->
            header.encode().let { gzip.writeVarInt32(it.size); gzip.write(it) }
            frames.forEach { frame -> frame.encode().let { gzip.writeVarInt32(it.size); gzip.write(it) } }
        }
        // PaddedGzipOutputStream's padding: zeros after the gzip trailer.
        raw.write(ByteArray(300))
        return raw.toByteArray()
    }
}
