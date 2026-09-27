package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import com.wanderwildwood.kotozute.repository.AudioOutput
import timber.log.Timber

/**
 * Where a call is heard, and the phone's audio in call mode while it is up.
 *
 * Signal Android's `FullSignalAudioManagerApi31`, which is what Signal uses on Android 12, trimmed
 * to what a voice call needs: the audio in communication mode with call audio focus, and the
 * call routed to a device with `setCommunicationDevice`. Bluetooth first, then a wired headset,
 * then the earpiece, then the loudspeaker -- upstream's search order -- unless the person chose
 * one, which holds for as long as it is there. A headset plugged in or a Bluetooth device
 * connecting mid-call is followed, as upstream follows it, through an `AudioDeviceCallback`.
 *
 * Upstream's watches are skipped here as they are there: a watch that offers itself as a
 * communication device is not somewhere anybody means to hold a call.
 */
internal class CallAudio(context: Context, private val onChanged: () -> Unit) {

    private val am = context.getSystemService(AudioManager::class.java)
    private val hasEarpiece = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    private val thread = HandlerThread("signal-call-audio").apply { start() }
    private val handler = Handler(thread.looper)

    private var running = false
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false
    private var focus: AudioFocusRequest? = null
    private var chosen: AudioOutput? = null

    @Volatile var output: AudioOutput = AudioOutput.EARPIECE
        private set
    @Volatile var outputs: List<AudioOutput> = listOf(AudioOutput.EARPIECE, AudioOutput.SPEAKER)
        private set

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = route()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = route()
    }

    /** Upstream's `initialize` then `start`: focus, communication mode, and the first route. */
    fun start() = handler.post {
        if (running) return@post
        running = true
        savedMode = am.mode
        @Suppress("DEPRECATION")
        savedSpeaker = am.isSpeakerphoneOn
        requestFocus()
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
            .onFailure { Timber.w(it, "signal calls: could not put the audio into call mode") }
        am.registerAudioDeviceCallback(devices, handler)
        route()
    }

    /** Hear the call on [wanted] while it is there. */
    fun select(wanted: AudioOutput) = handler.post {
        chosen = wanted
        route()
    }

    /** Upstream's `stop`: everything put back as it was found. */
    fun stop() = handler.post {
        if (!running) return@post
        running = false
        chosen = null
        runCatching {
            am.unregisterAudioDeviceCallback(devices)
            am.clearCommunicationDevice()
            @Suppress("DEPRECATION")
            if (am.isSpeakerphoneOn != savedSpeaker) am.isSpeakerphoneOn = savedSpeaker
            am.mode = savedMode
        }.onFailure { Timber.w(it, "signal calls: could not put the audio back") }
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    /** Upstream's `updateAudioDeviceState`. */
    private fun route() {
        if (!running) return
        val available = am.availableCommunicationDevices
            .filterNot { it.productName?.toString()?.contains(" Watch", ignoreCase = true) == true }
        val byKind = available.mapNotNull { d -> kindOf(d.type)?.let { it to d } }
        outputs = ORDER.filter { kind -> byKind.any { it.first == kind } }
        // A choice that has gone -- the headset unplugged -- stops being a choice.
        if (chosen != null && byKind.none { it.first == chosen }) chosen = null

        // The chosen one while it is there; otherwise upstream's order, with the earpiece
        // standing in for "default" on a phone, and the speaker on anything without one.
        val order = listOfNotNull(chosen) + listOf(
            AudioOutput.BLUETOOTH, AudioOutput.WIRED,
            if (hasEarpiece) AudioOutput.EARPIECE else AudioOutput.SPEAKER,
            AudioOutput.EARPIECE, AudioOutput.SPEAKER
        )
        for (kind in order.distinct()) {
            val device = byKind.firstOrNull { it.first == kind }?.second ?: continue
            if (am.communicationDevice?.id == device.id || am.setCommunicationDevice(device)) {
                output = kind
                Timber.i("signal calls: heard on %s (of %s)", kind, outputs)
                onChanged()
                return
            }
        }
        Timber.w("signal calls: no output would take the call; of %s", outputs)
        am.clearCommunicationDevice()
        onChanged()
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build()
            )
            .setOnAudioFocusChangeListener { Timber.i("signal calls: audio focus %d", it) }
            .build()
        val granted = runCatching { am.requestAudioFocus(request) }.getOrNull()
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.w("signal calls: call audio focus not granted (%s)", granted)
        }
        focus = request
    }

    private companion object {
        val ORDER = listOf(AudioOutput.BLUETOOTH, AudioOutput.WIRED, AudioOutput.EARPIECE, AudioOutput.SPEAKER)

        /** Upstream's `AudioDeviceMapping`. */
        fun kindOf(type: Int): AudioOutput? = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID -> AudioOutput.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET -> AudioOutput.WIRED
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioOutput.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> AudioOutput.SPEAKER
            else -> null
        }
    }
}
