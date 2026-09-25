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
import com.wanderwildwood.kotozute.signalstore.AppliedStorageState
import com.wanderwildwood.kotozute.signalstore.ServiceOutage
import com.wanderwildwood.kotozute.signalstore.SignalKeyTransparency
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

private const val ATTACHMENT_PREVIEW = "\uD83D\uDCCE Attachment"


/**
 * What a view-once message says instead of nothing.
 *
 * The bridge deliberately kept the row -- "so the conversation does not have a silent hole
 * in it" -- with an empty body and no attachment, because the picture is gone by design.
 * Without a marker the phone reintroduced exactly the hole the bridge had gone out of its
 * way to avoid: an empty bubble, a blank inbox snippet, and no way to tell a view-once photo
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
     * This is how Signal reaches this app, and now the only way. It was once one of two --
     * the other being a bridge on another machine, supported alongside this one so a phone
     * already paired to a bridge would not lose its messages the day it learned to fetch its
     * own. That transition is finished and the bridge is gone.
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

    // Was: which rail to use, when this app could reach Signal either through a bridge on
    // another machine or as a linked device itself. Signal now reaches this phone one way
    // only -- see the v1.17 removal -- so the question no longer exists and every caller
    // that asked it is settled in favour of the device's own connection.
    //
    // Worth keeping from it: syncNow() and startStream() once disagreed about which rail to
    // prefer, so a phone with both caught up over one and streamed over the other. Both
    // wrote the same rows through store(), which deduplicates rather than duplicating -- but
    // two writers racing for one conversation was not a thing to leave in place merely
    // because it happened to be survivable.

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
     * Whether Android says there is a usable network, and a lock the listen loop waits on.
     *
     * Upstream's `IncomingMessageObserver` holds a `NetworkConnectionListener`: it disconnects
     * when the network goes, waits for it, and reconnects the moment Android reports one
     * available. This loop had no listener at all, so it went on trying every half minute with
     * nothing to reach, and after the network came back could sit out the rest of a backoff --
     * up to ~37s -- before noticing. On a phone with no push, that wait is a message late.
     */
    @Volatile private var networkAvailable = true
    private val networkWake = Object()
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var physicalNetworkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun watchNetwork() {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return
        networkAvailable = cm.activeNetwork != null
        // What the last event said, so a capability update that changes nothing that matters
        // -- Android re-sends them every ~30s with new bandwidth estimates -- is not a network
        // change. Upstream keys its own on the same three flags (`logCapabilitiesIfChanged`).
        var lastSeen: String? = null
        // The real (non-VPN) networks up right now; see the second callback below.
        val physical = java.util.Collections.synchronizedSet(mutableSetOf<android.net.Network>())
        fun changed(available: Boolean, why: String) {
            networkAvailable = available
            Timber.i("signal: network %s (%s)", if (available) "available" else "unavailable", why)
            signalStore.onNetworkChange()
            synchronized(networkWake) { networkWake.notifyAll() }
        }
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                lastSeen = null
                changed(physical.isNotEmpty(), "default network available")
            }

            override fun onLost(network: android.net.Network) {
                lastSeen = null
                changed(false, "default network lost")
            }

            override fun onBlockedStatusChanged(network: android.net.Network, blocked: Boolean) {
                changed(!blocked, if (blocked) "blocked" else "unblocked")
            }

            // ⚠ The one that matters behind a VPN. With Tailscale (or any VPN) up, the default
            // network IS the VPN, and it stays "available" through airplane mode -- no onLost,
            // no onAvailable -- while its capabilities change as the network under it goes and
            // comes back. Seen on David's phone: the stream sat 23s after Wi-Fi returned.
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities
            ) {
                val internet = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val validated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val portal = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                val key = "$network $internet $validated $portal"
                if (key == lastSeen) return
                lastSeen = key
                // Upstream's rule: unavailable when the default network has no internet.
                changed(internet && physical.isNotEmpty(), "internet=$internet validated=$validated")
            }
        }

        // ⚠ **The default network is not enough behind a VPN.** Measured on David's phone with
        // Tailscale up: airplane mode on and off produced NO default-network event at all --
        // the default network is the VPN, and to an ordinary app it never changes. The real
        // networks under it (Wi-Fi, mobile) do come and go, so they are watched directly: a
        // request for internet that is NOT a VPN. No real network left means none, whatever
        // the VPN says; one arriving is a network change, which resets libsignal's attempt.
        val physicalCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                physical += network
                changed(true, "a real network is up")
            }

            override fun onLost(network: android.net.Network) {
                physical -= network
                changed(physical.isNotEmpty(), "a real network went away")
            }
        }
        runCatching {
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                physicalCallback
            )
        }.onSuccess { physicalNetworkCallback = physicalCallback }
            .onFailure { Timber.w(it, "signal: could not watch the real networks") }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Timber.w(it, "signal: could not watch the network; reconnecting on the backoff alone") }
    }

    private fun unwatchNetwork() {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        listOfNotNull(networkCallback, physicalNetworkCallback).forEach { callback ->
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        physicalNetworkCallback = null
        networkAvailable = true
        synchronized(networkWake) { networkWake.notifyAll() }
    }

    /**
     * Waits before a reconnect: [backoff] while there is a network, cut short the moment a
     * network appears; while there is none, until one does. Bounded either way, so a callback
     * that never comes cannot strand the loop -- [NO_NETWORK_RECHECK_MS] without a network.
     */
    private fun waitToReconnect(backoff: Long) {
        synchronized(networkWake) {
            if (!networkAvailable) {
                Timber.i("signal: no network; waiting for one rather than retrying")
                networkWake.wait(NO_NETWORK_RECHECK_MS)
            } else if (backoff > 0) {
                networkWake.wait(backoff)
            }
        }
    }

    private val NO_NETWORK_RECHECK_MS = 5 * 60_000L

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
     * allowed to say Signal is unreachable while a stream is sitting there connected.
     */
    private val streamConnected = AtomicBoolean(false)

    /** Our own messages being sent by this process right now, by id. See [resendOrphans]. */
    private val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** [resendOrphans] runs once per process, as upstream's job does once per launch. */
    private val orphansSwept = AtomicBoolean(false)

    private val outbox by lazy {
        com.wanderwildwood.kotozute.signalstore.SignalOutbox(java.io.File(context.filesDir, "signal-outbox"))
    }

    /** Whether the socket is reaching for the server. See [noteConnecting]. */
    private val reaching = AtomicBoolean(false)

    init {
        publishState(signalConnected = false, error = null)
        signalStore.onRejected = ::onServerRefusedThisDevice
        // Asked fresh whenever a read receipt is about to go, rather than remembered from when
        // it was owed. See [SignalStore.readReceiptsEnabled].
        signalStore.readReceiptsEnabled = { prefs.signalReadReceipts.get() }
        signalStore.onPniRotationOwed = { owed -> prefs.signalPniRotationOwed.set(owed) }
        signalStore.onPrimaryIdle = ::notePrimaryIdle
        signalStore.onConnecting = ::noteConnecting
        signalStore.onConversationState = ::applyConversationState
        signalStore.storageDesired = ::desiredStorageState
    }

    /**
     * Muted and archived, as the account's own records hold them.
     *
     * Applied only to conversations this phone already has. A record for somebody never
     * written to is not a conversation yet, and creating an empty archived thread for every
     * contact on the account would fill the inbox with rows nobody has said anything in.
     *
     * ⚠ **Only from records that changed since the last read** -- see [AppliedStorageState].
     * A conversation unarchived here stays unarchived until the account says something new
     * about it. It does not reach the account: nothing here writes back to its records
     * (`docs/DECISION-storage-write.md`), so other devices keep their own answer.
     */
    private fun applyConversationState(
        states: List<com.wanderwildwood.kotozute.signalstore.SignalStorageService.ConversationState>
    ) = runOffThread {
        if (states.isEmpty()) return@runOffThread
        var changed = 0
        var skipped = 0
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                fun threadFor(key: String) = r.where(SignalThread::class.java)
                    .equalTo("threadKey", key)
                    .findFirst()
                val plan = AppliedStorageState.plan(
                    states,
                    prefs.signalAppliedStateRecords.get()
                ) { threadFor(it) != null }
                plan.apply.forEach { state ->
                    val thread = threadFor(state.threadKey) ?: return@forEach
                    if (thread.muted != state.muted || thread.archived != state.archived) {
                        thread.muted = state.muted
                        thread.archived = state.archived
                        changed++
                    }
                }
                skipped = plan.applied.size - plan.apply.size
                prefs.signalAppliedStateRecords.set(plan.applied)
            }
        }
        Timber.i("signal storage: %d record(s) unchanged since the last read, left as the phone has them", skipped)
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
        // ⚠ Whether the stream is still *wanted*, not whether it is connected. This was
        // `!streamConnected`, which is exactly the state of a freshly started app: the reason
        // is already on file from before the restart and nothing has connected yet. So every
        // refusal after a restart returned here, the loop was never retired, and a phone the
        // account had removed reconnected every thirty seconds for as long as it was on.
        if (!refusalIsNews(prefs.signalRejected.get(), reason, streamWanted.get())) return
        Timber.w("signal: server refused this device: %s", reason)
        prefs.signalRejected.set(reason)
        runOffThread {
            stopStream()
            publishState(signalConnected = false, error = reason)
        }
    }

    /**
     * The socket has started, or stopped, reaching for the server.
     *
     * Republished rather than only recorded: the composer is disabled off this state, and a
     * flag that changes without a publish is a screen that keeps saying what stopped being
     * true. Guarded on change because a socket announces CONNECTING on every retry.
     */
    private fun noteConnecting(connecting: Boolean) {
        if (reaching.getAndSet(connecting) == connecting) return
        publishState(
            signalConnected = state.value?.signalConnected ?: false,
            error = state.value?.error
        )
    }

    /**
     * Records that the server says the account's primary has not been seen for a long time.
     *
     * ⚠ Not a fault, and not something to interrupt anybody with -- but the one warning that
     * comes *before* the fault. A linked device whose primary stays idle is eventually
     * unlinked by the server, and when that happens this phone loses the account and every
     * message on it, with the first notice being that nothing works any more.
     *
     * Kept where a screen can read it rather than acted on here: what to do about an idle
     * primary is to go and open Signal on it, which is not something this app can do.
     */
    private fun notePrimaryIdle(idle: Boolean) {
        if (prefs.signalPrimaryIdle.get() == idle) return
        prefs.signalPrimaryIdle.set(idle)
        if (idle) {
            Timber.w("signal account: the server says this account's primary device has gone idle")
        } else {
            Timber.i("signal account: the account's primary device is active again")
        }
        // ⚠ Published, not only recorded. It was kept "where a screen can read it" and no
        // screen did, so the one warning that comes before an unlink reached nobody.
        publishState(
            signalConnected = state.value?.signalConnected ?: false,
            error = state.value?.error
        )
    }

    /**
     * Asks Signal whether Signal itself is down, after a send could not reach it.
     *
     * Upstream's `ServiceOutageDetectionJob`, run from the same place: a send that failed on
     * the network (`PushSendJob.onRetry`). Without it "Signal is down" and "this phone is
     * offline" look identical, and only one of them is something to go and fix.
     */
    private fun checkServiceOutage() = runOffThread {
        val now = System.currentTimeMillis()
        if (now - prefs.signalOutageCheckedAt.get() < ServiceOutage.CHECK_INTERVAL_MS) return@runOffThread
        val status = ServiceOutage.check()
        Timber.i("signal: service status check says %s", status)
        // Unknown is a network too broken to ask, which says nothing about Signal. Upstream
        // leaves the flag alone and retries; so does this, on the next failed send.
        if (status == ServiceOutage.Status.UNKNOWN) return@runOffThread
        prefs.signalOutageCheckedAt.set(now)
        setServiceOutage(status == ServiceOutage.Status.DOWN)
    }

    private fun setServiceOutage(down: Boolean) {
        if (prefs.signalServiceOutage.get() == down) return
        prefs.signalServiceOutage.set(down)
        publishState(
            signalConnected = state.value?.signalConnected ?: false,
            error = state.value?.error
        )
    }

    /**
     * Publishes this device's first full set of pre keys, and remembers if it could not.
     *
     * ⛔ **This used to be a single attempt with a comment saying maintenance would cover a
     * failure. It would not.** `PreKeyUploader.maintain` is gated on the age of the stored
     * signed key, and on a device that has just registered or linked that key is minutes old,
     * so the periodic pass answers "not due" and uploads nothing for the length of the refresh
     * interval. The device keeps working the whole time -- which is why nobody would notice --
     * with no one-time keys published, so every new session falls back to the last-resort key.
     *
     * ⚠ The flag is set **before** the attempt and cleared only on success, so a process that
     * dies mid-upload still owes it. See [Preferences.signalPreKeysOwed].
     *
     * Never fatal to the thing that called it: the account exists on the server by this point
     * either way, and failing the whole registration over a refill would be worse than owing
     * one.
     */
    private fun publishFirstPreKeys(after: String) {
        prefs.signalPreKeysOwed.set(true)
        runCatching { signalStore.uploadPreKeys() }
            .onSuccess {
                prefs.signalPreKeysOwed.set(false)
                Timber.i("signal keys: %s", it)
            }
            .onFailure {
                Timber.w(it, "signal keys: could not publish after %s; owed and retried later", after)
            }
    }

    /** Whether this phone is on the account at all. Every screen keys its Signal UI off it. */
    override fun isConfigured(): Boolean = linkedDirectly()

    override fun unpair() = runOffThread {
        stopStream()
        prefs.signalEnabled.set(false)
        prefs.signalLastSync.set(0L)
        prefs.signalRejected.set("")
        // The same, for the whole account. "Delete Signal data" that leaves every attachment
        // on disk is not what the row says it does.
        val doomed = mutableListOf<String>()
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                doomed += it.where(SignalMessage::class.java).findAll()
                    .flatMap { message -> attachmentIdsOf(message.attachments).orEmpty() }
                it.delete(SignalMessage::class.java)
                it.delete(SignalThread::class.java)
            }
        }
        if (doomed.isNotEmpty()) {
            runCatching { signalStore.forgetAttachments(doomed) }
                .onFailure { Timber.w(it, "signal: could not remove the account's files") }
            Timber.i("signal: removed %d attachment file(s) with the messages", doomed.size)
        }
        // ⚠ And the resend log, all of it. It holds the plaintext of everything this device
        // has ever sent; "delete Signal data" that leaves that behind is not what the row
        // says it does -- the same fault this function already had for attachments. Not by
        // timestamp: the rows are gone, so there is nothing left to enumerate, and nothing
        // that survives this is worth keeping anyway.
        runCatching { signalStore.forgetEverySentMessage() }
            .onFailure { Timber.w(it, "signal: could not clear the resend log") }
        // And the attachments of anything that never went.
        runCatching { outbox.clearAll() }
            .onFailure { Timber.w(it, "signal: could not clear the outbox") }
        publishState(signalConnected = false, error = null)
    }

    /**
     * Delete every message whose disappearing deadline has passed, and tidy the threads they
     * were the last of.
     *
     * This store is the only copy there is, so nothing else will ever remove these rows. It
     * was written when there was a second copy on a bridge: that one swept itself, which
     * deleted the only row that was ever going to go while the phone kept the message for
     * ever. Reads exclude expired rows too, so a message is gone from view the moment its
     * time is up whether or not the sweep has run.
     */
    override fun purgeExpired(): Int {
        var removed = 0
        val doomedAttachments = mutableListOf<String>()
        val expiredThreads = mutableListOf<String>()
        // ⚠ And the resend log, which this did not touch. A message with a thirty-second
        // timer left its plaintext in `message_log` for the fortnight that log keeps things,
        // so the words a disappearing message promised to take with it were still on the
        // phone -- and still resendable to anyone whose client asked. Upstream cannot forget
        // this at a call site because it is a SQL trigger on the messages table
        // (`MessageSendLogTables.AFTER_MESSAGE_DELETE_TRIGGER`); here it is a call, and two
        // of the four paths that delete a message had not made it.
        val expiredSentTimestamps = mutableListOf<Long>()
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
                doomedAttachments += dead.flatMap { attachmentIdsOf(it.attachments).orEmpty() }
                expiredSentTimestamps += dead.filter { it.outgoing }.map { it.date }
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
        forgetFromResendLog(expiredSentTimestamps)
        if (removed > 0) {
            Timber.i("signal: %d expired message(s) removed", removed)
            expiredThreads.forEach { this.removed.onNext(it) }
        }
        return removed
    }

    /**
     * The stored attachment ids on a message row -- empty if it had none, **null if the row's
     * list could not be read at all**.
     *
     * ⚠ Those last two were the same value, and the difference decides whether a file lives.
     * Every caller here is a deletion path: an unreadable list meant the row was deleted and
     * its files were never named, so the picture behind a disappearing message stayed on the
     * handset with nothing left that could ever ask for it again. Now it says so, and
     * [purgeAbandonedAttachments] can tell "this message has no attachments" from "I do not
     * know what this message had" -- which is the difference between a safe sweep and one
     * that deletes somebody's photograph.
     */
    private fun attachmentIdsOf(json: String?): List<String>? {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = org.json.JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
            }
        }.onFailure {
            Timber.w(it, "signal: a message's attachment list could not be read")
        }.getOrNull()
    }

    /**
     * Tries again for attachments that did not arrive the first time.
     *
     * ⚠ **An attachment that failed its three immediate attempts used to be lost for good.**
     * Those three cover a dropped socket; they do not cover a phone with no usable connection
     * for the length of one batch, which on a device built to sleep is an ordinary evening.
     * With the pointer thrown away there was nothing left to try again with, and the message
     * said "attachment, not downloaded" until the CDN copy expired weeks later.
     *
     * Upstream keeps trying for a full day: `AttachmentDownloadJob` is `setLifespan(1 day)`
     * with `setMaxAttempts(UNLIMITED)`, retrying on network errors. This is the same promise
     * without a job queue -- the pointer rides on the message row and this pass walks them.
     *
     * @return how many arrived this time.
     */
    override fun retryPendingAttachments(): Int {
        val now = System.currentTimeMillis()
        // Read first, write after. The download reaches the network, and holding a Realm
        // transaction open across it would block every other writer for as long as the CDN
        // takes -- the same care the contact store needed in finding 43.
        val owed = Realm.getDefaultInstance().use { realm ->
            realm.where(SignalMessage::class.java)
                .contains("attachments", "\"pending\":true")
                .findAll()
                .map { it.id to it.attachments }
        }
        if (owed.isEmpty()) return 0

        var arrived = 0
        var abandoned = 0
        val rewritten = mutableListOf<Pair<String, String>>()
        owed.forEach { (id, json) ->
            val entries = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return@forEach
            var changed = false
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                if (!entry.optBoolean("pending", false)) continue
                val pointer = entry.optString("pointer").takeIf { it.isNotBlank() } ?: continue
                if (!stillWorthFetching(entry.optLong("firstTried", 0L), now)) {
                    // Given up on, and said so by dropping the pointer rather than by leaving
                    // it to be retried for ever. The row still reads "pending", which is the
                    // truth: it never arrived.
                    entry.remove("pointer")
                    entry.remove("firstTried")
                    changed = true
                    abandoned++
                    continue
                }
                val fetched = runCatching {
                    signalStore.downloadAttachment(
                        android.util.Base64.decode(pointer, android.util.Base64.NO_WRAP)
                    )
                }.onFailure {
                    Timber.w(it, "signal attachment: a retry could not run")
                }.getOrNull()
                if (fetched != null) {
                    entry.put("id", fetched)
                    entry.put("pending", false)
                    entry.remove("pointer")
                    entry.remove("firstTried")
                    changed = true
                    arrived++
                }
            }
            if (changed) rewritten += id to entries.toString()
        }

        if (rewritten.isNotEmpty()) {
            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction { r ->
                    rewritten.forEach { (id, json) ->
                        r.where(SignalMessage::class.java).equalTo("id", id).findFirst()
                            ?.attachments = json
                    }
                }
            }
        }
        if (arrived > 0 || abandoned > 0) {
            Timber.i(
                "signal attachment: %d arrived on a retry, %d given up on after a day",
                arrived, abandoned
            )
        }
        return arrived
    }

    /**
     * Deletes attachment files that no message refers to any more.
     *
     * The backstop behind every named deletion on this rail. Five paths delete a message and
     * then ask for its files by id, and each depends on the row's JSON parsing; this one asks
     * the opposite question -- what is on disk that nothing claims -- so a file survives none
     * of them only by being genuinely referenced.
     *
     * Upstream is `AttachmentTable.deleteAbandonedAttachmentFiles`, run from
     * `DeleteAbandonedAttachmentsJob`. It does not need the guard below, because its
     * references are columns rather than a JSON blob and cannot half-read.
     *
     * ⚠ **Refuses to run on an incomplete set.** If one row's list cannot be read, this does
     * nothing at all and says why. The two mistakes are not the same size: leaving an orphan
     * costs disk until the next pass, and deleting a live attachment costs somebody a picture
     * that has no other copy anywhere -- this store is the only one there is.
     */
    override fun purgeAbandonedAttachments(): Int {
        val known = mutableSetOf<String>()
        var unreadable = 0
        Realm.getDefaultInstance().use { realm ->
            realm.where(SignalMessage::class.java).findAll().forEach { message ->
                val ids = attachmentIdsOf(message.attachments)
                if (ids == null) unreadable++ else known += ids
            }
        }
        if (unreadable > 0) {
            Timber.w(
                "signal: %d message(s) would not say what they had attached; not sweeping, " +
                    "because a file this cannot account for might still be somebody's picture",
                unreadable
            )
            return 0
        }
        return runCatching { signalStore.forgetAbandonedAttachments(known) }
            .onFailure { Timber.w(it, "signal: could not sweep abandoned attachments") }
            .getOrDefault(0)
    }

    /**
     * Writes into the conversation that a second one was folded into it.
     *
     * ⚠ **A merge is the most visible thing this app does that nobody is told about.** Two
     * conversations become one: the second disappears from the inbox and its messages turn up
     * interleaved in the survivor. Without a line saying so, a reader sees a conversation they
     * were having vanish and its contents surface somewhere else, with no explanation available
     * anywhere.
     *
     * Upstream files a permanent row for it (`RecipientTable.merge` ->
     * `MessageTable.insertThreadMergeEvent`), carrying the number the absorbed side was known
     * by — `ThreadMergeEvent.previousE164` — which is the part that lets a reader recognise
     * which conversation it was.
     *
     * Read, not unread, exactly as upstream files it: nothing was lost and nothing needs doing.
     *
     * ⛔ Upstream's sibling `insertSessionSwitchoverEvent` is deliberately **not** ported.
     * Upstream skips it whenever a thread merge happened in the same pass ("Skipping SSE insert
     * because we already had a thread merge event"), and in this app the pairing that would
     * trigger one is the same event that drives the merge — so it would be the skipped case
     * every time.
     */
    private fun noteThreadMerge(threadKey: String, wasKnownAs: String) {
        val aci = threadKey.removePrefix("direct:")
        noteLocalEvent(
            aci,
            context.getString(
                com.wanderwildwood.kotozute.data.R.string.signal_threads_joined, wasKnownAs
            )
        )
    }

    /**
     * Writes into the conversation that somebody's name has replaced a different one.
     *
     * A contact's displayed name changing under the reader is how one person gets mistaken for
     * another, so it is a row in the conversation rather than a silent relabelling. Upstream
     * does the same (`RetrieveProfileJob` -> `insertProfileNameChangeMessages`).
     *
     * ⚠ **Read, not unread** -- `READ to 1` in upstream's insert, against `READ to 0` and an
     * `incrementUnread` for the could-not-read note next door. The difference is deliberate in
     * both places and copied rather than decided: a lost message is something to act on now, a
     * change of name is something to have seen when you next look.
     */
    private fun noteNameChange(aci: String, from: String, to: String) = noteLocalEvent(
        aci,
        context.getString(
            com.wanderwildwood.kotozute.data.R.string.signal_profile_name_changed, from, to
        )
    )

    /**
     * Writes a line into somebody's conversation about something this phone noticed.
     *
     * One function for both notes, because two copies of "file a local row" is how the two
     * drift into being subtly different rows. What differs between them is only the sentence.
     */
    private fun noteLocalEvent(aci: String, body: String) {
        val threadKey = "direct:$aci"
        // ⚠ **Only into a conversation that already exists.** Without this, somebody among two
        // hundred contacts changing their Signal name puts a *new* conversation in the inbox
        // whose entire content is "X is now called Y" -- a conversation that does not exist,
        // announcing itself.
        //
        // Upstream prevents the same thing from the other end: `MessageTable
        // .buildMeaningfulMessagesQuery` excludes `PROFILE_CHANGE_TYPE` and
        // `CHANGE_NUMBER_TYPE` outright, so a thread holding only those is not counted as
        // having anything in it and never reaches the conversation list. This app has no
        // meaningful-message column, so it declines to write the row at all -- the same
        // outcome by the only means available.
        //
        // ⛔ Deliberately **not** applied to the could-not-read note. A message that arrived
        // and could not be read is a real event in a real conversation, and upstream counts
        // `BAD_DECRYPT_TYPE` as meaningful for exactly that reason.
        val known = Realm.getDefaultInstance().use { realm ->
            realm.where(SignalThread::class.java).equalTo("threadKey", threadKey).count() > 0
        }
        if (!known) {
            Timber.i("signal: something changed about somebody with no conversation here; not starting one")
            return
        }
        val now = System.currentTimeMillis()
        ingest(
            listOf(
                com.wanderwildwood.kotozute.signal.BridgeMessage(
                    // Not a message identity: nobody sent this and no resend will replace it,
                    // so it is stamped with when this phone noticed rather than with a
                    // timestamp some other device might also use.
                    id = "local:$aci:$now",
                    threadKey = threadKey,
                    ts = now,
                    senderUuid = aci,
                    senderNumber = "",
                    outgoing = false,
                    body = body,
                    groupId = "",
                    quoteTs = 0,
                    read = true,
                    source = "live",
                    attachmentsJson = ""
                )
            )
        )
        Timber.i("signal: noted something in a conversation that nobody sent")
    }

    /**
     * Writes into the conversation that a message arrived and could not be read.
     *
     * An hour after this phone asked for it again and nothing came. Until then there is every
     * chance the resend arrives and nobody needs to know anything happened; after it, the
     * alternative is a conversation with a silent gap in it -- and a reader cannot ask about a
     * message they were never told existed. Upstream writes the same row on the same timer
     * (`PendingRetryReceiptManager` -> `insertBadDecryptMessage`).
     *
     * ⚠ Filed under the message's **own** identity, `(sender, sentTimestamp)`, which is what
     * every other device calls it. So a resend that turns up an hour or a week later replaces
     * this note with the real message instead of sitting beside it -- [ingest] keys on that id.
     * Upstream checks `messageExists` before inserting because its rows are keyed separately;
     * here the key does that work, and goes on doing it after the insert, which a check at
     * insert time cannot.
     */
    private fun noteUndecryptable(sender: String, sentTimestamp: Long, groupId: ByteArray?) =
        notePlaceholder(
            sender, sentTimestamp, groupId,
            com.wanderwildwood.kotozute.data.R.string.signal_message_unreadable
        )

    /**
     * Writes a stand-in row where a message should have been.
     *
     * One body for two reasons a message cannot be shown -- it never arrived, or this build is
     * too old to render it -- because everything except the sentence is the same and the part
     * that is easy to get wrong is the part they share: the thread key, and filing the row under
     * `(sender, sentTimestamp)` so the real message replaces the note if it ever lands.
     */
    private fun notePlaceholder(
        sender: String,
        sentTimestamp: Long,
        groupId: ByteArray?,
        @androidx.annotation.StringRes body: Int
    ) {
        val group = groupId
            ?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
            .orEmpty()
        // ⚠ The shared rule, not a second copy of it. Two rules for which conversation a
        // message belongs to do not fail loudly -- they quietly produce a second thread for a
        // conversation that already has one, and the only symptom is a duplicate in the inbox
        // a long time later. That is what `ThreadKeyTest` exists for.
        val threadKey = com.wanderwildwood.kotozute.signalstore.ContentNormalizer.threadKeyFor(
            outgoing = false,
            counterpartUuid = sender,
            counterpartNumber = "",
            groupId = group,
            selfAci = signalStore.selfAciOrNull(),
            selfE164 = runCatching { signalStore.selfNumberOrNull() }.getOrNull()
        ) ?: run {
            Timber.w("signal receive: a message that would not open has no conversation to go in")
            return
        }
        ingest(
            listOf(
                com.wanderwildwood.kotozute.signal.BridgeMessage(
                    id = "$sender:$sentTimestamp",
                    threadKey = threadKey,
                    ts = sentTimestamp,
                    senderUuid = sender,
                    senderNumber = "",
                    outgoing = false,
                    body = context.getString(body),
                    groupId = group,
                    quoteTs = 0,
                    // Not marked read. Somebody losing a message should hear about it the same
                    // way they would hear about the message itself -- that is the whole point
                    // of writing it down rather than counting it on a settings screen.
                    read = false,
                    source = "live",
                    attachmentsJson = ""
                )
            )
        )
        Timber.w("signal receive: a message that would not open was noted in %s", threadKey)
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
        // ⚠ The catch is what makes this safe to hand work to, and the log is what stops it
        // becoming a place work disappears into. An uncaught throw on a daemon thread takes
        // the process down with a trace nobody attributes to the caller, so every failure is
        // caught here -- and every caller is a background errand whose failure the next run
        // repeats, never a step somebody is waiting on.
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
                    groupMasterKeyFor(realm, thread.threadKey)
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

        // What each merge swallowed, so the surviving conversation can say so afterwards.
        // Collected rather than written here for the same reason as the number change: this is
        // inside a Realm transaction, and a note is a separate write that belongs after it.
        val merged = mutableListOf<Pair<String, String>>()
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
                    // Only where there was actually a second conversation to lose. A move
                    // that only re-keyed an empty thread is not something anybody watched
                    // happen, and upstream draws the same line -- `insertThreadMergeEvent` is
                    // reached only when `threadMerge.neededMerge`.
                    if (old != null) merged += to to counterpart
                    old?.deleteFromRealm()
                    refreshThreadPreview(r, to)
                }
            }
        }
        Timber.i("signal: joined %d split conversation(s)", moves.size)
        merged.forEach { (threadKey, wasKnownAs) -> noteThreadMerge(threadKey, wasKnownAs) }
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
        is com.wanderwildwood.kotozute.signalstore.SignalRegistrar.Step.NeedsPin -> {
            pinUnlocks[step.sessionId] = step
            SignalRepository.Registration.NeedsPin(step.sessionId, step.days, step.triesRemaining)
        }
        is com.wanderwildwood.kotozute.signalstore.SignalRegistrar.Step.Failed ->
            SignalRepository.Registration.Failed(step.failure)
        else -> SignalRepository.Registration.Failed(SignalRepository.RegistrationFailure.Unexpected)
    }

    private suspend fun registrationStep(
        body: suspend (com.wanderwildwood.kotozute.signalstore.SignalRegistrar) -> Any
    ): SignalRepository.Registration = try {
        val step = body(signalStore.registrar())
        // Registering ends in the same place linking does -- an account this device can use --
        // so the same things have to follow it, for the same reasons documented on linkDevice.
        if (step is com.wanderwildwood.kotozute.signalstore.SignalRegistrar.Step.Registered) {
            pinUnlocks.clear()
            step.pinReset?.let { reset ->
                // After the account exists, and not allowed to fail the registration: the
                // number has moved by now. A reset that did not happen is logged and costs one
                // guess, not the account.
                runCatching { signalStore.resetPinGuesses(reset) }
                    .onFailure { Timber.w(it, "signal register: resetting the PIN's guess count threw") }
            }
            publishFirstPreKeys("registering")
            prefs.signalEnabled.set(true)
            // ⚠ The same reason linking clears it: a refusal recorded earlier must not
            // outlive the thing that cures it. Registering is at least as much a cure as
            // linking -- the account is new and this phone is its primary -- and without
            // this a phone that had once been refused finished registration still showing
            // "This phone is no longer linked to Signal. Link it again to receive messages."
            // over a working account, with nothing on any screen able to clear it.
            prefs.signalRejected.set("")
            publishState(signalConnected = true, error = null)
            startStream()
        }
        stepToRegistration(step)
    } catch (t: Throwable) {
        Timber.w(t, "signal: registration threw")
        SignalRepository.Registration.Failed(
            SignalRepository.RegistrationFailure.Unexplained(t.message ?: t::class.java.simpleName)
        )
    }

    override suspend fun registerBegin(e164: String) = registrationStep { it.begin(e164) }

    override suspend fun registerCaptcha(sessionId: String, token: String) =
        registrationStep { it.submitCaptcha(sessionId, token) }

    override suspend fun registerResend(sessionId: String, voice: Boolean) =
        registrationStep { it.requestCode(sessionId, voice) }

    override suspend fun registerVerify(sessionId: String, code: String, e164: String) =
        registrationStep { it.verifyAndRegister(sessionId, code, e164) }

    /**
     * What a locked registration handed over for checking the PIN, by session. Memory only,
     * and dropped once registered: the SVR credentials in it are good for this attempt alone.
     */
    private val pinUnlocks =
        java.util.concurrent.ConcurrentHashMap<String, com.wanderwildwood.kotozute.signalstore.SignalRegistrar.Step.NeedsPin>()

    override suspend fun registerPin(sessionId: String, pin: String, e164: String): SignalRepository.Registration {
        val lock = pinUnlocks[sessionId] ?: return SignalRepository.Registration.Failed(
            SignalRepository.RegistrationFailure.Unexpected
        )
        return registrationStep {
            it.submitPin(sessionId, e164, pin, lock.days, lock.svrUsername, lock.svrPassword)
        }
    }

    override fun isPrimaryDevice(): Boolean = runCatching {
        signalStore.isLinked() &&
            signalStore.account.credentials().deviceId ==
            com.wanderwildwood.kotozute.signalstore.SignalRegistrar.PRIMARY_DEVICE_ID
    }
        // A store that will not open is not a primary. Answering false is the safe way to be
        // wrong here: it hides a row, where answering true would offer to overwrite a profile
        // this phone may not own.
        .getOrDefault(false)

    override suspend fun registerSetProfileName(
        given: String,
        family: String
    ): SignalRepository.ProfileNameFailure? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                signalStore.setOwnProfileName(given, family)
            } catch (t: Throwable) {
                // ⚠ Reported, never swallowed. The account exists by the time this runs, so a
                // silent failure here leaves a working account that is nameless to everybody
                // it writes to -- the one outcome nothing else in the app would ever surface.
                Timber.w(t, "signal profile: setting this account's name threw")
                SignalRepository.ProfileNameFailure.Unexplained(t.message ?: t::class.java.simpleName)
            }
        }

    override fun linkDevice(deviceName: String, onUrl: (String) -> Unit): SignalRepository.Link? = try {
        val result = kotlinx.coroutines.runBlocking {
            signalStore.linker(
                // The account's own setting, which the provisioning message carries. Without
                // this the phone used its local default until a Configuration sync happened to
                // land -- so a person whose account has read receipts off could send them from
                // this device without ever having turned them on.
                onReadReceipts = { on ->
                    prefs.signalReadReceipts.set(on)
                    Timber.i("signal link: the account says read receipts are %s", on)
                }
            ).link(deviceName) { url -> onUrl(url) }
        }
        when (result) {
            is com.wanderwildwood.kotozute.signalstore.DeviceLinker.Result.Linked -> {
                // Linking registers ONE signed pre key and ONE last-resort Kyber key -- that
                // is all the registration request carries. Without a batch of one-time keys
                // every new conversation falls back to the last-resort key, which is reuse and
                // is exactly what one-time keys exist to prevent. Nothing else does this, so
                // omitting it leaves a device permanently on the degraded path while looking
                // entirely healthy.
                publishFirstPreKeys("linking")

                // Linking is an explicit act that means "I want Signal on this phone", so the
                // rail goes on with it. Leaving it off left a device that had just linked
                // successfully showing no conversations and no explanation -- the user having
                // to find a second switch to make the first one mean anything.
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
                SignalRepository.Link.Linked(result.deviceId)
            }
            is com.wanderwildwood.kotozute.signalstore.DeviceLinker.Result.Failed ->
                SignalRepository.Link.Failed(result.failure)
        }
    } catch (t: Throwable) {
        Timber.w(t, "signal: linking threw")
        SignalRepository.Link.Failed(
            SignalRepository.LinkFailure.Unexplained(t.message ?: t::class.java.simpleName)
        )
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
     * The second half is not optional. A message we send does not come back to us -- Signal
     * does not deliver a message to the device that sent it -- so nothing else will ever
     * produce this row. The bridge this replaced got away without it, because it stored the
     * message on its own side and the phone read it back on the next sync.
     */
    private fun sendDirect(
        threadKey: String,
        body: String,
        attachments: List<String>,
        quote: com.wanderwildwood.kotozute.signalstore.SignalQuote? = null,
        /** The timestamp of a message already written down and being sent again, or 0. */
        resending: Long = 0L
    ): Long {
        if (threadKey.startsWith("group:")) return sendDirectToGroup(threadKey, body, attachments, quote, resending)
        if (!threadKey.startsWith("direct:")) {
            throw IllegalStateException("cannot send to $threadKey")
        }
        val recipient = threadKey.removePrefix("direct:")
        // Said in words rather than as "not a service id: +1555...". A thread keyed by a
        // number holds only our own sends, filed from a transcript that did not name who
        // they went to; there is no Signal address in it to reply to. It joins the real
        // conversation as soon as one arrives that does.
        if (recipient.startsWith("+")) {
            throw com.wanderwildwood.kotozute.repository.SendRefused(
                com.wanderwildwood.kotozute.repository.SendFailure.NumberOnly
            )
        }
        // The conversation's timer goes with it. Not sending one is not neutral: it reads as
        // a timer of zero and switches the other person's disappearing conversation off.
        val (expiresIn, timerVersion) = timerFor(threadKey)
        val timestamp = if (resending > 0) resending else System.currentTimeMillis()

        val selfAci = signalStore.selfAciOrNull().orEmpty()
        val row =
            com.wanderwildwood.kotozute.signal.BridgeMessage(
                // The same (author, timestamp) identity every other device will use for
                // this message, so a sync of it -- should one ever arrive -- replaces this
                // row instead of duplicating it.
                id = "$selfAci:$timestamp",
                threadKey = threadKey,
                ts = timestamp,
                senderUuid = selfAci,
                senderNumber = "",
                outgoing = true,
                body = body,
                groupId = "",
                quoteTs = quote?.sentAt ?: 0L,
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
        return sendThroughOutbox(row, attachments, resending > 0) {
            signalStore.send(recipient, body, attachments, expiresIn, timerVersion, quote, timestamp)
        }
    }

    /**
     * Writes an outgoing message down, sends it, and marks it sent -- in that order.
     *
     * ⚠ The order is the point. The row used to be written only once the server had the
     * message, so a send cut off by the process dying -- a voice note or a photo uploading
     * while Android reclaimed memory -- left nothing behind: not sent, not on screen, not
     * anywhere. Signal inserts the message as sending first and `RetryPendingSendsJob` sends
     * whatever is still pending when the app next starts. So does this; see [resendOrphans].
     *
     * A failure *in this process* removes the row again: the composer still holds the text,
     * which is where this app has always put a failed send, and a row saying "not sent" beside
     * it would be the same message twice. A failure while sending again marks it not sent,
     * because then there is no composer holding it.
     */
    private fun sendThroughOutbox(
        row: BridgeMessage,
        attachments: List<String>,
        resending: Boolean,
        send: () -> Long
    ): Long {
        if (!inFlight.add(row.id)) {
            throw com.wanderwildwood.kotozute.repository.SendRefused(
                com.wanderwildwood.kotozute.repository.SendFailure.Unexplained("already sending")
            )
        }
        try {
            if (resending) {
                setSendState(row.id, SignalMessage.SEND_SENDING)
            } else {
                // Written before anything reaches the network. A phone that cannot write this
                // down has not sent anything yet, and says so like any other failed send.
                try {
                    outbox.save(row.id, attachments)
                    fileOutgoing(row)
                } catch (t: Throwable) {
                    runCatching { outbox.clear(row.id) }
                    throw t
                }
            }
            val sentAt = try {
                send()
            } catch (t: Throwable) {
                if (resending) {
                    runCatching { setSendState(row.id, SignalMessage.SEND_FAILED) }
                        .onFailure { Timber.w(it, "signal send: could not mark a message not sent") }
                } else {
                    runCatching { dropUnsent(row.id) }
                        .onFailure { Timber.w(it, "signal send: could not remove a message that did not send") }
                }
                throw t
            }
            // ⚠ Past this point the message has gone. A failure from here is **not** a failed
            // send and must not be reported as one: the text is still sitting in the composer,
            // every other failure path says "it did not send", and the obvious response is to
            // press send again -- which sends it twice. See [SentButNotFiled]. The row stays
            // marked sending, so the worst case is a resend at next start with the same
            // timestamp, which every recipient drops as a copy.
            try {
                setSendState(row.id, SignalMessage.SEND_SENT)
            } catch (t: Throwable) {
                throw com.wanderwildwood.kotozute.repository.SentButNotFiled(t)
            }
            runCatching { outbox.clear(row.id) }
            return sentAt
        } finally {
            inFlight.remove(row.id)
        }
    }

    /** Files one of our own messages as on its way, in one transaction with the row. */
    private fun fileOutgoing(row: BridgeMessage) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                store(r, row)
                r.where(SignalMessage::class.java).equalTo("id", row.id).findFirst()
                    ?.sendState = SignalMessage.SEND_SENDING
            }
        }
        // What [ingest] does after filing, for a first message to somebody new.
        renameThreadsFromContacts()
        runCatching { nameGroupThreads() }.onFailure { Timber.w(it, "signal groups: naming failed; the next pass renames") }
    }

    private fun setSendState(id: String, state: Int) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                r.where(SignalMessage::class.java).equalTo("id", id).findFirst()?.sendState = state
            }
        }
    }

    /** Removes one of our own messages that never went. Never one that did. */
    private fun dropUnsent(id: String) {
        removeWithdrawn(id, "an unsent message removed") {
            it.outgoing && it.sendState != SignalMessage.SEND_SENT
        }
        outbox.clear(id)
    }

    override fun discardUnsent(messageId: String) = runOffThread { dropUnsent(messageId) }

    /**
     * Sends one of our own messages again, under its original timestamp.
     *
     * The same timestamp is what makes a second send safe: a recipient that already has the
     * message recognises the (author, timestamp) pair and drops the copy, which is what
     * upstream's `RetryPendingSendSecondCheckJob` relies on too.
     */
    override fun resend(messageId: String): Long {
        val m = Realm.getDefaultInstance().use { realm ->
            realm.where(SignalMessage::class.java).equalTo("id", messageId).findFirst()
                ?.takeIf { it.outgoing && it.sendState != SignalMessage.SEND_SENT }
                ?.let { realm.copyFromRealm(it) }
        } ?: throw com.wanderwildwood.kotozute.repository.SendRefused(
            com.wanderwildwood.kotozute.repository.SendFailure.Unexplained("nothing to send again")
        )
        val attachments = outbox.load(messageId, m.attachments.isNotBlank()) ?: run {
            setSendState(messageId, SignalMessage.SEND_FAILED)
            throw com.wanderwildwood.kotozute.repository.SendRefused(
                com.wanderwildwood.kotozute.repository.SendFailure.AttachmentUnprepared("it is no longer on this phone")
            )
        }
        return sendDirect(m.threadKey, m.body, attachments, quoteFor(m.threadKey, m.quoteTs), resending = m.date)
    }

    /**
     * Sends again whatever a previous process left on its way out.
     *
     * Upstream's `RetryPendingSendsJob`: once per launch, pending messages from the last day
     * go again; older ones are left for a person, marked not sent. Run once the socket is up,
     * because a resend attempted with no connection only turns into "not sent".
     *
     * ⚠ Skips anything this process is sending. A message written a moment ago is pending too,
     * and sending it a second time from here would race its own send.
     */
    private fun resendOrphans() {
        if (!orphansSwept.compareAndSet(false, true)) return
        runOffThread {
            val now = System.currentTimeMillis()
            val pending = Realm.getDefaultInstance().use { realm ->
                realm.where(SignalMessage::class.java)
                    .equalTo("outgoing", true)
                    .equalTo("sendState", SignalMessage.SEND_SENDING)
                    .findAll()
                    .map { it.id to it.date }
            }.filter { (id, _) -> id !in inFlight }
            if (pending.isEmpty()) return@runOffThread
            Timber.w("signal send: %d message(s) were still on their way when the app last stopped", pending.size)
            pending.forEach { (id, sentAt) ->
                if (!com.wanderwildwood.kotozute.signalstore.SignalOutbox.worthResending(sentAt, now)) {
                    runCatching { setSendState(id, SignalMessage.SEND_FAILED) }
                    return@forEach
                }
                runCatching { resend(id) }
                    .onSuccess { Timber.i("signal send: sent again after a restart") }
                    .onFailure { Timber.w(it, "signal send: could not send again after a restart; marked not sent") }
            }
        }
    }

    /**
     * Sends to a group over this device's own connection.
     *
     * The master key comes off the thread, and off a message in it only for the groups that
     * predate the thread holding one. It is the only handle the server will answer questions
     * about the group with, so a thread that has neither cannot be sent to -- which is
     * reported rather than guessed around.
     */
    private fun sendDirectToGroup(
        threadKey: String,
        body: String,
        attachments: List<String>,
        quote: com.wanderwildwood.kotozute.signalstore.SignalQuote? = null,
        resending: Long = 0L
    ): Long {
        if (attachments.isNotEmpty()) {
            throw com.wanderwildwood.kotozute.repository.SendRefused(
                com.wanderwildwood.kotozute.repository.SendFailure.AttachmentsToGroup
            )
        }
        val masterKey = Realm.getDefaultInstance().use { realm ->
            groupMasterKeyFor(realm, threadKey)
        } ?: throw com.wanderwildwood.kotozute.repository.SendRefused(
            com.wanderwildwood.kotozute.repository.SendFailure.NoGroupKey
        )

        val (expiresIn, timerVersion) = timerFor(threadKey)
        val timestamp = if (resending > 0) resending else System.currentTimeMillis()
        val selfAci = signalStore.selfAciOrNull().orEmpty()
        val row =
            com.wanderwildwood.kotozute.signal.BridgeMessage(
                id = "$selfAci:$timestamp",
                threadKey = threadKey,
                ts = timestamp,
                senderUuid = selfAci,
                senderNumber = "",
                outgoing = true,
                body = body,
                groupId = threadKey.removePrefix("group:"),
                quoteTs = quote?.sentAt ?: 0L,
                read = true,
                source = "live",
                attachmentsJson = "",
                // Our own copy of a group send expires on the group's timer too.
                expiresInSeconds = expiresIn.toLong(),
                expiresAt = if (expiresIn > 0) timestamp + expiresIn * 1000L else 0L,
                groupMasterKey = masterKey
            )
        // The same order as the one-to-one send; see [sendThroughOutbox].
        return sendThroughOutbox(row, emptyList(), resending > 0) {
            signalStore.sendToGroup(masterKey, body, expiresIn, timerVersion, quote, timestamp)
        }
    }

    /**
     * Describes what was attached to a message we sent.
     *
     * Deliberately minimal: the type only, and no id. A received attachment records an id the
     * repository can turn into bytes; a sent one has no such copy, and inventing an id that
     * resolves to nothing would make the row claim a file it cannot produce.
     */
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
                    // ⚠ Both ends, or only the far one is right. The wire flag is set from
                    // this same marker in `SignalSender.attachmentStream`, so a voice note
                    // sent from here already arrives as a voice note for the recipient --
                    // while the sender's own copy of it, which is this row, read as a plain
                    // attachment. The message would have looked wrong only to the person who
                    // recorded it, which is the half nobody thinks to check.
                    .put("voice", com.wanderwildwood.kotozute.signal.VoiceNotes.isMarked(dataUri))
                    .put("pending", false)
            )
        }
        return array.toString()
    }

    override fun applyReceipts(senderUuid: String, timestamps: List<Long>, read: Boolean): Int {
        if (timestamps.isEmpty()) return 0
        // ⚠ Read receipts are one setting in both directions, and only the outgoing half was
        // honouring it. Signal gates `handleReadReceipt` (and the viewed one) on the same
        // preference and leaves only delivery ungated -- so with the setting off this phone
        // was showing other people's read marks while every other client on the account showed
        // none, from a setting the owner had turned off.
        //
        // The setting is the account's, taken from the primary; see [applyConfiguration].
        if (read && !prefs.signalReadReceipts.get()) return 0
        // ⚠ Whose receipt it is decides which messages it can touch, and it was being ignored
        // entirely: any outgoing row with a matching sent timestamp was marked, whoever the
        // receipt came from. A timestamp is not a secret -- it rides on every message -- so
        // any contact could replay one and mark this account's messages *to somebody else*
        // delivered or read.
        //
        // Upstream's query is `DATE_SENT = target AND FROM_RECIPIENT_ID = self AND
        // (TO_RECIPIENT_ID = receiptAuthor OR the recipient is not an INDIVIDUAL)`. The last
        // clause is what lets any member of a group receipt a group message; outside a group
        // the receipt has to come from the person it was sent to.
        if (senderUuid.isBlank()) return 0
        val theirThread = "direct:$senderUuid"
        var changed = 0
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                r.where(SignalMessage::class.java)
                    .equalTo("outgoing", true)
                    .`in`("date", timestamps.toTypedArray())
                    // Theirs, or a group's. See above.
                    .beginGroup()
                    .equalTo("threadKey", theirThread)
                    .or()
                    .beginsWith("threadKey", "group:")
                    .endGroup()
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
        // Cosmetic and self-healing: a group whose name did not land shows its identifier
        // until the next sync or message re-runs this. Nothing depends on the name.
        runCatching { nameGroupThreads() }.onFailure { Timber.w(it, "signal groups: naming failed; the next pass renames") }
        return fresh.size
    }

    /**
     * Fetches from this device's own connection.
     *
     * Drain what the server is holding, decrypt, and file through [store] -- the one writer
     * every arriving message goes through, whether it came from here or from the stream.
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
        //
        // ⚠ And the answer to "is this phone behind" is settled here, not left at whatever a
        // failed sync last wrote. Returning without touching it stranded the flag: the only
        // code that can set it true is the branch below, which never runs while the stream
        // owns the socket -- and the stream owns the socket from process start whenever Signal
        // is on. So one failed sync left [lastSyncCaughtUp] false for ever, and the worker that
        // reads it asked WorkManager to retry, for ever, on a phone that was perfectly caught
        // up. Backed off rather than spinning, but wakeups on a phone built to stay asleep.
        //
        // A connected stream is draining the queue continuously, which is the whole claim the
        // flag makes. A stream that is between reconnects is not, and says so rather than
        // claiming to be level -- the next round finds it either connected or stopped.
        syncCaughtUp = streamConnected.get()
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
        // Somebody asked for a message again and the send did not happen. Upstream retries
        // that for a day with a job; this round is the nearest thing here, and it runs whether
        // or not anything has arrived -- which is the case the end of a receive batch cannot
        // cover, because on a quiet phone there is no batch.
        runCatching { signalStore.retryOwedResends() }
            .onFailure { Timber.w(it, "signal retry: could not try the owed resends") }
        // And the mirror of it: somebody whose message arrived here and was never told so.
        runCatching { signalStore.retryOwedReceipts() }
            .onFailure { Timber.w(it, "signal receipt: could not try the owed receipts") }

        // One number, one row. Upstream repairs this with a migration on every install
        // (`DuplicateE164MigrationJob`); here the merge logic prevents it at write time and
        // this is the check that it has always held. Cheap: one grouped read of a small table.
        runCatching { signalStore.reportDuplicateNumbers() }
            .onFailure { Timber.w(it, "signal contacts: the duplicate-number check did not run") }
        runCatching { signalStore.reportMalformedNumbers() }
            .onFailure { Timber.w(it, "signal contacts: the number-shape check did not run") }

        // The phone-number identity still running on keys the primary made for it. Upstream's
        // `PreKeysSyncJob` reads the same flag and clears it once it has rotated; this round is
        // what stands in for the job.
        if (prefs.signalPniRotationOwed.get()) {
            runCatching { signalStore.rotatePniIfOwed() }
                .onSuccess { done -> if (done) prefs.signalPniRotationOwed.set(false) }
                .onFailure { Timber.w(it, "signal keys: could not rotate the phone-number identity") }
        }
        // ⚠ The debt first, and it does NOT go through maintenance. Maintenance is gated on
        // the stored signed key's age, so on a device that still owes its first upload it
        // answers "not due" and does nothing -- which is the whole reason the debt is recorded
        // rather than left to the periodic pass to notice.
        if (prefs.signalPreKeysOwed.get()) {
            runCatching { signalStore.uploadPreKeys() }
                .onSuccess {
                    prefs.signalPreKeysOwed.set(false)
                    Timber.i("signal keys: the owed first upload went up -- %s", it)
                }
                .onFailure { Timber.w(it, "signal keys: still owe the first pre key upload") }
        }
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
        watchNetwork()

        run {
            Timber.i("signal: holding our own socket")
            // ⚠ Not once per start, which is what this was. A linked device knows nobody
            // until the primary answers, so the ask has to happen before the loop -- but the
            // answer costs the *other* phone a full contact upload every single time, because
            // the primary treats a request as `forceSync` and skips the six-hour cooldown it
            // puts on its own syncs. See [Preferences.signalLastContactRequest]. Android
            // restarts this process often; that was several full syncs a day on a phone that
            // did not ask for any of them.
            runOffThread {
                val askedAt = prefs.signalLastContactRequest.get()
                if (contactRequestDue(askedAt, System.currentTimeMillis())) {
                    runCatching { signalStore.requestContacts() }
                        .onSuccess {
                            // Stamped only on success. A request that did not go is one the
                            // primary never heard, and recording it would buy six hours of
                            // silence for an ask that never happened.
                            prefs.signalLastContactRequest.set(System.currentTimeMillis())
                            Timber.i("signal contacts: %s", it)
                        }
                        // Asking again is free and happens on the next connection; not
                        // asking costs only that this list is a sync later than it could be.
                        .onFailure { Timber.w(it, "signal contacts: could not ask; the next connection asks again") }
                } else {
                    Timber.i(
                        "signal contacts: asked %d hour(s) ago; not asking the primary again yet",
                        java.util.concurrent.TimeUnit.MILLISECONDS.toHours(
                            System.currentTimeMillis() - askedAt
                        )
                    )
                }
                // And the account's settings, for the same reason: they are volunteered only
                // when they change, so a device that never asks follows its own default rather
                // than the account. Read receipts are the one that shows.
                runCatching { signalStore.requestConfiguration() }
                    .onSuccess { Timber.i("signal configuration: %s", it) }
                    // The account's settings, not this phone's: until it answers, the
                    // local defaults stand and the next connection asks again.
                    .onFailure { Timber.w(it, "signal configuration: could not ask; the next connection asks again") }
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
                        // The key is still held, so every later read uses it; this one
                        // failing costs a sync of freshness, not the ability to read at all.
                        .onFailure { Timber.w(it, "signal storage: could not read; later reads use the same key") }
                    // Straight after the read, because that is where a write would go and
                    // because a row appearing here *because of* the read is the loop worth
                    // catching. Logged and sent nowhere -- see [logStoragePushDiff].
                    logStoragePushDiff()
                }

                // And whether Signal's public log still agrees with the server about who this
                // account is. Last in the block because it is the only thing here that is not
                // needed for the app to work -- everything above buys a working conversation,
                // this buys the knowledge that the conversation is with who it claims.
                // ⚠ Its own thread, not the startup thread. The first attempt on hardware
                // stopped inside the check and logged nothing further, and `withTimeout` did
                // not release it -- a coroutine only cancels at a suspension point, and a
                // native wait inside libsignal is not one. Until that is diagnosed, the cost
                // of it wedging is one daemon thread rather than the block that connects the
                // socket, reads the account's records and asks the primary for contacts.
                // ⚠ Its own thread. The check ends in a socket call, and nothing else in this
                // block should wait on it: everything above buys a working conversation, and
                // this buys the knowledge that the conversation is with who it claims to be.
                thread(name = "signal-kt", isDaemon = true) { checkKeyTransparency() }
            }
            thread(name = "signal-listen-$generation", isDaemon = true) { listenLoop(generation) }
        }
    }

    /**
     * Asks the key transparency log about this account, at most weekly.
     *
     * The schedule, the gates and the response to a failure are all
     * [com.wanderwildwood.kotozute.signalstore.SignalKeyTransparency]'s, which is Signal's. This
     * is the part that has to live here: the two flags that survive a restart, and what a second
     * failure does in an app with no sheet to show.
     *
     * ⚠ **A first failure is answered by re-reading rather than by telling anybody.** Signal
     * enqueues a storage sync and an attribute refresh and checks again tomorrow, because a
     * mismatch is far more often this device holding something stale than a server lying. Only
     * the second failure running reaches a person, and here that means the `error` field on the
     * published state — the same channel an unlinked device or a dead socket uses, because it
     * is the one place this app already says "something about this account is wrong".
     */
    private fun checkKeyTransparency() {
        // ⚠ **Bounded, and that is not a detail.** This runs on the shared startup thread, and
        // the check ends in a call that waits on the unauthenticated socket. Unbounded, a socket
        // that never answers wedges that thread for the life of the process -- the same shape as
        // the read timeout that once wedged the receive stream. A check that gives up is a check
        // that did not happen, which is a state this already has a name for; a check that hangs
        // is a bug with no symptom.
        // Bracketing the call, because the first attempt produced no line at all and there
        // was no way to tell a check that never started from one that never returned.
        Timber.i("signal kt: starting a check")
        // ⚠⚠ **Not `runBlocking`, and the reason is worth the paragraph.** The obvious bridge
        // from this thread to a suspend function is `runBlocking`, and it does not come back.
        // Instrumenting every step showed the check running to completion — *including the line
        // after the socket call returned* — and the caller never receiving the result.
        // `runBlocking` waits for its coroutine **and any children still alive in its scope**,
        // and the socket call leaves work running there. The body finishes; the bridge does not.
        //
        // ⚠ A `withTimeout` around it did not help, and looked like it should. A coroutine
        // cancels at suspension points, and neither a native wait inside libsignal nor a blocked
        // `runBlocking` is one. That is a bound which reads as a bound and is not.
        //
        // So the check runs in a scope this owns and the answer is collected with a latch that
        // really does expire. A check that gives up is a check that did not happen, which is a
        // state this already has a name for; a check that hangs is a bug with no symptom.
        val answer = java.util.concurrent.atomic.AtomicReference<SignalKeyTransparency.Outcome?>()
        val done = java.util.concurrent.CountDownLatch(1)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        scope.launch {
            runCatching {
                signalStore.checkKeyTransparency(
                    now = System.currentTimeMillis(),
                    nextDueAt = prefs.signalNextKeyTransparencyCheck.get(),
                    alreadyFailing = prefs.signalKeyTransparencyFailed.get(),
                    setNextDueAt = { prefs.signalNextKeyTransparencyCheck.set(it) }
                )
            }.onSuccess { answer.set(it) }
                .onFailure { Timber.w(it, "signal kt: the check could not be made") }
            done.countDown()
        }

        val finished = done.await(CHECK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        // Cancelled either way. On success there may still be children holding the scope open —
        // which is the finding above — and on a timeout there certainly are.
        scope.cancel()
        val outcome = answer.get()
        if (!finished || outcome == null) {
            Timber.w("signal kt: no answer within %d ms; that is not a failed check", CHECK_TIMEOUT_MS)
            return
        }

        // ⚠ **Unconditional, and it is the whole reason this took a evening to understand.**
        // The `Verified` arm below said nothing unless a previous check had failed, so a check
        // that ran and passed looked exactly like a check that never returned — and it was read
        // as a hang, through several rebuilds, while it was working the entire time. Upstream
        // logs every outcome before deciding anything about it
        // (`CheckKeyTransparencyJob.doRun`: "Key transparency complete, result: ..."), and the
        // reason is this one.
        Timber.i("signal kt: %s", outcome)

        when (outcome) {
            is SignalKeyTransparency.Outcome.Skipped ->
                Timber.i("signal kt: not checked -- %s", outcome.because)

            is SignalKeyTransparency.Outcome.Unreachable ->
                // Never a failure. The network saying nothing is not the log disagreeing.
                Timber.i("signal kt: could not reach the log -- %s", outcome.reason)

            is SignalKeyTransparency.Outcome.Verified -> {
                if (prefs.signalKeyTransparencyFailed.get()) {
                    Timber.i("signal kt: verified; the earlier disagreement is resolved")
                }
                prefs.signalKeyTransparencyFailed.set(false)
                prefs.signalKeyTransparencyFailureSeen.set(false)
            }

            is SignalKeyTransparency.Outcome.Failed -> {
                prefs.signalKeyTransparencyFailed.set(true)
                if (!outcome.tellSomebody) {
                    // The self-correcting half, and the reason a first failure is quiet: ask
                    // the account what it holds and tell it what this device holds, then look
                    // again. Upstream enqueues StorageSyncJob and RefreshAttributesJob here.
                    Timber.w("signal kt: disagreed once -- refreshing what is known and asking again tomorrow")
                    prefs.signalNextKeyTransparencyCheck.set(
                        System.currentTimeMillis() + java.util.concurrent.TimeUnit.DAYS.toMillis(1)
                    )
                    if (signalStore.storageKeyKnown()) {
                        runCatching { signalStore.readStorage() }
                            .onFailure { Timber.w(it, "signal kt: could not re-read the account's records") }
                    }
                    runCatching { signalStore.refreshCapabilities() }
                        .onFailure { Timber.w(it, "signal kt: could not re-send this device's attributes") }
                } else {
                    Timber.e("signal kt: disagreed twice -- saying so")
                    prefs.signalKeyTransparencyFailureSeen.set(true)
                    publishState(
                        signalConnected = state.value?.signalConnected ?: false,
                        error = context.getString(com.wanderwildwood.kotozute.data.R.string.signal_key_transparency_failed)
                    )
                }
            }
        }
    }

    override fun stopStream() {
        streamWanted.set(false)
        unwatchNetwork()
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
     * Reconnects on failure with a backoff, and gives up its generation when it is replaced,
     * so two loops cannot both believe they are current.
     */
    private fun listenLoop(generation: Int) {
        var attempts = 0
        try {
            while (streamWanted.get() && streamGeneration.get() == generation) {
                val connectedAt = System.currentTimeMillis()
                try {
                    streamConnected.set(true)
                    // ⚠ **A refusal that outlives its own condition is a dead end.** The
                    // server refuses a deregistered device every time, so reaching this line
                    // at all is proof that whatever was recorded is no longer true. Until
                    // this, the reason was cleared only by unpairing or by linking again --
                    // so a device that recovered by any other route (a corrected
                    // configuration, a transient server refusal, registering) kept telling
                    // its owner to link it again while quietly receiving their messages.
                    if (prefs.signalRejected.get().isNotBlank()) {
                        Timber.i("signal: the socket authenticated; clearing the old refusal")
                        prefs.signalRejected.set("")
                    }
                    // The same proof ends an outage: the server has just answered. Upstream
                    // waits for the next failed send to look again, which on a phone that
                    // sends little can leave the warning up for days after it stopped being
                    // true.
                    if (prefs.signalServiceOutage.get()) {
                        Timber.i("signal: the socket authenticated; the outage is over")
                        prefs.signalServiceOutage.set(false)
                    }
                    resendOrphans()
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
                        // ⚠ Not straight back into no network. An attempt that hangs for its
                        // whole timeout with nothing to reach also lands here, and without this
                        // the loop went round every half minute through airplane mode.
                        if (!networkAvailable) waitToReconnect(0)
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
                        waitToReconnect(wait)
                    } else {
                        Timber.w(t, "signal: listen failed; reconnecting")
                        // No backoff on the first failure, as upstream -- but not into a
                        // network that is not there.
                        if (!networkAvailable) waitToReconnect(0)
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

        // A reaction is not a message. It arrives as its own envelope and belongs on the
        // message it points at, not in the thread as a bubble of its own.
        if (m.reactionEmoji.isNotEmpty() && m.reactionTarget.isNotEmpty()) {
            applyReaction(realm, m)
            // Never "new" in the sense that rings: a reaction is not a message arriving.
            return false
        }
        val existing = realm.where(SignalMessage::class.java).equalTo("id", m.id).findFirst()
        val isNew = existing == null

        // ⚠ Two things belong to this device and not to whatever is arriving, and both were
        // being overwritten.
        //
        // The server redelivers an envelope whose ack it did not hear, and an edit rewrites
        // the row it names on purpose -- so "the row already exists" is two situations, and
        // this path serves both. Signal never has to tell them apart: a UNIQUE
        // (date_sent, from_recipient_id, thread_id) makes a redelivered insert fail outright,
        // and an edit goes through its own handler. Here they share a door, so the fields that
        // must survive it are named rather than the door being shut.
        //
        // `read` is one: a message the reader has read does not become unread because the
        // server said it again, and an edit does not un-read it either. The thread popping
        // back to unread with a notification is what that looked like.
        //
        // `expiresAt` is the other, and worse: it is *when this copy disappears*, started by
        // reading it. Rewriting it from the wire sets it back to 0 on an already-read
        // disappearing message -- un-starting a countdown that had begun, which leaves the
        // copy on this phone after it has gone everywhere else. A timer that has started is
        // not something an arriving message gets to restart.
        val wasRead = existing?.read == true
        val countdownStarted = existing?.expiresAt ?: 0L

        // ⚠ A message never changes the conversation it is in.
        //
        // This is Signal's `validGroup` check, made where the target is actually known.
        // Upstream compares an edit's group against the *target message's* thread and drops
        // the edit when they disagree; the equivalent here is that an arriving message may
        // rewrite a row it names but may not move it. Without this, an edit carrying no group
        // context resolved to `direct:<sender>` and pulled one of the sender's own group
        // messages out of the group -- on this device only, so the two phones disagreed about
        // where a conversation's messages were.
        if (existing != null && existing.threadKey != m.threadKey) {
            Timber.w("signal: a message tried to move to another conversation; left where it is")
            return false
        }

        val row = existing ?: realm.createObject(SignalMessage::class.java, m.id)
        row.threadKey = m.threadKey
        row.date = m.ts
        row.senderUuid = m.senderUuid
        row.senderNumber = m.senderNumber
        row.outgoing = m.outgoing
        row.body = m.body
        row.groupId = m.groupId
        row.quoteTs = m.quoteTs
        row.read = m.read || wasRead
        row.source = m.source
        // A view-once attachment is never stored. Signal's promise is that it can be opened
        // once; a copy in Realm is a copy that can be opened for ever. The row stays so the
        // thread does not have a silent hole where a message was.
        row.attachments = if (m.viewOnce) "" else m.attachmentsJson
        row.expiresAt = if (countdownStarted > 0L) countdownStarted else m.expiresAt
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
        // And on the thread, which is where it is looked up from. Backfills the groups that
        // existed before there was anywhere on a thread to keep it.
        if (m.groupMasterKey != null && thread.groupMasterKey == null) {
            thread.groupMasterKey = m.groupMasterKey
        }
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
     * A reaction can outrun its message -- envelopes arrive in the order the server held
     * them, not in the order they refer to each other -- and one whose target is not here yet
     * is dropped rather than held. Signal resends nothing, so a queue would never drain.
     */
    private fun applyReaction(realm: Realm, m: BridgeMessage) {
        val target = realm.where(SignalMessage::class.java)
            .equalTo("id", m.reactionTarget)
            .findFirst() ?: return

        // Our own reaction is recorded as "me" rather than as this account's uuid. It is what
        // every row already written says, and the row knowing on its own face that it is ours
        // is what taking one back reads -- no lookup, and nothing to get wrong for an account
        // whose identifiers have changed under it.
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

    override fun send(threadKey: String, body: String, attachments: List<String>, quoteTs: Long): Long =
        try {
            sendDirect(threadKey, body, attachments, quoteFor(threadKey, quoteTs))
        } catch (t: Throwable) {
            if (ServiceOutage.worthChecking(t)) checkServiceOutage()
            throw t
        }

    /**
     * The message being replied to, read off this thread.
     *
     * Null when there is nothing to quote, or when the message is not here or its author is
     * not known -- the reply then goes as an ordinary message, as upstream's `getQuoteFor`
     * does when it cannot name an author.
     */
    private fun quoteFor(threadKey: String, quoteTs: Long): com.wanderwildwood.kotozute.signalstore.SignalQuote? {
        if (quoteTs == 0L) return null
        val selfAci = signalStore.selfAciOrNull().orEmpty()
        return Realm.getDefaultInstance().use { realm ->
            val original = realm.where(SignalMessage::class.java)
                .equalTo("threadKey", threadKey)
                .equalTo("date", quoteTs)
                .findFirst()
                ?: return@use null
            val author = if (original.outgoing) selfAci else original.senderUuid
            if (author.isBlank()) return@use null
            // One attachment at most, as upstream quotes one.
            val first = runCatching { org.json.JSONArray(original.attachments).optJSONObject(0) }.getOrNull()
            com.wanderwildwood.kotozute.signalstore.SignalQuote(
                sentAt = original.date,
                author = author,
                text = original.body,
                attachmentType = first?.optString("type"),
                attachmentName = first?.optString("filename")
            )
        }
    }

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
        // See [forgetFromResendLog]. A delete that arrived from another device is still a
        // delete: the message must stop being resendable here too.
        val removedSentAt = mutableListOf<Long>()
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                messages.forEach { (author, at) ->
                    val row = r.where(SignalMessage::class.java)
                        .equalTo("id", "$author:$at").findFirst() ?: return@forEach
                    touched += row.threadKey
                    removedFiles += attachmentIdsOf(row.attachments).orEmpty()
                    if (row.outgoing) removedSentAt += row.date
                    row.deleteFromRealm()
                }
                threads.forEach { key ->
                    val all = r.where(SignalMessage::class.java)
                        .equalTo("threadKey", key)
                        .findAll()
                    if (all.isNotEmpty()) {
                        touched += key
                        removedFiles += all.flatMap { attachmentIdsOf(it.attachments).orEmpty() }
                        removedSentAt += all.filter { it.outgoing }.map { it.date }
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
        forgetFromResendLog(removedSentAt)
        if (removedFiles.isNotEmpty()) {
            runCatching { signalStore.forgetAttachments(removedFiles) }
                // ⚠ Files left behind, and the rows are already gone. Nothing re-runs this,
                // so the bytes sit in private storage until the abandoned-attachment sweep
                // notices nothing references them. Storage, not privacy: the conversation is
                // gone from the app either way.
                .onFailure { Timber.w(it, "signal delete sync: could not remove attachments; the sweep collects them") }
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
        // The timestamp of a withdrawn message of *ours*, so the resend log can forget it.
        var oursSentAt = 0L
        val doomed = mutableListOf<String>()
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val row = r.where(SignalMessage::class.java).equalTo("id", id).findFirst()
                    ?: return@executeTransaction
                if (!allow(row)) return@executeTransaction
                threadKey = row.threadKey
                if (row.outgoing) oursSentAt = row.date
                // Whatever was attached goes with it. Withdrawing a message is somebody
                // unsaying something; leaving the picture on disk unsays nothing.
                doomed += attachmentIdsOf(row.attachments).orEmpty()
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
            // ⚠ And it stops being resendable, which it did not. The log keeps the plaintext
            // of everything sent so it can go again if somebody's device says it could not
            // read it -- and a withdrawn message is exactly the one that must never go again.
            // Left there, a retry receipt arriving any time in the next fortnight would have
            // this device deliver the message the user was told had been taken back, to the
            // person it was taken back from. Signal drops the payloads in the same
            // transaction as the delete.
            if (oursSentAt > 0) {
                runCatching { signalStore.forgetSentMessage(oursSentAt) }
                    .onFailure { Timber.w(it, "signal: could not drop a withdrawn message from the resend log") }
            }
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

        override fun readElsewhere(read: List<Pair<String, Long>>, readAt: Long) =
            applyReadElsewhere(read, readAt)

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

        override fun numberChanged(aci: String, from: String, to: String) {
            // ⛔ **The direct conversation only, and not the groups they are in.** Upstream's
            // `MessageTable.insertNumberChangeMessages` writes the note into the direct thread
            // *and* every active group containing that person, which it can do for nothing:
            // `groups.getGroupsContainingMember` is a local table lookup.
            //
            // This app does not keep group membership. A group's members are fetched from the
            // server when they are needed (`SignalGroups.fetch`), so matching upstream here
            // would mean a round trip **per group** every time a contact sync reports a new
            // number -- a burst of network calls, triggered by a background event, to add a
            // line nobody is waiting for. The note is worth having where the conversation is
            // about that one person; it is not worth that.
            //
            // Recorded as a decision rather than left as an absence, because the two look
            // identical from the outside and only one of them can be argued with.
            //
            // ⚠ Nothing about somebody who has been blocked. Upstream gates **every**
            // `ChangeNumberInsert` on `!record.isBlocked` -- five sites in `RecipientTable`,
            // all carrying it -- so a blocked contact's number changing is applied silently
            // and never written into the conversation. Blocking somebody and then being told
            // about their new number is the opposite of what blocking was for.
            //
            // Deliberately narrow: upstream puts this guard on the number-change insert, so
            // it goes here rather than in `noteLocalEvent`, where it would quietly change
            // what a name change does too on no evidence.
            if (signalStore.isBlocked(aci)) {
                Timber.i("signal: a blocked contact's number changed; applied, not announced")
                return
            }
            noteLocalEvent(
                aci,
                context.getString(
                    com.wanderwildwood.kotozute.data.R.string.signal_number_changed, from, to
                )
            )
        }

        override fun profileNameChanged(aci: String, from: String, to: String) =
            noteNameChange(aci, from, to)

        override fun undecryptableGaveUp(
            sender: String,
            sentTimestamp: Long,
            groupId: ByteArray?
        ) = noteUndecryptable(sender, sentTimestamp, groupId)

        override fun cannotShow(
            sender: String,
            sentTimestamp: Long,
            groupId: ByteArray?,
            reason: com.wanderwildwood.kotozute.signalstore.CannotShow
        ) = notePlaceholder(
            sender, sentTimestamp, groupId,
            when (reason) {
                com.wanderwildwood.kotozute.signalstore.CannotShow.NEEDS_NEWER_APP ->
                    com.wanderwildwood.kotozute.data.R.string.signal_message_needs_update
                com.wanderwildwood.kotozute.signalstore.CannotShow.SENDER_TOO_OLD ->
                    com.wanderwildwood.kotozute.data.R.string.signal_message_sender_too_old
                com.wanderwildwood.kotozute.signalstore.CannotShow.UNREADABLE_FORM ->
                    com.wanderwildwood.kotozute.data.R.string.signal_message_unreadable_form
            }
        )

        override fun rotatePreKeys() {
            // ⚠ Asked, not obeyed. A prekey message that will not open indicts the bundle it
            // was built against, so replacing the keys is the right instinct -- and acting on
            // the instinct alone let anyone who can send this device traffic drive a full
            // rotation of both identities, on the receive thread, once per bad envelope. Every
            // rotation invalidates the bundles other people are already holding, so that is a
            // way to break sends that were about to work.
            //
            // The server is asked first now, and the clock second; see
            // [PreKeyUploader.rotateIfKeysAreWrong].
            runCatching {
                signalStore.rotatePreKeysIfWrong(prefs.signalLastForcedKeyRotation.get()) {
                    prefs.signalLastForcedKeyRotation.set(it)
                }
            }
                .onSuccess { Timber.i("signal keys: %s", it) }
                // The retry goes ahead regardless. This is a freshness check, and sending
                // with keys a little older than ideal is what the retry was for; refusing to
                // retry because the check failed would turn a maybe into a certainly-not.
                .onFailure { Timber.w(it, "signal keys: could not check or replace before a retry; retrying anyway") }
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
        // ⚠ Reached from every message now, not only from the one that announces a change --
        // see [ContentNormalizer.timerUpdateIn] -- so it has to decide whether anything
        // actually differs before it writes or says anything. Upstream's
        // `handlePossibleExpirationUpdate` is the same shape: it acts only when the message's
        // timer disagrees with the thread's, or carries a newer version.
        var changed = false
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val thread = r.where(SignalThread::class.java)
                    .equalTo("threadKey", threadKey)
                    .findFirst() ?: return@executeTransaction
                if (version != 0 && version < thread.expireTimerVersion) {
                    Timber.i("signal timer: ignored a timer change older than the one in force")
                    return@executeTransaction
                }
                val newer = version != 0 && version > thread.expireTimerVersion
                if (thread.expiresInSeconds == seconds && !newer) return@executeTransaction
                thread.expiresInSeconds = seconds
                if (version != 0) thread.expireTimerVersion = version
                changed = true
            }
        }
        if (!changed) return@runOffThread
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
     * The earliest each group's state may be asked for again after a fetch that did not come off.
     *
     * Separate from [groupRevisions] on purpose: that one records what has been *read*, and
     * writing a revision into it is a claim the state at that revision is in hand. A failed
     * fetch has to leave that claim unmade, so the cooldown is what stops a batch of group
     * messages becoming a fetch each in the meantime.
     *
     * A minute, which is the receive loop's own read timeout, so the next attempt lands on the
     * next thing that happens rather than on a timer of its own. Upstream leaves the pacing to
     * the job manager's backoff inside `RequestGroupV2InfoWorkerJob`.
     */
    private val groupFetchNotBefore = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val GROUP_FETCH_RETRY_MS = java.util.concurrent.TimeUnit.MINUTES.toMillis(1)

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

        // ⚠ A cooldown rather than recording the revision up front. It used to write
        // groupRevisions[id] = revision *before* fetching, so a fetch that failed still
        // counted as done: every later message at that revision returned early here, and the
        // group's new name and timer were never read. A rename was then lost until somebody
        // changed the group *again*, to a higher revision.
        //
        // Upstream has no such trap because the fetch is a job --
        // `RequestGroupV2InfoWorkerJob`, a day of unlimited attempts, retried on a network
        // error and queued per group. The cooldown is what stands in for that queue: it stops
        // a batch of group messages becoming a fetch each, without pretending the fetch
        // happened.
        val now = System.currentTimeMillis()
        if (now < (groupFetchNotBefore[id] ?: 0L)) return
        groupFetchNotBefore[id] = now + GROUP_FETCH_RETRY_MS

        runOffThread {
            val group = runCatching { signalStore.groupFor(masterKey) }.getOrNull()
            if (group == null) {
                Timber.w("signal group: could not read the group's state; will come back to it")
                return@runOffThread
            }
            // Only now, with the state actually in hand.
            groupRevisions[id] = revision
            val title = group.title.takeIf { it.isNotBlank() } ?: ""
            // The thread this group is, derived rather than searched for. This used to walk
            // every group thread's messages looking for one carrying the master key -- the
            // pre-Realm-26 way, and a spelling the sweep that replaced the others did not
            // match. A group with no message carrying a key was never renamed at all.
            val threadKey = "group:" + com.wanderwildwood.kotozute.signalstore.ContentNormalizer
                .groupIdForCheck(masterKey)
            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction { r ->
                    r.where(SignalThread::class.java)
                        .equalTo("threadKey", threadKey)
                        .findAll()
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
    private fun applyReadElsewhere(read: List<Pair<String, Long>>, readAt: Long) = runOffThread {
        if (read.isEmpty()) return@runOffThread
        val ids = read.map { (sender, at) -> "$sender:$at" }
        // ⚠ Not `System.currentTimeMillis()`, which is what this used. A disappearing
        // message's clock starts when somebody reads it, and when the reading happened on
        // another device the only honest answer is the timestamp that device sent -- which is
        // now a parameter. Dating it from local now meant a handset catching up on a backlog
        // restarted every countdown from the moment it reconnected, so the copy on the phone
        // that was *off* is the one that outlives the timer.
        //
        // Signal passes the sync's timestamp as `proposedExpireStarted` and takes
        // `min(proposed, existing)` where a clock has already started, which is the same rule
        // as the earlier of the two deadlines below.
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
                        if (row.expiresInSeconds > 0) {
                            val proposed = readAt + row.expiresInSeconds * 1000L
                            row.expiresAt =
                                if (row.expiresAt == 0L) proposed else minOf(row.expiresAt, proposed)
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
        if (justRead.isEmpty()) return@runOffThread

        // ⚠ The account's own devices first, and whatever the receipt setting says.
        //
        // These are two different messages to two different audiences and only one of them is
        // a courtesy. A read receipt goes to the person who wrote the message; a read *sync*
        // goes to this account's other devices and is the only thing that stops a conversation
        // read here from sitting unread and notifying on the primary and on Desktop for ever.
        // Both were behind the receipt preference, so turning receipts off also stopped this
        // phone telling *itself* anything -- and even with them on, nothing sent one at all.
        //
        // Upstream keeps the order and the independence: `MarkReadReceiver` runs
        // `MultiDeviceReadUpdateJob.enqueue(...)` before it considers receipts, and only
        // `SendReadReceiptJob` consults the preference.
        // ⚠ Kept when it does not go. Upstream's `MultiDeviceReadUpdateJob` is a day of
        // unlimited attempts; one attempt and a log line meant a blip left the primary and
        // Desktop notifying for ever about a conversation already read here -- which is the
        // exact thing this call exists to stop, said two paragraphs up.
        val pairs = justRead.filterNot { it.first.isBlank() }
        val toldOurselves = runCatching { signalStore.sendReadSync(pairs) }
            .onFailure { Timber.w(it, "signal read sync: telling our own devices threw") }
            .getOrDefault(false)
        if (!toldOurselves && pairs.isNotEmpty()) {
            Timber.w("signal read sync: could not tell our own devices; will keep trying")
            signalStore.oweReadSync(pairs)
        }

        if (!prefs.signalReadReceipts.get()) return@runOffThread

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
                val distinct = timestamps.distinct()
                // ⚠ Two faults in four lines, and the same two as everywhere else on this
                // axis. onSuccess fired whether or not the receipt *went* -- the call returns
                // false when it is refused and raises nothing -- so a refusal was announced as
                // a delivery. And the failure was logged at debug, which a release build does
                // not keep, so the one arm that did notice said it where nobody could read it.
                val went = runCatching { signalStore.sendReadReceipt(sender, distinct) }
                    .onFailure { Timber.w(it, "signal receipt: sending a read receipt threw") }
                    .getOrDefault(false)
                if (went) {
                    Timber.i("signal receipt: told somebody about %d message(s)", distinct.size)
                } else {
                    // Upstream's SendReadReceiptJob is the same day of unlimited attempts the
                    // rest of these get. The setting is checked again when it is sent.
                    Timber.w("signal receipt: could not send a read receipt; will keep trying")
                    signalStore.oweReadReceipt(sender, distinct)
                }
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

                // Then Signal's own name for them. Without this step a group member who is
                // not in the address book and has never been messaged one to one fell
                // straight through to a phone number, which is what somebody in a group
                // reported seeing beside one person's messages while everybody else in the
                // same group read correctly: the difference was only that the others had
                // direct threads. Signal knows that name -- it arrives with the profile --
                // and it was simply never asked.
                val fromSignal = runCatching { signalStore.contactName(uuid) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }

                out[uuid] = fromContacts
                    ?: fromThread
                    ?: fromSignal
                    ?: number.ifBlank { uuid.take(SignalDirectory.SHORT_SERVICE_ID) }
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
            // ⚠ Pinned first, which this ignored. Pin is offered on every Signal row and the
            // model calls it "kept at the top of the list", and then the list sorted purely by
            // date -- so the one thing pinning promises was the one thing it did not do.
            // Signal's conversation list splits on the pin and orders only the remainder by
            // date; the same split, expressed as a sort, is what a Realm query can say.
            .sort(arrayOf("pinned", "lastTs"), arrayOf(Sort.DESCENDING, Sort.DESCENDING))
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
     * never send it. Returns what happened, including when the answer has to arrive later,
     * for the screen to word.
     */
    override fun fetchContactsFromSignal(): SignalRepository.ContactsReport = when {
        !linkedDirectly() -> SignalRepository.ContactsReport.NotLinked
        // ⚠ Reads the records AND tops up what is missing. The pool is not the storage key:
        // a phone linked under an older build has the second and not the first, and this
        // branch used to return before ever asking -- so the one request that could fill it
        // in was gated behind a condition that phone can never satisfy again.
        signalStore.storageKeyKnown() -> signalStore.readStorage()
            .also { contactsChanged() }
            .also {
                if (!signalStore.backupKeyKnown()) {
                    Timber.i(
                        "signal keys: %s",
                        runCatching { signalStore.requestKeys() }.getOrElse { it.message.orEmpty() }
                    )
                }
            }
        // ⚠ A **primary** with no key material must not be sent down the branch below.
        // `requestKeys()` asks the account's primary device to send the account entropy pool,
        // and on this phone that is this phone: the request goes to nobody, no answer ever
        // arrives, and the message underneath says to wait for one. That is a dead end with
        // a reassuring caption on it -- the shape of thing that reads as "Signal is slow"
        // for ever rather than as a fault.
        //
        // A primary has nobody to ask because it is the source, so it makes the key instead.
        signalStore.ensureAccountKeysForPrimary() -> signalStore.readStorage()
            .also { contactsChanged() }

        else -> {
            val asked = runCatching { signalStore.requestKeys() }.getOrElse { it.message.orEmpty() }
            Timber.i("signal keys: %s", asked)
            // The answer comes back through the socket, and reading the list follows it; see
            // the callback in SignalStore.
            SignalRepository.ContactsReport.Requested
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

    override fun discoverContactsByNumber(): SignalRepository.ContactsReport = when {
        !linkedDirectly() -> SignalRepository.ContactsReport.NotLinked
        else -> {
            val numbers = addressBookNumbers()
            if (numbers.isEmpty()) {
                SignalRepository.ContactsReport.NoNumbers
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
        // ⚠ Collected before the rows go, because afterwards nothing says which files belonged
        // to them. Deleting a conversation left every photo and voice note in it sitting in
        // `files/signal-attachments`, unreferenced and unreachable, for the life of the
        // install -- so "delete this conversation" removed the words and kept the pictures.
        // Signal reclaims them as a matter of course: `deleteConversations` enqueues
        // `DeleteAbandonedAttachmentsJob` once the messages are gone.
        val doomed = mutableListOf<String>()
        // ⚠ And the same for the resend log, which nothing cleared. It holds the plaintext of
        // everything this device has sent, so it can go again if somebody's client says it
        // could not read it -- and a message deleted from this phone is one that must not go
        // again. Left there, a retry receipt arriving any time in the next fortnight would
        // deliver a message out of a conversation the person had deleted, and the plaintext
        // sat in a plaintext-at-rest table whose header justifies itself on holding only live
        // messages. Signal has a SQL trigger on message delete that drops the payloads; the
        // trigger is not portable here -- the messages are in Realm and the log in the
        // SQLCipher store -- but purging by sent timestamp is.
        val sentTimestamps = mutableListOf<Long>()
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val messages = r.where(SignalMessage::class.java)
                    .equalTo("threadKey", threadKey)
                    .findAll()
                removed = messages.size
                doomed += messages.flatMap { attachmentIdsOf(it.attachments).orEmpty() }
                sentTimestamps += messages.filter { it.outgoing }.map { it.date }
                messages.deleteAllFromRealm()
                r.where(SignalThread::class.java)
                    .equalTo("threadKey", threadKey)
                    .findAll()
                    .deleteAllFromRealm()
            }
        }
        if (doomed.isNotEmpty()) {
            runCatching { signalStore.forgetAttachments(doomed) }
                // As with the delete sync above: the conversation is gone and these bytes
                // are orphaned rather than exposed. The abandoned-attachment sweep collects
                // what nothing references.
                .onFailure { Timber.w(it, "signal: could not remove a deleted conversation's files; the sweep collects them") }
        }
        forgetFromResendLog(sentTimestamps)
        Timber.i(
            "signal: a conversation was deleted from this phone, %d message(s), %d file(s)",
            removed, doomed.size
        )
        return removed
    }

    /**
     * Makes deleted messages un-resendable.
     *
     * ⚠ The resend log keeps the plaintext of everything this device has sent so it can go
     * again when somebody's client says it could not read it. That is exactly what a deleted
     * message must not do: a retry receipt arriving any time in the next fortnight would
     * deliver a message the person had deleted -- or, for a message withdrawn with "delete for
     * everyone", deliver it back to the person it was withdrawn from.
     *
     * Signal drops the payloads by SQL trigger on message delete, and explicitly with
     * `deleteAllRelatedToMessage` on a remote delete. The trigger cannot be ported -- the
     * messages live in Realm and the log in the SQLCipher protocol store, so there is no table
     * for it to fire on -- but purging by sent timestamp reaches the same rows.
     *
     * Only outgoing messages have log entries, so only their timestamps are worth passing.
     */
    private fun forgetFromResendLog(sentTimestamps: List<Long>) {
        val wanted = sentTimestamps.filter { it > 0 }.distinct()
        if (wanted.isEmpty()) return
        var dropped = 0
        wanted.forEach { at ->
            runCatching { dropped += signalStore.forgetSentMessage(at) }
                .onFailure { Timber.w(it, "signal: could not drop a deleted message from the resend log") }
        }
        if (dropped > 0) {
            Timber.i("signal message log: %d deleted message(s) can no longer be resent", dropped)
        }
    }

    override fun canBlock(): Boolean = runCatching { signalStore.blockedListKnown() }.getOrDefault(false)

    override fun needsBackupKeyFromPerson(folder: String): Boolean = runCatching {
        val meta = com.wanderwildwood.kotozute.signalstore.TreeExportSource(
            context, android.net.Uri.parse(folder)
        ).meta() ?: return@runCatching false          // a Signal Desktop export; no key of ours
        // The same reading [importHistory] does, and it has to stay the same reading: a header
        // with no `key` predates account locking and is thirty digits. Anything account-locked
        // opens with what this phone already holds and must never be asked about.
        val header = runCatching { JSONObject(meta) }.getOrNull() ?: return@runCatching false
        header.optString(
            "key",
            com.wanderwildwood.kotozute.signalstore.EncryptedExportDestination.DIGITS
        ) != com.wanderwildwood.kotozute.signalstore.EncryptedExportDestination.ACCOUNT
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
        // Cosmetic and self-healing: a group whose name did not land shows its identifier
        // until the next sync or message re-runs this. Nothing depends on the name.
        runCatching { nameGroupThreads() }.onFailure { Timber.w(it, "signal groups: naming failed; the next pass renames") }
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
        // ⚠ **Not the same key the storage service needs, which is what this comment used to
        // claim.** `SignalKeyStore.store()` writes the storage key and the pool together, so a
        // phone that learned its keys under this code has both -- but one that learned them
        // under the build that kept only the storage key has a row with the pool still empty,
        // and no amount of reading the account's records fills it in. Inferring one key from
        // the presence of another was wrong, and it made this failure unreachable to fix.
        //
        // So ask, here, where somebody has just said they want a copy. The answer arrives
        // through the socket; the export cannot wait for it, and a second attempt in a moment
        // finds the key. Asking is the only route back: the request is otherwise sent only
        // while the storage key is missing, which on such a phone it never is.
        val backupKey = signalStore.messageBackupKey() ?: run {
            val asked = runCatching { signalStore.requestKeys() }.getOrElse { it.message.orEmpty() }
            Timber.i("signal export: no backup key yet, asked the account for its keys: %s", asked)
            throw com.wanderwildwood.kotozute.repository.AccountKeyNotSent(asked == "requested")
        }
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

    override fun createGroup(title: String, memberThreadKeys: List<String>): String {
        val acis = memberThreadKeys
            .map { key -> key.removePrefix("direct:") }
            .filter { aci -> aci.isNotBlank() }
        val created = signalStore.createGroup(title, acis)

        // Our own copy of the notice that just went out to everybody else. Upstream files one
        // too -- `SendGroupUpdateHelper.sendGroupUpdate` inserts the outgoing update into the
        // creator's own thread -- and here it does the work a thread needs anyway: it gives
        // the conversation a date and a first line, so it appears in the list saying what
        // happened rather than as an empty room with an invented timestamp on it.
        //
        // Written here because a message this device sends never comes back to it.
        val selfAci = signalStore.selfAciOrNull().orEmpty()
        val timestamp = System.currentTimeMillis()
        ingest(
            listOf(
                com.wanderwildwood.kotozute.signal.BridgeMessage(
                    id = "$selfAci:$timestamp",
                    threadKey = created.threadKey,
                    ts = timestamp,
                    senderUuid = selfAci,
                    senderNumber = "",
                    outgoing = true,
                    body = context.getString(
                        com.wanderwildwood.kotozute.data.R.string.signal_group_created_by_you
                    ),
                    groupId = created.threadKey.removePrefix("group:"),
                    quoteTs = 0,
                    read = true,
                    source = "live",
                    attachmentsJson = "",
                    expiresInSeconds = 0L,
                    expiresAt = 0L,
                    groupMasterKey = created.masterKey
                )
            )
        )

        // The title and the key, which no message carries.
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val thread = r.where(SignalThread::class.java)
                    .equalTo("threadKey", created.threadKey)
                    .findFirst()
                    ?: r.createObject(SignalThread::class.java, created.threadKey)
                thread.kind = "group"
                thread.title = title
                thread.groupMasterKey = created.masterKey
            }
        }
        if (!created.told) {
            Timber.w("signal groups: made the group, but its members have not been told yet")
        }
        return created.threadKey
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
            Reacting(row.threadKey, who, row.date, groupMasterKeyFor(realm, row.threadKey))
        }
        if (author.isBlank()) throw IllegalStateException("nothing says who wrote that message")

        if (threadKey.startsWith("group:")) {
            val master = groupKey ?: throw com.wanderwildwood.kotozute.repository.SendRefused(
                com.wanderwildwood.kotozute.repository.SendFailure.NoGroupKey
            )
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
                        id = "", threadKey = threadKey, ts = ts,
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
            Withdrawing(row.threadKey, row.date, groupMasterKeyFor(realm, row.threadKey))
        }

        if (threadKey.startsWith("group:")) {
            val master = groupKey ?: throw com.wanderwildwood.kotozute.repository.SendRefused(
                com.wanderwildwood.kotozute.repository.SendFailure.NoGroupKey
            )
            signalStore.sendRemoteDeleteToGroup(master, sentAt)
        } else {
            signalStore.sendRemoteDelete(threadKey.removePrefix("direct:"), sentAt)
        }

        // Only now. Our own other devices hear about this through the sent transcript the
        // send itself carries, the same way they hear about a message.
        // It announces the thread itself -- see [removeWithdrawn].
        removeWithdrawn(messageId, "a message was taken back") { true }
    }

    /**
     * The key a group is reached by, and the one place that answers it.
     *
     * The thread's own first; a message's only for the groups that predate the thread holding
     * one (Realm 26). Four callers each did this their own way and two of them were still
     * reading only the messages, so a group made on this phone or one whose messages had all
     * expired could be named but not reacted to, or written to but not taken back from --
     * whichever of the four happened to have been updated.
     */
    private fun groupMasterKeyFor(realm: Realm, threadKey: String): ByteArray? =
        realm.where(SignalThread::class.java)
            .equalTo("threadKey", threadKey)
            .findFirst()
            ?.groupMasterKey
            ?: realm.where(SignalMessage::class.java)
                .equalTo("threadKey", threadKey)
                .findAll()
                .firstOrNull { it.groupMasterKey != null }
                ?.groupMasterKey

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

    /**
     * Pins a conversation to the top of the list, or lets it go.
     *
     * ⚠ **Deliberately does not `markNeedsSync`,** and the reason is not an oversight to fix.
     * A pin does not live on the conversation's own storage record the way mute and archive
     * do: it rides the *account's* record, as `AccountRecord.pinnedConversations`, and Signal
     * marks `Recipient.self()` for it -- `pinConversations` calls
     * `markNeedsSync(Recipient.self().id)`. Marking this thread's recipient instead would
     * rotate the wrong record's storage id and make this device believe it had a contact
     * change to push that it does not have.
     *
     * This app has no row for its own account and does not read or write AccountRecord at all,
     * so there is nothing here to mark yet. When the storage write path grows the account half,
     * this is where the mark goes -- on self, not on the thread.
     */
    override fun setPinned(threadKey: String, pinned: Boolean) = runOffThread {
        editThread(threadKey) { it.pinned = pinned }
    }

    override fun setMuted(threadKey: String, muted: Boolean) = runOffThread {
        editThread(threadKey) { it.muted = muted }
        markNeedsSync(threadKey)
        pushStorageNow()
    }

    /**
     * What a marked conversation should say in the account's records: this phone's archive
     * and mute. Null, and the row is left alone, when the conversation is not on this phone.
     */
    private fun desiredStorageState(
        row: com.wanderwildwood.kotozute.signalstore.SignalContactStore.Pending
    ): com.wanderwildwood.kotozute.signalstore.SignalStorageWriter.Desired? {
        val threadKey = row.groupId?.let { "group:$it" } ?: row.serviceId?.let { "direct:$it" } ?: return null
        return Realm.getDefaultInstance().use { realm ->
            realm.where(SignalThread::class.java).equalTo("threadKey", threadKey).findFirst()?.let {
                com.wanderwildwood.kotozute.signalstore.SignalStorageWriter.Desired(
                    muted = it.muted,
                    archived = it.archived
                )
            }
        }
    }

    /**
     * Sends a change made here to the account straight away, as upstream schedules a
     * `StorageSyncJob` on every archive and mute.
     */
    private fun pushStorageNow() {
        if (!runCatching { signalStore.storageKeyKnown() }.getOrDefault(false)) return
        runCatching { signalStore.readStorage() }
            .onFailure { Timber.w(it, "signal storage: could not send a change; the next read sends it") }
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
                    .findFirst()?.let { thread ->
                        thread.archived = archived
                        // ⚠ And archiving un-pins, which it did not. A pinned conversation
                        // archived and later unarchived came back pinned and sorted above
                        // newer conversations -- a state nobody chose, arrived at by two
                        // settings that contradict each other. `ThreadTable.setArchived` nulls
                        // PINNED_ORDER whenever it archives, so the two are exclusive.
                        if (archived) thread.pinned = false
                    }
            }
        }
        pushStorageNow()
    }

    override fun getThreadsSnapshot(archived: Boolean): List<SignalThread> =
        Realm.getDefaultInstance().use { realm ->
            realm.copyFromRealm(
                realm.where(SignalThread::class.java)
                    .equalTo("archived", archived)
                    .greaterThan("lastTs", 0L)
                    // The same order as [getThreads]; a snapshot that sorted differently from
                    // the live list is a list that reorders itself when it refreshes.
                    .sort(arrayOf("pinned", "lastTs"), arrayOf(Sort.DESCENDING, Sort.DESCENDING))
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
                contactCounts = runCatching { signalStore.contactCounts() }.getOrNull(),
                undecryptableReasons =
                    runCatching { signalStore.undecryptableReasons() }.getOrDefault(emptyList()),
                enabled = prefs.signalEnabled.get(),
                signalConnected = signalConnected,
                // Never both: a socket that has arrived is not still on its way, and saying
                // so would leave the connecting line on screen for the whole session.
                connecting = reaching.get() && !signalConnected,
                lastSyncedAt = prefs.signalLastSync.get(),
                error = error,
                rejected = prefs.signalRejected.get().takeIf { it.isNotBlank() },
                // Only a linked device has a primary to be idle. Signal gates it the same way
                // (`isLinkedDevice && hasInactivePrimaryDeviceAlert`).
                primaryIdle = prefs.signalPrimaryIdle.get() && !isPrimaryDevice(),
                serviceOutage = prefs.signalServiceOutage.get(),
            )
        )
    }


    companion object {
        /**
         * How long a key transparency check may take before it is abandoned.
         *
         * ⚠ It runs on the startup thread. Thirty seconds is upstream's own patience for the
         * one blocking job it runs at link time (`runJobBlocking(RefreshOwnProfileJob(),
         * 30.seconds)`), and it is long enough for a socket that is merely slow.
         */
        private val CHECK_TIMEOUT_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(30)


        /**
         * How long to keep trying for an attachment that did not arrive.
         *
         * A day, which is `AttachmentDownloadJob`'s `setLifespan(TimeUnit.DAYS.toMillis(1))`.
         * Past it the CDN copy is still there for a while, but somebody waiting on a picture
         * has long since stopped waiting, and a pointer retried for ever is a pointer kept for
         * ever.
         */
        private val ATTACHMENT_RETRY_WINDOW_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(1)

        /**
         * Whether an attachment that has not arrived is still worth asking for.
         *
         * Its own function because both edges decide whether somebody gets their picture. A
         * first-tried time of zero is an entry written before this existed: it is given the
         * benefit of the window rather than abandoned on sight, because the alternative is
         * throwing away the one chance those rows have.
         *
         * A time in the future is a clock that moved, and it keeps trying for the same reason:
         * the cost of one more attempt is a request, and the cost of stopping is a picture.
         */
        internal fun stillWorthFetching(firstTried: Long, now: Long): Boolean =
            firstTried <= 0 || firstTried > now || now - firstTried < ATTACHMENT_RETRY_WINDOW_MS

        /**
         * How often this phone may ask the primary to send its contacts.
         *
         * Six hours, which is `MultiDeviceContactUpdateJob.FULL_SYNC_TIME` -- the cooldown the
         * primary puts on its *own* contact syncs. It does not apply it to one this phone asks
         * for, because a request arrives as `MultiDeviceContactUpdateJob(true)` and the `true` is
         * `forceSync`. So the number is upstream's judgement of how often a full contacts sync is
         * worth doing, applied at the only end that can apply it.
         */
        private val CONTACT_REQUEST_INTERVAL_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(6)

        /**
         * Whether it is time to ask the primary for its contacts again.
         *
         * Its own function so both edges can be tested: never asked, and a stamp in the future.
         *
         * ⚠ **Never-asked must ask.** This is a linked device's first and only way to learn who
         * anybody is, so the case that has to work is the one right after linking -- and a zero
         * stamp is exactly that case, not a recent one.
         *
         * A stamp in the future is a clock that moved, and it asks: the cost of asking once more
         * is one sync on the other phone, and the cost of not asking is a device that knows
         * nobody until the clock catches up.
         */
        /**
         * Whether a refusal from the server still needs acting on: a reason not yet on file,
         * or a stream still running that should have been retired by it. The same reason with
         * the stream already stopped is the socket repeating itself.
         */
        internal fun refusalIsNews(stored: String, reason: String, streamWanted: Boolean): Boolean =
            stored != reason || streamWanted

        internal fun contactRequestDue(askedAt: Long, now: Long): Boolean =
            askedAt <= 0 || askedAt > now || now - askedAt >= CONTACT_REQUEST_INTERVAL_MS
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
