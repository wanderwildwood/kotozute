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
import io.realm.Case
import io.realm.Realm
import io.realm.RealmResults
import io.realm.Sort
import timber.log.Timber
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

private const val ATTACHMENT_PREVIEW = "\uD83D\uDCCE Attachment"

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
        // Adopting existing history is not the same as receiving news. On the very first
        // sync the bridge hands over everything it holds, and announcing all of it would
        // greet someone who has just finished setting Signal up with a screen of
        // notifications about conversations they already know about. A later catch-up
        // does announce: those are messages genuinely missed.
        val firstSync = prefs.signalCursor.get() == 0L
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
                                        if (newest.attachments.isNotBlank() &&
                                            newest.attachments != "[]") ATTACHMENT_PREVIEW else ""
                                    }
                                    row.snippetOutgoing = newest.outgoing
                                }
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
        try {
            streamLoopInner()
        } finally {
            // Whatever ended this -- a normal stop or something thrown -- the flag must not
            // be left set. startStream() refuses to start a second loop while it is, so a
            // thread that died holding it would mean the stream could never be revived.
            streamWanted.set(false)
        }
    }

    private fun streamLoopInner() {
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
        // Only the newest message speaks for the thread. Messages can arrive out of
        // order -- a reconnect replays by cursor, and an imported backup arrives
        // backwards -- so this is guarded on the timestamp rather than on arrival.
        if (m.ts >= thread.lastTs) {
            thread.lastTs = m.ts
            thread.snippet = previewOf(m)
            thread.snippetOutgoing = m.outgoing
        }
        thread.unread = realm.where(SignalMessage::class.java)
            .equalTo("threadKey", m.threadKey)
            .equalTo("outgoing", false)
            .equalTo("read", false)
            .count().toInt()
        return isNew
    }

    /** What the inbox row shows. A picture with no caption still needs to say something. */
    private fun previewOf(m: BridgeMessage): String = when {
        m.body.isNotBlank() -> m.body
        m.attachmentsJson.isNotBlank() && m.attachmentsJson != "[]" -> ATTACHMENT_PREVIEW
        else -> ""
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
        runCatching {
            BridgeClient(cfg).markRead(threadKey, upToTs, prefs.signalReadReceipts.get())
        }
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
                .sort("date", Sort.ASCENDING)
                .findAll()
            // The tail, like the SMS side: a long thread is re-fetched every few seconds.
            val from = maxOf(0, all.size - limit.coerceAtLeast(1))
            realm.copyFromRealm(all.subList(from, all.size))
        }

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
