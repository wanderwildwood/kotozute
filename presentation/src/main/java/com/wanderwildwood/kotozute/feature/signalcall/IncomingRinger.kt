package com.wanderwildwood.kotozute.feature.signalcall

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import timber.log.Timber

/**
 * The ringtone and the buzz for an incoming Signal call.
 *
 * Signal Android's `IncomingRinger`, ported: the phone's own ringtone, looping, on the ringtone
 * stream, and only when the ringer is on. Set to vibrate it only vibrates, and on silent it does
 * neither. The one exception is upstream's too: without Do Not Disturb access a phone that says
 * "silent" while its ring volume is up is taken to mean the ring volume.
 */
internal class IncomingRinger(context: Context) {

    private val context = context.applicationContext
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var player: MediaPlayer? = null

    fun start() {
        player?.release()
        player = defaultRingtone()?.let { createPlayer(it) }

        val ringerMode = ringerMode()
        if (shouldVibrate(ringerMode)) {
            startVibrate()
        }

        val p = player
        if (p != null && ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            try {
                if (!p.isPlaying) {
                    p.prepare()
                    p.start()
                }
            } catch (e: Exception) {
                Timber.w(e, "signal calls: the ringtone would not play")
                player = null
            }
        }
    }

    fun stop() {
        player?.release()
        player = null
        vibrator?.cancel()
    }

    private fun startVibrate() {
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(VIBRATE_PATTERN, 1),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VIBRATE_PATTERN, 1, ATTRIBUTES)
        }
    }

    private fun ringerMode(): Int {
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val mode = audio.ringerMode
        if (!notifications.isNotificationPolicyAccessGranted) {
            val volume = audio.getStreamVolume(AudioManager.STREAM_RING)
            if (volume > 0 && mode == AudioManager.RINGER_MODE_SILENT) return AudioManager.RINGER_MODE_NORMAL
        }
        return mode
    }

    private fun shouldVibrate(ringerMode: Int): Boolean {
        if (player == null) return true
        if (vibrator == null || !vibrator.hasVibrator()) return false
        return ringerMode != AudioManager.RINGER_MODE_SILENT
    }

    private fun defaultRingtone(): Uri? = try {
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
    } catch (e: SecurityException) {
        Timber.w(e, "signal calls: could not read the default ringtone")
        null
    }

    private fun createPlayer(uri: Uri): MediaPlayer? = try {
        MediaPlayer().apply {
            setDataSource(context, uri)
            setOnErrorListener { _, what, extra ->
                Timber.w("signal calls: ringtone player error %d %d", what, extra)
                player = null
                false
            }
            isLooping = true
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
            )
        }
    } catch (e: Exception) {
        Timber.w(e, "signal calls: could not open the ringtone")
        null
    }

    private companion object {
        val VIBRATE_PATTERN = longArrayOf(0, 1000, 1000)
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()
    }
}
