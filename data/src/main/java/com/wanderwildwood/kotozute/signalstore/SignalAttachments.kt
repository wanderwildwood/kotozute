package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import org.signal.libsignal.protocol.InvalidMessageException
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil
import timber.log.Timber
import java.io.File
import java.io.InputStream

/**
 * Downloads attachments and keeps them on disk.
 *
 * The bridge used to fetch these on another machine and hand the phone an id to ask for. This
 * does the same job locally, and keeps the same shape -- a message records an id, and the
 * repository turns an id into bytes -- so the screens above are unchanged.
 *
 * Attachments are downloaded **at receive time**, not lazily when a bubble is drawn. An
 * attachment lives on Signal's CDN for a limited window and then stops existing; fetching it
 * on demand means the ones worth keeping are exactly the ones that fail.
 */
internal class SignalAttachments(
    context: Context,
    private val receiver: () -> SignalServiceMessageReceiver
) {

    private val dir = File(context.filesDir, "signal-attachments").apply { mkdirs() }

    /**
     * Fetches one attachment and returns the id to record, or null if it could not be had.
     *
     * Null rather than throwing, deliberately: a message whose picture failed to download is
     * still a message, and losing the text because the image was unavailable would be the
     * wrong trade.
     */
    fun download(pointer: AttachmentPointer): String? = try {
        val servicePointer = AttachmentPointerUtil.createSignalAttachmentPointer(pointer)
        val digest = servicePointer.digest.orElse(null)
            // Without a digest there is nothing to check the bytes against, and the library
            // refuses the download rather than accepting whatever the CDN returns. Correct:
            // the digest is what makes an attachment the sender's and not the server's.
            ?: throw InvalidMessageException("attachment has no digest")

        val id = idFor(servicePointer.remoteId.toString())
        val destination = File(dir, id)
        if (destination.exists()) {
            id
        } else {
            // Downloads to a temporary file, decrypts on the way out. The library needs a
            // seekable destination for the ciphertext, so this cannot stream straight to its
            // final home.
            val temp = File.createTempFile("att", null, dir)
            try {
                receiver().retrieveAttachment(
                    servicePointer,
                    temp,
                    MAX_ATTACHMENT_SIZE,
                    AttachmentCipherInputStream.IntegrityCheck.forEncryptedDigest(digest)
                ).use { plaintext -> destination.outputStream().use { plaintext.copyTo(it) } }
                id
            } finally {
                temp.delete()
            }
        }
    } catch (t: Throwable) {
        Timber.w(t, "signal attachment: could not download")
        null
    }

    fun read(id: String): ByteArray? = File(dir, id).takeIf { it.isFile }?.readBytes()

    /** Downloads to a stream without keeping it: for blobs that are parsed once, like a contacts sync. */
    fun <T> streamOnce(pointer: AttachmentPointer, consume: (InputStream) -> T): T? = try {
        val servicePointer = AttachmentPointerUtil.createSignalAttachmentPointer(pointer)
        val digest = servicePointer.digest.orElse(null)
            ?: throw InvalidMessageException("attachment has no digest")
        val temp = File.createTempFile("sync", null, dir)
        try {
            receiver().retrieveAttachment(
                servicePointer,
                temp,
                MAX_ATTACHMENT_SIZE,
                AttachmentCipherInputStream.IntegrityCheck.forEncryptedDigest(digest)
            ).use(consume)
        } finally {
            temp.delete()
        }
    } catch (t: Throwable) {
        Timber.w(t, "signal attachment: could not stream")
        null
    }

    /**
     * A filename that is only ever hex.
     *
     * The remote id is server-chosen and reaches this device inside a message, so it is not a
     * safe path component: a `../` in one would write outside this directory. Hashing removes
     * the question rather than trying to sanitise it.
     */
    private fun idFor(remoteId: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(remoteId.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    companion object {
        /** signal-cli's limit. */
        private const val MAX_ATTACHMENT_SIZE = 150L * 1024 * 1024
    }
}
