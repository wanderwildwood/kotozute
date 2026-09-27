package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.wanderwildwood.kotozute.repository.SignalCallControl
import com.wanderwildwood.kotozute.repository.SignalCallState
import com.wanderwildwood.kotozute.repository.SignalCallState.EndReason
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.ringrtc.AudioConfig
import org.signal.ringrtc.CallException
import org.signal.ringrtc.CallId
import org.signal.ringrtc.CallManager
import org.signal.ringrtc.CallSummary
import org.signal.ringrtc.CameraControl
import org.signal.ringrtc.HttpHeader
import org.signal.ringrtc.NetworkRoute
import org.signal.ringrtc.Remote
import org.signal.ringrtc.VideoConfig
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoSink
import org.whispersystems.signalservice.api.messages.calls.AnswerMessage
import org.whispersystems.signalservice.api.messages.calls.BusyMessage
import org.whispersystems.signalservice.api.messages.calls.HangupMessage
import org.whispersystems.signalservice.api.messages.calls.IceUpdateMessage
import org.whispersystems.signalservice.api.messages.calls.OfferMessage
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage
import org.whispersystems.signalservice.api.messages.calls.TurnServerInfo
import org.whispersystems.signalservice.internal.push.CallMessage
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Signal voice calls, one to one, through RingRTC.
 *
 * A trimmed port of Signal Android's `SignalCallManager` and the incoming-call half of its
 * action processors (`WebRtcActionProcessor`, `IdleActionProcessor`,
 * `IncomingCallActionProcessor`), with video, group calls, call links and Telecom taken out.
 * Upstream keeps a state object threaded through a dozen processor classes because it has all
 * of those to coordinate; one voice call at a time fits in the fields here.
 *
 * Everything that touches the [CallManager] runs on [worker], one thread, as upstream's
 * `process()` does: RingRTC calls back on its own threads, and a call's steps must not
 * interleave. Sending and the relay-server fetch go to [io], because they wait on the network
 * and the worker must stay free to take the next callback.
 */
internal class SignalCallEngine(
    private val context: Context,
    private val store: SignalStore,
    /** Writes the line in the conversation about how a call went. */
    private val record: (peer: String, callId: Long, at: Long, video: Boolean, outcome: CallOutcome) -> Unit
) : SignalCallControl, CallManager.Observer {

    /** The other end, as RingRTC knows it: by ACI, which is how a call message names its sender. */
    private class Peer(val aci: String) : Remote {
        override fun recipientEquals(other: Remote?): Boolean = other is Peer && other.aci == aci
        override fun toString(): String = "Peer(${aci.take(8)})"
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "signal-call") }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "signal-call-io") }

    private val states = BehaviorSubject.createDefault<SignalCallState>(SignalCallState.Idle)

    private var manager: CallManager? = null
    private var eglBase: EglBase? = null

    /** The call in hand. One at a time. */
    private var active: Active? = null

    private class Active(
        val peer: Peer,
        var callId: CallId,
        val remoteDevice: Int,
        val video: Boolean,
        val offeredAt: Long
    ) {
        var accepted = false
        var connectedAt = 0L
        var muted = false
        var speaker = false
        var reconnecting = false
        var ended = false
    }

    override fun state(): Observable<SignalCallState> = states.hide()
    override fun current(): SignalCallState = states.value ?: SignalCallState.Idle

    private fun publish(state: SignalCallState) {
        Timber.i("signal calls: now %s", state::class.simpleName)
        states.onNext(state)
    }

    // --- Setting up ---------------------------------------------------------------------------

    /**
     * RingRTC, loaded on the first call rather than at start: it is a large native library, and
     * most runs of the app never see a call.
     */
    private fun managerOrNull(): CallManager? {
        manager?.let { return it }
        return try {
            CallManager.initialize(context.applicationContext, RingRtcLogger, emptyMap())
            val m = CallManager.createCallManager(this) ?: return null
            store.selfAciOrNull()?.let { m.setSelfUuid(UUID.fromString(it)) }
            manager = m
            m
        } catch (t: Throwable) {
            // An UnsatisfiedLinkError as much as a CallException: upstream treats a library that
            // will not load as fatal, but here it only means calls cannot be answered, and the
            // messages around them must keep working.
            Timber.w(t, "signal calls: RingRTC would not start")
            null
        }
    }

    // --- Call messages in ---------------------------------------------------------------------

    /**
     * A call message from somebody, handed over by [SignalReceiver]. Upstream's
     * `CallMessageProcessor` plus the validation at the top of `handleReceivedOffer`.
     */
    fun received(
        call: CallMessage,
        from: String,
        sourceDevice: Int,
        serverReceivedAt: Long,
        serverDeliveredAt: Long
    ) = worker.execute {
        try {
            when {
                call.offer != null -> receivedOffer(call.offer!!, from, sourceDevice, serverReceivedAt, serverDeliveredAt)
                call.answer != null -> receivedAnswer(call.answer!!, from, sourceDevice)
                call.iceUpdate.isNotEmpty() -> receivedIce(call.iceUpdate, from, sourceDevice)
                call.hangup != null -> receivedHangup(call.hangup!!, from, sourceDevice)
                call.busy != null -> receivedBusy(call.busy!!, from, sourceDevice)
                // Opaque messages are group calls only.
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal calls: a call message could not be handled")
        }
    }

    private fun receivedOffer(offer: CallMessage.Offer, from: String, device: Int, receivedAt: Long, deliveredAt: Long) {
        val id = offer.id ?: return
        val opaque = offer.opaque?.toByteArray() ?: return
        val video = offer.type == CallMessage.Offer.Type.OFFER_VIDEO_CALL
        val m = managerOrNull() ?: return

        // Upstream bails out, and records a missed call, when the caller's identity key is not
        // on record: RingRTC needs it to tie the call's keys to the person.
        val remoteKey = store.identityKeyOf(from)?.let { publicKeyBytes(it) }
        val localKey = store.ownIdentityKey()?.let { publicKeyBytes(it) }
        if (remoteKey == null || localKey == null) {
            Timber.w("signal calls: no identity key on record for the caller; not answering")
            return
        }

        val peer = Peer(from)
        val callId = CallId(id)
        if (active == null) {
            active = Active(peer, callId, device, video, receivedAt)
        }
        val ageSec = maxOf(deliveredAt - receivedAt, 0L) / 1000
        Timber.i("signal calls: offer %s from device %d, %d s old", callId, device, ageSec)
        m.receivedOffer(
            callId, peer, device, opaque, ageSec,
            if (video) CallManager.CallMediaType.VIDEO_CALL else CallManager.CallMediaType.AUDIO_CALL,
            store.deviceId(), remoteKey, localKey
        )
    }

    private fun receivedAnswer(answer: CallMessage.Answer, from: String, device: Int) {
        val id = answer.id ?: return
        val opaque = answer.opaque?.toByteArray() ?: return
        val remoteKey = store.identityKeyOf(from)?.let { publicKeyBytes(it) } ?: return
        val localKey = store.ownIdentityKey()?.let { publicKeyBytes(it) } ?: return
        manager?.receivedAnswer(CallId(id), Peer(from), device, opaque, remoteKey, localKey)
    }

    private fun receivedIce(updates: List<CallMessage.IceUpdate>, from: String, device: Int) {
        var id: Long = -1
        val candidates = updates.filter { it.opaque != null && it.id != null }.map {
            id = it.id!!
            it.opaque!!.toByteArray()
        }
        if (candidates.isEmpty()) return
        manager?.receivedIceCandidates(CallId(id), Peer(from), device, candidates)
    }

    private fun receivedHangup(hangup: CallMessage.Hangup, from: String, device: Int) {
        val id = hangup.id ?: return
        val type = when (hangup.type) {
            CallMessage.Hangup.Type.HANGUP_ACCEPTED -> CallManager.HangupType.ACCEPTED
            CallMessage.Hangup.Type.HANGUP_DECLINED -> CallManager.HangupType.DECLINED
            CallMessage.Hangup.Type.HANGUP_BUSY -> CallManager.HangupType.BUSY
            CallMessage.Hangup.Type.HANGUP_NEED_PERMISSION -> CallManager.HangupType.NEED_PERMISSION
            else -> CallManager.HangupType.NORMAL
        }
        manager?.receivedHangup(CallId(id), Peer(from), device, type, hangup.deviceId ?: 0)
    }

    private fun receivedBusy(busy: CallMessage.Busy, from: String, device: Int) {
        val id = busy.id ?: return
        manager?.receivedBusy(CallId(id), Peer(from), device)
    }

    // --- What the screens do ------------------------------------------------------------------

    /**
     * Upstream's `handleAcceptCall` then `handleAudioReadyForAccept`: the audio is put into
     * call mode first, so the first words are not lost to a device still set up for media.
     */
    override fun accept() = worker.execute {
        val call = active ?: return@execute
        if (call.accepted || call.ended) return@execute
        call.accepted = true
        publish(SignalCallState.Connecting(call.peer.aci))
        audio.startCall()
        try {
            manager?.acceptCall(call.callId)
        } catch (e: CallException) {
            fail("accept failed", e)
        }
    }

    /** Upstream's `handleDenyCall`: a hangup while it is still ringing. */
    override fun decline() = worker.execute {
        val call = active ?: return@execute
        if (call.accepted || call.ended) return@execute
        settle(call, CallOutcome.DECLINED)
        try {
            manager?.hangup()
        } catch (e: CallException) {
            fail("decline failed", e)
        }
    }

    override fun hangUp() = worker.execute {
        val call = active ?: return@execute
        try {
            manager?.hangup()
        } catch (e: CallException) {
            fail("hangup failed", e)
        }
    }

    override fun setMuted(muted: Boolean) = worker.execute {
        val call = active ?: return@execute
        call.muted = muted
        runCatching { manager?.setAudioEnable(!muted) }
        publishConnected(call)
    }

    override fun setSpeaker(on: Boolean) = worker.execute {
        val call = active ?: return@execute
        call.speaker = on
        audio.setSpeaker(on)
        publishConnected(call)
    }

    private fun publishConnected(call: Active) {
        if (call.connectedAt > 0 && !call.ended) {
            publish(SignalCallState.Connected(call.peer.aci, call.connectedAt, call.muted, call.speaker, call.reconnecting))
        }
    }

    // --- RingRTC's callbacks ------------------------------------------------------------------

    override fun onStartCall(remote: Remote?, callId: CallId, isOutgoing: Boolean, callMediaType: CallManager.CallMediaType?) {
        worker.execute {
            val peer = remote as? Peer ?: return@execute
            val call = active
            if (call == null || !call.peer.recipientEquals(peer)) {
                Timber.w("signal calls: start for a call not in hand; dropping it")
                runCatching { manager?.drop(callId) }
                return@execute
            }
            call.callId = callId
            if (isOutgoing) {
                // Placing calls is a later step; nothing starts one yet.
                runCatching { manager?.drop(callId) }
                return@execute
            }
            fetchRelaysThenProceed(call)
        }
    }

    /** Upstream's `retrieveTurnServers` then `IncomingCallActionProcessor.proceed`. */
    private fun fetchRelaysThenProceed(call: Active) = io.execute {
        val servers = store.turnServers()
        worker.execute {
            if (call.ended || active !== call) return@execute
            if (servers == null) {
                fail("no relay servers", null)
                return@execute
            }
            val ice = iceServersOf(servers)
            val egl = eglBase ?: EglBase.create().also { eglBase = it }
            try {
                manager?.proceed(
                    call.callId,
                    context.applicationContext,
                    egl,
                    audioConfig(),
                    VideoConfig(),
                    NoVideo,
                    NoVideo,
                    NoCamera,
                    ice,
                    // Always through Signal's relays, never peer to peer. Upstream relays for
                    // anybody the account has not shared its profile with; relaying for
                    // everybody means nobody on a call learns this phone's IP address.
                    true,
                    CallManager.DataMode.NORMAL,
                    null,
                    null,
                    false,
                    null
                )
            } catch (e: CallException) {
                fail("proceed failed", e)
            }
        }
    }

    override fun onCallEvent(remote: Remote?, event: CallManager.CallEvent) {
        worker.execute {
            val call = active ?: return@execute
            if (remote !is Peer || !call.peer.recipientEquals(remote)) return@execute
            Timber.i("signal calls: event %s", event)
            when (event) {
                CallManager.CallEvent.LOCAL_RINGING -> publish(SignalCallState.Ringing(call.peer.aci, call.video))
                CallManager.CallEvent.LOCAL_CONNECTED, CallManager.CallEvent.REMOTE_CONNECTED -> {
                    if (call.connectedAt == 0L) {
                        call.connectedAt = System.currentTimeMillis()
                        settle(call, CallOutcome.ANSWERED)
                    }
                    call.reconnecting = false
                    publishConnected(call)
                }
                CallManager.CallEvent.RECONNECTING -> { call.reconnecting = true; publishConnected(call) }
                CallManager.CallEvent.RECONNECTED -> { call.reconnecting = false; publishConnected(call) }
                CallManager.CallEvent.RECEIVED_OFFER_EXPIRED -> {
                    settle(call, CallOutcome.MISSED)
                    end(call, EndReason.TIMED_OUT)
                }
                CallManager.CallEvent.RECEIVED_OFFER_WHILE_ACTIVE,
                CallManager.CallEvent.RECEIVED_OFFER_WITH_GLARE -> {
                    // RingRTC has already answered busy. The call in hand is untouched.
                }
                CallManager.CallEvent.GLARE_HANDLING_FAILURE -> fail("glare", null)
                else -> {}
            }
        }
    }

    override fun onCallEnded(remote: Remote?, reason: CallManager.CallEndReason, summary: CallSummary) {
        worker.execute {
            val call = active ?: return@execute
            if (remote !is Peer || !call.peer.recipientEquals(remote)) return@execute
            Timber.i("signal calls: ended, %s", reason)
            val why = when (reason) {
                CallManager.CallEndReason.LOCAL_HANGUP -> if (call.accepted) EndReason.HUNG_UP else EndReason.DECLINED
                CallManager.CallEndReason.REMOTE_HANGUP -> EndReason.THEY_HUNG_UP
                CallManager.CallEndReason.REMOTE_HANGUP_ACCEPTED -> EndReason.ANSWERED_ELSEWHERE
                CallManager.CallEndReason.REMOTE_HANGUP_DECLINED -> EndReason.DECLINED_ELSEWHERE
                CallManager.CallEndReason.REMOTE_HANGUP_BUSY,
                CallManager.CallEndReason.REMOTE_BUSY -> EndReason.BUSY
                CallManager.CallEndReason.TIMEOUT -> EndReason.TIMED_OUT
                else -> if (call.accepted) EndReason.FAILED else EndReason.THEY_HUNG_UP
            }
            when (why) {
                EndReason.ANSWERED_ELSEWHERE -> settle(call, CallOutcome.ANSWERED_ELSEWHERE)
                EndReason.DECLINED_ELSEWHERE -> settle(call, CallOutcome.DECLINED_ELSEWHERE)
                else -> if (!call.accepted) settle(call, CallOutcome.MISSED)
            }
            end(call, why)
        }
    }

    override fun onCallConcluded(remote: Remote?) {
        worker.execute {
            val call = active ?: return@execute
            if (remote !is Peer || !call.peer.recipientEquals(remote)) return@execute
            active = null
            audio.stopCall()
            eglBase?.release()
            eglBase = null
            publish(SignalCallState.Idle)
        }
    }

    // --- Call messages out --------------------------------------------------------------------

    override fun onSendOffer(callId: CallId, remote: Remote?, remoteDevice: Int, broadcast: Boolean, opaque: ByteArray, callMediaType: CallManager.CallMediaType) {
        val peer = remote as? Peer ?: return
        val type = if (callMediaType == CallManager.CallMediaType.VIDEO_CALL) OfferMessage.Type.VIDEO_CALL else OfferMessage.Type.AUDIO_CALL
        send(peer, callId, SignalServiceCallMessage.forOffer(OfferMessage(callId.longValue(), type, opaque), destination(broadcast, remoteDevice)))
    }

    override fun onSendAnswer(callId: CallId, remote: Remote?, remoteDevice: Int, broadcast: Boolean, opaque: ByteArray) {
        val peer = remote as? Peer ?: return
        send(peer, callId, SignalServiceCallMessage.forAnswer(AnswerMessage(callId.longValue(), opaque), destination(broadcast, remoteDevice)))
    }

    override fun onSendIceCandidates(callId: CallId, remote: Remote?, remoteDevice: Int, broadcast: Boolean, candidates: List<ByteArray>) {
        val peer = remote as? Peer ?: return
        val updates = candidates.map { IceUpdateMessage(callId.longValue(), it) }
        send(peer, callId, SignalServiceCallMessage.forIceUpdates(updates, destination(broadcast, remoteDevice)))
    }

    override fun onSendHangup(callId: CallId, remote: Remote?, remoteDevice: Int, broadcast: Boolean, hangupType: CallManager.HangupType, deviceId: Int) {
        val peer = remote as? Peer ?: return
        val type = when (hangupType) {
            CallManager.HangupType.ACCEPTED -> HangupMessage.Type.ACCEPTED
            CallManager.HangupType.DECLINED -> HangupMessage.Type.DECLINED
            CallManager.HangupType.BUSY -> HangupMessage.Type.BUSY
            CallManager.HangupType.NEED_PERMISSION -> HangupMessage.Type.NEED_PERMISSION
            else -> HangupMessage.Type.NORMAL
        }
        send(peer, callId, SignalServiceCallMessage.forHangup(HangupMessage(callId.longValue(), type, deviceId), destination(broadcast, remoteDevice)))
    }

    override fun onSendBusy(callId: CallId, remote: Remote?, remoteDevice: Int, broadcast: Boolean) {
        val peer = remote as? Peer ?: return
        send(peer, callId, SignalServiceCallMessage.forBusy(BusyMessage(callId.longValue()), destination(broadcast, remoteDevice)))
    }

    /** Upstream: to every device when broadcasting, otherwise to the one the call is with. */
    private fun destination(broadcast: Boolean, remoteDevice: Int): Int? = if (broadcast) null else remoteDevice

    /** Upstream's `sendCallMessage`, then `handleMessageSentSuccess` or `handleMessageSentError`. */
    private fun send(peer: Peer, callId: CallId, message: SignalServiceCallMessage) = io.execute {
        val result = store.sendCallMessage(peer.aci, message)
        worker.execute {
            try {
                if (result == SignalSender.CallSend.SENT) manager?.messageSent(callId)
                else manager?.messageSendFailure(callId)
            } catch (e: CallException) {
                Timber.w(e, "signal calls: could not report a send to RingRTC")
            }
        }
    }

    // Group calls, call links and their HTTP requests are not part of this.
    override fun onSendCallMessage(recipient: UUID, message: ByteArray, urgency: CallManager.CallMessageUrgency) {}
    override fun onSendCallMessageToGroup(groupId: ByteArray, message: ByteArray, urgency: CallManager.CallMessageUrgency, overrideRecipients: List<UUID>) {}
    override fun onSendCallMessageToAdhocGroup(message: ByteArray, urgency: CallManager.CallMessageUrgency, expiration: java.time.Instant, recipientsToEndorsements: Map<UUID, ByteArray>) {}
    override fun onSendHttpRequest(requestId: Long, url: String, method: CallManager.HttpMethod, headers: List<HttpHeader>?, body: ByteArray?) {
        runCatching { manager?.httpRequestFailed(requestId) }
    }
    override fun onGroupCallRingUpdate(groupId: ByteArray, ringId: Long, sender: UUID, update: CallManager.RingUpdate) {}
    override fun onNetworkRouteChanged(remote: Remote?, networkRoute: NetworkRoute) {}
    override fun onAudioLevels(remote: Remote?, capturedLevel: Int, receivedLevel: Int) {}
    override fun onLowBandwidthForVideo(remote: Remote?, recovered: Boolean) {}

    // --- Endings ------------------------------------------------------------------------------

    /** Writes how the call went, once. The receiver's own bookkeeping for the offer is cleared. */
    private val settled = mutableSetOf<Long>()

    private fun settle(call: Active, outcome: CallOutcome) {
        val id = call.callId.longValue()
        if (!settled.add(id)) return
        store.forgetRingingCall(id)
        runCatching { record(call.peer.aci, id, call.offeredAt, call.video, outcome) }
            .onFailure { Timber.w(it, "signal calls: could not record the call") }
    }

    private fun end(call: Active, why: EndReason) {
        if (call.ended) return
        call.ended = true
        audio.stopCall()
        publish(SignalCallState.Ended(call.peer.aci, why))
    }

    private fun fail(what: String, e: Throwable?) {
        Timber.w(e, "signal calls: %s", what)
        val call = active ?: return
        runCatching { manager?.hangup() }
        end(call, EndReason.FAILED)
        runCatching { manager?.reset() }
        active = null
        publish(SignalCallState.Idle)
    }

    // --- Audio --------------------------------------------------------------------------------

    /**
     * The phone's audio in call mode while a call is up, back to normal after.
     *
     * The minimum a voice call needs. Upstream's `SignalAudioManager` also routes to Bluetooth
     * and wired headsets and handles the proximity sensor; that is a later step.
     */
    private val audio = object {
        private val am get() = context.getSystemService(AudioManager::class.java)
        private var on = false

        fun startCall() {
            if (on) return
            on = true
            runCatching {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = false
            }.onFailure { Timber.w(it, "signal calls: could not put the audio into call mode") }
        }

        fun setSpeaker(on: Boolean) {
            runCatching {
                val wanted = am.availableCommunicationDevices.firstOrNull {
                    it.type == if (on) android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    else android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                if (wanted != null) am.setCommunicationDevice(wanted) else am.isSpeakerphoneOn = on
            }.onFailure { Timber.w(it, "signal calls: could not switch the speaker") }
        }

        fun stopCall() {
            if (!on) return
            on = false
            runCatching {
                am.clearCommunicationDevice()
                am.mode = AudioManager.MODE_NORMAL
            }.onFailure { Timber.w(it, "signal calls: could not put the audio back") }
        }
    }

    /**
     * RingRTC's defaults with upstream's overrides (`AudioDeviceConfig.applyOverrides`): the
     * software echo canceller and noise suppressor stand in when the phone has none of its own.
     */
    private fun audioConfig(): AudioConfig = AudioConfig().apply {
        if (!AcousticEchoCanceler.isAvailable()) useSoftwareAec = true
        if (!NoiseSuppressor.isAvailable()) useSoftwareNs = true
        if (useInputLowLatency &&
            !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUDIO_LOW_LATENCY)
        ) {
            useInputLowLatency = false
        }
    }

    private companion object {
        /** Upstream's `WebRtcUtil.getPublicKeyBytes`: the key without its type byte. */
        fun publicKeyBytes(serialized: ByteArray): ByteArray? =
            runCatching { ECPublicKey(serialized).publicKeyBytes }.getOrNull()

        /** Upstream's `mapToIceServers`: the IP forms first, each with the hostname for TLS. */
        fun iceServersOf(infos: List<TurnServerInfo>): List<PeerConnection.IceServer> {
            val out = ArrayList<PeerConnection.IceServer>()
            infos.forEach { info ->
                info.urlsWithIps?.forEach { url ->
                    out += PeerConnection.IceServer.builder(url)
                        .setUsername(info.username)
                        .setPassword(info.password)
                        .setHostname(info.hostname)
                        .createIceServer()
                }
                info.urls?.forEach { url ->
                    out += PeerConnection.IceServer.builder(url)
                        .setUsername(info.username)
                        .setPassword(info.password)
                        .createIceServer()
                }
            }
            return out
        }

        /** No video goes anywhere on a voice call; RingRTC still wants somewhere to send it. */
        val NoVideo = VideoSink { }

        /**
         * A camera that is never opened.
         *
         * ⚠ It has to say it has a capturer. RingRTC adds a video track only when it does
         * (`CallContext`), and a Signal offer always carries a video section, even for a voice
         * call -- so an answer without one has its m-lines out of order and WebRTC rejects it
         * ("The order of m-lines in answer doesn't match order in offer"), ending the call as
         * an internal failure before it rings. The track is created disabled and nothing ever
         * enables it or starts a capturer, so no camera is touched and no video is sent.
         */
        val NoCamera = object : CameraControl {
            override fun hasCapturer(): Boolean = true
            override fun initCapturer(observer: CapturerObserver) {}
            override fun setEnabled(enable: Boolean) {}
            override fun flip() {}
            override fun setOrientation(orientation: Int?) {}
        }
    }

    /** RingRTC's own log, into ours. */
    private object RingRtcLogger : org.signal.ringrtc.Log.Logger {
        override fun v(tag: String?, message: String?, t: Throwable?) {}
        override fun d(tag: String?, message: String?, t: Throwable?) {}
        override fun i(tag: String?, message: String?, t: Throwable?) { Timber.tag("RingRTC").i(t, message) }
        override fun w(tag: String?, message: String?, t: Throwable?) { Timber.tag("RingRTC").w(t, message) }
        override fun e(tag: String?, message: String?, t: Throwable?) { Timber.tag("RingRTC").e(t, message) }
    }
}
