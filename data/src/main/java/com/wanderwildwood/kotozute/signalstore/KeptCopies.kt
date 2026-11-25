package com.wanderwildwood.kotozute.signalstore

import org.whispersystems.signalservice.api.crypto.SignalServiceCipherResult
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.Envelope
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Messages that decrypted and then could not be processed, tried again from their kept copy.
 *
 * Upstream never needs this: decryption and processing share one transaction
 * (`IncomingMessageObserver.processBatchInTransaction`), so a throw rolls the session back and
 * the envelope is processed again. Here messages live in Realm, outside any transaction the
 * session could share, so what decrypted is kept beside its envelope and processed again from
 * that copy -- never decrypted twice, which could only come back as a duplicate.
 *
 * Three rules, each because the copy is older than whatever arrived after it:
 * - **Backed off, then given up.** A copy that fails every time is not tried on every drain for
 *   a fortnight; it is tried [MAX_ATTEMPTS] times, further apart each time, and then given up
 *   with a note in the conversation, as a message that was asked for and never came is.
 * - **No state from the past.** A sync that replaces a whole picture -- contacts, the blocked
 *   list, settings, keys, a verification, a message-request answer -- and a profile key are
 *   taken out before the copy is processed again ([forReplay]): newer envelopes have been
 *   processed since, and an old snapshot would overwrite what they set.
 * - **Kept until settled.** A copy is cleared only once it has been processed.
 *
 * The rows are kept by [SignalReceiver]; this holds the decisions, so they can be tested.
 */
internal class KeptCopies(private val store: Store) {

    interface Store {
        /** Kept copies whose next attempt is due and which have not been given up. */
        fun due(now: Long): List<Kept>

        /** Processed: the envelope goes, and its message (if any) takes its place. */
        fun processed(id: Long, message: com.wanderwildwood.kotozute.signal.BridgeMessage?)

        /** Failed again: tried [attempts] times, next at [nextAt]. The first reason stays. */
        fun failedAgain(id: Long, attempts: Int, nextAt: Long)

        /**
         * Given up after [attempts]. With a [note], the conversation is told a message is
         * missing, the same way as for one that never decrypted; without one (the copy itself
         * could not be read, so nobody is known to have sent it, or it was not a message) the
         * row just waits to be swept.
         */
        fun gaveUp(id: Long, attempts: Int, note: Note?)
    }

    class Kept(
        val id: Long,
        val envelope: Envelope,
        val deliveredAt: Long,
        val copy: ByteArray,
        /** Tries so far, counting the one that first failed. */
        val attempts: Int
    )

    /**
     * Who a missing-message note is filed under, and where it goes; see
     * `announceGivenUpEnvelopes`.
     *
     * [sender] is the message's author, half of the id the note is filed under so the message
     * replaces it if it ever lands. For our own message synced from another device the author is
     * this account, and the conversation is the one it was sent *to*: [sentTo] (a service id,
     * else [sentToNumber]), blank for a group or Note to Self. Null for anybody else's message.
     */
    class Note(
        val sender: String,
        val deviceId: Int,
        val groupId: ByteArray?,
        val sentTo: String? = null,
        val sentToNumber: String = "",
        /** The message's own timestamp, where it is not the envelope's. */
        val sentAt: Long? = null
    )

    sealed interface Outcome {
        class Processed(
            val sender: String,
            val message: com.wanderwildwood.kotozute.signal.BridgeMessage?
        ) : Outcome

        object Failed : Outcome
    }

    /**
     * Tries every due copy once. Returns what was processed, for the caller to file with the
     * rest of the batch.
     */
    fun run(now: Long, process: (Kept, SignalServiceCipherResult) -> Outcome): List<Outcome.Processed> {
        val done = mutableListOf<Outcome.Processed>()
        store.due(now).forEach { kept ->
            val decoded = SignalReceiver.decodeUnprocessed(kept.copy)
            if (decoded == null) {
                // Unreadable now, unreadable every time. Given up at once, first reason kept.
                Timber.w("signal receive: a kept copy could not be read; given up")
                store.gaveUp(kept.id, MAX_ATTEMPTS, null)
                return@forEach
            }
            val replay = SignalServiceCipherResult(forReplay(decoded.content), decoded.metadata)
            val outcome = runCatching { process(kept, replay) }.getOrElse { Outcome.Failed }
            when (outcome) {
                is Outcome.Processed -> {
                    store.processed(kept.id, outcome.message)
                    done += outcome
                }
                Outcome.Failed -> {
                    val attempts = kept.attempts + 1
                    if (attempts >= MAX_ATTEMPTS) {
                        Timber.w("signal receive: a kept message failed %d times; given up", attempts)
                        store.gaveUp(kept.id, attempts, noteFor(decoded))
                    } else {
                        store.failedAgain(kept.id, attempts, now + backoff(attempts))
                    }
                }
            }
        }
        return done
    }

    companion object {
        /** Tries in all, the first included. About five and a half hours from first to last. */
        const val MAX_ATTEMPTS = 6

        private val BACKOFF = listOf(
            TimeUnit.MINUTES.toMillis(1),
            TimeUnit.MINUTES.toMillis(5),
            TimeUnit.MINUTES.toMillis(15),
            TimeUnit.HOURS.toMillis(1),
            TimeUnit.HOURS.toMillis(4)
        )

        /** How long after the [attempts]th failure the next try is due. */
        fun backoff(attempts: Int): Long = BACKOFF[(attempts - 1).coerceIn(0, BACKOFF.size - 1)]

        /**
         * The copy as it may be processed again: without the parts that replace state.
         *
         * A sync of contacts, the blocked list, configuration, keys, a verification or a
         * message-request answer each says "this is how things are", and a profile key says
         * which key is current. Processed late, after newer envelopes, each would put an older
         * picture over a newer one. Dropped rather than compared, because none of them carries
         * a timestamp to compare by; each is sent again when it changes. What stays is the
         * message itself and the events that point at one -- reads, deletes, receipts.
         */
        fun forReplay(content: Content): Content {
            val sync = content.syncMessage?.let { s ->
                s.copy(
                    contacts = null,
                    blocked = null,
                    configuration = null,
                    keys = null,
                    verified = null,
                    messageRequestResponse = null,
                    sent = s.sent?.let { sent ->
                        sent.copy(message = sent.message?.copy(profileKey = null))
                    }
                )
            }
            return content.copy(
                syncMessage = sync,
                dataMessage = content.dataMessage?.copy(profileKey = null)
            )
        }

        /**
         * Filed where the message itself would have gone, under the id it would have had.
         *
         * Somebody else's message: under them, in the group it was sent to. Our own, synced from
         * another device: under this account, in the conversation it was sent to -- filed under
         * the sender alone, a one-to-one send went to Note to Self, and the message landing
         * later could not replace the note, because a row never changes conversation. Upstream
         * keys the same transcript by its destination (`PushProcessMessageJob.processOrDefer`).
         *
         * Null for what is not a message -- a receipt, a read sync, typing, a recipient update:
         * a note saying a message is missing would be a note about nothing.
         */
        internal fun noteFor(decoded: SignalServiceCipherResult): Note? {
            val content = decoded.content
            val sent = content.syncMessage?.sent
            // In the order [ContentNormalizer.normalize] reads them, so the note and the message
            // agree about whose it is.
            val carrier = sent?.message ?: content.dataMessage ?: sent?.editMessage?.dataMessage
                ?: content.editMessage?.dataMessage ?: return null
            if (sent?.isRecipientUpdate == true) return null
            val own = carrier === sent?.message || carrier === sent?.editMessage?.dataMessage
            val master = carrier.groupV2?.masterKey?.toByteArray()
            val group = master?.let { key ->
                runCatching { java.util.Base64.getDecoder().decode(ContentNormalizer.groupIdForCheck(key)) }
                    .getOrNull()
            } ?: decoded.metadata.groupId
            val sender = decoded.metadata.sourceServiceId.toString()
            val device = decoded.metadata.sourceDeviceId
            if (!own) return Note(sender, device, group)
            return Note(
                sender, device, group,
                sentTo = ContentNormalizer.destinationServiceIdOf(sent!!),
                sentToNumber = sent.destinationE164.orEmpty(),
                sentAt = carrier.timestamp ?: sent.timestamp
            )
        }
    }
}
