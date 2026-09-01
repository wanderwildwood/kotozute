package com.wanderwildwood.kotozute.repository

import com.wanderwildwood.kotozute.model.SignalMessage
import com.wanderwildwood.kotozute.model.SignalThread
import com.wanderwildwood.kotozute.signal.BridgeClient
import com.wanderwildwood.kotozute.signal.BridgeConfig
import com.wanderwildwood.kotozute.signal.BridgeMessage
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
    private val prefs: Preferences
) : SignalRepository {

    private val state = BehaviorSubject.createDefault(
        SignalRepository.ConnectionState(
            configured = false, enabled = false, bridgeReachable = false,
            signalConnected = false, lastSyncedAt = 0
        )
    )

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
                    threads.forEach { t ->
                        val row = r.where(SignalThread::class.java)
                            .equalTo("threadKey", t.threadKey).findFirst()
                            ?: r.createObject(SignalThread::class.java, t.threadKey)
                        row.kind = t.kind
                        row.title = t.title
                        if (t.lastTs > row.lastTs) row.lastTs = t.lastTs
                        row.counterpartUuid = t.threadKey.substringAfter("direct:", "")
                        if (t.counterpartNumber.isNotBlank()) {
                            row.counterpartNumber = t.counterpartNumber
                        }
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
                Realm.getDefaultInstance().use { realm ->
                    realm.executeTransaction { r -> msgs.forEach { store(r, it) } }
                }
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
                        Realm.getDefaultInstance().use { realm ->
                            realm.executeTransaction { r -> store(r, msg) }
                        }
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

    /** Idempotent by primary key: the same message may arrive more than once. */
    private fun store(realm: Realm, m: BridgeMessage) {
        if (m.id.isBlank()) return
        val row = realm.where(SignalMessage::class.java).equalTo("id", m.id).findFirst()
            ?: realm.createObject(SignalMessage::class.java, m.id)
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
    }

    override fun send(threadKey: String, body: String): Long {
        val cfg = config() ?: throw IllegalStateException("no bridge paired")
        return BridgeClient(cfg).send(threadKey, body)
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
