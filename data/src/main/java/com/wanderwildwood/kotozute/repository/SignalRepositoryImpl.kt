package com.wanderwildwood.kotozute.repository

import com.wanderwildwood.kotozute.model.Contact
import com.wanderwildwood.kotozute.model.SignalMessage
import com.wanderwildwood.kotozute.model.SignalThread
import com.wanderwildwood.kotozute.signal.BridgeClient
import com.wanderwildwood.kotozute.signal.BridgeConfig
import com.wanderwildwood.kotozute.signal.BridgeMessage
import com.wanderwildwood.kotozute.signal.isTerminalBridgeFailure
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
    private val phoneNumberUtils: PhoneNumberUtils
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
    private fun useBridge(): Boolean = config() != null

    /**
     * How many envelopes are sitting undecrypted.
     *
     * Cheap and swallowing: this runs on every state publish, and a store that will not open
     * must not make the settings screen fail -- the number is a diagnostic, not a fact the
     * app depends on.
     */
    private fun undecryptableCount(): Int =
        if (useBridge()) 0 else runCatching { signalStore.undecryptableCount() }.getOrDefault(0)

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
            configured = false, enabled = false, bridgeReachable = false,
            signalConnected = false, lastSyncedAt = 0
        )
    )

    private val incoming = io.reactivex.subjects.PublishSubject.create<SignalMessage>()

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

    /**
     * Bumped whenever the pairing is torn down. A sync already in flight checks it between
     * pages and abandons the rest.
     *
     * stopStream() only closes the SSE connection; it cannot reach a syncNow() that is
     * midway through paging the bridge. Unpair therefore used to stop the stream, wipe the
     * store, and then have the sync it did not interrupt write every message straight back.
     */
    private val pairingEpoch = AtomicInteger(0)

    init {
        publishState(reachable = false, signalConnected = false, error = null)
    }

    private fun config(): BridgeConfig? {
        val host = prefs.signalBridgeHost.get()
        val token = prefs.signalBridgeToken.get()
        val fp = prefs.signalBridgeFingerprint.get()
        val cfg = BridgeConfig(host, prefs.signalBridgePort.get(), token, fp)
        return if (cfg.isValid()) cfg else null
    }

    /**
     * Configured by **either** route.
     *
     * Every screen keys its Signal UI off this, and it used to mean "a bridge is paired".
     * Leaving it that way would have left a device that is itself linked to the account
     * showing no Signal at all -- messages arriving into Realm and nothing displaying them,
     * which is exactly what happened.
     */
    override fun isConfigured(): Boolean = config() != null || linkedDirectly()

    override fun pair(payload: String): Boolean {
        val cfg = BridgeConfig.parse(payload) ?: return false
        prefs.signalBridgeHost.set(cfg.host)
        prefs.signalBridgePort.set(cfg.port)
        prefs.signalBridgeToken.set(cfg.token)
        prefs.signalBridgeFingerprint.set(cfg.fingerprint)
        publishState(reachable = false, signalConnected = false, error = null)
        return true
    }

    override fun stopUsingBridge() = runOffThread {
        // The in-flight sync checks this between pages, so it stops paging a bridge that is
        // about to be forgotten.
        pairingEpoch.incrementAndGet()
        stopStream()

        prefs.signalBridgeHost.set("")
        prefs.signalBridgeToken.set("")
        prefs.signalBridgeFingerprint.set("")
        // The cursor and instance describe a position in *that* bridge's stream and mean
        // nothing without it. Messages and threads are deliberately untouched: they are the
        // user's conversations, not the bridge's cache.
        prefs.signalCursor.set(0L)
        prefs.signalBridgeInstance.set("")
        prefs.signalLastSync.set(0L)

        // useBridge() is false from here, so this starts the device's own socket rather than
        // reconnecting to the bridge that just went away.
        publishState(reachable = false, signalConnected = false, error = null)
        if (prefs.signalEnabled.get()) startStream()
    }

    override fun unpair() = runOffThread {
        // Before anything else: a sync in flight is paging the bridge right now, and it
        // checks this between pages.
        pairingEpoch.incrementAndGet()
        stopStream()
        prefs.signalEnabled.set(false)
        prefs.signalBridgeHost.set("")
        prefs.signalBridgeToken.set("")
        prefs.signalBridgeFingerprint.set("")
        prefs.signalCursor.set(0L)
        prefs.signalBridgeInstance.set("")
        prefs.signalLastSync.set(0L)
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                it.delete(SignalMessage::class.java)
                it.delete(SignalThread::class.java)
            }
        }
        publishState(reachable = false, signalConnected = false, error = null)
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
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val dead = r.where(SignalMessage::class.java)
                    .greaterThan("expiresAt", 0L)
                    .lessThanOrEqualTo("expiresAt", System.currentTimeMillis())
                    .findAll()
                removed = dead.size
                if (removed == 0) return@executeTransaction
                val touched = dead.map { it.threadKey }.distinct()
                dead.deleteAllFromRealm()
                // A thread whose newest message just vanished would otherwise keep showing
                // it as the preview on the inbox row.
                touched.forEach { key -> refreshThreadPreview(r, key) }
            }
        }
        if (removed > 0) Timber.i("signal: %d expired message(s) removed", removed)
        return removed
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
            reachable = state.value?.bridgeReachable ?: false,
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
        if (useBridge()) return
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

    private fun renameThreadsFromContacts() {
        val names = signalStore.contactNames()
        if (names.isEmpty()) return
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                r.where(SignalThread::class.java).equalTo("kind", "direct").findAll().forEach { thread ->
                    val name = names[thread.counterpartUuid] ?: return@forEach
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
            publishState(reachable = true, signalConnected = true, error = null)
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
                // The state has changed in a way nothing else will notice: this device was
                // not a Signal device a moment ago and now is. Publishing it here is what
                // makes the settings screen stop offering to link.
                publishState(reachable = true, signalConnected = true, error = null)
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
        Timber.i(
            "signal: refresh -- bridge=%s linked=%s configured=%s",
            config() != null, linkedDirectly(), isConfigured()
        )
        publishState(
            reachable = state.value?.bridgeReachable ?: false,
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
        val timestamp = signalStore.send(recipient, body, attachments)

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
                    attachmentsJson = outgoingAttachmentsJson(attachments)
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

        val timestamp = signalStore.sendToGroup(masterKey, body)
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
        val summary = signalStore.receive({ ingest(it) }, ::renameThreadsFromContacts, ::applyReceipts)
        Timber.i("signal: direct sync %s", summary)
        prefs.signalLastSync.set(System.currentTimeMillis())
        syncCaughtUp = true
        publishState(reachable = true, signalConnected = true, error = null)
        0
    } catch (t: Throwable) {
        Timber.w(t, "signal: direct sync failed")
        syncCaughtUp = false
        publishState(reachable = false, signalConnected = false, error = t.message)
        0
    }

    override fun syncNow(): Int {
        // A directly linked device fetches for itself. The bridge is only consulted when
        // there is no link -- asking both would deliver every message twice, and while
        // store() would deduplicate them, the two would still race to write the same rows.
        if (!useBridge() && linkedDirectly()) return syncDirect()

        val cfg = config() ?: return 0
        val client = BridgeClient(cfg)
        var written = 0
        // Adopting existing history is not the same as receiving news. On the very first
        // sync the bridge hands over everything it holds, and announcing all of it would
        // greet someone who has just finished setting Signal up with a screen of
        // notifications about conversations they already know about. A later catch-up
        // does announce: those are messages genuinely missed.
        val firstSync = prefs.signalCursor.get() == 0L
        val epoch = pairingEpoch.get()
        try {
            val remote = client.state()

            // A cursor is only meaningful against the store that issued it. If the bridge
            // has been rebuilt or moved, its sequence numbers started again and ours points
            // past everything it will ever have -- so the phone would sit silent forever,
            // waiting for a number that is not coming. Start over instead; the inserts are
            // idempotent, so re-reading what we already hold costs nothing.
            val knownInstance = prefs.signalBridgeInstance.get()
            if (remote.instance.isNotBlank() && knownInstance != remote.instance) {
                if (knownInstance.isNotBlank()) {
                    Timber.i("bridge store changed; restarting from the beginning")
                    prefs.signalCursor.set(0L)
                }
                prefs.signalBridgeInstance.set(remote.instance)
            }
            // Refresh thread titles first, so a new message never lands in an unnamed thread.
            val threads = client.threads()
            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction { r ->
                    // Resolved once per sync rather than per drawn row: the address
                    // book rarely moves and a lookup per bind would run on every scroll.
                    val contacts = r.where(Contact::class.java).findAll()

                    threads.forEach { t ->
                        val row = r.where(SignalThread::class.java)
                            .equalTo("threadKey", t.threadKey).findFirst()
                            ?: r.createObject(SignalThread::class.java, t.threadKey)
                        row.kind = t.kind
                        if (t.lastTs > row.lastTs) row.lastTs = t.lastTs
                        row.counterpartUuid = t.threadKey.substringAfter("direct:", "")
                        if (t.counterpartNumber.isNotBlank()) {
                            row.counterpartNumber = t.counterpartNumber
                        }

                        // Prefer the name this phone already knows the person by. Signal's
                        // own profile name is the fallback, and a bare number the last
                        // resort -- otherwise the same person reads differently depending
                        // on which rail their message came in on.
                        val local = row.counterpartNumber
                            .takeIf { it.isNotBlank() && t.kind == "direct" }
                            ?.let { number ->
                                contacts.firstOrNull { c ->
                                    c.numbers.any { phoneNumberUtils.compare(it.address, number) }
                                }?.name?.takeIf { n -> n.isNotBlank() }
                            }
                        row.title = local ?: t.title

                        // Threads that existed before previews did, and any created from
                        // the directory rather than from a message, have nothing to show.
                        if (row.snippet.isBlank()) {
                            r.where(SignalMessage::class.java)
                                .equalTo("threadKey", row.threadKey)
                                .sort("date", Sort.DESCENDING)
                                .findFirst()
                                ?.let { newest ->
                                    row.snippet = newest.body.ifBlank {
                                        when {
                                            newest.viewOnce -> VIEW_ONCE_PREVIEW
                                            newest.attachments.isNotBlank() &&
                                                newest.attachments != "[]" -> ATTACHMENT_PREVIEW
                                            else -> ""
                                        }
                                    }
                                    row.snippetOutgoing = newest.outgoing
                                }
                        }
                    }
                }
            }

            var cursor = prefs.signalCursor.get()
            while (true) {
                // Between pages, not only at the start: unpair can land at any point in a
                // long catch-up, and everything after it would otherwise be written into a
                // store the user has just emptied.
                if (pairingEpoch.get() != epoch) {
                    Timber.i("signal: sync abandoned, the pairing changed under it")
                    return written
                }
                val (msgs, maxSeq) = client.changes(cursor, 200)
                if (msgs.isEmpty()) {
                    if (maxSeq > cursor) prefs.signalCursor.set(maxSeq)
                    break
                }
                val fresh = mutableListOf<BridgeMessage>()
                Realm.getDefaultInstance().use { realm ->
                    realm.executeTransaction { r ->
                        msgs.forEach { if (store(r, it)) fresh.add(it) }
                    }
                }
                if (!firstSync) announce(fresh)
                written += msgs.size
                cursor = msgs.maxOf { it.seq }
                prefs.signalCursor.set(cursor)
                if (cursor >= maxSeq) break
            }

            prefs.signalLastSync.set(System.currentTimeMillis())
            // Did the catch-up actually reach what the bridge said it was holding? The loop
            // above ends either because it drew level or because a page stopped early, and
            // those two look identical from outside. Saying which lets the worker try again
            // now instead of leaving the phone quietly behind until the next round.
            val caughtUp = prefs.signalCursor.get() >= remote.maxSeq
            if (!caughtUp) {
                Timber.w("signal: sync stopped short, cursor=%d bridge=%d",
                    prefs.signalCursor.get(), remote.maxSeq)
            }
            syncCaughtUp = caughtUp
            publishState(reachable = true, signalConnected = remote.signalConnected, error = null)
        } catch (t: Throwable) {
            Timber.w(t, "signal sync failed")
            // A sync that threw did not draw level, whatever it managed before it stopped.
            syncCaughtUp = false
            // Only when nothing better is known. syncNow() runs from other threads -- the
            // conversations screen fires one on every creation -- and one timed-out call
            // used to publish "cannot reach the bridge" straight over a live stream's
            // healthy state. Nothing republishes on a timer and the bridge's keepalive is a
            // comment line that never reaches onMessage, so on an account nobody happened
            // to be messaging, both Signal screens sat with the composer disabled until
            // someone else sent something.
            if (!streamConnected.get()) {
                publishState(
                    reachable = false,
                    signalConnected = false,
                    error = t.message,
                    rejected = isTerminalBridgeFailure(t)
                )
            }
        }
        return written
    }

    override fun startStream() {
        if (!prefs.signalEnabled.get() || !isConfigured()) return
        // Claimed before either branch, so both rails take the flag and the generation the
        // same way. stopStream() and a second startStream() then behave identically whichever
        // one is live, and neither can start while the other is running.
        if (!streamWanted.compareAndSet(false, true)) return
        val generation = streamGeneration.incrementAndGet()

        // A bridge's stream is server-sent events from another machine. A directly linked
        // device holds its own websocket to Signal instead. Same shape, different socket.
        if (!useBridge()) {
            Timber.i("signal: linked directly; holding our own socket")
            // Ask once per start. A linked device knows nobody until the primary answers, and
            // the answer arrives through the socket below -- so the ask has to happen before
            // the loop, not as part of it.
            runOffThread {
                runCatching { signalStore.requestContacts() }
                    .onSuccess { Timber.i("signal contacts: %s", it) }
                    .onFailure { Timber.w(it, "signal contacts: could not ask") }
            }
            thread(name = "signal-listen-$generation", isDaemon = true) { listenLoop(generation) }
        } else {
            thread(name = "signal-stream-$generation", isDaemon = true) { streamLoop(generation) }
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
        // The direct rail's socket is shared and outlives any one listen loop, so this is the
        // only place that closes it.
        if (!useBridge()) runCatching { signalStore.disconnect() }
    }

    /**
     * The direct-link equivalent of [streamLoop].
     *
     * Reconnects on failure with the same backoff shape as the bridge loop, and gives up its
     * generation the same way, so the two rails cannot both believe they are current.
     */
    private fun listenLoop(generation: Int) {
        var backoff = 2_000L
        try {
            while (streamWanted.get() && streamGeneration.get() == generation) {
                val connectedAt = System.currentTimeMillis()
                try {
                    streamConnected.set(true)
                    publishState(reachable = true, signalConnected = true, error = null)
                    signalStore.listen(
                        keepGoing = { streamWanted.get() && streamGeneration.get() == generation },
                        file = { ingest(it) },
                        onNamesLearned = ::renameThreadsFromContacts,
                        receipts = { sender, timestamps, read -> applyReceipts(sender, timestamps, read) },
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
                    backoff = 2_000L
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
                        backoff = 2_000L
                        continue
                    }

                    Timber.w(t, "signal: listen failed; retrying in %d ms", backoff)
                    publishState(reachable = false, signalConnected = false, error = t.message)
                    Thread.sleep(backoff)
                    // Capped, because a phone that has been out of signal for an hour should
                    // not then wait an hour more once it is back.
                    backoff = (backoff * 2).coerceAtMost(60_000L)
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

    private fun streamLoop(generation: Int) {
        try {
            streamLoopInner(generation)
        } finally {
            // Whatever ended this -- a normal stop or something thrown -- the flag must not
            // be left set. startStream() refuses to start a second loop while it is, so a
            // thread that died holding it would mean the stream could never be revived.
            //
            // Only if this loop is still the current one, though. A retired loop clearing
            // the flag would stop whichever loop replaced it.
            if (streamGeneration.get() == generation) {
                streamConnected.set(false)
                streamWanted.set(false)
            }
        }
    }

    private fun streamLoopInner(generation: Int) {
        var backoff = 2_000L
        while (streamWanted.get() && streamGeneration.get() == generation) {
            val cfg = config()
            if (cfg == null) { streamWanted.set(false); return }

            syncNow() // catch up before going live

            val done = java.util.concurrent.CountDownLatch(1)
            // Why the stream ended, so the loop below can tell a refusal from a dropped
            // network. Written on the stream's own thread and read on this one, which is
            // safe because the write happens before the countDown that releases the await
            // below -- the latch is the ordering, not a lucky read.
            var closedBy: Throwable? = null
            val client = BridgeClient(cfg)
            try {
                // Assigned to the shared field only while this loop is still the current
                // one, so a retired loop cannot overwrite its replacement's connection with
                // one nothing can close.
                if (streamGeneration.get() != generation) return
                stream = client.openEvents(
                    sinceSeq = prefs.signalCursor.get(),
                    onMessage = { msg ->
                        var isNew = false
                        Realm.getDefaultInstance().use { realm ->
                            realm.executeTransaction { r -> isNew = store(r, msg) }
                        }
                        if (isNew) announce(listOf(msg))
                        if (msg.seq > prefs.signalCursor.get()) prefs.signalCursor.set(msg.seq)
                        prefs.signalLastSync.set(System.currentTimeMillis())
                        streamConnected.set(true)
                        publishState(reachable = true, signalConnected = true, error = null)
                    },
                    onClosed = { err ->
                        streamConnected.set(false)
                        if (err != null) Timber.d("signal stream closed: ${err.message}")
                        closedBy = err
                        done.countDown()
                    }
                )
                backoff = 2_000L
                // The connection is open; nothing has necessarily arrived on it yet, and on
                // a quiet account nothing will for hours. Say so now rather than waiting for
                // a message to prove it, or the composer sits disabled on a working link.
                streamConnected.set(true)
                publishState(reachable = true, signalConnected = true, error = null)
                done.await()
            } catch (t: Throwable) {
                Timber.w(t, "signal stream failed")
                closedBy = t
            }

            streamConnected.set(false)
            // A stream that is refused keeps being refused, and the backoff below would
            // retry it every minute for as long as the phone is on without ever saying
            // why. Publishing it is what turns that into something visible.
            publishState(
                reachable = false,
                signalConnected = false,
                error = closedBy?.message.takeIf { isTerminalBridgeFailure(closedBy) },
                rejected = isTerminalBridgeFailure(closedBy)
            )
            if (!streamWanted.get() || streamGeneration.get() != generation) return
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

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
                    else -> signalStore.contactName(counterpartUuid).orEmpty()
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

    override fun send(threadKey: String, body: String, attachments: List<String>): Long {
        if (!useBridge() && linkedDirectly()) return sendDirect(threadKey, body, attachments)
        val cfg = config() ?: throw IllegalStateException("no bridge paired")
        try {
            return BridgeClient(cfg).send(threadKey, body, attachments)
        } catch (t: Throwable) {
            // Pressing send is the moment someone is most likely to be looking, and it can
            // easily come before any poll has noticed. Publish here rather than let them
            // watch a failure the rest of the app has not heard about yet.
            if (isTerminalBridgeFailure(t)) {
                publishState(reachable = false, signalConnected = false, error = t.message, rejected = true)
            }
            throw t
        }
    }

    /**
     * All of this runs off the caller's thread. The Realm here is configured to refuse
     * writes on the UI thread, and this is reached straight from a change listener, which
     * is delivered on the main looper.
     */
    override fun markRead(threadKey: String, upToTs: Long) = runOffThread {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                r.where(SignalMessage::class.java)
                    .equalTo("threadKey", threadKey)
                    .equalTo("outgoing", false)
                    .equalTo("read", false)
                    .lessThanOrEqualTo("date", upToTs)
                    .findAll()
                    .forEach { it.read = true }
                r.where(SignalThread::class.java).equalTo("threadKey", threadKey)
                    .findFirst()?.unread = 0
            }
        }
        val cfg = config() ?: return@runOffThread
        runCatching {
            BridgeClient(cfg).markRead(threadKey, upToTs, prefs.signalReadReceipts.get())
        }
            .onFailure { Timber.d("markRead not delivered: ${it.message}") }
    }

    /**
     * Bytes for an attachment, from whichever rail brought it in.
     *
     * The local store is asked first. A bridge fetch is a network round trip to another
     * machine, and on a directly linked device the file is already on disk -- so trying the
     * bridge first would be slower when it worked and wrong when there is no bridge at all.
     */
    override fun loadAttachment(id: String): ByteArray? {
        signalStore.readAttachment(id)?.let { return it }
        val cfg = config() ?: return null
        return runCatching { BridgeClient(cfg).fetchAttachment(id) }
            .onFailure { Timber.d("attachment $id: ${it.message}") }
            .getOrNull()
    }

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

                out[uuid] = fromContacts ?: fromThread ?: number.ifBlank { uuid.take(8) }
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

    override fun threadDirectory(): List<SignalThread> =
        Realm.getDefaultInstance().use { realm ->
            realm.copyFromRealm(
                realm.where(SignalThread::class.java)
                    .equalTo("kind", "direct")
                    .findAll()
            ).sortedBy { it.title.lowercase() }
        }

    override fun account(): SignalAccount {
        val cfg = config() ?: throw IllegalStateException("no bridge paired")
        val a = BridgeClient(cfg).account()
        return SignalAccount(
            number = a.number,
            selfUuid = a.selfUuid,
            devices = a.devices.map { SignalDevice(it.id, it.name, it.created) },
            thisDeviceId = a.thisDeviceId
        )
    }

    override fun identity(threadKey: String): SignalIdentity {
        if (!useBridge()) {
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
        val cfg = config() ?: throw IllegalStateException("no bridge paired")
        val i = BridgeClient(cfg).identity(threadKey)
        return SignalIdentity(i.safetyNumber, i.trustLevel)
    }

    override fun acceptIdentity(threadKey: String): Boolean {
        if (!threadKey.startsWith("direct:")) return false
        // Only the direct rail: on a bridge the keys live on the bridge, and pretending to
        // accept one here would report success for something that did not happen.
        if (useBridge()) return false
        return signalStore.acceptIdentity(threadKey.removePrefix("direct:"))
    }

    override fun react(messageId: String, emoji: String, remove: Boolean) {
        val cfg = config() ?: throw IllegalStateException("no bridge paired")

        // Signal names a message by who wrote it and when they sent it. Our id is a thing
        // this app made up, so the real identifiers are read off the row -- and for a
        // message we sent ourselves the author is this account, which the row records as
        // outgoing rather than by writing our own uuid into senderUuid.
        val (threadKey, author, ts) = Realm.getDefaultInstance().use { realm ->
            val row = realm.where(SignalMessage::class.java).equalTo("id", messageId).findFirst()
                ?: throw IllegalStateException("no such message")
            // Left empty for our own messages: the bridge knows this account's uuid and
            // fills it in, so the phone does not have to carry a copy of it.
            val who = when {
                row.outgoing -> ""
                else -> row.senderUuid.ifBlank { row.senderNumber }
            }
            Triple(row.threadKey, who, row.date)
        }

        BridgeClient(cfg).react(threadKey, emoji, author, ts, remove)
        // Deliberately not written locally. signal-cli echoes the reaction back through the
        // sync stream within moments and that echo takes the same path as anyone else's, so
        // writing it here too would mean two sources of truth for one emoji -- and the echo
        // is the one that reflects what Signal actually accepted.
    }

    override fun setBlocked(threadKey: String, blocked: Boolean) {
        // Not runOffThread: this one has to be able to fail in front of the caller. The
        // others are local writes that cannot really go wrong; this one leaves the phone.
        val cfg = config() ?: throw IllegalStateException("no bridge paired")
        BridgeClient(cfg).setBlocked(threadKey, blocked)
    }

    override fun setPinned(threadKey: String, pinned: Boolean) = runOffThread {
        editThread(threadKey) { it.pinned = pinned }
    }

    override fun setMuted(threadKey: String, muted: Boolean) = runOffThread {
        editThread(threadKey) { it.muted = muted }
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

    private fun publishState(
        reachable: Boolean,
        signalConnected: Boolean,
        error: String?,
        rejected: Boolean = false
    ) {
        state.onNext(
            SignalRepository.ConnectionState(
                configured = isConfigured(),
                linkedDirectly = linkedDirectly(),
                usingBridge = useBridge(),
                undecryptable = undecryptableCount(),
                contactSummary = if (useBridge()) "" else runCatching { signalStore.contactSummary() }.getOrDefault(""),
                undecryptableReasons = if (useBridge()) emptyList()
                    else runCatching { signalStore.undecryptableReasons() }.getOrDefault(emptyList()),
                enabled = prefs.signalEnabled.get(),
                bridgeReachable = reachable,
                signalConnected = signalConnected,
                lastSyncedAt = prefs.signalLastSync.get(),
                error = error,
                rejected = rejected
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
