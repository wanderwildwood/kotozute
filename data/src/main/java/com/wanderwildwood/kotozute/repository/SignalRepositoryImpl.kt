package com.wanderwildwood.kotozute.repository

import com.wanderwildwood.kotozute.model.Contact
import com.wanderwildwood.kotozute.model.SignalMessage
import com.wanderwildwood.kotozute.model.SignalThread
import com.wanderwildwood.kotozute.signal.BridgeClient
import com.wanderwildwood.kotozute.signal.BridgeConfig
import com.wanderwildwood.kotozute.signal.BridgeMessage
import com.wanderwildwood.kotozute.util.PhoneNumberUtils
import com.wanderwildwood.kotozute.util.Preferences
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import io.realm.Realm
import io.realm.RealmResults
import io.realm.Sort
import timber.log.Timber
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

@Singleton
class SignalRepositoryImpl @Inject constructor(
    private val prefs: Preferences,
    private val phoneNumberUtils: PhoneNumberUtils
) : SignalRepository {

    private val state = BehaviorSubject.createDefault(
        SignalRepository.ConnectionState(
            configured = false, enabled = false, bridgeReachable = false,
            signalConnected = false, lastSyncedAt = 0
        )
    )

    private val incoming = io.reactivex.subjects.PublishSubject.create<SignalMessage>()

    private var stream: Closeable? = null
    private val streamWanted = AtomicBoolean(false)

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

    override fun isConfigured(): Boolean = config() != null

    override fun pair(payload: String): Boolean {
        val cfg = BridgeConfig.parse(payload) ?: return false
        prefs.signalBridgeHost.set(cfg.host)
        prefs.signalBridgePort.set(cfg.port)
        prefs.signalBridgeToken.set(cfg.token)
        prefs.signalBridgeFingerprint.set(cfg.fingerprint)
        publishState(reachable = false, signalConnected = false, error = null)
        return true
    }

    override fun unpair() = runOffThread {
        stopStream()
        prefs.signalEnabled.set(false)
        prefs.signalBridgeHost.set("")
        prefs.signalBridgeToken.set("")
        prefs.signalBridgeFingerprint.set("")
        prefs.signalCursor.set(0L)
        prefs.signalLastSync.set(0L)
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                it.delete(SignalMessage::class.java)
                it.delete(SignalThread::class.java)
            }
        }
        publishState(reachable = false, signalConnected = false, error = null)
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
    override fun syncNow(): Int {
        val cfg = config() ?: return 0
        val client = BridgeClient(cfg)
        var written = 0
        try {
            val remote = client.state()
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
                    }
                }
            }

            var cursor = prefs.signalCursor.get()
            while (true) {
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
                announce(fresh)
                written += msgs.size
                cursor = msgs.maxOf { it.seq }
                prefs.signalCursor.set(cursor)
                if (cursor >= maxSeq) break
            }

            prefs.signalLastSync.set(System.currentTimeMillis())
            publishState(reachable = true, signalConnected = remote.signalConnected, error = null)
        } catch (t: Throwable) {
            Timber.w(t, "signal sync failed")
            publishState(reachable = false, signalConnected = false, error = t.message)
        }
        return written
    }

    override fun startStream() {
        if (!prefs.signalEnabled.get() || !isConfigured()) return
        if (!streamWanted.compareAndSet(false, true)) return
        thread(name = "signal-stream", isDaemon = true) { streamLoop() }
    }

    override fun stopStream() {
        streamWanted.set(false)
        runCatching { stream?.close() }
        stream = null
    }

    /**
     * Reconnects with backoff for as long as the stream is wanted. Every reconnect
     * re-sends the cursor, so a dropped connection is a delay and never a hole.
     */
    private fun streamLoop() {
        var backoff = 2_000L
        while (streamWanted.get()) {
            val cfg = config()
            if (cfg == null) { streamWanted.set(false); return }

            syncNow() // catch up before going live

            val done = java.util.concurrent.CountDownLatch(1)
            val client = BridgeClient(cfg)
            try {
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
                        publishState(reachable = true, signalConnected = true, error = null)
                    },
                    onClosed = { err ->
                        if (err != null) Timber.d("signal stream closed: ${err.message}")
                        done.countDown()
                    }
                )
                backoff = 2_000L
                done.await()
            } catch (t: Throwable) {
                Timber.w(t, "signal stream failed")
            }

            publishState(reachable = false, signalConnected = false, error = null)
            if (!streamWanted.get()) return
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
        row.attachments = m.attachmentsJson

        val thread = realm.where(SignalThread::class.java)
            .equalTo("threadKey", m.threadKey).findFirst()
            ?: realm.createObject(SignalThread::class.java, m.threadKey).apply {
                kind = if (m.groupId.isNotEmpty()) "group" else "direct"
                counterpartUuid = m.threadKey.substringAfter("direct:", "")
            }
        if (m.ts > thread.lastTs) thread.lastTs = m.ts
        thread.unread = realm.where(SignalMessage::class.java)
            .equalTo("threadKey", m.threadKey)
            .equalTo("outgoing", false)
            .equalTo("read", false)
            .count().toInt()
        return isNew
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
        val cfg = config() ?: throw IllegalStateException("no bridge paired")
        return BridgeClient(cfg).send(threadKey, body, attachments)
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
        runCatching { BridgeClient(cfg).markRead(threadKey, upToTs) }
            .onFailure { Timber.d("markRead not delivered: ${it.message}") }
    }

    override fun loadAttachment(id: String): ByteArray? {
        val cfg = config() ?: return null
        return runCatching { BridgeClient(cfg).fetchAttachment(id) }
            .onFailure { Timber.d("attachment $id: ${it.message}") }
            .getOrNull()
    }

    override fun findThreadForNumber(number: String): SignalThread? {
        if (number.isBlank()) return null
        Realm.getDefaultInstance().use { realm ->
            val threads = realm.where(SignalThread::class.java)
                .equalTo("kind", "direct")
                .findAll()

            // The number itself first.
            threads.firstOrNull { phoneNumberUtils.compare(it.counterpartNumber, number) }
                ?.let { return realm.copyFromRealm(it) }

            // Then every other number on the same address-book card. Someone who changed
            // numbers and left Signal on the old one is one person with two numbers, not
            // two people -- and that is common enough to be worth the second lookup.
            val alternates = realm.where(Contact::class.java)
                .findAll()
                .firstOrNull { c -> c.numbers.any { phoneNumberUtils.compare(it.address, number) } }
                ?.numbers
                ?.map { it.address }
                .orEmpty()

            val viaContact = threads.firstOrNull { t ->
                alternates.any { alt -> phoneNumberUtils.compare(t.counterpartNumber, alt) }
            }
            // Detached: the caller is on another thread and outlives this Realm.
            return viaContact?.let { realm.copyFromRealm(it) }
        }
    }

    override fun numbersForContactOf(number: String): List<String> {
        if (number.isBlank()) return listOf()
        Realm.getDefaultInstance().use { realm ->
            val nums = realm.where(Contact::class.java)
                .findAll()
                .firstOrNull { c -> c.numbers.any { phoneNumberUtils.compare(it.address, number) } }
                ?.numbers
                ?.map { it.address }
                .orEmpty()
            return if (nums.isEmpty()) listOf(number) else nums
        }
    }

    override fun getThreads(): RealmResults<SignalThread> =
        Realm.getDefaultInstance()
            .where(SignalThread::class.java)
            .equalTo("archived", false)
            .sort("lastTs", Sort.DESCENDING)
            .findAllAsync()

    override fun getMessages(threadKey: String): RealmResults<SignalMessage> =
        Realm.getDefaultInstance()
            .where(SignalMessage::class.java)
            .equalTo("threadKey", threadKey)
            .sort("date", Sort.ASCENDING)
            .findAllAsync()

    override fun connectionState(): Observable<SignalRepository.ConnectionState> = state

    override fun newIncoming(): Observable<SignalMessage> = incoming

    private fun publishState(reachable: Boolean, signalConnected: Boolean, error: String?) {
        state.onNext(
            SignalRepository.ConnectionState(
                configured = isConfigured(),
                enabled = prefs.signalEnabled.get(),
                bridgeReachable = reachable,
                signalConnected = signalConnected,
                lastSyncedAt = prefs.signalLastSync.get(),
                error = error
            )
        )
    }
}
