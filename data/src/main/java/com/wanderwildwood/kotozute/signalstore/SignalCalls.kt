package com.wanderwildwood.kotozute.signalstore

import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.SyncMessage
import timber.log.Timber

/**
 * What became of a Signal call this phone could not answer.
 *
 * This app has no calling, and until now a call to the account left no trace here at all:
 * somebody rang, the other phone was in a drawer, and the person carrying this one never
 * learned anyone had tried. Signal records every call in the conversation -- "Missed voice
 * call" with a notification, or an incoming call answered on another device -- so this does
 * the same, without ringing.
 */
enum class CallOutcome { MISSED, ANSWERED_ELSEWHERE, DECLINED_ELSEWHERE, OUTGOING }

internal object SignalCalls {

    /**
     * How long an offer can go unanswered before it is a missed call. RingRTC ends an
     * unanswered call after a minute and treats an offer older than two as expired on arrival
     * (`handleReceivedOfferExpired` -> `insertMissedCall`); two minutes covers both, so a
     * caller whose hangup never arrives still leaves a missed call rather than nothing.
     */
    const val MAX_RING_MS = 120_000L

    fun expired(offeredAt: Long, now: Long): Boolean = now - offeredAt >= MAX_RING_MS

    /**
     * The caller's hangup, as `ActiveCallActionProcessorDelegate.handleEndedRemote` reads it:
     * accepted or declined *by another of this account's devices* is not missed; an ordinary
     * hangup before anybody answered is. Busy means another device was already in a call,
     * which from where the person is standing is a call they missed.
     */
    fun outcomeOfHangup(type: CallMessage.Hangup.Type?): CallOutcome = when (type) {
        CallMessage.Hangup.Type.HANGUP_ACCEPTED -> CallOutcome.ANSWERED_ELSEWHERE
        CallMessage.Hangup.Type.HANGUP_DECLINED -> CallOutcome.DECLINED_ELSEWHERE
        else -> CallOutcome.MISSED
    }

    /**
     * A call event another of this account's devices reports (`SyncMessageProcessor
     * .handleSynchronizeCallEvent`). One-to-one calls only; null for anything this app does
     * not record.
     */
    fun outcomeOfEvent(
        type: SyncMessage.CallEvent.Type?,
        direction: SyncMessage.CallEvent.Direction?,
        event: SyncMessage.CallEvent.Event?
    ): CallOutcome? {
        if (type != SyncMessage.CallEvent.Type.AUDIO_CALL && type != SyncMessage.CallEvent.Type.VIDEO_CALL) return null
        val settled = event == SyncMessage.CallEvent.Event.ACCEPTED || event == SyncMessage.CallEvent.Event.NOT_ACCEPTED
        if (!settled) return null
        return when (direction) {
            SyncMessage.CallEvent.Direction.OUTGOING -> CallOutcome.OUTGOING
            SyncMessage.CallEvent.Direction.INCOMING ->
                if (event == SyncMessage.CallEvent.Event.ACCEPTED) CallOutcome.ANSWERED_ELSEWHERE
                else CallOutcome.DECLINED_ELSEWHERE
            else -> null
        }
    }

    /** A one-to-one call event names the other person by their service id's 16 bytes. */
    fun aciOf(conversationId: ByteArray?): String? {
        if (conversationId == null || conversationId.size != 16) return null
        val buf = java.nio.ByteBuffer.wrap(conversationId)
        return java.util.UUID(buf.long, buf.long).toString()
    }
}

/** Calls ringing now, kept so a process that dies mid-ring still settles them. */
internal class SignalCallStore(private val db: ProtocolDatabase) {

    data class Ringing(val callId: Long, val peer: String, val video: Boolean, val offeredAt: Long)

    fun offer(r: Ringing) = withStoreLock(db) {
        db.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO call_offer (call_id, peer, video, offered_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(r.callId, r.peer, if (r.video) 1 else 0, r.offeredAt)
        )
    }

    /** The ringing call with this id, removed; null when there is none. */
    fun take(callId: Long): Ringing? = withStoreLock(db) {
        val r = db.readableDatabase.rawQuery(
            "SELECT call_id, peer, video, offered_at FROM call_offer WHERE call_id = ?",
            arrayOf(callId.toString())
        ).use { c -> if (c.moveToFirst()) Ringing(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getLong(3)) else null }
        if (r != null) db.writableDatabase.execSQL("DELETE FROM call_offer WHERE call_id = ?", arrayOf<Any?>(callId))
        r
    }

    /** Every call that has rung past [SignalCalls.MAX_RING_MS], removed. */
    fun takeExpired(now: Long): List<Ringing> = withStoreLock(db) {
        val all = db.readableDatabase.rawQuery("SELECT call_id, peer, video, offered_at FROM call_offer", null).use { c ->
            generateSequence { if (c.moveToNext()) Ringing(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getLong(3)) else null }.toList()
        }
        all.filter { SignalCalls.expired(it.offeredAt, now) }.also { gone ->
            gone.forEach { db.writableDatabase.execSQL("DELETE FROM call_offer WHERE call_id = ?", arrayOf<Any?>(it.callId)) }
            if (gone.isNotEmpty()) Timber.i("signal calls: %d unanswered past the ring window", gone.size)
        }
    }
}
