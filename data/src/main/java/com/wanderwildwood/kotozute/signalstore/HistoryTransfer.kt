package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.repository.SignalRepository
import org.signal.core.models.ServiceId
import org.signal.network.NetworkResult
import org.whispersystems.signalservice.api.link.TransferArchiveResponse
import org.whispersystems.signalservice.api.messages.AttachmentTransferProgress
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Brings across the message history a primary sends after linking.
 *
 * When the code this phone shows says `backup5` (see [DeviceLinker]), Signal's link screen on
 * the primary offers "Transfer message history". If its owner takes it, the provisioning
 * message carries a 32-byte ephemeral key, the primary waits for this device to appear,
 * exports its history as a backup archive encrypted to that key, uploads it to the transit CDN
 * and tells the server where. This device long-polls `GET /v1/devices/transfer_archive` for
 * that location, downloads the file, and imports it.
 *
 * Upstream's own linked device does exactly that during registration
 * (`AppRegistrationNetworkController.awaitTransferArchiveFromPrimary`, then
 * `BackupRepository.restoreLinkAndSyncBackup`). The difference here is that it is resumable,
 * because this app lets the person leave the screen: the key is kept in the encrypted store
 * from the moment of linking until the import finishes or the archive is proven unusable, the
 * location once the primary gives it, and the downloaded file until the import is done. A run
 * interrupted at any point -- network, process death -- picks up at the next start.
 *
 * Proven unusable means: the primary said it will not send one, the CDN says the file is gone,
 * the MAC does not match, the format is newer than this build reads, or it will not decode.
 * Each of those is the same on every retry, so the key is dropped. Everything else is retried.
 */
internal class HistoryTransfer(
    private val store: Store,
    private val aci: () -> ServiceId.ACI?,
    /** `LinkDeviceApi.waitForPrimaryDevice`, a long poll. */
    private val waitForPrimary: () -> NetworkResult<TransferArchiveResponse>,
    /** `SignalServiceMessageReceiver.retrieveLinkAndSyncBackup`. */
    private val download: (cdn: Int, key: String, into: File, SignalServiceAttachment.ProgressListener) -> NetworkResult<Unit>,
    /** Where the archive is kept while it is needed. Private to the app. */
    private val dir: File,
    /** The Desktop import's own path: [SignalHistoryImporter] over the Realm sink. */
    private val import: (SignalExportSource, (Int) -> Unit) -> SignalRepository.ImportStats,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep
) {

    /** What a link left behind. See [SignalAccountStore.startHistoryTransfer]. */
    class Pending(
        val ephemeralKey: ByteArray,
        val linkedAt: Long,
        val cdn: Int?,
        val cdnKey: String?
    )

    internal interface Store {
        fun pending(): Pending?
        fun saveLocation(linkedAt: Long, cdn: Int, cdnKey: String)

        /** Only the row [linkedAt] names; see [SignalAccountStore.finishHistoryTransfer]. */
        fun finish(linkedAt: Long)
    }

    /** Named for its link, so a file left by an earlier one is never read with this key. */
    private fun archiveFor(pending: Pending) = File(dir, "$ARCHIVE_PREFIX${pending.linkedAt}.archive")

    /** Runs to the end or to the next interruption. Blocking; call it off the main thread. */
    fun run(onState: (SignalRepository.HistoryTransfer) -> Unit) {
        val pending = store.pending() ?: return
        val archive = archiveFor(pending)
        val partial = File(dir, archive.name + ".part")
        val self = aci() ?: run {
            Timber.w("signal history: no ACI yet; trying again at the next start")
            return onState(SignalRepository.HistoryTransfer.Later)
        }

        val (cdn, cdnKey) = if (pending.cdn != null && pending.cdnKey != null) {
            pending.cdn to pending.cdnKey
        } else {
            if (now() - pending.linkedAt > GIVE_UP_AFTER_MS) {
                Timber.w("signal history: the primary never said where the history is; giving up")
                return drop(pending, onState, SignalRepository.HistoryTransfer.Unusable)
            }
            onState(SignalRepository.HistoryTransfer.Waiting)
            when (val located = awaitLocation()) {
                is Located.At -> {
                    store.saveLocation(pending.linkedAt, located.cdn, located.key)
                    located.cdn to located.key
                }
                Located.Declined -> return drop(pending, onState, SignalRepository.HistoryTransfer.Declined)
                Located.Later -> return onState(SignalRepository.HistoryTransfer.Later)
            }
        }

        if (!archive.isFile) {
            onState(SignalRepository.HistoryTransfer.Downloading(0))
            dir.mkdirs()
            partial.delete()
            var shown = 0
            val listener = object : SignalServiceAttachment.ProgressListener {
                override fun onAttachmentProgress(progress: AttachmentTransferProgress) {
                    val percent = (progress.value * 100).toInt().coerceIn(0, 100)
                    // Sparse: the screen this is drawn on repaints in full.
                    if (percent >= shown + 5) {
                        shown = percent
                        onState(SignalRepository.HistoryTransfer.Downloading(percent))
                    }
                }

                override fun shouldCancel(): Boolean = false
            }
            when (val result = download(cdn, cdnKey, partial, listener)) {
                is NetworkResult.Success -> {
                    if (!partial.renameTo(archive)) {
                        partial.delete()
                        Timber.w("signal history: could not keep the downloaded archive")
                        return onState(SignalRepository.HistoryTransfer.Later)
                    }
                }
                is NetworkResult.StatusCodeError -> {
                    partial.delete()
                    if (result.code in GONE) {
                        Timber.w("signal history: the CDN says %d; the archive is gone", result.code)
                        return drop(pending, onState, SignalRepository.HistoryTransfer.Unusable)
                    }
                    Timber.w("signal history: download answered %d; the next start tries again", result.code)
                    return onState(SignalRepository.HistoryTransfer.Later)
                }
                is NetworkResult.NetworkError -> {
                    partial.delete()
                    Timber.w(result.exception, "signal history: download failed; the next start tries again")
                    return onState(SignalRepository.HistoryTransfer.Later)
                }
                is NetworkResult.ApplicationError -> {
                    partial.delete()
                    Timber.w(result.throwable, "signal history: download threw; the next start tries again")
                    return onState(SignalRepository.HistoryTransfer.Later)
                }
            }
        }

        val reader = try {
            TransferArchiveReader.forLinking(pending.ephemeralKey, self, archive).also { it.verify() }
        } catch (e: TransferArchiveReader.Unusable) {
            Timber.w(e, "signal history: the archive will not open")
            return drop(pending, onState, SignalRepository.HistoryTransfer.Unusable)
        } catch (e: IOException) {
            Timber.w(e, "signal history: could not read the archive; the next start tries again")
            return onState(SignalRepository.HistoryTransfer.Later)
        }

        onState(SignalRepository.HistoryTransfer.Importing(0))
        val stats = try {
            import(TransferArchiveSource(reader)) { count ->
                onState(SignalRepository.HistoryTransfer.Importing(count))
            }
        } catch (e: TransferArchiveSource.NewerVersion) {
            Timber.w("signal history: archive version %d is newer than this build reads", e.version)
            return drop(pending, onState, SignalRepository.HistoryTransfer.Unusable)
        } catch (e: IOException) {
            // The MAC matched, so the bytes are the primary's: a decode failure now is in the
            // archive itself and will be the same next time. Rows already filed stay; the
            // insert is add-only, so they would be found again rather than doubled.
            Timber.w(e, "signal history: the archive would not decode")
            return drop(pending, onState, SignalRepository.HistoryTransfer.Unusable)
        }
        Timber.i("signal history: %d message(s) brought across, %d already here", stats.messages, stats.alreadyPresent)
        drop(pending, onState, SignalRepository.HistoryTransfer.Done(stats))
    }

    /** The key has done its work, one way or the other: forget it, and the file with it. */
    private fun drop(
        pending: Pending,
        onState: (SignalRepository.HistoryTransfer) -> Unit,
        end: SignalRepository.HistoryTransfer
    ) {
        store.finish(pending.linkedAt)
        // This link's file and any an earlier link left: none of them is needed any more.
        dir.listFiles()?.filter { it.name.startsWith(ARCHIVE_PREFIX) }?.forEach { it.delete() }
        onState(end)
    }

    private sealed interface Located {
        data class At(val cdn: Int, val key: String) : Located
        data object Declined : Located
        data object Later : Located
    }

    /**
     * Upstream's `awaitTransferArchiveFromPrimary`, case for case: long-poll for up to an hour,
     * retrying transient answers. A 204 is the poll's own timeout and is simply asked again.
     */
    private fun awaitLocation(): Located {
        val deadline = now() + WAIT_MS
        while (now() < deadline) {
            when (val result = waitForPrimary()) {
                is NetworkResult.Success -> {
                    val response = result.result
                    Timber.i("signal history: the primary answered (archive=%s, error=%s)", response.hasArchive, response.error)
                    return if (response.hasArchive) Located.At(response.cdn!!, response.key!!) else Located.Declined
                }
                is NetworkResult.ApplicationError -> {
                    Timber.w(result.throwable, "signal history: could not read the primary's answer")
                    return Located.Later
                }
                is NetworkResult.NetworkError -> sleep(RETRY_MS)
                is NetworkResult.StatusCodeError -> when (result.code) {
                    400 -> return Located.Later
                    429 -> sleep(result.retryAfter()?.inWholeMilliseconds ?: RETRY_MS)
                    204 -> Unit
                    else -> sleep(RETRY_MS)
                }
            }
        }
        Timber.w("signal history: no answer from the primary within the hour; the next start asks again")
        return Located.Later
    }

    companion object {
        const val ARCHIVE_PREFIX = "history-transfer-"

        /** Upstream's `maxWaitTime` for one wait. */
        val WAIT_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(1)

        /** Upstream's `RETRY_BACKOFF`. */
        val RETRY_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(5)

        /**
         * How long after linking a location is still worth waiting for, across restarts.
         *
         * ⚠ Not upstream's: Signal's linked device waits its one hour inside registration and
         * never again. A day is a guess at how long the server keeps an unanswered transfer
         * open; the primary itself gives up on the device after a minute and on its export
         * long before this.
         */
        val GIVE_UP_AFTER_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(1)

        /** CDN answers that mean the file is not there and will not be. */
        private val GONE = setOf(400, 401, 403, 404, 410)
    }
}
