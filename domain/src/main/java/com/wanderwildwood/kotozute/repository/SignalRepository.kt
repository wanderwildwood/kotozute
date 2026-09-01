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

    fun send(threadKey: String, body: String): Long

    fun markRead(threadKey: String, upToTs: Long)

    /** Blocking. Returns null if the bridge cannot be reached or has no such attachment. */
    fun loadAttachment(id: String): ByteArray?

    fun getThreads(): RealmResults<SignalThread>
    fun getMessages(threadKey: String): RealmResults<SignalMessage>

    fun connectionState(): Observable<ConnectionState>
}
