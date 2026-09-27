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

    /**
     * Calling somebody from here. [ringing] once their phone has said it is ringing; before
     * that the call is still being set up.
     */
    data class Calling(val peer: String, val ringing: Boolean) : SignalCallState()

    /** Accepted here, and the two ends are still finding each other. */
    data class Connecting(val peer: String) : SignalCallState()

    /** Talking. [since] is when the audio first connected, for the timer. */
    data class Connected(
        val peer: String,
        val since: Long,
        val muted: Boolean,
        /** Where the call is heard. */
        val output: AudioOutput,
        /** Every output there is to choose from right now, in the order they are offered. */
        val outputs: List<AudioOutput>,
        val reconnecting: Boolean
    ) : SignalCallState()

    /** Over. Shown for a moment so the screen can say so before it closes. */
    data class Ended(val peer: String, val why: EndReason) : SignalCallState()

    enum class EndReason { HUNG_UP, THEY_HUNG_UP, DECLINED, ANSWERED_ELSEWHERE, DECLINED_ELSEWHERE, BUSY, FAILED, TIMED_OUT }
}

/**
 * Where a call is heard. Upstream's `SignalAudioManager.AudioDevice`, less its NONE: a Bluetooth
 * headset or hearing aid, a headset on the jack or USB, the earpiece, or the loudspeaker.
 */
enum class AudioOutput { BLUETOOTH, WIRED, EARPIECE, SPEAKER }

/** What the screens may do about the call. See [SignalCallState]. */
interface SignalCallControl {
    fun state(): Observable<SignalCallState>
    fun current(): SignalCallState
    /** Whether somebody can be called: a person with an account id, and not this account itself. */
    fun canCall(peer: String): Boolean

    /** Calls somebody, by ACI. Does nothing while another call is up. */
    fun call(peer: String)
    fun accept()
    fun decline()
    fun hangUp()
    fun setMuted(muted: Boolean)
    /** Hear the call on [output], for as long as it is there. */
    fun selectOutput(output: AudioOutput)
}
