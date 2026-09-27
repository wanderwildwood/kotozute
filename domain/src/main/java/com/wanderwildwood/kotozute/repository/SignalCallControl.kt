package com.wanderwildwood.kotozute.repository

import io.reactivex.Observable

/**
 * A Signal voice call on this phone, as the screens see it.
 *
 * One call at a time, which is Signal's own rule for one-to-one calls: an offer that arrives
 * while another call is up is answered busy by RingRTC without anything here being asked.
 */
sealed class SignalCallState {
    /** No call. */
    object Idle : SignalCallState()

    /** Somebody is calling, and the phone is ringing. [peer] is their ACI. */
    data class Ringing(val peer: String, val video: Boolean) : SignalCallState()

    /** Accepted here, and the two ends are still finding each other. */
    data class Connecting(val peer: String) : SignalCallState()

    /** Talking. [since] is when the audio first connected, for the timer. */
    data class Connected(
        val peer: String,
        val since: Long,
        val muted: Boolean,
        val speaker: Boolean,
        val reconnecting: Boolean
    ) : SignalCallState()

    /** Over. Shown for a moment so the screen can say so before it closes. */
    data class Ended(val peer: String, val why: EndReason) : SignalCallState()

    enum class EndReason { HUNG_UP, THEY_HUNG_UP, DECLINED, ANSWERED_ELSEWHERE, DECLINED_ELSEWHERE, BUSY, FAILED, TIMED_OUT }
}

/** What the screens may do about the call. See [SignalCallState]. */
interface SignalCallControl {
    fun state(): Observable<SignalCallState>
    fun current(): SignalCallState
    fun accept()
    fun decline()
    fun hangUp()
    fun setMuted(muted: Boolean)
    fun setSpeaker(on: Boolean)
}
