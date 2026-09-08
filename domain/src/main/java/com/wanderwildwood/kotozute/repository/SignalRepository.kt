package com.wanderwildwood.kotozute.repository

import com.wanderwildwood.kotozute.signal.BridgeMessage
import com.wanderwildwood.kotozute.model.SignalMessage
import com.wanderwildwood.kotozute.model.SignalThread
import io.reactivex.Observable
import io.realm.RealmResults

/**
 * Signal, reached through a kotozute-bridge.
 *
 * Receiving degrades softly: while the bridge is unreachable, messages queue on Signal's
 * servers and arrive when it comes back. Sending fails hard -- there is no offline queue,
 * because a message the user believes they sent and which never arrives is worse than a
 * composer that plainly refuses. [ConnectionState] is what the UI uses to say so.
 */
interface SignalRepository {

    data class ConnectionState(
        val configured: Boolean,
        val enabled: Boolean,
        /** The bridge answered us. */
        val bridgeReachable: Boolean,
        /** The bridge says signal-cli is connected to Signal. */
        val signalConnected: Boolean,
        val lastSyncedAt: Long,
        val error: String? = null,
        /**
         * The bridge answered and refused us -- a wrong token, or a pairing that was
         * revoked. Distinct from [bridgeReachable] being false, which is the ordinary
         * case of a host that is switched off and will come back on its own. This one
         * will not, so it is the only state worth interrupting anybody about.
         */
        val rejected: Boolean = false
    ) {
        /** Only then may the composer offer to send. */
        val canSend: Boolean get() = enabled && bridgeReachable && signalConnected
    }

    fun isConfigured(): Boolean

    /** Parses a pairing payload and stores it. Returns false if it is not a valid one. */
    fun pair(payload: String): Boolean

    /** Forgets the bridge and every Signal row it gave us. */
    fun unpair()

    fun setEnabled(enabled: Boolean)

    /** Pulls everything after our cursor. Safe to call repeatedly; it is idempotent. */
    /**
     * Files messages that arrived some other way than the bridge -- currently the device's own
     * Signal connection.
     *
     * The same storage path the bridge sync uses, on purpose. Threads, previews, reactions and
     * expiry all key off the same rules, so a second writer with its own idea of them would
     * produce a second set of threads beside the real ones.
     *
     * @return how many were new.
     */
    /**
     * Recomputes and republishes the connection state.
     *
     * Needed because the state starts as a literal "nothing is configured" and is otherwise
     * only republished by an event -- pairing, a sync, a stream change. A device that is
     * itself linked to the account has no such event at startup, so without this every Signal
     * screen would keep offering to connect a bridge while messages arrived behind it.
     *
     * Does its work off the calling thread: answering it opens the keystore and an encrypted
     * database, which is not a main-thread question.
     */
    /**
     * Links this phone to a Signal account as a secondary device.
     *
     * Blocks until the exchange finishes or the code expires, so the caller owns the thread.
     * [onUrl] is called once, as soon as there is something to show -- the wait after that is
     * a person picking up their other phone, so showing the code late wastes the window.
     *
     * @return a description of what happened, beginning "linked" on success, or null if
     *   nothing came back before the code expired.
     */
    fun linkDevice(deviceName: String, onUrl: (String) -> Unit): String?

    fun refresh()

    fun ingest(messages: List<BridgeMessage>): Int

    fun syncNow(): Int

    /**
     * Whether the last [syncNow] reached the sequence the bridge said it was holding.
     *
     * False means the catch-up stopped short -- a dropped connection part-way through a
     * backlog, or a page that failed. Nothing is lost, because the cursor only advances over
     * what actually landed, but the phone is behind and should come back for the rest rather
     * than wait out the next round.
     *
     * Its own answer rather than a flag on [ConnectionState]: nine other things publish that
     * object, every one of them with this at its default, so a state change between the sync
     * and the reader would report the opposite of what happened.
     */
    fun lastSyncCaughtUp(): Boolean

    /** Holds the bridge's event stream open, reconnecting as needed. */
    fun startStream()
    fun stopStream()

    /** [attachments] are RFC 2397 data URIs. Returns the Signal timestamp. */
    fun send(threadKey: String, body: String, attachments: List<String> = emptyList()): Long

    fun markRead(threadKey: String, upToTs: Long)

    /** Blocking. Returns null if the bridge cannot be reached or has no such attachment. */
    fun loadAttachment(id: String): ByteArray?

    /**
     * The Signal thread for a phone number, if this account has one. Matching is by
     * phone-number comparison rather than string equality -- the same person is
     * "+15551234567" to Signal and "(555) 123-4567" to the address book.
     */
    fun findThreadForNumber(number: String): SignalThread?

    /** The SMS conversation this Signal thread has been tied to by hand, or null. */
    fun linkedConversationId(threadKey: String): Long?

    /** The Signal thread tied by hand to this SMS conversation, or null. */
    fun linkedThreadKeyFor(conversationId: Long): String?

    /** Tie a Signal thread to an SMS conversation; null unties it. */
    fun linkConversation(threadKey: String, conversationId: Long?)


    /**
     * Display names for the people who sent messages in a thread, keyed by their Signal
     * uuid. Resolved in one pass rather than per drawn row, and only ever needed for a
     * group -- a one-to-one thread already says who it is at the top.
     */
    fun senderNamesFor(threadKey: String): Map<String, String>

    /** [archived] selects which shelf: the inbox, or the archive. */
    fun getThreads(archived: Boolean = false): RealmResults<SignalThread>

    /**
     * Detached copies, readable from any thread.
     *
     * The async results [getThreads] returns need a Looper, and the desktop relay serves
     * each request on a plain worker thread -- the same reason the SMS side has a Sync
     * variant.
     */
    fun getThreadsSnapshot(archived: Boolean = false): List<SignalThread>

    /**
     * Everyone Signal knows on this account, whether or not there is a conversation --
     * the recipient list for starting one. The conversation lists exclude these on purpose.
     */
    fun threadDirectory(): List<SignalThread>

    /**
     * Threads whose title or messages match [query]. Returns each thread once, with how
     * many of its messages matched and the newest matching body; a thread that matched only
     * by name reports zero.
     */
    fun searchThreads(query: String): List<SignalSearchHit>

    fun getMessagesSnapshot(threadKey: String, limit: Int): List<SignalMessage>

    /**
     * One message in a thread by its timestamp, detached, or null if it is not held.
     *
     * What a quoted reply needs: Signal names what it answers by timestamp, and the answer
     * is usually older than whatever page is on screen.
     */
    fun getMessageAt(threadKey: String, date: Long): SignalMessage?

    /**
     * How many unexpired messages this thread holds, so a caller showing only the tail can
     * say whether there is older history behind it.
     */
    fun countMessages(threadKey: String): Int

    /**
     * Archiving a Signal thread only hides it here. Signal has no such notion, so this
     * is not sent anywhere and other devices are unaffected.
     */
    fun setArchived(threadKey: String, archived: Boolean)

    /**
     * Block or unblock this thread's other party on the Signal account itself. Throws if
     * the bridge cannot be reached, so the caller can say so rather than imply success.
     */
    /**
     * Who the bridge is signed in as and which devices are on that account. Throws if the
     * bridge cannot be reached, so a screen can say so rather than show a blank.
     */
    fun account(): SignalAccount

    /**
     * The safety number for a one-to-one thread and whether the key is still the accepted
     * one. Throws if the bridge cannot be reached.
     */
    fun identity(threadKey: String): SignalIdentity

    /**
     * React to a message, or take a reaction back. [messageId] is this app's own id for the
     * message; the author and timestamp Signal needs are read off the stored row.
     */
    fun react(messageId: String, emoji: String, remove: Boolean)

    fun setBlocked(threadKey: String, blocked: Boolean)

    fun setPinned(threadKey: String, pinned: Boolean)

    fun setMuted(threadKey: String, muted: Boolean)

    /** Put a thread back to unread, so it is picked up again later. */
    /**
     * Delete messages whose disappearing deadline has passed. Returns how many went. Reads
     * already hide them; this is what stops the phone being the copy that outlives the timer.
     */
    fun purgeExpired(): Int

    fun markUnread(threadKey: String)

    /** Whether this thread's notifications are silenced. Read on the notification path. */
    fun isMuted(threadKey: String): Boolean
    fun getMessages(threadKey: String): RealmResults<SignalMessage>

    fun connectionState(): Observable<ConnectionState>

    /**
     * Emits each newly stored *incoming* message, once. Notifications live in the
     * presentation layer, so the repository announces rather than notifies -- and because
     * it emits only on a genuinely new row, a message redelivered by the bridge or
     * replayed on reconnect cannot ring twice.
     */
    fun newIncoming(): Observable<SignalMessage>
}

/** One thread that matched a search, and what matched in it. */
data class SignalSearchHit(val thread: SignalThread, val messages: Int, val snippet: String)

/** One device on the Signal account. Id 1 is the primary; the rest are linked. */
data class SignalDevice(val id: Int, val name: String, val created: Long) {
    val isPrimary: Boolean get() = id == 1
}

/** The Signal account this phone reaches through the bridge. */
data class SignalAccount(
    val number: String,
    val selfUuid: String,
    val devices: List<SignalDevice>,
    /** Which of [devices] the bridge itself is, or 0 when it could not be worked out. */
    val thisDeviceId: Int = 0
)

/** A contact's safety number, and whether their key is still the one that was accepted. */
data class SignalIdentity(val safetyNumber: String, val trustLevel: String) {
    val changed: Boolean get() = trustLevel == "UNTRUSTED"
    val verified: Boolean get() = trustLevel == "TRUSTED_VERIFIED"
}
