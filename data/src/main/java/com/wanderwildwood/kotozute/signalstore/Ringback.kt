package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.wanderwildwood.kotozute.data.R
import timber.log.Timber

/**
 * What the caller hears while the other phone rings, and the busy tone when it is engaged.
 *
 * Signal Android's `OutgoingRinger`, with Signal's own two sounds (`redphone_outring`,
 * `redphone_busy`), played on the voice-call stream so they come out of the earpiece like the
 * call will. The busy tone stops by itself: the call is already over when it plays.
 */
internal class Ringback(context: Context) {

    enum class Tone { RINGING, BUSY }

    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    fun start(tone: Tone) {
        stop()
        val sound = if (tone == Tone.RINGING) R.raw.redphone_outring else R.raw.redphone_busy
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .build()
                )
                isLooping = true
                setDataSource(context, Uri.parse("android.resource://${context.packageName}/$sound"))
                prepare()
                start()
            }
        } catch (e: Exception) {
            Timber.w(e, "signal calls: the ringback would not play")
            null
        }
    }

    /** The busy tone, for a few seconds. */
    fun busy() {
        start(Tone.BUSY)
        handler.postDelayed({ stop() }, BUSY_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.release() } }
        player = null
    }

    private companion object {
        const val BUSY_MS = 3_000L
    }
}
