package com.wanderwildwood.kotozute.repository

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
        val error: String? = null
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
    fun syncNow(): Int

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

    /**
     * Every number on the address-book card that [number] belongs to, or just [number]
     * when there is no card. Used to find the SMS conversation for a Signal thread whose
     * number is an old one the person no longer texts from.
     */
    fun numbersForContactOf(number: String): List<String>

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
     * Archiving a Signal thread only hides it here. Signal has no such notion, so this
     * is not sent anywhere and other devices are unaffected.
     */
    fun setArchived(threadKey: String, archived: Boolean)

    /**
     * Block or unblock this thread's other party on the Signal account itself. Throws if
     * the bridge cannot be reached, so the caller can say so rather than imply success.
     */
    fun setBlocked(threadKey: String, blocked: Boolean)

    fun setPinned(threadKey: String, pinned: Boolean)

    fun setMuted(threadKey: String, muted: Boolean)

    /** Put a thread back to unread, so it is picked up again later. */
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
