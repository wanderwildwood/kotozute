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
    /**
     * Tries more than once, because most of what goes wrong here is the network.
     *
     * A dropped socket mid-transfer, or a moment of bad wifi, used to lose a photo or a voice
     * note permanently: one failure, a row marked pending, the pointer thrown away, and
     * nothing in the app that could ever ask again. The bytes stay on the CDN for weeks and
     * were never fetched.
     *
     * Bounded and immediate rather than a queue. A real retry mechanism -- keep the pointer,
     * retry later with backoff, surface it in the UI -- is worth building, and this is not it;
     * this is the cheap part that covers the common cause. What it cannot fix is a phone with
     * no connection at all for the whole batch.
     *
     * A failure that is not worth retrying is not retried: a missing digest, or bytes that do
     * not match one, will fail the same way every time.
     */
    fun download(pointer: AttachmentPointer): String? {
        var lastFailure: Throwable? = null
        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            when (val outcome = downloadOnce(pointer)) {
                is Outcome.Got -> return outcome.id
                is Outcome.NotWorthRetrying -> {
                    Timber.w(outcome.why, "signal attachment: cannot be downloaded at all")
                    return null
                }
                is Outcome.Failed -> {
                    lastFailure = outcome.why
                    if (attempt < DOWNLOAD_ATTEMPTS - 1) {
                        Timber.i("signal attachment: download failed, trying again")
                    }
                }
            }
        }
        Timber.w(lastFailure, "signal attachment: could not download after %d tries", DOWNLOAD_ATTEMPTS)
        return null
    }

    private sealed interface Outcome {
        data class Got(val id: String) : Outcome
        /** Worth another go -- a socket, a timeout, the CDN having a moment. */
        data class Failed(val why: Throwable) : Outcome
        /** The same every time: no digest, or bytes that do not match one. */
        data class NotWorthRetrying(val why: Throwable) : Outcome
    }

    private fun downloadOnce(pointer: AttachmentPointer): Outcome = try {
        val servicePointer = AttachmentPointerUtil.createSignalAttachmentPointer(pointer)
        val digest = servicePointer.digest.orElse(null)
            // Without a digest there is nothing to check the bytes against, and the library
            // refuses the download rather than accepting whatever the CDN returns. Correct:
            // the digest is what makes an attachment the sender's and not the server's.
            ?: throw InvalidMessageException("attachment has no digest")

        val id = idFor(servicePointer.remoteId.toString())
        val destination = File(dir, id)
        if (destination.exists()) {
            Outcome.Got(id)
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
                Outcome.Got(id)
            } finally {
                temp.delete()
            }
        }
    } catch (t: InvalidMessageException) {
        // The sender's own digest is missing or does not match what the CDN served. Asking
        // again gets the same answer.
        Outcome.NotWorthRetrying(t)
    } catch (t: Throwable) {
        Outcome.Failed(t)
    }

    fun read(id: String): ByteArray? = File(dir, id).takeIf { it.isFile }?.readBytes()

    /**
     * Keeps bytes that arrived without being downloaded -- an import reading them out of a
     * folder -- under an id the rest of the app can ask for. Already there is success: the
     * same file referenced by two messages is one file.
     */
    fun keep(id: String, open: () -> InputStream): Boolean = try {
        val destination = File(dir, id)
        if (!destination.isFile) {
            open().use { source -> destination.outputStream().use { source.copyTo(it) } }
        }
        true
    } catch (t: Throwable) {
        Timber.w(t, "signal attachment: could not keep %s", id)
        false
    }

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
        /**
         * How many times to ask the CDN for the same attachment.
         *
         * Three, immediately, in the receive loop. Enough to ride out a dropped socket without
         * holding the batch up: every attempt is on a connection that is already open and
         * already working, since a message just arrived over it.
         */
        private const val DOWNLOAD_ATTEMPTS = 3

        /** signal-cli's limit. */
        private const val MAX_ATTACHMENT_SIZE = 150L * 1024 * 1024
    }
}
