package com.wanderwildwood.kotozute.signalstore

import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
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
 * - **Tried again by a new build.** A copy given up by one build is given a fresh set of tries
 *   by the next ([Store.freshTries]): a failure that was this app's fault may be fixed by an
 *   update, and until the sweep the copy is still there to be processed. Its note, if one
 *   was written, is not written again; the message replaces it if it now lands.
 * - **What points at it waits for it.** An edit, a withdrawal, a reaction, a pin or a vote
 *   aimed at a copy still being tried would otherwise be applied first and lost -- the copy is
 *   older than they are. Such an event is kept beside the copy, waiting ([targetOf], `waits_for`),
 *   and processed once the copy has landed and been filed ([afterFiled]), in the order the
 *   events arrived. Upstream holds them in `EarlyMessageCache` and replays them when the target
 *   is inserted; here they are in the store, so they outlast a restart, as the copy does.
 *
 * The rows are kept by [SignalReceiver]; this holds the decisions, so they can be tested.
 */
internal class KeptCopies(
    private val store: Store,
    /** This app's version code, which a given-up copy is stamped with. */
    private val build: Int
) {

    interface Store {
        /** Kept copies whose next attempt is due, not given up and not waiting for another. */
        fun due(now: Long): List<Kept>

        /** Every kept copy, waiting or not, given up or not, in the order they arrived. */
        fun all(): List<Kept>

        /**
         * An event that waited for a message that was then given up, applied anyway (to the
         * message's note) and kept, waiting as `applied:<target>`, so it is applied again if
         * a later build's fresh tries land the message after all. Its [message], if any, is
         * filed as a processed copy's is.
         */
        fun appliedEarly(id: Long, message: com.wanderwildwood.kotozute.signal.BridgeMessage?, target: String)

        /** Processed: the envelope goes, and its message (if any) takes its place. */
        fun processed(id: Long, message: com.wanderwildwood.kotozute.signal.BridgeMessage?)

        /**
         * Failed again: tried [attempts] times, next at [nextAt]. The first reason stays. An
         * event that was waiting stops waiting: from here it is an ordinary kept copy.
         */
        fun failedAgain(id: Long, attempts: Int, nextAt: Long)

        /**
         * Given up after [attempts]. With a [note], the conversation is told a message is
         * missing, the same way as for one that never decrypted; without one (the copy itself
         * could not be read, so nobody is known to have sent it, or it was not a message) the
         * row just waits to be swept.
         */
        fun gaveUp(id: Long, attempts: Int, note: Note?, build: Int)

        /**
         * A kept event that points at a kept message still being tried (or given up and not
         * yet noted) waits for it from here, as one arriving now would: `waits_for` set to
         * [target], tries back to none. See [run].
         */
        fun waitFor(id: Long, target: String)

        /**
         * Copies given up by any build but [build] start again: one try spent, the next due at
         * [now], stamped with [build] so this build gives each of them only the one fresh set.
         *
         * ⚠ The note stays as it was. `placeholder_at` is untouched, so a copy that fails again
         * and is given up again is not announced twice.
         */
        fun freshTries(build: Int, now: Long)
    }

    class Kept(
        val id: Long,
        val envelope: Envelope,
        val deliveredAt: Long,
        val copy: ByteArray,
        /** Tries so far, counting the one that first failed. */
        val attempts: Int,
        /**
         * The id of the kept message this event waits for, or null. See [targetOf]. Prefixed
         * [APPLIED] once it has been applied to that message's note; see [Store.appliedEarly].
         */
        val waitsFor: String? = null,
        /** Whether the conversation has been told this message is missing (`placeholder_at`). */
        val noted: Boolean = false
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

    /** A note already in a conversation: its row's id and conversation. */
    data class EarlyNote(val id: String, val threadKey: String)

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
     *
     * [stop] is asked before each copy: a copy that changes the account's identity ends the
     * run there, as it ends a live batch, and what is left waits for the new connection.
     *
     * ⚠ A message that somebody has withdrawn is processed and not filed. The withdrawal is
     * waiting for it (or already ran against its note); filing it first would put it back in
     * the conversation, with a notification, until the withdrawal caught up.
     */
    fun run(
        now: Long,
        stop: () -> Boolean = { false },
        process: (Kept, SignalServiceCipherResult) -> Outcome
    ): List<Outcome.Processed> {
        val done = mutableListOf<Outcome.Processed>()
        store.freshTries(build, now)
        val due = store.due(now)
        if (due.isEmpty()) return done
        val rows = store.all()
        val withdrawn = withdrawalsByTarget(rows)
        // A kept edit, withdrawal, reaction, pin or vote -- one that failed itself, or failed
        // after its release -- ends the way one arriving live does (see [targets]): its message
        // still being tried, it waits for it; given up and noted, it is applied to the note
        // and kept as [APPLIED], so a later build that lands the message applies it again and
        // a withdrawn one stays out. Taken before the loop on purpose: a message landing
        // earlier in this run is not filed until after it, so what points at it waits for
        // [afterFiled] like the rest.
        val targets = targets(rows)
        for (kept in due) {
            if (stop()) break
            val target = SignalReceiver.decodeUnprocessed(kept.copy)?.let { targetOf(it.content, it.metadata) }
            if (target != null && target in targets.wait) {
                store.waitFor(kept.id, target)
                continue
            }
            if (target != null && target in targets.applyAndKeep) {
                attempt(kept, now, process, done) { k, _, outcome ->
                    store.appliedEarly(k.id, outcome.message, target)
                    done += outcome
                }
                continue
            }
            attempt(kept, now, process, done) { k, decoded, outcome ->
                if (isWithdrawn(decoded, withdrawn)) {
                    Timber.i("signal receive: a kept message was withdrawn before it landed; not filed")
                    store.processed(k.id, null)
                    done += Outcome.Processed(outcome.sender, null)
                } else {
                    store.processed(k.id, outcome.message)
                    done += outcome
                }
            }
        }
        return done
    }

    /**
     * Withdrawals waiting for a message, already run against its note, or kept themselves
     * (one that failed, which [run] is about to make wait): when each was sent.
     */
    private fun withdrawalsByTarget(rows: List<Kept>): Map<String, List<Long>> = rows
        .mapNotNull { event ->
            val decoded = SignalReceiver.decodeUnprocessed(event.copy) ?: return@mapNotNull null
            val m = decoded.content.syncMessage?.sent?.message ?: decoded.content.dataMessage
            if (m?.delete == null) return@mapNotNull null
            val target = event.waitsFor?.removePrefix(APPLIED)
                ?: targetOf(decoded.content, decoded.metadata) ?: return@mapNotNull null
            target to (event.envelope.serverTimestamp ?: event.envelope.clientTimestamp ?: 0L)
        }
        .groupBy({ it.first }, { it.second })

    /** The rule `applyWithdrawal` applies: our own always, anybody else's within the window. */
    private fun isWithdrawn(decoded: SignalServiceCipherResult, withdrawn: Map<String, List<Long>>): Boolean {
        val target = landsAs(decoded) ?: return false
        val at = withdrawn[target] ?: return false
        val ours = decoded.content.syncMessage?.sent?.message != null
        val sentAt = (decoded.content.syncMessage?.sent?.message ?: decoded.content.dataMessage)?.timestamp ?: 0L
        return at.any { ours || it - sentAt < ContentNormalizer.WITHDRAWAL_WINDOW_MS }
    }

    /**
     * What an arriving edit, withdrawal, reaction, pin or vote does about a kept message it
     * points at, by that message's id.
     *
     * - [wait]: the message is still being tried, or given up and its note not yet written.
     *   The event is kept, waiting, and applied once the message lands (or to its note).
     * - [applyAndKeep]: the message was given up and its note written. The event is applied
     *   now, to the note, and kept as [APPLIED], so that if a later build's fresh tries land
     *   the message after all it is applied again after it -- as for one that waited.
     *
     * Anything else is processed as it always was.
     */
    class Targets(val wait: MutableSet<String>, val applyAndKeep: Set<String>) {
        fun isEmpty() = wait.isEmpty() && applyAndKeep.isEmpty()
    }

    /**
     * The [Targets] for this batch. Asked once per batch, and empty -- one query, nothing
     * decoded -- when nothing is kept.
     */
    fun targets(): Targets = targets(store.all())

    private fun targets(rows: List<Kept>): Targets {
        val wait = mutableSetOf<String>()
        val applyAndKeep = mutableSetOf<String>()
        rows.filter { it.waitsFor == null }.forEach { kept ->
            val id = SignalReceiver.decodeUnprocessed(kept.copy)?.let { landsAs(it) } ?: return@forEach
            if (kept.attempts < MAX_ATTEMPTS || !kept.noted) wait += id else applyAndKeep += id
        }
        return Targets(wait, applyAndKeep)
    }

    /**
     * Once the batch is filed: processes the events whose message has now landed, in the order
     * they arrived, and returns what they produced for the caller to file in turn.
     *
     * After filing and not inside [run], because a withdrawal or a reaction looks for the row
     * at once and the row is only there once the batch is filed. Asked every drain rather than
     * remembered from [run], so a restart in between loses nothing.
     *
     * - Still being tried: the event keeps waiting.
     * - Given up, and its note written (by the drain before): applied to the note -- an edit
     *   replaces it, a withdrawal removes it -- and kept as applied ([Store.appliedEarly]).
     *   Given up and not yet noted: it waits for the note, so the note does not undo it.
     * - Landed, or gone: applied now. One applied earlier is applied once more, because its
     *   message has now arrived after all and the note it changed has been replaced.
     */
    fun afterFiled(
        now: Long,
        stop: () -> Boolean = { false },
        process: (Kept, SignalServiceCipherResult) -> Outcome
    ): List<Outcome.Processed> {
        val rows = store.all()
        val waiting = rows.filter { it.waitsFor != null }
        if (waiting.isEmpty()) return emptyList()
        val landing = rows.filter { it.waitsFor == null }.mapNotNull { kept ->
            SignalReceiver.decodeUnprocessed(kept.copy)?.let { landsAs(it) }?.let { it to kept }
        }
        val trying = landing.filter { it.second.attempts < MAX_ATTEMPTS }.mapTo(mutableSetOf()) { it.first }
        val givenUp = landing.filter { it.second.attempts >= MAX_ATTEMPTS }
            .associate { it.first to it.second.noted }
        val done = mutableListOf<Outcome.Processed>()
        for (event in waiting) {
            if (stop()) break
            val waitsFor = event.waitsFor!!
            val applied = waitsFor.startsWith(APPLIED)
            val target = waitsFor.removePrefix(APPLIED)
            val fresh = Kept(event.id, event.envelope, event.deliveredAt, event.copy, 0)
            when {
                target in trying -> Unit
                target in givenUp -> if (!applied && givenUp[target] == true) {
                    attempt(fresh, now, process, done) { k, _, outcome ->
                        store.appliedEarly(k.id, outcome.message, target)
                        done += outcome
                    }
                }
                else -> attempt(fresh, now, process, done)
            }
        }
        return done
    }

    private fun attempt(
        kept: Kept,
        now: Long,
        process: (Kept, SignalServiceCipherResult) -> Outcome,
        done: MutableList<Outcome.Processed>,
        succeeded: (Kept, SignalServiceCipherResult, Outcome.Processed) -> Unit = { k, _, outcome ->
            store.processed(k.id, outcome.message)
            done += outcome
        }
    ) {
        val decoded = SignalReceiver.decodeUnprocessed(kept.copy)
        if (decoded == null) {
            // Unreadable now, unreadable every time. Given up at once, first reason kept.
            Timber.w("signal receive: a kept copy could not be read; given up")
            store.gaveUp(kept.id, MAX_ATTEMPTS, null, build)
            return
        }
        val replay = SignalServiceCipherResult(forReplay(decoded.content), decoded.metadata)
        val outcome = runCatching { process(kept, replay) }.getOrElse { Outcome.Failed }
        when (outcome) {
            is Outcome.Processed -> succeeded(kept, decoded, outcome)
            Outcome.Failed -> {
                val attempts = kept.attempts + 1
                if (attempts >= MAX_ATTEMPTS) {
                    Timber.w("signal receive: a kept message failed %d times; given up", attempts)
                    store.gaveUp(kept.id, attempts, noteFor(decoded), build)
                } else {
                    store.failedAgain(kept.id, attempts, now + backoff(attempts))
                }
            }
        }
    }

    companion object {
        /** How [Kept.waitsFor] marks an event already applied to its message's note. */
        const val APPLIED = "applied:"

        /** The [APPLIED] marks of events kept for these copies' messages, which go with them. */
        internal fun appliedFor(copies: List<ByteArray>): List<String> = copies.mapNotNull { copy ->
            SignalReceiver.decodeUnprocessed(copy)?.let { landsAs(it) }?.let { APPLIED + it }
        }

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
         *
         * Nor a retry receipt, which acts on a session rather than on a message: it asks for
         * the session to be rebuilt and a message sent again, and acted on hours late it throws
         * away whatever session was set up since, for a request already answered or abandoned.
         *
         * ⚠ A sender key distribution stays. Processing one again is harmless in libsignal
         * (v0.108.0, `rust/protocol/src/sender_keys.rs` `add_sender_key_state`): a state with
         * the same chain id and signing key is kept as it is, iteration and all; a sender's new
         * key comes with a new random chain id (`group_cipher.rs`
         * `create_sender_key_distribution_message`), so an old distribution only adds its old
         * chain beside the current one, and decryption finds a chain by its id. Dropping it is
         * what would hurt: if the first pass threw before the key was taken (it is taken early,
         * but after the block check), the copy is the only place the key is left, and without
         * it that sender's group messages would not open.
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
                dataMessage = content.dataMessage?.copy(profileKey = null),
                decryptionErrorMessage = null
            )
        }

        /**
         * The note written for an envelope that would not open, once it opens and is kept
         * instead, when that note is not where the message or its own note will go.
         *
         * ⚠ The early note is filed by who the failure named and the envelope's timestamp. For
         * our own message synced from another device that is ourselves, so it sits in Note to
         * Self under the message's own id -- and a row never changes conversation, so the
         * message landing in the conversation it was sent to would be refused there. Returned
         * so it can be taken out; null when it is already where the message goes, which then
         * replaces it as usual.
         *
         * @param sender, [groupId] what the failure recorded (`retry_sender`, `retry_group`).
         * @param envelopeTs the envelope's own timestamp, which the early note is filed under.
         */
        internal fun staleEarlyNote(
            sender: String,
            groupId: ByteArray?,
            envelopeTs: Long,
            copy: SignalServiceCipherResult,
            selfAci: String?,
            selfE164: String?
        ): EarlyNote? {
            fun b64(g: ByteArray?) = g?.let { java.util.Base64.getEncoder().encodeToString(it) }.orEmpty()
            val earlyKey = ContentNormalizer.noteThreadKey(sender, b64(groupId), null, "", selfAci, selfE164)
                ?: return null
            val early = EarlyNote(ContentNormalizer.messageIdFor(sender, "", envelopeTs), earlyKey)
            val note = noteFor(copy) ?: return early
            val key = ContentNormalizer.noteThreadKey(
                note.sender, b64(note.groupId), note.sentTo, note.sentToNumber, selfAci, selfE164
            )
            val id = ContentNormalizer.messageIdFor(note.sender, "", note.sentAt ?: envelopeTs)
            return if (key == early.threadKey && id == early.id) null else early
        }

        /**
         * The id (`author:timestamp`) of the message this content acts on, or null if it acts
         * on none: an edit, a withdrawal for everyone or by an administrator, a reaction, a pin
         * or unpin, a poll vote or a poll ended. Each names its target the way the handler
         * that applies it does -- an edit, a withdrawal and a poll ended are always the
         * sender's own message; the rest name the target's author.
         */
        internal fun targetOf(content: Content, metadata: EnvelopeMetadata): String? {
            val self = metadata.sourceServiceId.toString()
            fun id(author: String?, at: Long?): String? =
                if (author.isNullOrBlank() || at == null || at == 0L) null
                else ContentNormalizer.messageIdFor(author, "", at)
            fun aci(text: String?, binary: okio.ByteString?): String? =
                org.signal.core.models.ServiceId.parseOrNull(text, binary)?.toString()

            val sent = content.syncMessage?.sent
            (content.editMessage ?: sent?.editMessage)?.let { return id(self, it.targetSentTimestamp) }
            val m = sent?.message ?: content.dataMessage ?: return null
            m.delete?.let { return id(self, it.targetSentTimestamp) }
            m.adminDelete?.let { return id(aci(null, it.targetAuthorAciBinary), it.targetSentTimestamp) }
            m.reaction?.let { return id(aci(it.targetAuthorAci, it.targetAuthorAciBinary), it.targetSentTimestamp) }
            m.pinMessage?.let { return id(aci(null, it.targetAuthorAciBinary), it.targetSentTimestamp) }
            m.unpinMessage?.let { return id(aci(null, it.targetAuthorAciBinary), it.targetSentTimestamp) }
            m.pollVote?.let { return id(aci(null, it.targetAuthorAciBinary), it.targetSentTimestamp) }
            m.pollTerminate?.let { return id(self, it.targetSentTimestamp) }
            return null
        }

        /**
         * The id a kept copy's message will be filed under once it lands, or null when it is
         * not a message something could point at (an edit, an event, a sync of something else).
         * The same `author:timestamp` [ContentNormalizer.normalize] gives it.
         */
        internal fun landsAs(decoded: SignalServiceCipherResult): String? {
            val content = decoded.content
            if (targetOf(content, decoded.metadata) != null) return null
            if (content.syncMessage?.sent?.isRecipientUpdate == true) return null
            val m = content.syncMessage?.sent?.message ?: content.dataMessage ?: return null
            val at = m.timestamp?.takeIf { it != 0L } ?: return null
            return ContentNormalizer.messageIdFor(decoded.metadata.sourceServiceId.toString(), "", at)
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
         * Null for what is not a message -- a receipt, a read sync, typing, a recipient update,
         * an event pointing at another message: a note saying a message is missing would be a
         * note about nothing.
         */
        internal fun noteFor(decoded: SignalServiceCipherResult): Note? {
            val content = decoded.content
            // An edit, a withdrawal, a reaction and the rest are not a message that went
            // missing; the message they point at has its own note, if it needs one.
            if (targetOf(content, decoded.metadata) != null) return null
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
