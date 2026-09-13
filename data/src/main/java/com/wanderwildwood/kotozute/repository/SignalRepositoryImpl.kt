package com.wanderwildwood.kotozute.repository

import com.wanderwildwood.kotozute.model.Contact
import com.wanderwildwood.kotozute.model.SignalMessage
import com.wanderwildwood.kotozute.model.SignalThread
import com.wanderwildwood.kotozute.signal.BridgeMessage
import com.wanderwildwood.kotozute.util.PhoneNumberUtils
import com.wanderwildwood.kotozute.util.Preferences
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import io.realm.Case
import io.realm.Realm
import io.realm.RealmResults
import io.realm.Sort
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

private const val ATTACHMENT_PREVIEW = "\uD83D\uDCCE Attachment"


/**
 * What a view-once message says instead of nothing.
 *
 * The bridge deliberately keeps the row -- "so the conversation does not have a silent hole
 * in it" -- with an empty body and no attachment, because the picture is gone by design.
 * Without a marker the phone reintroduced exactly the hole the bridge went out of its way
 * to avoid: an empty bubble, a blank inbox snippet, and no way to tell a view-once photo
 * you never saw from a rendering fault.
 */
private const val VIEW_ONCE_PREVIEW = "\uD83D\uDC41 View-once photo (not kept)"

@Singleton
class SignalRepositoryImpl @Inject constructor(
    private val context: android.content.Context,
    private val prefs: Preferences,
    private val phoneNumberUtils: PhoneNumberUtils,
    /** The other rail, for the things that are done to a person rather than a conversation. */
    private val conversations: com.wanderwildwood.kotozute.repository.ConversationRepository,
    private val messages: com.wanderwildwood.kotozute.repository.MessageRepository
) : SignalRepository {

    /**
     * This device's own Signal connection, when it has one.
     *
     * There are now two ways Signal can reach this app: a bridge on another machine, or this
     * device being a linked device itself. The second is the destination; the first is what
     * it replaces. Both are supported at once because a phone that is already paired to a
     * bridge should not lose its messages the day it learns to fetch its own.
     *
     * Lazy, and it must stay lazy: constructing it opens the keystore and the encrypted
     * database, and this repository is built during startup on the main thread.
     */
    private val signalStore by lazy { com.wanderwildwood.kotozute.signalstore.SignalStore(context) }

    /**
     * "Note to Self", read from resources so it follows the phone's language.
     */
    private val noteToSelfTitle: String
        get() = context.getString(com.wanderwildwood.kotozute.data.R.string.signal_note_to_self)

    /**
     * Which rail this device should use, when it could use either.
     *
     * One answer, consulted everywhere. syncNow() used to prefer the direct link while
     * startStream() preferred the bridge, so a phone with both -- which is exactly what a
     * phone that has been using a bridge and then links looks like -- would catch up over one
     * and stream over the other. Both write the same rows through store(), so it deduplicates
     * rather than duplicating, but two writers racing for the same conversation is not a
     * thing to leave in place because it happens to be survivable.
     *
     * The bridge wins while one is configured. It is the rail already in use on such a phone,
     * and unpairing it is a deliberate act the user can take when they want the other.
     */
    // Was: whether a bridge was paired. Signal now reaches this phone one way, so the
    // question no longer exists -- see the v1.17 removal. Kept nowhere: every caller that
    // asked it has been settled in favour of the device's own connection.

    /**
     * How many envelopes are sitting undecrypted.
     *
     * Cheap and swallowing: this runs on every state publish, and a store that will not open
     * must not make the settings screen fail -- the number is a diagnostic, not a fact the
     * app depends on.
     */
    private fun undecryptableCount(): Int =
        runCatching { signalStore.undecryptableCount() }.getOrDefault(0)

    /** True when this device is itself a device on the account. */
    private fun linkedDirectly(): Boolean = try {
        signalStore.isLinked()
    } catch (t: Throwable) {
        // A store that will not open is not a linked device, and it must not be a crash at
        // startup either. See ProtocolStoreKey: the key is deliberately not recoverable, so
        // the honest answer here is "no Signal" rather than a dead app.
        Timber.w(t, "signal: could not read the protocol store")
        false
    }

    private val state = BehaviorSubject.createDefault(
        SignalRepository.ConnectionState(
            configured = false, enabled = false,
            signalConnected = false, lastSyncedAt = 0
        )
    )

    private val incoming = io.reactivex.subjects.PublishSubject.create<SignalMessage>()

    /** Threads whose messages have gone; see [messagesRemoved]. */
    private val removed = io.reactivex.subjects.PublishSubject.create<String>()

    /** Threads read on another of the account's devices; see [conversationsRead]. */
    private val readElsewhere = io.reactivex.subjects.PublishSubject.create<String>()

    private var stream: Closeable? = null
    private val streamWanted = AtomicBoolean(false)

    /**
     * Which stream loop is the current one.
     *
     * A single shared "wanted" flag was not enough to say that, because a loop told to stop
     * is not stopped yet -- it can be inside a sleep, or between its latch and its next
     * check. A startStream() landing in that window won the flag and started a second loop,
     * and the first then read the flag as true again, carried on, and overwrote the new
     * loop's Closeable. Two live connections against the bridge, only one of them closable,
     * every inbound message stored twice, and the older loop publishing "cannot reach the
     * bridge" over the newer one's healthy state. Whichever finished first cleared the
     * shared flag and stopped the other.
     *
     * A loop now owns its generation. It runs while it is still the current one and stops
     * the moment it is not, so a replacement can never be sabotaged by its predecessor.
     */
    private val streamGeneration = AtomicInteger(0)

    /**
     * Whether a live SSE stream is currently up. syncNow() runs on other threads -- the
     * conversations screen fires one on every creation -- and its failures must not be
     * allowed to say the bridge is unreachable while a stream is sitting there connected.
     */
    private val streamConnected = AtomicBoolean(false)

    init {
        publishState(signalConnected = false, error = null)
        signalStore.onRejected = ::onServerRefusedThisDevice
        signalStore.onConversationState = ::applyConversationState
    }

    /**
     * Muted and archived, as the account's own records hold them.
     *
     * Applied only to conversations this phone already has. A record for somebody never
     * written to is not a conversation yet, and creating an empty archived thread for every
     * contact on the account would fill the inbox with rows nobody has said anything in.
     *
     * Not the other way round: nothing here writes back to the account's records, so the
     * account's answer is the only shared one and it wins.
     */
    private fun applyConversationState(
        states: List<com.wanderwildwood.kotozute.signalstore.SignalStorageService.ConversationState>
    ) = runOffThread {
        if (states.isEmpty()) return@runOffThread
        var changed = 0
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                states.forEach { state ->
                    val thread = r.where(SignalThread::class.java)
                        .equalTo("threadKey", state.threadKey)
                        .findFirst()
                        ?: return@forEach
                    if (thread.muted != state.muted || thread.archived != state.archived) {
                        thread.muted = state.muted
                        thread.archived = state.archived
                        changed++
                    }
                }
            }
        }
        if (changed > 0) {
            Timber.i("signal storage: %d conversation(s) muted or archived to match the account", changed)
            contactsChanged()
        }
    }

    /**
     * The server has refused this device: the account no longer has it, or the build is too
     * old to talk to.
     *
     * Both are permanent until someone acts, so the reconnect loop is retired rather than
     * backed off. Left running it reconnects every minute for ever, and -- this is the part
     * that matters -- the phone looks connected the whole time while no message can arrive.
     *
     * The reason is written down as well as published, because the next thing that happens is
     * usually the app being restarted, and a reason held only in memory would take the
     * explanation with it.
     */
    private fun onServerRefusedThisDevice(reason: String) {
        if (prefs.signalRejected.get() == reason && !streamConnected.get()) return
        Timber.w("signal: server refused this device: %s", reason)
        prefs.signalRejected.set(reason)
        runOffThread {
            stopStream()
            publishState(signalConnected = false, error = reason)
        }
    }

    /** Whether this phone is on the account at all. Every screen keys its Signal UI off it. */
    override fun isConfigured(): Boolean = linkedDirectly()

    override fun unpair() = runOffThread {
        stopStream()
        prefs.signalEnabled.set(false)
        prefs.signalLastSync.set(0L)
        prefs.signalRejected.set("")
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                it.delete(SignalMessage::class.java)
                it.delete(SignalThread::class.java)
            }
        }
        publishState(signalConnected = false, error = null)
    }

    /**
     * Delete every message whose disappearing deadline has passed, and tidy the threads they
     * were the last of.
     *
     * The bridge sweeps its own store, but that copy is not the one anyone reads. Without
     * this the bridge deletes the only row that was ever going to go and the phone keeps the
     * message for ever. Reads exclude expired rows too, so a message is gone from view the
     * moment its time is up whether or not the sweep has run.
     */
    override fun purgeExpired(): Int {
        var removed = 0
        val doomedAttachments = mutableListOf<String>()
        val expiredThreads = mutableListOf<String>()
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val dead = r.where(SignalMessage::class.java)
                    .greaterThan("expiresAt", 0L)
                    .lessThanOrEqualTo("expiresAt", System.currentTimeMillis())
                    .findAll()
                removed = dead.size
                if (removed == 0) return@executeTransaction
                val touched = dead.map { it.threadKey }.distinct()
                expiredThreads += touched
                // Collected before the rows go, because afterwards there is nothing left
                // saying which files belonged to them.
                doomedAttachments += dead.flatMap { attachmentIdsOf(it.attachments) }
                dead.deleteAllFromRealm()
                // A thread whose newest message just vanished would otherwise keep showing
                // it as the preview on the inbox row.
                touched.forEach { key -> refreshThreadPreview(r, key) }
            }
        }
        if (doomedAttachments.isNotEmpty()) {
            // The point of a disappearing message is not that its words go. Anything kept on
            // disk for it goes with them.
            val gone = runCatching { signalStore.forgetAttachments(doomedAttachments) }
                .onFailure { Timber.w(it, "signal: could not remove expired attachments") }
                .getOrDefault(0)
            if (gone > 0) Timber.i("signal: %d expired attachment(s) removed from disk", gone)
        }
        if (removed > 0) {
            Timber.i("signal: %d expired message(s) removed", removed)
            expiredThreads.forEach { this.removed.onNext(it) }
        }
        return removed
    }

    /** The stored attachment ids on a message row, or nothing if it had none. */
    private fun attachmentIdsOf(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = org.json.JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /** Re-derive a thread's snippet, timestamp and unread count from what is left. */
    private fun refreshThreadPreview(r: Realm, threadKey: String) {
        val thread = r.where(SignalThread::class.java)
            .equalTo("threadKey", threadKey).findFirst() ?: return
        val newest = r.where(SignalMessage::class.java)
            .equalTo("threadKey", threadKey)
            .sort("date", Sort.DESCENDING)
            .findFirst()
        if (newest == null) {
            thread.snippet = ""
            thread.snippetOutgoing = false
            thread.unread = 0
            return
        }
        thread.snippet = previewOf(newest)
        thread.snippetOutgoing = newest.outgoing
        thread.lastTs = newest.date
        thread.unread = r.where(SignalMessage::class.java)
            .equalTo("threadKey", threadKey)
            .equalTo("outgoing", false)
            .equalTo("read", false)
            .count().toInt()
    }

    private fun runOffThread(block: () -> Unit) {
        thread(isDaemon = true) { runCatching(block).onFailure { Timber.w(it, "signal") } }
    }

    override fun setEnabled(enabled: Boolean) {
        prefs.signalEnabled.set(enabled)
        if (enabled) startStream() else stopStream()
        publishState(
            signalConnected = state.value?.signalConnected ?: false,
            error = null
        )
    }

    /**
     * Pull everything after our cursor. Runs on the caller's thread and opens its own
     * Realm, because Realm instances belong to the thread that created them.
     */
    /**
     * The same transaction and the same [store] the bridge sync uses.
     *
     * Deliberately not a parallel path. Every rule about threads, previews, reactions and
     * expiry lives in [store]; a second writer with its own copy of them would drift, and the
     * drift would show up as duplicate threads rather than as an error.
     */
    /**
     * Renames threads once names are known.
     *
     * Threads are created the moment a message arrives, which is usually before the contacts
     * sync has been answered -- so naming only at creation would leave every conversation
     * that predates the sync showing a service id forever.
     */
    /**
     * Names group threads that have none.
     *
     * Separate from filing, and after it, for two reasons: it asks the server, which must not
     * happen inside a Realm transaction; and a group thread that already existed would
     * otherwise keep showing its own identifier for ever, because a title is only chosen when
     * a thread is created.
     */
    private fun nameGroupThreads() {
        val toName = mutableListOf<Pair<String, ByteArray>>()
        Realm.getDefaultInstance().use { realm ->
            realm.where(SignalThread::class.java)
                .equalTo("kind", "group")
                .findAll()
                .filter { it.title.isBlank() }
                .forEach { thread ->
                    realm.where(SignalMessage::class.java)
                        .equalTo("threadKey", thread.threadKey)
                        .findAll()
                        .firstOrNull { it.groupMasterKey != null }
                        ?.groupMasterKey
                        ?.let { toName += thread.threadKey to it }
                }
        }
        if (toName.isEmpty()) return

        // Fetched outside any transaction, then written in one.
        val names = toName.mapNotNull { (key, master) ->
            signalStore.groupFor(master)?.title?.takeIf { it.isNotBlank() }?.let { key to it }
        }
        if (names.isEmpty()) return
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                names.forEach { (threadKey, title) ->
                    r.where(SignalThread::class.java)
                        .equalTo("threadKey", threadKey)
                        .findFirst()
                        ?.takeIf { it.title.isBlank() }
                        ?.title = title
                }
            }
        }
        Timber.i("signal groups: named %d thread(s)", names.size)
    }

    /**
     * The Connection line counts contacts, and the state it reads is only republished when
     * something happens. Learning 71 contacts is something happening: without this the line
     * still said none, and the reader concludes the thing they just pressed did nothing.
     */
    private fun contactsChanged() {
        publishState(
            signalConnected = state.value?.signalConnected ?: false,
            error = state.value?.error
        )
    }

    /**
     * What the phone's own address book calls a number, or null.
     *
     * A linked device has no way to resolve a service id on its own -- that is why threads
     * show raw ids until somebody's client shares a profile key. But once a number is known
     * for a service id, the reader almost always already has that number in their contacts,
     * under the name they chose. That name is better than anything the account can supply,
     * and it costs a lookup rather than a network call.
     *
     * Numbers are compared, not matched as text: the address book holds "828 555 0123" where
     * Signal says "+18285550123".
     */
    private fun addressBookName(number: String): String? {
        if (number.isBlank()) return null
        return runCatching {
            Realm.getDefaultInstance().use { realm ->
                realm.where(com.wanderwildwood.kotozute.model.Contact::class.java)
                    .findAll()
                    .firstOrNull { contact ->
                        contact.numbers.any { phoneNumberUtils.compare(it.address, number) }
                    }
                    ?.name
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** A name for a service id: what the account calls them, else the reader's own contacts. */
    private fun nameForCounterpart(uuid: String): String? {
        // A thread whose counterpart is a bare number. Our own sends used to be filed this
        // way, and there is nothing to ask the account about -- but the reader has that
        // number in their contacts, which is the whole answer.
        if (uuid.startsWith("+")) return addressBookName(uuid)
        return signalStore.contactName(uuid)
            ?: signalStore.contactNumber(uuid)?.let { addressBookName(it) }
    }

    /**
     * Joins a conversation that got split across two threads for one person.
     *
     * A transcript of our own send used to be filed under the recipient's *number* whenever it
     * did not name them by service id, while everything they sent arrived under their service
     * id -- so one conversation sat in the inbox twice, and the half keyed by a number refused
     * every reply, because a number is not a service id. Transcripts are read properly now;
     * this is for the threads that already exist on a phone that ran the old build.
     *
     * Messages are moved, never deleted. The only row that goes is the thread it emptied.
     */
    private fun mergeNumberKeyedThreads() {
        val strays = Realm.getDefaultInstance().use { realm ->
            realm.where(SignalThread::class.java)
                .equalTo("kind", "direct")
                .findAll()
                .map { it.threadKey }
                // Two kinds of half-conversation, folded the same way. A number-keyed thread
                // holds our own sends, filed from a transcript that never named who they went
                // to. A PNI-keyed one is a person Signal would only tell us about by phone
                // number -- writable, but their replies arrive under their account id, so
                // without this one person sits in the inbox as two rows.
                .filter { it.startsWith("direct:+") || it.startsWith("direct:PNI:") }
        }
        if (strays.isEmpty()) return
        // Resolved outside the transaction: the pairing lives in the protocol database, and
        // asking it is not something to do with a Realm write held open.
        val moves = strays.mapNotNull { key ->
            val counterpart = key.removePrefix("direct:")
            val target = if (counterpart.startsWith("PNI:")) {
                // Only ever a pairing the account itself stated, on its own authority; see
                // the note on ProtocolStoreSchema.PNI_ACI for what is deliberately not
                // believed here.
                signalStore.contactAciForPni(counterpart)?.let { "direct:$it" }
            } else {
                signalStore.contactAciForNumber(counterpart)?.let { "direct:$it" }
                    // Or the link the reader made by hand. Saying "this Signal conversation
                    // is the same person as this text conversation" also says which service
                    // id that number belongs to -- which is the one thing the account never
                    // told this phone, and the reason the orphan exists at all.
                    ?: threadLinkedToNumber(counterpart)
            }
            target?.let { key to it }
        }.filter { (from, to) -> from != to }
        if (moves.isEmpty()) return

        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                moves.forEach { (from, to) ->
                    val counterpart = from.removePrefix("direct:")
                    val target = r.where(SignalThread::class.java).equalTo("threadKey", to).findFirst()
                        ?: r.createObject(SignalThread::class.java, to).apply {
                            kind = "direct"
                            counterpartUuid = to.removePrefix("direct:")
                        }
                    // Only a real number is worth carrying across. A PNI is an address, not a
                    // phone number, and putting it in this column would make the merged row
                    // claim a number nobody can dial.
                    if (target.counterpartNumber.isBlank() && counterpart.startsWith("+")) {
                        target.counterpartNumber = counterpart
                    }
                    val old = r.where(SignalThread::class.java).equalTo("threadKey", from).findFirst()
                    if (target.title.isBlank() && old != null && old.title.isNotBlank()) {
                        target.title = old.title
                    }
                    // Snapshot: the rows are being changed by the very field the query
                    // selects on, so a live result would shrink underneath the loop and
                    // leave half the conversation behind.
                    r.where(SignalMessage::class.java)
                        .equalTo("threadKey", from)
                        .findAll()
                        .createSnapshot()
                        .forEach { it.threadKey = to }
                    old?.deleteFromRealm()
                    refreshThreadPreview(r, to)
                }
            }
        }
        Timber.i("signal: joined %d split conversation(s)", moves.size)
    }

    /**
     * The Signal thread whose hand-linked text conversation is reached on [number].
     *
     * Read from the links the reader set, not guessed: they said these two conversations are
     * one person, and a conversation names the number it is carried on. That pairing is
     * exactly what a transcript would have supplied.
     */
    private fun threadLinkedToNumber(number: String): String? {
        if (number.isBlank()) return null
        val all = links()
        val keys = all.keys().asSequence().toList()
        if (keys.isEmpty()) return null
        return Realm.getDefaultInstance().use { realm ->
            keys.firstOrNull { key ->
                val conversationId = all.optLong(key, 0L)
                conversationId != 0L && realm.where(com.wanderwildwood.kotozute.model.Conversation::class.java)
                    .equalTo("id", conversationId)
                    .findFirst()
                    ?.recipients
                    ?.any { phoneNumberUtils.compare(it.address, number) } == true
            }
        }
    }

    private fun renameThreadsFromContacts() {
        mergeNumberKeyedThreads()
        contactsChanged()
        val names = signalStore.contactNames()
        // Not returned on an empty map any more. The account's own names are one source of
        // two now, and the reader's address book -- reached through a number learned from a
        // transcript -- is the one that answers on an account where no name has ever arrived.
        val nameless = Realm.getDefaultInstance().use { realm ->
            realm.where(SignalThread::class.java)
                .equalTo("kind", "direct")
                .findAll()
                .filter { it.title.isBlank() }
                .map { it.counterpartUuid }
                .filter { it.isNotBlank() }
                .distinct()
        }
        // Resolved outside the transaction: each one reads Realm itself, and a nested
        // transaction on the same thread is not a thing.
        val fromAddressBook = nameless
            .filter { names[it].isNullOrBlank() }
            .mapNotNull { uuid -> nameForCounterpart(uuid)?.let { uuid to it } }
            .toMap()
        if (names.isEmpty() && fromAddressBook.isEmpty()) return
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                r.where(SignalThread::class.java).equalTo("kind", "direct").findAll().forEach { thread ->
                    val name = names[thread.counterpartUuid]
                        ?: fromAddressBook[thread.counterpartUuid]
                        ?: return@forEach
                    // Only fills a gap. A title the user's own address book supplied, or one
                    // the bridge resolved, is the better answer and must not be overwritten by
                    // whatever the primary happens to call the same person.
                    if (thread.title.isBlank()) thread.title = name
                }
            }
        }
    }

    // --- registering this phone as its own account ---------------------------------------

    private fun stepToRegistration(step: Any): SignalRepository.Registration = when (step) {
        is com.wanderwildwood.kotozute.signalstore.SignalRegistrar.Step.NeedsCaptcha ->
            SignalRepository.Registration.NeedsCaptcha(step.sessionId)
        is com.wanderwildwood.kotozute.signalstore.SignalRegistrar.Step.CodeSent ->
            SignalRepository.Registration.CodeSent(step.sessionId)
        is com.wanderwildwood.kotozute.signalstore.SignalRegistrar.Step.Registered ->
            SignalRepository.Registration.Registered(step.e164)
        is com.wanderwildwood.kotozute.signalstore.SignalRegistrar.Step.Failed ->
            SignalRepository.Registration.Failed(step.reason)
        else -> SignalRepository.Registration.Failed("unexpected registration state")
    }

    private suspend fun registrationStep(
        body: suspend (com.wanderwildwood.kotozute.signalstore.SignalRegistrar) -> Any
    ): SignalRepository.Registration = try {
        val step = body(signalStore.registrar())
        // Registering ends in the same place linking does -- an account this device can use --
        // so the same things have to follow it, for the same reasons documented on linkDevice.
        if (step is com.wanderwildwood.kotozute.signalstore.SignalRegistrar.Step.Registered) {
            runCatching { signalStore.uploadPreKeys() }
                .onSuccess { Timber.i("signal keys: %s", it) }
                .onFailure { Timber.w(it, "signal keys: could not publish after registering") }
            prefs.signalEnabled.set(true)
            publishState(signalConnected = true, error = null)
            startStream()
        }
        stepToRegistration(step)
    } catch (t: Throwable) {
        Timber.w(t, "signal: registration threw")
        SignalRepository.Registration.Failed(t.message ?: t::class.java.simpleName)
    }

    override suspend fun registerBegin(e164: String) = registrationStep { it.begin(e164) }

    override suspend fun registerCaptcha(sessionId: String, token: String) =
        registrationStep { it.submitCaptcha(sessionId, token) }

    override suspend fun registerResend(sessionId: String, voice: Boolean) =
        registrationStep { it.requestCode(sessionId, voice) }

    override suspend fun registerVerify(sessionId: String, code: String, e164: String) =
        registrationStep { it.verifyAndRegister(sessionId, code, e164) }

    override fun linkDevice(deviceName: String, onUrl: (String) -> Unit): String? = try {
        val result = kotlinx.coroutines.runBlocking {
            signalStore.linker().link(deviceName) { url -> onUrl(url) }
        }
        when (result) {
            is com.wanderwildwood.kotozute.signalstore.DeviceLinker.Result.Linked -> {
                // Linking registers ONE signed pre key and ONE last-resort Kyber key -- that
                // is all the registration request carries. Without a batch of one-time keys
                // every new conversation falls back to the last-resort key, which is reuse and
                // is exactly what one-time keys exist to prevent. Nothing else does this, so
                // omitting it leaves a device permanently on the degraded path while looking
                // entirely healthy.
                runCatching { signalStore.uploadPreKeys() }
                    .onSuccess { Timber.i("signal keys: %s", it) }
                    .onFailure { Timber.w(it, "signal keys: could not publish after linking") }

                // Linking is an explicit act that means "I want Signal on this phone", so the
                // rail goes on with it. Leaving it off left a device that had just linked
                // successfully showing no conversations and no explanation -- the user having
                // to find a second switch to make the first one mean anything.
                //
                // Pairing a bridge stays a separate step, because that one can be done to
                // point at a machine that is not ready yet.
                prefs.signalEnabled.set(true)
                // A fresh link is the cure for every refusal, so the old reason must not
                // outlive it -- otherwise a phone that has just been linked again shows the
                // message telling its owner to link it again.
                prefs.signalRejected.set("")
                // The state has changed in a way nothing else will notice: this device was
                // not a Signal device a moment ago and now is. Publishing it here is what
                // makes the settings screen stop offering to link.
                publishState(signalConnected = true, error = null)
                // And start receiving. Without this the first messages wait for the next
                // launch, which reads as linking not having worked.
                startStream()
                "linked as device ${result.deviceId}"
            }
            is com.wanderwildwood.kotozute.signalstore.DeviceLinker.Result.Failed -> result.reason
        }
    } catch (t: Throwable) {
        Timber.w(t, "signal: linking threw")
        t.message ?: t::class.java.simpleName
    }

    override fun refresh() = runOffThread {
        Timber.i("signal: refresh -- linked=%s", linkedDirectly())
        publishState(
            signalConnected = state.value?.signalConnected ?: false,
            error = state.value?.error
        )
    }

    /**
     * Sends on this device's own authority, and writes the message down.
     *
     * The second half is not optional, and it is the difference from the bridge path. A
     * message we send does not come back to us -- Signal does not deliver a message to the
     * device that sent it -- so nothing else will ever produce this row. The bridge got away
     * without it because the bridge stored the message on its own side and the phone read it
     * back on the next sync.
     */
    private fun sendDirect(threadKey: String, body: String, attachments: List<String>): Long {
        if (threadKey.startsWith("group:")) return sendDirectToGroup(threadKey, body, attachments)
        if (!threadKey.startsWith("direct:")) {
            throw IllegalStateException("cannot send to $threadKey")
        }
        val recipient = threadKey.removePrefix("direct:")
        // Said in words rather than as "not a service id: +1555...". A thread keyed by a
        // number holds only our own sends, filed from a transcript that did not name who
        // they went to; there is no Signal address in it to reply to. It joins the real
        // conversation as soon as one arrives that does.
        if (recipient.startsWith("+")) {
            throw IllegalStateException(
                "This conversation has only a phone number, not a Signal address. " +
                    "Write to them from a new message instead."
            )
        }
        // The conversation's timer goes with it. Not sending one is not neutral: it reads as
        // a timer of zero and switches the other person's disappearing conversation off.
        val (expiresIn, timerVersion) = timerFor(threadKey)
        val timestamp = signalStore.send(recipient, body, attachments, expiresIn, timerVersion)

        val selfAci = signalStore.selfAciOrNull().orEmpty()
        ingest(
            listOf(
                com.wanderwildwood.kotozute.signal.BridgeMessage(
                    // The same (author, timestamp) identity every other device will use for
                    // this message, so a sync of it -- should one ever arrive -- replaces this
                    // row instead of duplicating it.
                    id = "$selfAci:$timestamp",
                    seq = 0,
                    threadKey = threadKey,
                    ts = timestamp,
                    senderUuid = selfAci,
                    senderNumber = "",
                    outgoing = true,
                    body = body,
                    groupId = "",
                    quoteTs = 0,
                    read = true,
                    source = "live",
                    // What we sent, so the row can draw it. Marked not pending: unlike a
                    // received attachment this one is not on disk under an id -- it was
                    // uploaded from the composer's own copy -- so there is nothing to fetch
                    // and nothing to say is missing.
                    attachmentsJson = outgoingAttachmentsJson(attachments),
                    // Our own copy expires too. The clock starts now because this is the
                    // moment we sent it, which is what Signal stamps as the start for an
                    // outgoing message.
                    expiresInSeconds = expiresIn.toLong(),
                    expiresAt = if (expiresIn > 0) timestamp + expiresIn * 1000L else 0L
                )
            )
        )
        return timestamp
    }

    /**
     * Describes what was attached to a message we sent.
     *
     * Deliberately minimal: the type only, and no id. A received attachment records an id the
     * repository can turn into bytes; a sent one has no such copy, and inventing an id that
     * resolves to nothing would make the row claim a file it cannot produce.
     */
    /**
     * Sends to a group over this device's own connection.
     *
     * The master key comes from a message already in the thread: it is what a group message
     * carries, and it is the only handle the server will answer questions about the group
     * with. A thread with no message in it therefore cannot be sent to, which is a real limit
     * and is reported rather than guessed around.
     */
    private fun sendDirectToGroup(threadKey: String, body: String, attachments: List<String>): Long {
        if (attachments.isNotEmpty()) {
            throw IllegalStateException("sending attachments to a group is not supported yet")
        }
        val masterKey = Realm.getDefaultInstance().use { realm ->
            realm.where(SignalMessage::class.java)
                .equalTo("threadKey", threadKey)
                .findAll()
                .firstOrNull { it.groupMasterKey != null }
                ?.groupMasterKey
        } ?: throw IllegalStateException("no group key on this thread yet")

        val (expiresIn, timerVersion) = timerFor(threadKey)
        val timestamp = signalStore.sendToGroup(masterKey, body, expiresIn, timerVersion)
        val selfAci = signalStore.selfAciOrNull().orEmpty()
        ingest(
            listOf(
                com.wanderwildwood.kotozute.signal.BridgeMessage(
                    id = "$selfAci:$timestamp",
                    seq = 0,
                    threadKey = threadKey,
                    ts = timestamp,
                    senderUuid = selfAci,
                    senderNumber = "",
                    outgoing = true,
                    body = body,
                    groupId = threadKey.removePrefix("group:"),
                    quoteTs = 0,
                    read = true,
                    source = "live",
                    attachmentsJson = "",
                    // Our own copy of a group send expires on the group's timer too.
                    expiresInSeconds = expiresIn.toLong(),
                    expiresAt = if (expiresIn > 0) timestamp + expiresIn * 1000L else 0L,
                    groupMasterKey = masterKey
                )
            )
        )
        return timestamp
    }

    private fun outgoingAttachmentsJson(attachments: List<String>): String {
        if (attachments.isEmpty()) return ""
        val array = org.json.JSONArray()
        attachments.forEach { dataUri ->
            val type = dataUri.substringAfter("data:", "").substringBefore(';')
            array.put(
                org.json.JSONObject()
                    .put("id", "")
                    .put("type", type.ifBlank { "application/octet-stream" })
                    .put("filename", "")
                    .put("size", 0)
                    .put("pending", false)
            )
        }
        return array.toString()
    }

    override fun applyReceipts(senderUuid: String, timestamps: List<Long>, read: Boolean): Int {
        if (timestamps.isEmpty()) return 0
        var changed = 0
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                r.where(SignalMessage::class.java)
                    .equalTo("outgoing", true)
                    .`in`("date", timestamps.toTypedArray())
                    .findAll()
                    .forEach { message ->
                        // A read receipt implies delivery -- it cannot have been read without
                        // arriving -- so it fills in a delivery time that may never have been
                        // recorded, rather than leaving a message that is read but not
                        // delivered.
                        if (message.deliveredAt == 0L) {
                            message.deliveredAt = System.currentTimeMillis()
                            changed++
                        }
                        if (read && message.readAt == 0L) {
                            message.readAt = System.currentTimeMillis()
                            changed++
                        }
                    }
            }
        }
        if (changed > 0) Timber.i("signal receipts: %d applied (read=%s)", changed, read)
        return changed
    }

    override fun ingest(messages: List<BridgeMessage>): Int {
        if (messages.isEmpty()) return 0
        val fresh = mutableListOf<BridgeMessage>()
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                messages.forEach { if (store(r, it)) fresh.add(it) }
            }
        }
        announce(fresh)
        // A contacts sync can have landed in the same batch as the messages it names.
        renameThreadsFromContacts()
        runCatching { nameGroupThreads() }.onFailure { Timber.w(it, "signal groups: naming failed") }
        return fresh.size
    }

    /**
     * Fetches from this device's own connection.
     *
     * The direct equivalent of a bridge sync: drain what the server is holding, decrypt, and
     * file through the same [store] the bridge path uses, so both produce one set of threads
     * rather than two.
     */
    // streamWanted, not streamConnected. The flag is set synchronously inside startStream()
    // before any thread exists, whereas streamConnected is set by the loop once it is already
    // running -- so a syncNow() arriving in that window saw "not connected", opened its own
    // reader, and produced exactly the two-reader race this guard exists to prevent. Observed:
    // two "connecting as device 3" lines in the same millisecond, from different threads.
    private fun syncDirect(): Int = if (streamWanted.get()) {
        // The listen loop already owns the socket and is reading it continuously, so there is
        // nothing for a catch-up to do -- and doing it anyway is actively harmful: two threads
        // calling readMessageBatch on one connection race for each message, and the loser sees
        // the read fail. The symptom was a reconnect roughly every seventy seconds with
        // healthy keepalives on either side of it, which looks like a network problem and is
        // not one. The library's own comment warns about exactly this race.
        0
    } else try {
        val summary = signalStore.receive(signalEvents, ::renameThreadsFromContacts)
        Timber.i("signal: direct sync %s", summary)
        prefs.signalLastSync.set(System.currentTimeMillis())
        syncCaughtUp = true
        publishState(signalConnected = true, error = null)
        0
    } catch (t: Throwable) {
        Timber.w(t, "signal: direct sync failed")
        syncCaughtUp = false
        publishState(signalConnected = false, error = t.message)
        0
    }

    override fun syncNow(): Int = syncDirect()

    /**
     * Tops up and rotates this account's keys if either is owed.
     *
     * Deliberately not part of [syncDirect]: that returns early whenever the listen loop owns
     * the socket, which is the steady state, so anything hung off it would never run on a
     * phone that is working properly -- the opposite of what maintenance is for.
     *
     * Cheap when nothing is owed: two count requests and no upload.
     */
    override fun maintainKeys() {
        if (!prefs.signalEnabled.get() || !linkedDirectly()) return
        // What this build can do, told to the server once per run. Cheap and idempotent, and
        // it belongs beside the keys for the same reason: both are claims this device makes
        // about itself that nothing else would ever notice had gone stale.
        runCatching { signalStore.refreshCapabilities() }
            .onSuccess { Timber.i("signal account: %s", it) }
            .onFailure { Timber.w(it, "signal account: could not refresh capabilities") }
        runCatching { signalStore.maintainPreKeys() }
            .onSuccess { Timber.i("signal keys: %s", it) }
            .onFailure {
                // Loud. Failing to keep keys topped up is not visible from the outside: the
                // phone goes on working while every new session opened with it loses the
                // forward secrecy those keys exist to provide.
                Timber.e(it, "signal keys: maintenance failed")
            }
    }

    override fun startStream() {
        if (!prefs.signalEnabled.get() || !isConfigured()) return
        // Claimed before either branch, so both rails take the flag and the generation the
        // same way. stopStream() and a second startStream() then behave identically whichever
        // one is live, and neither can start while the other is running.
        if (!streamWanted.compareAndSet(false, true)) return
        val generation = streamGeneration.incrementAndGet()

        run {
            Timber.i("signal: holding our own socket")
            // Ask once per start. A linked device knows nobody until the primary answers, and
            // the answer arrives through the socket below -- so the ask has to happen before
            // the loop, not as part of it.
            runOffThread {
                runCatching { signalStore.requestContacts() }
                    .onSuccess { Timber.i("signal contacts: %s", it) }
                    .onFailure { Timber.w(it, "signal contacts: could not ask") }
                // And the account's settings, for the same reason: they are volunteered only
                // when they change, so a device that never asks follows its own default rather
                // than the account. Read receipts are the one that shows.
                runCatching { signalStore.requestConfiguration() }
                    .onSuccess { Timber.i("signal configuration: %s", it) }
                    .onFailure { Timber.w(it, "signal configuration: could not ask") }
                // Only while it is missing. This is the account's own key material and there
                // is no reason to have it sent again once it is here.
                // No asking for the account's keys here. An empty contact list does not say
                // whether Signal failed to send one or there is nobody in it, and the keys
                // are not something to fetch on a guess -- they are asked for by a person who
                // can see the list is missing, in Settings. Where they are already here, the
                // list is read, because by then somebody has asked for exactly that.
                if (signalStore.storageKeyKnown()) {
                    runCatching { signalStore.readStorage() }
                        .onSuccess { Timber.i("signal storage: %s", it) }
                        .onFailure { Timber.w(it, "signal storage: could not read") }
                    // Straight after the read, because that is where a write would go and
                    // because a row appearing here *because of* the read is the loop worth
                    // catching. Logged and sent nowhere -- see [logStoragePushDiff].
                    logStoragePushDiff()
                }
            }
            thread(name = "signal-listen-$generation", isDaemon = true) { listenLoop(generation) }
        }
    }

    override fun stopStream() {
        streamWanted.set(false)
        // Retires the running loop as well as clearing the flag, so a loop still alive in a
        // backoff sleep cannot come back round and reconnect.
        streamGeneration.incrementAndGet()
        streamConnected.set(false)
        runCatching { stream?.close() }
        stream = null
        // The socket is shared and outlives any one listen loop, so this is the only place
        // that closes it.
        runCatching { signalStore.disconnect() }
    }

    /**
     * The direct-link equivalent of [streamLoop].
     *
     * Reconnects on failure with the same backoff shape as the bridge loop, and gives up its
     * generation the same way, so the two rails cannot both believe they are current.
     */
    private fun listenLoop(generation: Int) {
        var attempts = 0
        try {
            while (streamWanted.get() && streamGeneration.get() == generation) {
                val connectedAt = System.currentTimeMillis()
                try {
                    streamConnected.set(true)
                    publishState(signalConnected = true, error = null)
                    signalStore.listen(
                        keepGoing = { streamWanted.get() && streamGeneration.get() == generation },
                        events = signalEvents,
                        onNamesLearned = ::renameThreadsFromContacts,
                        onBatch = {
                            Timber.i("signal: received %s", it)
                            // The only place the direct rail can record that traffic is
                            // genuinely flowing. syncDirect() also writes this, but it
                            // returns early whenever this loop owns the socket -- which is
                            // the steady state -- so without this the phone reported "Not
                            // synced yet" forever while receiving messages perfectly well.
                            prefs.signalLastSync.set(System.currentTimeMillis())
                        }
                    )
                    attempts = 0
                } catch (t: Throwable) {
                    if (!streamWanted.get() || streamGeneration.get() != generation) break
                    streamConnected.set(false)

                    // A read that ended after the socket had been up a while is not evidence
                    // of a broken network, and treating it as one is actively harmful: the
                    // backoff grows to seconds and then tens of seconds, and every one of
                    // those is a message arriving late.
                    //
                    // This happens routinely and is not understood: the socket wrapper reports
                    // CONNECTED throughout while the read reports the connection closed, so
                    // the two disagree somewhere inside the library. It recovers immediately
                    // and delivery is unaffected. Backing off would be the only real damage.
                    val wasStable = System.currentTimeMillis() - connectedAt > STABLE_CONNECTION_MS
                    if (wasStable) {
                        Timber.d(t, "signal: read ended after a stable connection; reconnecting")
                        attempts = 0
                        continue
                    }

                    attempts++
                    publishState(signalConnected = false, error = t.message)
                    // Signal's shape, not one invented here: `IncomingMessageObserver` retries
                    // immediately for the first couple of attempts and only then backs off,
                    // through `BackoffUtil.exponentialBackoff(attempts, 30s)`.
                    //
                    // ⚠ The jitter is the part that was missing and is the reason to copy this
                    // rather than reason about it. Without it every device knocked off by the
                    // same event -- a router reboot, a cell handover, the server closing
                    // connections -- comes back in lockstep, and keeps coming back in lockstep
                    // on every subsequent failure. A fixed doubling is a synchronised retry
                    // storm with extra steps.
                    if (attempts > 1) {
                        val wait = reconnectBackoff(attempts)
                        Timber.w(t, "signal: listen failed %d time(s); retrying in %d ms", attempts, wait)
                        Thread.sleep(wait)
                    } else {
                        Timber.w(t, "signal: listen failed; reconnecting")
                    }
                }
            }
        } finally {
            if (streamGeneration.get() == generation) {
                streamConnected.set(false)
                streamWanted.set(false)
            }
        }
    }

    /**
     * How long a connection must have lasted for its ending to count as routine rather than
     * as a failure. Comfortably longer than a connect-and-immediately-fail, and shorter than
     * the read timeout, so a socket that survived a full read window is never treated as a
     * network problem.
     */
    private val STABLE_CONNECTION_MS = 30_000L

    /**
     * How old a message may be when its sender withdraws it.
     *
     * Signal's `normalDeleteMaxAgeInSeconds` default is a day, and `RECEIVE_THRESHOLD` adds
     * another for delivery -- a withdrawal that took a while to arrive is still honest.
     */
    private val WITHDRAWAL_WINDOW_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(2)

    /**
     * `BackoffUtil.exponentialBackoff`, unchanged: two to the power of the attempt in seconds,
     * capped, then multiplied by a random 0.75-1.25.
     *
     * The cap is thirty seconds because that is what `IncomingMessageObserver` passes. This was
     * sixty, doubling from two, with no jitter -- a number chosen here.
     */
    private fun reconnectBackoff(attempts: Int): Long {
        val bounded = attempts.coerceAtMost(30)
        val exponential = Math.pow(2.0, bounded.toDouble()).toLong() * 1000L
        val capped = exponential.coerceAtMost(RECONNECT_MAX_BACKOFF_MS)
        val jitter = 0.75 + (Math.random() * 0.5)
        return (capped * jitter).toLong()
    }

    private val RECONNECT_MAX_BACKOFF_MS = 30_000L

    /**
     * Idempotent by primary key: the same message may arrive more than once. Returns
     * whether a row was actually created, which is what stops a redelivery from ringing.
     */
    private fun store(realm: Realm, m: BridgeMessage): Boolean {
        if (m.id.isBlank()) return false

        // A reaction is not a message. It arrives as its own row -- the bridge cannot move
        // an existing one to the head of the change stream -- and belongs on the message it
        // points at, not in the thread as a bubble of its own.
        if (m.reactionEmoji.isNotEmpty() && m.reactionTarget.isNotEmpty()) {
            applyReaction(realm, m)
            // Never "new" in the sense that rings: a reaction is not a message arriving.
            return false
        }
        val existing = realm.where(SignalMessage::class.java).equalTo("id", m.id).findFirst()
        val isNew = existing == null
        val row = existing ?: realm.createObject(SignalMessage::class.java, m.id)
        row.seq = m.seq
        row.threadKey = m.threadKey
        row.date = m.ts
        row.senderUuid = m.senderUuid
        row.senderNumber = m.senderNumber
        row.outgoing = m.outgoing
        row.body = m.body
        row.groupId = m.groupId
        row.quoteTs = m.quoteTs
        row.read = m.read
        row.source = m.source
        // A view-once attachment is never stored. Signal's promise is that it can be opened
        // once; a copy in Realm is a copy that can be opened for ever. The row stays so the
        // thread does not have a silent hole where a message was.
        row.attachments = if (m.viewOnce) "" else m.attachmentsJson
        row.expiresAt = m.expiresAt
        row.expiresInSeconds = m.expiresInSeconds
        row.viewOnce = m.viewOnce

        val thread = realm.where(SignalThread::class.java)
            .equalTo("threadKey", m.threadKey).findFirst()
            ?: realm.createObject(SignalThread::class.java, m.threadKey).apply {
                kind = if (m.groupId.isNotEmpty()) "group" else "direct"
                counterpartUuid = m.threadKey.substringAfter("direct:", "")
                // A thread created by the bridge arrived with a name, resolved by signal-cli
                // from its own contact store. A thread created by this device's own
                // connection has none -- there is no profile fetching yet -- so it would
                // otherwise show a bare ACI in the inbox. Naming our own account is the one
                // case that needs no lookup, and it is the one a user meets first.
                // Deliberately no group name here. Naming a group means asking the server,
                // and this runs inside a Realm transaction -- a network round trip would hold
                // the write open for as long as the network felt like taking. Groups are named
                // afterwards, in nameGroupThreads(), which also catches the threads that
                // already existed before there was a name to give them.
                title = when {
                    counterpartUuid.isBlank() -> ""
                    counterpartUuid == signalStore.selfAciOrNull() -> noteToSelfTitle
                    // From the primary's contacts sync -- the only place a linked device can
                    // learn a name. Blank until that sync arrives, which the UI renders as the
                    // service id; naming happens again in renameThreadsFromContacts() once it
                    // does, so a thread created before the sync is not stuck nameless.
                    // The account's own name for them, then the reader's own address book
                    // via a number the account knows -- which is the only one that answers
                    // for a person whose client has never shared a profile key.
                    else -> nameForCounterpart(counterpartUuid).orEmpty()
                }
            }
        // Only the newest message speaks for the thread. Messages can arrive out of
        // order -- a reconnect replays by cursor, and an imported backup arrives
        // backwards -- so this is guarded on the timestamp rather than on arrival.
        if (m.ts >= thread.lastTs) {
            thread.lastTs = m.ts
            thread.snippet = previewOf(m)
            thread.snippetOutgoing = m.outgoing
        }
        if (m.groupMasterKey != null) row.groupMasterKey = m.groupMasterKey
        thread.unread = realm.where(SignalMessage::class.java)
            .equalTo("threadKey", m.threadKey)
            .equalTo("outgoing", false)
            .equalTo("read", false)
            .count().toInt()
        return isNew
    }

    /** What the inbox row shows. A picture with no caption still needs to say something. */
    private fun previewOf(m: BridgeMessage): String =
        preview(m.body, m.attachmentsJson, m.viewOnce)

    /** The same, from a stored row -- the sweep re-derives previews from what is left. */
    private fun previewOf(m: SignalMessage): String =
        preview(m.body, m.attachments, m.viewOnce)

    private fun preview(body: String, attachmentsJson: String, viewOnce: Boolean): String = when {
        body.isNotBlank() -> body
        viewOnce -> VIEW_ONCE_PREVIEW
        attachmentsJson.isNotBlank() && attachmentsJson != "[]" -> ATTACHMENT_PREVIEW
        else -> ""
    }

    /**
     * Put a reaction on the message it points at, or take it off again.
     *
     * Stored as JSON on the target rather than as rows of its own: reactions are only ever
     * read while drawing the message they belong to, and a handful per message is not worth
     * a table. Keyed on who reacted, so one person changing their mind replaces their own
     * and two people are two entries.
     *
     * A reaction can outrun its message -- the bridge orders by arrival, not by what the
     * reaction refers to -- and one whose target is not here yet is dropped rather than
     * held. Signal resends nothing, so a queue would be a queue that never drains.
     */
    private fun applyReaction(realm: Realm, m: BridgeMessage) {
        val target = realm.where(SignalMessage::class.java)
            .equalTo("id", m.reactionTarget)
            .findFirst() ?: return

        // Our own reaction is recorded as "me" rather than as this account's uuid. The
        // phone has no copy of that uuid, and the alternative -- asking the bridge for it
        // whenever somebody wants to take a reaction back -- is a network call to answer a
        // question the row already knows the answer to.
        val who = if (m.outgoing) "me" else m.senderUuid.ifBlank { m.senderNumber }
        val existing = runCatching { JSONArray(target.reactions.ifBlank { "[]" }) }
            .getOrElse { JSONArray() }

        val kept = JSONArray()
        for (i in 0 until existing.length()) {
            val e = existing.optJSONObject(i) ?: continue
            if (e.optString("who") != who) kept.put(e)
        }
        if (!m.reactionRemove) {
            kept.put(JSONObject().put("emoji", m.reactionEmoji).put("who", who))
        }
        target.reactions = if (kept.length() == 0) "" else kept.toString()
    }

    /**
     * An unmanaged copy. The managed row belongs to the Realm and the thread that opened
     * it; whoever listens for this is on neither.
     */
    private fun detached(m: BridgeMessage) = SignalMessage().apply {
        id = m.id
        seq = m.seq
        threadKey = m.threadKey
        date = m.ts
        senderUuid = m.senderUuid
        senderNumber = m.senderNumber
        outgoing = m.outgoing
        body = m.body
        groupId = m.groupId
        read = m.read
        source = m.source
        attachments = m.attachmentsJson
    }

    private fun announce(msgs: List<BridgeMessage>) {
        msgs.filter { !it.outgoing }.forEach { incoming.onNext(detached(it)) }
    }

    override fun send(threadKey: String, body: String, attachments: List<String>): Long =
        sendDirect(threadKey, body, attachments)

    /**
     * Takes the account's own settings from its primary.
     *
     * Read receipts are one setting for the whole account in Signal, not a per-device choice,
     * and this is how every other linked device learns it. Before this the setting here was a
     * guess that started at off and never changed -- so this phone could sit telling nobody
     * their messages had been read while the account said to tell them, or the reverse.
     *
     * ⚠ It therefore **overrides what was set on this phone**, which is the behaviour Signal
     * has and the reason the setting's own description had to change: it is no longer a
     * separate thing this device decides.
     */
    private fun applyConfiguration(readReceipts: Boolean?) {
        val wanted = readReceipts ?: return
        if (prefs.signalReadReceipts.get() == wanted) return
        prefs.signalReadReceipts.set(wanted)
        Timber.i("signal configuration: the account says read receipts are %b", wanted)
    }

    /**
     * Removes what the account has deleted on another device, for itself.
     *
     * Not the same gesture as a withdrawal: nobody else is affected and nothing is taken back
     * from anyone. This account tidied its own copy, and this phone holds a copy too.
     *
     * A deleted conversation keeps its thread row. Emptying a conversation and removing it
     * are different things to have done, and a thread that vanishes takes with it the link to
     * its text conversation and whatever the reader had set on it. An empty conversation is
     * also the honest picture: the person is still there to write to.
     */
    private fun applyDeletedElsewhere(
        messages: List<Pair<String, Long>>,
        threads: List<String>
    ) = runOffThread {
        if (messages.isEmpty() && threads.isEmpty()) return@runOffThread
        val touched = mutableSetOf<String>()
        val removedFiles = mutableListOf<String>()
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                messages.forEach { (author, at) ->
                    val row = r.where(SignalMessage::class.java)
                        .equalTo("id", "$author:$at").findFirst() ?: return@forEach
                    touched += row.threadKey
                    removedFiles += attachmentIdsOf(row.attachments)
                    row.deleteFromRealm()
                }
                threads.forEach { key ->
                    val all = r.where(SignalMessage::class.java)
                        .equalTo("threadKey", key)
                        .findAll()
                    if (all.isNotEmpty()) {
                        touched += key
                        removedFiles += all.flatMap { attachmentIdsOf(it.attachments) }
                        // A snapshot, for the same reason every other bulk change here takes
                        // one: deleting from live results takes rows out from under the walk.
                        all.createSnapshot().forEach { it.deleteFromRealm() }
                    }
                }
                touched.forEach { key ->
                    val stillUnread = r.where(SignalMessage::class.java)
                        .equalTo("threadKey", key)
                        .equalTo("outgoing", false)
                        .equalTo("read", false)
                        .count()
                    r.where(SignalThread::class.java).equalTo("threadKey", key).findFirst()
                        ?.unread = stillUnread.toInt()
                    refreshThreadPreview(r, key)
                }
            }
        }
        if (removedFiles.isNotEmpty()) {
            runCatching { signalStore.forgetAttachments(removedFiles) }
                .onFailure { Timber.w(it, "signal delete sync: could not remove attachments") }
        }
        if (touched.isNotEmpty()) {
            Timber.i(
                "signal delete sync: removed %d message(s) and emptied %d conversation(s)",
                messages.size, threads.size
            )
            touched.forEach { removed.onNext(it) }
            contactsChanged()
        }
    }

    /**
     * Removes a message its sender has withdrawn for everyone.
     *
     * The row goes, rather than staying as a note that something was removed. Signal leaves a
     * tombstone; this does not, and the reason is what the gesture means: somebody decided
     * that what they wrote should not be readable, and a bubble still sitting in the thread
     * saying a message was here is a smaller version of the thing they asked to undo. A
     * conversation with a gap is the honest result.
     *
     * A person can only reach their own messages this way. The row is keyed by its author and
     * the timestamp they sent it with, and the author here is the envelope's sender -- so a
     * delete naming somebody else's message resolves to a row that does not exist.
     */
    private fun applyWithdrawal(author: String, sentAt: Long, withdrawnAt: Long) = runOffThread {
        removeWithdrawn("$author:$sentAt", "a message was withdrawn by the person who sent it") { row ->
            // ⚠ Bounded in time, which it was not. Signal accepts a withdrawal only within
            // `normalDeleteMaxAgeInSeconds` (a day) plus a day of slack for delivery --
            // `MessageConstraintsUtil.isValidRemoteDeleteReceive`. Unbounded, "delete for
            // everyone" is a power to reach back and erase any message ever sent to this
            // phone, months later, from the person who sent it. That is not what the
            // gesture is for, and the reader has no way to know it happened.
            //
            // Our own messages are exempt, as they are in Signal: a withdrawal of an
            // outgoing message is this account tidying up after itself on another device,
            // and there is nothing to protect the reader from.
            if (!row.outgoing && withdrawnAt - row.date >= WITHDRAWAL_WINDOW_MS) {
                Timber.w("signal delete: a withdrawal arrived too late to be honoured; keeping the message")
                false
            } else {
                true
            }
        }
    }

    /**
     * Removes a withdrawn message and puts back everything that described it.
     *
     * Shared by a withdrawal that arrives and one this phone sends, because the local half
     * of the gesture is the same either way: the row goes, its attachments go with it, and
     * the thread's preview and unread count are recomputed rather than adjusted.
     *
     * [allow] is asked before anything is removed and is where the two differ -- an arriving
     * withdrawal has a window to check, one we sent has already been checked.
     */
    private fun removeWithdrawn(id: String, note: String, allow: (SignalMessage) -> Boolean) {
        var threadKey: String? = null
        val doomed = mutableListOf<String>()
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val row = r.where(SignalMessage::class.java).equalTo("id", id).findFirst()
                    ?: return@executeTransaction
                if (!allow(row)) return@executeTransaction
                threadKey = row.threadKey
                // Whatever was attached goes with it. Withdrawing a message is somebody
                // unsaying something; leaving the picture on disk unsays nothing.
                doomed += attachmentIdsOf(row.attachments)
                row.deleteFromRealm()
            }
            threadKey?.let { key ->
                realm.executeTransaction { r ->
                    // The preview and the unread count both described a message that is gone.
                    val stillUnread = r.where(SignalMessage::class.java)
                        .equalTo("threadKey", key)
                        .equalTo("outgoing", false)
                        .equalTo("read", false)
                        .count()
                    r.where(SignalThread::class.java).equalTo("threadKey", key).findFirst()
                        ?.unread = stillUnread.toInt()
                    refreshThreadPreview(r, key)
                }
            }
        }
        if (threadKey != null) {
            if (doomed.isNotEmpty()) {
                runCatching { signalStore.forgetAttachments(doomed) }
                    .onFailure { Timber.w(it, "signal delete: could not remove its attachments") }
            }
            Timber.i("signal delete: %s", note)
            threadKey?.let { removed.onNext(it) }
            contactsChanged()
        }
    }

    /**
     * Marks read what the account has already read on another device.
     *
     * A message is identified here by its author and the timestamp it was sent with, which is
     * exactly the pair a read sync carries -- so this resolves to a row directly rather than
     * having to guess at a thread and a cutoff.
     *
     * **No receipts.** The device that did the reading has already told the sender; saying so
     * again from here would tell them twice for one reading. That is the difference between
     * this and [markRead], and it is the whole reason it is not simply that function.
     *
     * Each thread's unread count is recomputed rather than decremented: counting what is
     * actually unread cannot drift, and a decrement applied twice -- a sync redelivered, say --
     * would leave a count that never reaches zero.
     */
    /**
     * Everything the receive path tells this repository, in one place.
     *
     * Each of these used to be a lambda threaded through [SignalStore] into the receiver, and
     * every new thing Signal syncs added one to four files. See [SignalEvents].
     */
    private val signalEvents = object : com.wanderwildwood.kotozute.signalstore.SignalEvents {
        override fun store(
            messages: List<com.wanderwildwood.kotozute.signal.BridgeMessage>
        ): Int = ingest(messages)

        override fun receipts(sender: String, timestamps: List<Long>, read: Boolean) {
            // applyReceipts answers with how many rows it changed, which is of use to the
            // browser rail and to nobody here.
            applyReceipts(sender, timestamps, read)
        }

        override fun readElsewhere(read: List<Pair<String, Long>>) = applyReadElsewhere(read)

        override fun withdrawn(author: String, sentAt: Long, withdrawnAt: Long) =
            applyWithdrawal(author, sentAt, withdrawnAt)

        override fun deletedElsewhere(
            messages: List<Pair<String, Long>>,
            threads: List<String>
        ) = applyDeletedElsewhere(messages, threads)

        override fun configuration(readReceipts: Boolean?) = applyConfiguration(readReceipts)

        override fun groupChanged(masterKey: ByteArray, revision: Int) =
            noteGroupRevision(masterKey, revision)

        override fun timerChanged(threadKey: String, seconds: Long, version: Int) =
            applyTimerChange(threadKey, seconds, version)

        override fun refreshStoredRecords() = rereadStoredRecords()

        override fun rotatePreKeys() {
            runCatching { signalStore.uploadPreKeys() }
                .onSuccess { Timber.i("signal keys: replaced before a retry -- %s", it) }
                .onFailure { Timber.w(it, "signal keys: could not replace before a retry") }
        }
    }

    /**
     * Re-reads the account's stored records because the account asked us to.
     *
     * Off the receive thread: this is a network round trip and the socket is mid-batch. Only
     * where the key is already held -- without it the read cannot succeed, and asking for the
     * key is a deliberate act somebody performs in Settings, not something to do on a push.
     */
    private fun rereadStoredRecords() = runOffThread {
        if (!signalStore.storageKeyKnown()) {
            Timber.d("signal storage: asked to re-read, but the key for it is not here")
            return@runOffThread
        }
        runCatching { signalStore.readStorage() }
            .onSuccess {
                Timber.i("signal storage: re-read on request -- %s", it)
                contactsChanged()
                renameThreadsFromContacts()
            }
            .onFailure { Timber.w(it, "signal storage: could not re-read on request") }
    }

    /**
     * Records a conversation's disappearing-messages timer.
     *
     * Resolved by version, not by arrival order. Signal numbers timer changes so that a late
     * delivery of an older one cannot undo a newer one -- and the server does redeliver. A
     * change carrying no version at all is from an older client and is taken as current,
     * because refusing it would leave the conversation on a timer nobody chose.
     */
    private fun applyTimerChange(threadKey: String, seconds: Long, version: Int) = runOffThread {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val thread = r.where(SignalThread::class.java)
                    .equalTo("threadKey", threadKey)
                    .findFirst() ?: return@executeTransaction
                if (version != 0 && version < thread.expireTimerVersion) {
                    Timber.i("signal timer: ignored a timer change older than the one in force")
                    return@executeTransaction
                }
                thread.expiresInSeconds = seconds
                if (version != 0) thread.expireTimerVersion = version
            }
        }
        Timber.i("signal timer: a conversation's disappearing-messages timer is now %d second(s)", seconds)
        contactsChanged()
    }

    /** The timer to stamp on anything sent into [threadKey], and which version says so. */
    private fun timerFor(threadKey: String): Pair<Int, Int> =
        Realm.getDefaultInstance().use { realm ->
            realm.where(SignalThread::class.java)
                .equalTo("threadKey", threadKey)
                .findFirst()
                ?.let { it.expiresInSeconds.toInt() to it.expireTimerVersion }
                ?: (0 to 0)
        }

    /**
     * The highest group revision this process has already gone and looked at.
     *
     * In memory rather than in the database, which costs one extra fetch per group after a
     * restart and saves a schema change. The wrong way to be wrong: a fetch too many is a
     * round trip, where a fetch too few is a group wearing the wrong name indefinitely.
     */
    private val groupRevisions = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Re-reads a group whose revision has moved past what this device has seen.
     *
     * A group's name was only ever filled in when it was blank, so a group renamed after this
     * phone first met it kept the old name for good. Asking the server about every group on
     * every batch would fix that at the cost of a round trip per group per batch; the revision
     * number a message carries is exactly what makes the cheap version possible.
     */
    private fun noteGroupRevision(masterKey: ByteArray, revision: Int) {
        val id = android.util.Base64.encodeToString(masterKey, android.util.Base64.NO_WRAP)
        val seen = groupRevisions[id]
        if (seen != null && revision <= seen) return
        groupRevisions[id] = revision
        runOffThread {
            val group = runCatching { signalStore.groupFor(masterKey) }.getOrNull() ?: return@runOffThread
            val title = group.title.takeIf { it.isNotBlank() } ?: ""
            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction { r ->
                    r.where(SignalThread::class.java)
                        .equalTo("kind", "group")
                        .findAll()
                        .filter { thread ->
                            r.where(SignalMessage::class.java)
                                .equalTo("threadKey", thread.threadKey)
                                .findAll()
                                .any { it.groupMasterKey?.contentEquals(masterKey) == true }
                        }
                        // Set when it differs, not only when it is blank. That difference is
                        // the whole point: a rename that never arrives is the bug.
                        .forEach { thread ->
                            if (thread.title != title) {
                                Timber.i("signal group: a group has been renamed")
                                thread.title = title
                            }
                            // The group's timer travels with its state. Without this a group
                            // thread only ever learned its timer if somebody happened to
                            // change it while this phone was listening.
                            if (thread.expiresInSeconds != group.expiresInSeconds) {
                                thread.expiresInSeconds = group.expiresInSeconds
                            }
                        }
                }
            }
            contactsChanged()
        }
    }

    private fun applyReadElsewhere(read: List<Pair<String, Long>>) = runOffThread {
        if (read.isEmpty()) return@runOffThread
        val ids = read.map { (sender, at) -> "$sender:$at" }
        val readAt = System.currentTimeMillis()
        val touched = mutableSetOf<String>()
        val cleared = mutableSetOf<String>()
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                ids.forEach { id ->
                    val row = r.where(SignalMessage::class.java).equalTo("id", id).findFirst()
                    if (row != null && !row.read) {
                        row.read = true
                        // Read elsewhere is still read, so the clock starts here too --
                        // otherwise a disappearing message read on her own phone would sit
                        // here for ever, never counted down and never removed.
                        if (row.expiresInSeconds > 0 && row.expiresAt == 0L) {
                            row.expiresAt = readAt + row.expiresInSeconds * 1000L
                        }
                        touched += row.threadKey
                    }
                }
                touched.forEach { key ->
                    val stillUnread = r.where(SignalMessage::class.java)
                        .equalTo("threadKey", key)
                        .equalTo("outgoing", false)
                        .equalTo("read", false)
                        .count()
                    r.where(SignalThread::class.java).equalTo("threadKey", key)
                        .findFirst()?.unread = stillUnread.toInt()
                    // Only once the conversation is genuinely clear. Dismissing while
                    // something in it is still unread would hide a message nobody has seen.
                    if (stillUnread == 0L) cleared += key
                }
            }
        }
        if (touched.isNotEmpty()) {
            Timber.i(
                "signal read sync: %d message(s) already read elsewhere, in %d conversation(s)",
                ids.size, touched.size
            )
            contactsChanged()
            // The notification is the other half of "already read". Announced here rather than
            // cancelled here: notifications belong to the presentation layer.
            cleared.forEach { readElsewhere.onNext(it) }
        }
    }

    /**
     * All of this runs off the caller's thread. The Realm here is configured to refuse
     * writes on the UI thread, and this is reached straight from a change listener, which
     * is delivered on the main looper.
     */
    override fun markRead(threadKey: String, upToTs: Long) = runOffThread {
        // Taken before they are changed, and only the ones this call changes. Afterwards
        // nothing tells a message read a moment ago from one read last year, and the cost of
        // that difference is a receipt for every message in the thread, every time a new one
        // arrives -- telling somebody over and over that their whole history has just been
        // read.
        // Grouped by whoever wrote each message, not by the conversation. See below: in a
        // group every author gets a receipt for their own messages, which is what Signal does
        // and what keying off the thread made impossible.
        val justRead = mutableListOf<Pair<String, Long>>()
        val now = System.currentTimeMillis()
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val unread = r.where(SignalMessage::class.java)
                    .equalTo("threadKey", threadKey)
                    .equalTo("outgoing", false)
                    .equalTo("read", false)
                    .lessThanOrEqualTo("date", upToTs)
                    .findAll()
                // A snapshot, because `read` is what the query filters on: setting it while
                // walking the live results takes rows out from under the iteration and
                // silently skips half of them.
                unread.createSnapshot().forEach { message ->
                    justRead += message.senderUuid to message.date
                    message.read = true
                    // Reading is what starts a disappearing message's clock. Until now it
                    // started when the message arrived, so a short timer could run out while
                    // the phone sat in a pocket and the message was deleted unseen.
                    if (message.expiresInSeconds > 0 && message.expiresAt == 0L) {
                        message.expiresAt = now + message.expiresInSeconds * 1000L
                    }
                }
                r.where(SignalThread::class.java).equalTo("threadKey", threadKey)
                    .findFirst()?.unread = 0
            }
        }
        // The receipt goes out on this device's own connection, and only where the reader
        // asked for receipts to be sent. A receipt names the messages by the timestamps they
        // were sent with, which is what was just collected.
        if (!prefs.signalReadReceipts.get()) return@runOffThread
        if (justRead.isEmpty()) return@runOffThread

        // ⚠ One receipt per author, not one per conversation. This used to return early for
        // anything that was not a `direct:` thread, so reading a group told nobody -- with
        // receipts switched on, and with no way for the reader to know their group messages
        // were being treated differently from everyone else's.
        //
        // `MarkReadReceiver` groups what was just read by thread and then by the recipient who
        // sent each message, and enqueues a job per sender. A group thread is not a special
        // case there; it simply has more than one author in it.
        justRead.groupBy({ it.first }, { it.second })
            .forEach { (sender, timestamps) ->
                if (sender.isBlank()) return@forEach
                runCatching { signalStore.sendReadReceipt(sender, timestamps.distinct()) }
                    .onSuccess {
                        Timber.i("signal receipt: told %s about %d message(s)", "somebody", timestamps.size)
                    }
                    .onFailure { Timber.d("read receipt not delivered: ${it.message}") }
            }
    }

    /**
     * Bytes for an attachment.
     *
     * Downloaded when the message arrived and kept on disk, so this is a local read. An
     * attachment that was never downloaded is gone: its pointer was good for a window on
     * Signal's CDN and that window has closed.
     */
    override fun loadAttachment(id: String): ByteArray? = signalStore.readAttachment(id)

    /**
     * Links the user has made by hand between a Signal thread and an SMS conversation.
     *
     * Held as a small JSON object in a preference and read on each call rather than
     * cached: it changes only when someone sets one, and being certain it is current
     * matters more than the microseconds.
     */
    private fun links(): JSONObject =
        runCatching { JSONObject(prefs.signalThreadLinks.get()) }.getOrElse { JSONObject() }

    override fun linkedConversationId(threadKey: String): Long? =
        links().optLong(threadKey, 0L).takeIf { it != 0L }

    override fun linkedThreadKeyFor(conversationId: Long): String? {
        val all = links()
        return all.keys().asSequence().firstOrNull { all.optLong(it, 0L) == conversationId }
    }

    override fun linkConversation(threadKey: String, conversationId: Long?) {
        val all = links()
        // One SMS conversation belongs to at most one Signal thread. Without this, linking
        // a second Signal thread to the same conversation would leave the first pointing
        // at it too, and crossing back would land on whichever the map happened to yield.
        if (conversationId != null) {
            all.keys().asSequence().toList()
                .filter { all.optLong(it, 0L) == conversationId }
                .forEach { all.remove(it) }
            all.put(threadKey, conversationId)
        } else {
            all.remove(threadKey)
        }
        prefs.signalThreadLinks.set(all.toString())
    }

    override fun findThreadForNumber(number: String): SignalThread? {
        if (number.isBlank()) return null
        Realm.getDefaultInstance().use { realm ->
            val threads = realm.where(SignalThread::class.java)
                .equalTo("kind", "direct")
                .findAll()

            // The number, and only the number.
            //
            // This used to fall back to every other number on the same address-book card,
            // so that someone who changed numbers and left Signal on the old one still
            // crossed between their two threads. That is deliberately gone: two rails
            // reached on two different numbers are left as two conversations, because
            // keeping them apart is a thing a person may well want, and the app cannot
            // tell that intent from an oversight. Same number, one person, one crossing;
            // anything less certain than that is not asserted.
            //
            // Detached: the caller is on another thread and outlives this Realm.
            return threads
                .firstOrNull { phoneNumberUtils.compare(it.counterpartNumber, number) }
                ?.let { realm.copyFromRealm(it) }
        }
    }

    override fun senderNamesFor(threadKey: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Realm.getDefaultInstance().use { realm ->
            val senders = realm.where(SignalMessage::class.java)
                .equalTo("threadKey", threadKey)
                .equalTo("outgoing", false)
                .findAll()
                .mapNotNull { m ->
                    m.senderUuid.takeIf { it.isNotBlank() }?.let { it to m.senderNumber }
                }
                .toMap()
            if (senders.isEmpty()) return emptyMap()

            val contacts = realm.where(Contact::class.java).findAll()
            senders.forEach { (uuid, number) ->
                // The address book first, the same order the thread titles use, so one
                // person is named the same way wherever they appear.
                val fromContacts = number
                    .takeIf { it.isNotBlank() }
                    ?.let { n ->
                        contacts.firstOrNull { c ->
                            c.numbers.any { phoneNumberUtils.compare(it.address, n) }
                        }?.name
                    }
                    ?.takeIf { it.isNotBlank() }

                // Then whatever their own direct thread is called -- that already carries
                // Signal's profile name for them.
                val fromThread = realm.where(SignalThread::class.java)
                    .equalTo("threadKey", "direct:" + uuid)
                    .findFirst()
                    ?.title
                    ?.takeIf { it.isNotBlank() }

                out[uuid] = fromContacts ?: fromThread ?: number.ifBlank { uuid.take(SignalDirectory.SHORT_SERVICE_ID) }
            }
        }
        return out
    }

    override fun getThreads(archived: Boolean): RealmResults<SignalThread> =
        Realm.getDefaultInstance()
            .where(SignalThread::class.java)
            .equalTo("archived", archived)
            // A conversation list lists conversations. Signal's directory gives a row for
            // every contact it knows, which on this account was 53 people never messaged
            // against 2 who had been; undated, they piled up at the bottom of the inbox and
            // filled the Signal list entirely. They stay in the store as the directory for
            // starting a new conversation -- see threadDirectory.
            .greaterThan("lastTs", 0L)
            .sort("lastTs", Sort.DESCENDING)
            .findAllAsync()

    /**
     * Everyone Signal knows about on this account, conversation or not, by name. This is
     * what the conversation list deliberately does not show: the people you have never
     * messaged. Sorted by how they read, since there is no recency to sort by.
     */
    override fun searchThreads(query: String): List<SignalSearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return Realm.getDefaultInstance().use { realm ->
            // Only threads that are conversations. The directory holds a row per contact,
            // and offering "no messages" rows as search results would bury the real hits.
            val threads = realm.where(SignalThread::class.java)
                .greaterThan("lastTs", 0L)
                .findAll()

            val matches = threads.mapNotNull { thread ->
                val hits = realm.where(SignalMessage::class.java)
                    .equalTo("threadKey", thread.threadKey)
                    .unexpired()
                    .contains("body", q, Case.INSENSITIVE)
                    .sort("date", Sort.DESCENDING)
                    .findAll()
                val byName = thread.title.contains(q, ignoreCase = true)
                when {
                    hits.isNotEmpty() -> SignalSearchHit(
                        realm.copyFromRealm(thread), hits.size, hits.first()?.body.orEmpty()
                    )
                    // A thread whose name matches is a result even with no matching message,
                    // the same way the SMS side treats a conversation title.
                    byName -> SignalSearchHit(realm.copyFromRealm(thread), 0, thread.snippet)
                    else -> null
                }
            }
            // Name matches first, then by how much matched: the same order the SMS results
            // arrive in, so the merged list does not read as two lists stapled together.
            matches.sortedWith(compareBy({ it.messages > 0 }, { -it.messages }))
        }
    }

    override fun isBlocked(threadKey: String): Boolean = runCatching {
        threadKey.startsWith("direct:") && signalStore.isBlocked(threadKey.removePrefix("direct:"))
    }.getOrDefault(false)

    /**
     * Asks Signal for the account's contact list, the way modern Signal keeps it.
     *
     * Deliberately a thing somebody does rather than a thing that happens: it fetches the
     * account's key material, and an account whose contacts arrive the ordinary way should
     * never send it. Returns a sentence saying what happened, including when the answer has
     * to arrive later.
     */
    override fun fetchContactsFromSignal(): String = when {
        !linkedDirectly() -> "This phone is not linked to Signal yet"
        signalStore.storageKeyKnown() -> signalStore.readStorage().also { contactsChanged() }
        else -> {
            val asked = runCatching { signalStore.requestKeys() }.getOrElse { it.message.orEmpty() }
            Timber.i("signal keys: %s", asked)
            // The answer comes back through the socket, and reading the list follows it; see
            // the callback in SignalStore.
            "Asked Signal for the contact list. It arrives in a moment, if your Signal answers"
        }
    }

    /**
     * Every number in the phone's address book, in the one format discovery accepts.
     *
     * Read from the app's own mirror of the address book rather than the content provider:
     * it is already synced, already on a worker's schedule, and it is what the inbox resolves
     * names against, so the two cannot disagree about who is in the book.
     *
     * Deduplicated, because a person with a mobile and a home number listed the same way is
     * one number to ask about, and the account is charged per number.
     */
    private fun addressBookNumbers(): Set<String> = runCatching {
        Realm.getDefaultInstance().use { realm ->
            realm.where(com.wanderwildwood.kotozute.model.Contact::class.java)
                .findAll()
                .flatMap { contact -> contact.numbers.mapNotNull { it.address } }
                .mapNotNullTo(mutableSetOf()) { phoneNumberUtils.toE164(it) }
        }
    }.getOrElse {
        Timber.w(it, "signal discovery: could not read the address book")
        emptySet()
    }

    override fun discoverContactsByNumber(): String = when {
        !linkedDirectly() -> "This phone is not linked to Signal yet"
        else -> {
            val numbers = addressBookNumbers()
            if (numbers.isEmpty()) {
                "There are no phone numbers in this phone's contacts to look up"
            } else {
                signalStore.discover(numbers).also { contactsChanged() }
            }
        }
    }

    override fun shouldOfferContactFetch(): Boolean = runCatching {
        SignalDirectory.shouldOfferContactFetch(
            linkedDirectly = linkedDirectly(),
            storageKeyKnown = signalStore.storageKeyKnown(),
            anyNamesKnown = signalStore.contactNames().isNotEmpty()
        )
    }.getOrDefault(false)

    override fun selfNumber(): String = runCatching { signalStore.selfNumberOrNull() }
        .getOrNull().orEmpty()

    override fun actOnPerson(
        threadKey: String,
        action: SignalRepository.PersonAction
    ): Boolean = runCatching {
        when (action) {
            SignalRepository.PersonAction.ARCHIVE -> setArchived(threadKey, true)
            SignalRepository.PersonAction.UNARCHIVE -> setArchived(threadKey, false)
            SignalRepository.PersonAction.PIN -> setPinned(threadKey, true)
            SignalRepository.PersonAction.UNPIN -> setPinned(threadKey, false)
            SignalRepository.PersonAction.MUTE -> setMuted(threadKey, true)
            SignalRepository.PersonAction.UNMUTE -> setMuted(threadKey, false)
            SignalRepository.PersonAction.UNREAD -> markUnread(threadKey)
            SignalRepository.PersonAction.BLOCK -> setBlocked(threadKey, true)
            SignalRepository.PersonAction.UNBLOCK -> setBlocked(threadKey, false)
            SignalRepository.PersonAction.DELETE -> deleteThread(threadKey)
        }
        // And the same to the text half, where the row stands for both. Reading is the one
        // thing deliberately left apart -- the two are separate screens and reading one is
        // not a claim to have read the other -- so it is not in this list.
        linkedConversationId(threadKey)?.takeIf { it != 0L }?.let { id ->
            when (action) {
                SignalRepository.PersonAction.ARCHIVE -> conversations.markArchived(id)
                SignalRepository.PersonAction.UNARCHIVE -> conversations.markUnarchived(listOf(id))
                SignalRepository.PersonAction.PIN -> conversations.markPinned(id)
                SignalRepository.PersonAction.UNPIN -> conversations.markUnpinned(id)
                SignalRepository.PersonAction.MUTE -> prefs.notifications(id).set(false)
                SignalRepository.PersonAction.UNMUTE -> prefs.notifications(id).set(true)
                SignalRepository.PersonAction.UNREAD -> messages.markUnread(listOf(id))
                SignalRepository.PersonAction.BLOCK ->
                    conversations.markBlocked(listOf(id), prefs.blockingManager.get(), null)
                SignalRepository.PersonAction.UNBLOCK -> conversations.markUnblocked(id)
                SignalRepository.PersonAction.DELETE -> conversations.deleteConversations(id)
            }
        }
        true
    }.onFailure { Timber.w(it, "signal: %s on a person failed", action) }.getOrDefault(false)

    override fun deleteThread(threadKey: String): Int {
        var removed = 0
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val messages = r.where(SignalMessage::class.java)
                    .equalTo("threadKey", threadKey)
                    .findAll()
                removed = messages.size
                messages.deleteAllFromRealm()
                r.where(SignalThread::class.java)
                    .equalTo("threadKey", threadKey)
                    .findAll()
                    .deleteAllFromRealm()
            }
        }
        Timber.i("signal: a conversation was deleted from this phone, %d message(s)", removed)
        return removed
    }

    override fun canBlock(): Boolean = runCatching { signalStore.blockedListKnown() }.getOrDefault(false)

    override fun isLockedBackup(folder: String): Boolean = runCatching {
        com.wanderwildwood.kotozute.signalstore.TreeExportSource(
            context, android.net.Uri.parse(folder)
        ).meta() != null
    }.getOrDefault(false)

    override fun importHistory(
        folder: String,
        key: String,
        onProgress: (Int) -> Unit
    ): SignalRepository.ImportStats {
        val tree = com.wanderwildwood.kotozute.signalstore.TreeExportSource(
            context, android.net.Uri.parse(folder)
        )
        // A folder this app wrote is sealed, and says so in a header that is not itself
        // secret. A Signal Desktop export has no such header and is read as it always was --
        // both have to keep working, because one is what people arrive with and the other is
        // what they leave with.
        val source = when (val meta = tree.meta()) {
            null -> tree
            else -> {
                val header = runCatching { JSONObject(meta) }.getOrNull()
                    ?: throw SignalRepository.NotAnExport()
                val salt = runCatching {
                    android.util.Base64.decode(header.getString("salt"), android.util.Base64.DEFAULT)
                }.getOrNull() ?: throw SignalRepository.NotAnExport()

                // A header with no `key` was written before copies were locked with the
                // account, and is thirty digits. Nothing about an old copy changes.
                val lockedToAccount = header.optString(
                    "key",
                    com.wanderwildwood.kotozute.signalstore.EncryptedExportDestination.DIGITS
                ) == com.wanderwildwood.kotozute.signalstore.EncryptedExportDestination.ACCOUNT

                val lock = if (lockedToAccount) {
                    // Nothing to ask for: the key is the account's, and this phone is on it.
                    val backupKey = signalStore.messageBackupKey()
                        ?: throw SignalRepository.BackupKeyNeeded()
                    com.wanderwildwood.kotozute.signalstore.EncryptedExportDestination
                        .Lock.Account(backupKey)
                } else {
                    if (key.isBlank()) throw SignalRepository.BackupKeyNeeded()
                    com.wanderwildwood.kotozute.signalstore.EncryptedExportDestination
                        .Lock.Digits(key)
                }
                com.wanderwildwood.kotozute.signalstore.EncryptedExportSource(tree, lock, salt)
            }
        }
        // The account's own identifiers. The bridge had to be told these -- an export
        // carries a profile and settings but no identifier for the account itself -- and it
        // is the reason importing needed an operator at a keyboard. On the phone they are
        // simply known: this device is the account.
        val importer = com.wanderwildwood.kotozute.signalstore.SignalHistoryImporter(
            source = source,
            sink = RealmImportSink(),
            selfUuid = signalStore.selfAciOrNull().orEmpty(),
            selfNumber = signalStore.selfNumberOrNull().orEmpty()
        )
        val stats = try {
            importer.run(onProgress)
        } catch (e: com.wanderwildwood.kotozute.signalstore.SignalHistoryImporter.NotAnExport) {
            throw SignalRepository.NotAnExport()
        } catch (e: javax.crypto.AEADBadTagException) {
            // The bytes did not authenticate: the wrong digits, or a folder that has been
            // altered since it was written. There is no telling those apart from here, and
            // the answer is the same either way.
            throw SignalRepository.WrongBackupKey()
        } catch (e: java.io.EOFException) {
            throw SignalRepository.WrongBackupKey()
        }
        // The same two passes a sync ends with: a thread named from this phone's own address
        // book beats one named from the export, and a group that arrived nameless can be
        // asked about now that it has messages in it.
        renameThreadsFromContacts()
        runCatching { nameGroupThreads() }.onFailure { Timber.w(it, "signal groups: naming failed") }
        Timber.i("signal import: %d message(s), %d already present", stats.messages, stats.alreadyPresent)
        return SignalRepository.ImportStats(
            messages = stats.messages,
            alreadyPresent = stats.alreadyPresent,
            attachments = stats.attachments,
            attachmentsLost = stats.attachmentsLost,
            skippedEvents = stats.skippedEvents,
            skippedDeleted = stats.skippedDeleted,
            skippedExpired = stats.skippedExpired,
            skippedNoThread = stats.skippedNoThread,
            skippedNoAuthor = stats.skippedNoAuthor,
            skippedUnknownGroup = stats.skippedUnknownGroup
        )
    }

    override fun exportHistory(
        folder: String,
        onProgress: (Int) -> Unit
    ): SignalRepository.ExportStats {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val tree = com.wanderwildwood.kotozute.signalstore.TreeExportDestination(
            context, android.net.Uri.parse(folder), "kotozute-backup-$day"
        )
        // ⚠ Not a secret invented here any more. Signal derives a backup key from the account
        // -- `AccountEntropyPool.deriveMessageBackupKey()` -- so a copy opens on any device
        // that can reach the account, and there is nothing to show once and nothing to lose.
        // Thirty digits generated at the moment of writing and stored nowhere meant a copy
        // died with the piece of paper, which is the failure a backup exists to prevent.
        //
        // The account has to have answered the KEYS request first. It is the same key the
        // storage service needs, so a phone that has read the account's records has it.
        val backupKey = signalStore.messageBackupKey()
            ?: throw IllegalStateException("the account has not sent its keys yet")
        val destination = com.wanderwildwood.kotozute.signalstore.EncryptedExportDestination(
            tree,
            com.wanderwildwood.kotozute.signalstore.EncryptedExportDestination.Lock.Account(backupKey)
        )
        destination.writeMeta()
        val stats = com.wanderwildwood.kotozute.signalstore.SignalHistoryExporter(
            source = RealmExportSource(),
            destination = destination,
            selfUuid = signalStore.selfAciOrNull().orEmpty()
        ).run(onProgress)
        Timber.i(
            "signal export: %d message(s) in %d thread(s), %d attachment(s), %d missing",
            stats.messages, stats.threads, stats.attachments, stats.missing
        )
        return SignalRepository.ExportStats(
            threads = stats.threads,
            messages = stats.messages,
            attachments = stats.attachments,
            missing = stats.missing,
            folder = stats.folder,
            // Empty, and that is the change: there is no longer a secret for the reader to
            // carry out of this screen. The copy is locked to the account.
            key = ""
        )
    }

    /**
     * The phone's side of an export.
     *
     * One Realm instance for the whole run, opened on the thread the export runs on: it is
     * a consistent snapshot, so a message arriving while a history is being written cannot
     * land halfway through and be written twice or not at all.
     */
    private inner class RealmExportSource :
        com.wanderwildwood.kotozute.signalstore.SignalHistoryExporter.Source {

        override fun threads(): List<com.wanderwildwood.kotozute.signalstore.SignalHistoryExporter.Source.Thread> =
            Realm.getDefaultInstance().use { realm ->
                realm.where(SignalThread::class.java)
                    .findAll()
                    .map { thread ->
                        com.wanderwildwood.kotozute.signalstore.SignalHistoryExporter.Source.Thread(
                            key = thread.threadKey,
                            title = thread.title,
                            number = thread.counterpartNumber
                        )
                    }
            }

        override fun eachMessage(
            threadKey: String,
            consume: (com.wanderwildwood.kotozute.signalstore.SignalHistoryExporter.Source.Message) -> Unit
        ) {
            Realm.getDefaultInstance().use { realm ->
                realm.where(SignalMessage::class.java)
                    .equalTo("threadKey", threadKey)
                    .findAll()
                    // Oldest first, and by id where two share a timestamp -- which is
                    // ordinary in a group. Sorting on the timestamp alone leaves the order
                    // of a tie to the database, and a backup that reorders a conversation
                    // every time it is written is a backup nobody can compare.
                    .sort(arrayOf("date", "id"), arrayOf(Sort.ASCENDING, Sort.ASCENDING))
                    .forEach { message ->
                        consume(
                            com.wanderwildwood.kotozute.signalstore.SignalHistoryExporter.Source.Message(
                                ts = message.date,
                                senderUuid = message.senderUuid,
                                outgoing = message.outgoing,
                                body = message.body,
                                read = message.read,
                                quoteTs = message.quoteTs,
                                expiresAt = message.expiresAt,
                                expiresInSeconds = message.expiresInSeconds,
                                attachmentsJson = message.attachments
                            )
                        )
                    }
            }
        }

        override fun attachment(
            id: String
        ): com.wanderwildwood.kotozute.signalstore.SignalHistoryExporter.Source.Attachment? {
            val bytes = signalStore.readAttachment(id) ?: return null
            return com.wanderwildwood.kotozute.signalstore.SignalHistoryExporter.Source.Attachment(
                size = bytes.size.toLong(),
                open = { java.io.ByteArrayInputStream(bytes) }
            )
        }
    }

    /**
     * The phone's side of an import.
     *
     * Deliberately not [ingest]: that announces what it stored, and a history arriving is not
     * news -- it would ring for every message in it. It writes through the same [store] so
     * there is still one place that turns a message into a row.
     */
    private inner class RealmImportSink :
        com.wanderwildwood.kotozute.signalstore.SignalHistoryImporter.Sink {

        override fun groupThreadsByTitle(): Map<String, String> =
            Realm.getDefaultInstance().use { realm ->
                realm.where(SignalThread::class.java)
                    .equalTo("kind", "group")
                    .findAll()
                    .filter { it.title.isNotBlank() }
                    .groupBy { it.title.lowercase() }
                    // Two groups can be called the same thing, and `associate` would hand the
                    // importer whichever one Realm happened to return last -- a silent choice
                    // between two conversations. A title that names more than one of them
                    // names none: the importer treats the group as unplaceable and says so.
                    .filterValues { it.size == 1 }
                    .mapValues { (_, threads) -> threads.first().threadKey }
            }

        override fun insert(messages: List<BridgeMessage>): Int {
            var inserted = 0
            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction { r ->
                    messages.forEach { message ->
                        // Insert-only. store() updates a row it finds, which is right for a
                        // message arriving again over the wire and wrong here: an imported
                        // copy would overwrite what live delivery knows about the same
                        // message -- its read state, an attachment it actually downloaded.
                        val held = r.where(SignalMessage::class.java)
                            .equalTo("id", message.id)
                            .findFirst() != null
                        if (!held && store(r, message)) inserted++
                    }
                }
            }
            return inserted
        }

        override fun nameThreadIfUnnamed(threadKey: String, title: String) {
            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction { r ->
                    r.where(SignalThread::class.java)
                        .equalTo("threadKey", threadKey)
                        .findFirst()
                        ?.let { thread -> if (thread.title.isBlank()) thread.title = title }
                }
            }
        }

        override fun storeAttachment(name: String, open: () -> java.io.InputStream): String? =
            signalStore.keepImportedAttachment(name, open)
    }

    override fun people(): List<SignalRepository.Person> {
        val threads = Realm.getDefaultInstance().use { realm ->
            realm.where(SignalThread::class.java)
                .equalTo("kind", "direct")
                .findAll()
                .map { thread ->
                    SignalDirectory.Row(
                        uuid = thread.counterpartUuid
                            .ifBlank { thread.threadKey.substringAfter("direct:", "") },
                        name = thread.title,
                        number = thread.counterpartNumber
                    )
                }
        }

        val contacts = signalStore.contactDirectory().map { contact ->
            SignalDirectory.Row(
                uuid = contact.serviceId,
                name = contact.name.orEmpty(),
                number = contact.e164.orEmpty(),
                username = contact.username.orEmpty()
            )
        }

        return SignalDirectory.merge(threads, contacts, signalStore.selfAciOrNull()) { number ->
            addressBookName(number)
        }
    }

    /**
     * What this phone knows about the account it is on.
     *
     * The number and the service id come from the account store. The list of devices does
     * not: that is a question for the server, and the bridge used to ask it. Rather than
     * show a device list that is a guess, this shows the two things it can stand behind and
     * says what it is.
     */
    override fun account(): SignalAccount = SignalAccount(
        number = signalStore.selfNumberOrNull().orEmpty(),
        selfUuid = signalStore.selfAciOrNull().orEmpty(),
        devices = emptyList(),
        thisDeviceId = signalStore.deviceId()
    )

    override fun identity(threadKey: String): SignalIdentity {
        run {
            val aci = threadKey.removePrefix("direct:")
            val local = signalStore.identityFor(aci)
                ?: return SignalIdentity("", "")
            return SignalIdentity(
                local.safetyNumber,
                when (local.trustLevel) {
                    0 -> "UNTRUSTED"
                    2 -> "TRUSTED_VERIFIED"
                    else -> "TRUSTED_UNVERIFIED"
                }
            )
        }
    }

    override fun acceptIdentity(threadKey: String): Boolean {
        if (!threadKey.startsWith("direct:")) return false
        return signalStore.acceptIdentity(threadKey.removePrefix("direct:"))
    }

    override fun react(messageId: String, emoji: String, remove: Boolean) {

        // Signal names a message by who wrote it and when they sent it. Our id is a thing
        // this app made up, so the real identifiers are read off the row -- and for a
        // message we sent ourselves the author is this account, which the row records as
        // outgoing rather than by writing our own uuid into senderUuid.
        val selfAci = signalStore.selfAciOrNull().orEmpty()
        val (threadKey, author, ts, groupKey) = Realm.getDefaultInstance().use { realm ->
            val row = realm.where(SignalMessage::class.java).equalTo("id", messageId).findFirst()
                ?: throw IllegalStateException("no such message")
            // Our own account when the message being reacted to is ours. The bridge used to
            // fill this in from its own side, which is why it was left empty here; on this
            // rail there is nobody else to know it.
            val who = com.wanderwildwood.kotozute.signalstore.SignalBlockList.targetAuthor(
                row.outgoing, row.senderUuid, row.senderNumber, selfAci
            )
            // The key a group is reached by arrives on a message and nowhere else, so it is
            // read from the thread's rows rather than from the thread.
            val master = realm.where(SignalMessage::class.java)
                .equalTo("threadKey", row.threadKey)
                .findAll()
                .firstOrNull { it.groupMasterKey != null }
                ?.groupMasterKey
            Reacting(row.threadKey, who, row.date, master)
        }
        if (author.isBlank()) throw IllegalStateException("nothing says who wrote that message")

        if (threadKey.startsWith("group:")) {
            val master = groupKey ?: throw IllegalStateException("no group key on this thread yet")
            signalStore.sendReactionToGroup(master, emoji, remove, author, ts)
        } else {
            signalStore.sendReaction(threadKey.removePrefix("direct:"), emoji, remove, author, ts)
        }

        // Written here, because nothing sends it back. The bridge rail had signal-cli echo a
        // reaction through the sync stream moments later and that echo was the one truth;
        // this device's own send is not echoed to itself, so the emoji would otherwise land
        // everywhere except the screen it was tapped on.
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                applyReaction(
                    r,
                    BridgeMessage(
                        id = "", seq = 0, threadKey = threadKey, ts = ts,
                        senderUuid = selfAci, senderNumber = "", outgoing = true, body = "",
                        groupId = "", quoteTs = 0, read = true, source = "live",
                        attachmentsJson = "",
                        reactionEmoji = emoji, reactionTarget = messageId, reactionRemove = remove
                    )
                )
            }
        }
        // No announcing: the row is managed, and the thread screen is listening to the Realm
        // results it came from.
    }

    /**
     * Takes one of this account's own messages back, for everyone it was sent to.
     *
     * Signal's `MessageSender.sendRemoteDelete` marks the message deleted locally and *then*
     * enqueues the send, because its send is a retrying job and the row has to show the
     * user's decision immediately. This one sends first and removes afterwards: there is no
     * job queue here, so a failed send has to leave the message standing and say so, rather
     * than blank it on this screen and nowhere else.
     *
     * The window is Signal's own and is checked again here, not only in the menu -- a dialog
     * left open across midnight is enough for the two to disagree.
     */
    override fun withdraw(messageId: String) {
        val selfAci = signalStore.selfAciOrNull().orEmpty()
        if (selfAci.isBlank()) throw IllegalStateException("this phone is not on a Signal account")

        val (threadKey, sentAt, groupKey) = Realm.getDefaultInstance().use { realm ->
            val row = realm.where(SignalMessage::class.java).equalTo("id", messageId).findFirst()
                ?: throw IllegalStateException("no such message")
            if (!SignalRepository.canWithdraw(row.outgoing, row.date)) {
                throw IllegalStateException("that message can no longer be taken back")
            }
            val master = realm.where(SignalMessage::class.java)
                .equalTo("threadKey", row.threadKey)
                .findAll()
                .firstOrNull { it.groupMasterKey != null }
                ?.groupMasterKey
            Withdrawing(row.threadKey, row.date, master)
        }

        if (threadKey.startsWith("group:")) {
            val master = groupKey ?: throw IllegalStateException("no group key on this thread yet")
            signalStore.sendRemoteDeleteToGroup(master, sentAt)
        } else {
            signalStore.sendRemoteDelete(threadKey.removePrefix("direct:"), sentAt)
        }

        // Only now. Our own other devices hear about this through the sent transcript the
        // send itself carries, the same way they hear about a message.
        // It announces the thread itself -- see [removeWithdrawn].
        removeWithdrawn(messageId, "a message was taken back") { true }
    }

    /** What a withdrawal needs off the row it is taking back. */
    private data class Withdrawing(
        val threadKey: String,
        val sentAt: Long,
        val groupMasterKey: ByteArray?
    )

    /** What a reaction needs off the row it is hung on. */
    private data class Reacting(
        val threadKey: String,
        val author: String,
        val ts: Long,
        val groupMasterKey: ByteArray?
    )

    override fun setBlocked(threadKey: String, blocked: Boolean) {
        // Not runOffThread: this one has to be able to fail in front of the caller. The
        // others are local writes that cannot really go wrong; this one leaves the phone.
        if (!threadKey.startsWith("direct:")) {
            throw IllegalStateException("only a person can be blocked from here, not a group")
        }
        // The blocked list belongs to the account and syncs whole, so this device has to have
        // been given it before it can send one back. Ask, and say plainly that the answer has
        // not arrived -- the alternative is sending a list of one, which unblocks everybody
        // else on the account and announces nothing.
        if (!signalStore.blockedListKnown()) {
            runCatching { signalStore.requestBlockedList() }
                .onFailure { Timber.w(it, "signal blocked: could not ask for the list") }
            throw IllegalStateException("this phone has not been given the blocked list yet")
        }
        if (!signalStore.setBlocked(threadKey.removePrefix("direct:"), blocked)) {
            throw IllegalStateException("Signal would not take the change")
        }
    }

    override fun setPinned(threadKey: String, pinned: Boolean) = runOffThread {
        editThread(threadKey) { it.pinned = pinned }
    }

    override fun setMuted(threadKey: String, muted: Boolean) = runOffThread {
        editThread(threadKey) { it.muted = muted }
        markNeedsSync(threadKey)
    }

    override fun markUnread(threadKey: String) = runOffThread {
        // The newest incoming message, not the thread's counter. thread.unread is recomputed
        // from message read-state every time a message lands, so a counter set by hand is
        // wiped by the next arrival -- the feature would work until the moment it mattered.
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val newest = r.where(SignalMessage::class.java)
                    .equalTo("threadKey", threadKey)
                    .equalTo("outgoing", false)
                    .sort("date", Sort.DESCENDING)
                    .findFirst()
                if (newest != null) {
                    newest.read = false
                    r.where(SignalThread::class.java)
                        .equalTo("threadKey", threadKey)
                        .findFirst()?.unread = r.where(SignalMessage::class.java)
                        .equalTo("threadKey", threadKey)
                        .equalTo("outgoing", false)
                        .equalTo("read", false)
                        .count().toInt()
                }
            }
        }
    }

    override fun isMuted(threadKey: String): Boolean =
        Realm.getDefaultInstance().use { realm ->
            realm.where(SignalThread::class.java)
                .equalTo("threadKey", threadKey)
                .findFirst()?.muted == true
        }

    /**
     * Notes that a conversation now differs from what the account's records hold.
     *
     * ⚠ The mark goes on the **recipient row**, not on the conversation -- even though the
     * value that changed (muted, archived) lives in Realm. That is Signal's model, not a
     * convenience: `ThreadTable.setArchived` resolves its threads back to their recipients and
     * calls `markNeedsSync`, which is `rotateStorageId` and nothing else. One dirty flag, in
     * one place, whatever table holds the value -- otherwise every store that can hold part of
     * a conversation needs its own, and the sync has to consult all of them.
     *
     * ⚠ Groups too, which they were not. A group's state rides a GroupV2Record rather than a
     * ContactRecord, and that difference was being read as "the dirty flag does not apply" --
     * so muting or archiving a group was a local change nothing recorded. Signal keeps groups
     * as rows in the same `RecipientTable` for exactly this reason, and the flag does not care
     * which kind of record the value will eventually travel in.
     */
    private fun markNeedsSync(threadKey: String) {
        runCatching {
            when {
                threadKey.startsWith("direct:") ->
                    signalStore.rotateStorageId(threadKey.removePrefix("direct:"))
                threadKey.startsWith("group:") ->
                    signalStore.rotateStorageIdForGroup(threadKey.removePrefix("group:"))
                else -> return
            }
        }.onFailure { Timber.w(it, "signal storage: could not mark a conversation for a push") }
    }

    /**
     * What a write to the storage service would carry, written to the log and sent nowhere.
     *
     * Step 2 of `docs/DECISION-storage-write.md`, and the reason it comes before the write:
     * the marks are set from a handful of places and the only way to know they are set in the
     * right ones -- and nowhere else -- is to watch what accumulates over a few days of
     * ordinary use. A row that appears here after merely *reading* the account's records is
     * the loop `StorageSyncLoopDetector` exists to catch, showing up where it costs nothing.
     *
     * ⚠ Not a field-level diff against the manifest, and cannot be one yet. This app keeps no
     * copy of the record the account holds -- the read path applies what arrives and discards
     * it -- so what this can say is "these rows are marked, and here is what they would go up
     * as", not "this field differs". Keeping the remote record is part of step 3, not this.
     *
     * Nothing anybody is named is logged. What is useful here is the shape and the count; who
     * is in a conversation is not, and a log is read in places a thread is not.
     */
    private fun logStoragePushDiff() {
        val pending = runCatching { signalStore.needingStoragePush() }
            .onFailure { Timber.w(it, "signal storage: could not read what is marked for a push") }
            .getOrNull() ?: return
        if (pending.isEmpty()) {
            Timber.i("signal storage: nothing is marked for a push")
            return
        }

        val (groups, people) = pending.partition { it.groupId != null }
        Timber.i(
            "signal storage: %d record(s) would be written -- %d contact, %d group",
            pending.size, people.size, groups.size
        )

        Realm.getDefaultInstance().use { realm ->
            pending.forEach { row ->
                val threadKey = row.groupId?.let { "group:$it" } ?: "direct:${row.serviceId}"
                val thread = realm.where(SignalThread::class.java)
                    .equalTo("threadKey", threadKey)
                    .findFirst()
                val blocked = runCatching {
                    if (row.groupId != null) isBlocked(threadKey)
                    else row.serviceId?.let { signalStore.isBlocked(it) } ?: false
                }.getOrDefault(false)

                Timber.i(
                    "signal storage:   %s record -- named=%b profileKey=%b muted=%b archived=%b blocked=%b",
                    if (row.groupId != null) "group" else "contact",
                    !row.name.isNullOrBlank(),
                    row.hasProfileKey,
                    thread?.muted ?: false,
                    thread?.archived ?: false,
                    blocked
                )
            }
        }
    }

    private fun editThread(threadKey: String, block: (SignalThread) -> Unit) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                r.where(SignalThread::class.java)
                    .equalTo("threadKey", threadKey)
                    .findFirst()?.let(block)
            }
        }
    }

    override fun setArchived(threadKey: String, archived: Boolean) = runOffThread {
        markNeedsSync(threadKey)
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                r.where(SignalThread::class.java)
                    .equalTo("threadKey", threadKey)
                    .findFirst()?.archived = archived
            }
        }
    }

    override fun getThreadsSnapshot(archived: Boolean): List<SignalThread> =
        Realm.getDefaultInstance().use { realm ->
            realm.copyFromRealm(
                realm.where(SignalThread::class.java)
                    .equalTo("archived", archived)
                    .greaterThan("lastTs", 0L)
                    .sort("lastTs", Sort.DESCENDING)
                    .findAll()
            )
        }

    override fun getMessagesSnapshot(threadKey: String, limit: Int): List<SignalMessage> =
        Realm.getDefaultInstance().use { realm ->
            val all = realm.where(SignalMessage::class.java)
                .equalTo("threadKey", threadKey)
                .unexpired()
                .sort("date", Sort.ASCENDING)
                .findAll()
            // The tail, like the SMS side: a long thread is re-fetched every few seconds.
            val from = maxOf(0, all.size - limit.coerceAtLeast(1))
            realm.copyFromRealm(all.subList(from, all.size))
        }

    override fun getMessageAt(threadKey: String, date: Long): SignalMessage? =
        Realm.getDefaultInstance().use { realm ->
            realm.where(SignalMessage::class.java)
                .equalTo("threadKey", threadKey)
                .equalTo("date", date)
                .findFirst()
                ?.let(realm::copyFromRealm)
        }

    override fun countMessages(threadKey: String): Int =
        Realm.getDefaultInstance().use { realm ->
            realm.where(SignalMessage::class.java)
                .equalTo("threadKey", threadKey)
                .unexpired()
                .findAll()
                .size
        }

    override fun getMessages(threadKey: String): RealmResults<SignalMessage> =
        Realm.getDefaultInstance()
            .where(SignalMessage::class.java)
            .equalTo("threadKey", threadKey)
            .unexpired()
            .sort("date", Sort.ASCENDING)
            .findAllAsync()

    /** Written at the end of a sync, read by the worker that scheduled it. */
    @Volatile private var syncCaughtUp: Boolean = true

    override fun lastSyncCaughtUp(): Boolean = syncCaughtUp

    override fun connectionState(): Observable<SignalRepository.ConnectionState> = state

    override fun newIncoming(): Observable<SignalMessage> = incoming

    override fun messagesRemoved(): Observable<String> = removed

    override fun conversationsRead(): Observable<String> = readElsewhere

    private fun publishState(
        signalConnected: Boolean,
        error: String?,
    ) {
        state.onNext(
            SignalRepository.ConnectionState(
                configured = isConfigured(),
                linkedDirectly = linkedDirectly(),
                undecryptable = undecryptableCount(),
                contactSummary = runCatching { signalStore.contactSummary() }.getOrDefault(""),
                undecryptableReasons =
                    runCatching { signalStore.undecryptableReasons() }.getOrDefault(emptyList()),
                enabled = prefs.signalEnabled.get(),
                signalConnected = signalConnected,
                lastSyncedAt = prefs.signalLastSync.get(),
                error = error,
                rejected = prefs.signalRejected.get().takeIf { it.isNotBlank() },
            )
        )
    }

}

/**
 * Everything whose disappearing deadline has not passed. Applied on every read as well as by
 * the sweep: a message must be gone from view the moment its time is up, not whenever a
 * timer next happens to fire.
 */
private fun io.realm.RealmQuery<SignalMessage>.unexpired(): io.realm.RealmQuery<SignalMessage> =
    beginGroup()
        .equalTo("expiresAt", 0L)
        .or()
        .greaterThan("expiresAt", System.currentTimeMillis())
        .endGroup()
