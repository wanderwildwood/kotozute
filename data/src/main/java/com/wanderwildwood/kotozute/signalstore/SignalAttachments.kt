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
     *
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

        // What the sender says it is, before a byte is fetched. See [refuseReason].
        val declaredSize = servicePointer.size.orElse(0).toLong()
        refuseReason(declaredSize)?.let { why ->
            throw InvalidMessageException("refusing an attachment: $why")
        }

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
                    // Bounded by what the sender declared, not by a flat ceiling.
                    downloadLimitFor(declaredSize),
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
     * Removes the bytes behind an attachment.
     *
     * ⚠ There was no way to do this at all. Every incoming picture and voice note was
     * downloaded here and nothing ever deleted one -- so a disappearing message lost its text
     * on schedule and left its media on disk indefinitely, and a message withdrawn by its
     * sender left the file behind as well. The promise a disappearing message makes is not
     * "the words go"; anybody with the handset afterwards still had the photograph.
     *
     * @return whether a file was actually there to remove.
     */
    fun forget(id: String): Boolean {
        if (id.isBlank()) return false
        val file = File(dir, id)
        return runCatching { file.isFile && file.delete() }
            .onFailure { Timber.w(it, "signal attachment: could not remove one") }
            .getOrDefault(false)
    }

    /**
     * Deletes every file on disk that no message refers to any more.
     *
     * ⚠ **Nothing swept these.** Five paths delete a message and then ask for its files by id,
     * and every one of them depends on a JSON column parsing -- so a row whose list could not
     * be read took its files' only names with it when it went. A disappearing message's
     * photograph then stays on the handset for ever, which is the one promise that kind of
     * message makes. The same gap swallows a file left by a crash between the row's deletion
     * and the file's, and by a download that finished after its message was withdrawn.
     *
     * Upstream's answer is exactly this sweep -- `AttachmentTable.deleteAbandonedAttachmentFiles`
     * takes the files on disk, subtracts the ones any row names, and deletes the difference,
     * run from `DeleteAbandonedAttachmentsJob`. Named deletion is the fast path; this is what
     * makes the promise true when the fast path misses one.
     *
     * ⚠ **[known] must be every id, or this deletes a live attachment.** That is the reverse
     * failure and it is the worse one: an orphan left costs disk, a file destroyed costs
     * somebody their picture. The caller aborts rather than passing a partial set, and
     * [known] being empty is treated as "nothing was read" and does nothing -- a phone with no
     * Signal messages has no files here to sweep anyway.
     *
     * The grace period is ours, not upstream's: a file is written here before the row that
     * names it exists, so a download finishing during the sweep would otherwise be deleted a
     * moment after it arrived. Upstream has no window because it writes the row first and
     * marks the file protected (`PartFileProtector`); we have neither, so recency stands in.
     */
    fun forgetAbandoned(
        known: Set<String>,
        now: Long = System.currentTimeMillis()
    ): Int {
        if (known.isEmpty()) return 0
        val files = dir.listFiles() ?: return 0
        var removed = 0
        files.forEach { file ->
            if (!isAbandoned(file.name, file.lastModified(), known, now)) return@forEach
            if (runCatching { file.delete() }.getOrDefault(false)) {
                removed++
            } else {
                Timber.w("signal attachment: an abandoned file would not delete")
            }
        }
        if (removed > 0) Timber.i("signal attachment: %d abandoned file(s) removed", removed)
        return removed
    }

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
                downloadLimitFor(servicePointer.size.orElse(0).toLong()),
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
         * How long a file is left alone after it is written, whatever the rows say.
         *
         * A file is written here before the message row that names it exists, so without a
         * window the sweep could delete an attachment between its download and its row -- a
         * picture lost in the gap it arrived through. An hour is far longer than that gap and
         * far shorter than anyone would notice a stale file for.
         *
         * Ours, not upstream's: upstream writes the row first and marks the file protected
         * (`PartFileProtector.isProtected`), so it has no window to cover.
         */
        internal const val ORPHAN_GRACE_MS = 60L * 60 * 1000

        /**
         * Whether one file on disk should go.
         *
         * Pure so the two halves can be tested apart from a filesystem: **is it referenced**
         * and **is it old enough**. Getting either backwards deletes somebody's picture, which
         * is why the rule is not left inline in a loop over `listFiles()`.
         */
        internal fun isAbandoned(
            name: String,
            lastModified: Long,
            known: Set<String>,
            now: Long
        ): Boolean = when {
            name in known -> false
            // A clock that went backwards, or a file stamped in the future, reads as "new".
            // Left alone: the cost of waiting is disk, the cost of being wrong is a picture.
            lastModified <= 0L || lastModified > now -> false
            now - lastModified < ORPHAN_GRACE_MS -> false
            else -> true
        }

        /**
         * How many times to ask the CDN for the same attachment.
         *
         * Three, immediately, in the receive loop. Enough to ride out a dropped socket without
         * holding the batch up: every attempt is on a connection that is already open and
         * already working, since a message just arrived over it.
         */
        private const val DOWNLOAD_ATTEMPTS = 3

        /**
         * The most this device will accept from the CDN for one attachment.
         *
         * Signal's `RemoteConfig.maxAttachmentReceiveSizeBytes`, whose default works out to
         * 125 MiB -- `maxAttachmentSizeBytes` is 100 MiB and the receive ceiling is
         * `max(that, that * 1.25)`. The 150 MB here was signal-cli's number and is not what
         * the service or Signal use.
         */
        private const val MAX_RECEIVE_SIZE = 125L * 1024 * 1024

        /**
         * How much ciphertext a plaintext of [declaredSize] should come to.
         *
         * ⚠ This is the bound that matters, and there was none. Every download passed a flat
         * ceiling, so a CDN body far longer than the attachment claims to be was written to
         * internal storage **in full** -- up to the ceiling, three times over, since the
         * download is retried -- and only then rejected by the digest check. Signal bounds
         * each download to `minOf(expectedCiphertextSize, maxReceiveSize)`, so an over-long
         * body is cut off at the length the sender declared rather than at a global limit.
         *
         * `AttachmentCipherStreamUtil.getCiphertextLength(PaddingInputStream.getPaddedSize(n))`
         * is upstream's own arithmetic: the padding the sender applied, then the cipher
         * overhead on top.
         */
        internal fun downloadLimitFor(declaredSize: Long): Long {
            if (declaredSize <= 0) return MAX_RECEIVE_SIZE
            val expected = org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
                .getCiphertextLength(
                    org.whispersystems.signalservice.internal.crypto.PaddingInputStream
                        .getPaddedSize(declaredSize)
                )
            return minOf(expected, MAX_RECEIVE_SIZE)
        }

        /**
         * Why an attachment is refused before a byte of it is fetched, or null to go ahead.
         *
         * Signal's two up-front guards, which this had neither of. A size beyond the ceiling
         * is a download that cannot succeed, and no declared size at all means there is
         * nothing to bound the download by -- both are answered by not starting.
         */
        internal fun refuseReason(declaredSize: Long): String? = when {
            declaredSize > MAX_RECEIVE_SIZE ->
                "it declares $declaredSize bytes, beyond the $MAX_RECEIVE_SIZE byte ceiling"
            declaredSize <= 0 -> "it declares no size, so nothing bounds the download"
            else -> null
        }
    }
}
