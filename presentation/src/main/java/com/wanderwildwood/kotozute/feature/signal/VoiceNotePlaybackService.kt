package com.wanderwildwood.kotozute.feature.signal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.wanderwildwood.kotozute.R
import timber.log.Timber

/**
 * Keeps a Signal voice note playing with the screen locked or the conversation closed, and
 * puts its play/pause in a notification and on the lock screen.
 *
 * Upstream's `VoiceNotePlaybackService` -- a media3 `MediaSessionService`, foreground type
 * `mediaPlayback` -- reduced to what is on this classpath: the platform's `MediaSession` and
 * `Notification.MediaStyle` instead of media3's, and [SignalVoicePlayer] instead of ExoPlayer.
 * The behaviour is upstream's:
 * - foreground while playing; on pause it leaves the foreground but keeps the notification,
 *   which can then be swiped away (`stopForeground(false)` in its player listener, and
 *   `setOngoing(false)` in `VoiceNoteMediaNotificationProvider`);
 * - swiping the notification away stops playback (its delete intent is `COMMAND_STOP`);
 * - play/pause and stop in the notification, title from the conversation, a tap opens it
 *   (`createCurrentContentIntent`);
 * - headphones pulled out pause it (`setHandleAudioBecomingNoisy(true)` in `VoiceNotePlayer`);
 * - the app swiped out of recents stops it (`onTaskRemoved`).
 *
 * It owns no playback state. It listens to [SignalVoicePlayer] and stops itself, notification
 * and all, the moment nothing is loaded.
 */
class VoiceNotePlaybackService : Service(), SignalVoicePlayer.Listener {

    private var session: MediaSession? = null
    private var inForeground = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) SignalVoicePlayer.pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        session = runCatching {
            MediaSession(this, "kotozute-voice-note").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = SignalVoicePlayer.resume(this@VoiceNotePlaybackService)
                    override fun onPause() = SignalVoicePlayer.pause()
                    override fun onStop() = SignalVoicePlayer.stop()
                    override fun onSeekTo(pos: Long) {
                        SignalVoicePlayer.currentId?.let { SignalVoicePlayer.seekTo(it, pos.toInt()) }
                    }
                })
                isActive = true
            }
        }.onFailure { Timber.w(it, "signal voice: no media session") }.getOrNull()
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        SignalVoicePlayer.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stoppedSelf = false
        when (intent?.action) {
            ACTION_PLAY -> SignalVoicePlayer.resume(this)
            ACTION_PAUSE -> SignalVoicePlayer.pause()
            ACTION_STOP -> SignalVoicePlayer.stop()
        }
        // ⚠ Foreground first, whatever the state, when this came from [start]: that is
        // startForegroundService, which promises a startForeground() within seconds, and a
        // service stopped before keeping that promise is a crash. The note may have ended or
        // been paused between the promise and here; onVoiceStateChanged then steps back out
        // of the foreground, or stops the service, straight after. The notification's own
        // buttons (an action, plain getService) promise nothing and are left alone -- Stop
        // has already stopped the service by now.
        if (intent?.action == null) foreground(buildNotification())
        onVoiceStateChanged()
        // Not sticky: a note cannot be resumed by a restarted process, its cache file is gone.
        return START_NOT_STICKY
    }

    override fun onVoiceStateChanged() {
        if (SignalVoicePlayer.currentId == null) {
            finish()
            return
        }
        updateSession()
        val notification = buildNotification()
        if (SignalVoicePlayer.isPlaying) {
            foreground(notification)
        } else {
            // Paused: out of the foreground, notification kept -- upstream's stopForeground(false).
            if (inForeground) stopForeground(STOP_FOREGROUND_DETACH)
            inForeground = false
            notificationManager().notify(NOTIFICATION_ID, notification)
        }
    }

    private fun foreground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            inForeground = true
        } catch (e: Exception) {
            // Android 12 refuses a foreground start from the background outside its
            // exemptions (upstream logs the same case, `onForegroundServiceStartNotAllowedException`).
            // The note plays on; it only loses the protection against being killed.
            Timber.w(e, "signal voice: could not become a foreground service")
            notificationManager().notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        SignalVoicePlayer.stop()
    }

    override fun onDestroy() {
        SignalVoicePlayer.removeListener(this)
        runCatching { unregisterReceiver(noisy) }
        session?.release()
        session = null
        // ⚠ The system may stop a paused, backgrounded service on its own. A note left loaded
        // behind it would keep its cache file and be controllable from nowhere.
        // ⚠ But not when the service stopped itself: then onDestroy arrives after the stop,
        // and a note started in between -- one ends, the next is tapped straight away --
        // belongs to the next instance and would be killed by this one.
        if (!stoppedSelf) {
            SignalVoicePlayer.stop()
            notificationManager().cancel(NOTIFICATION_ID)
        }
        super.onDestroy()
    }

    /** Set once this service has asked to stop; see [onDestroy]. */
    private var stoppedSelf = false

    private fun finish() {
        stoppedSelf = true
        if (inForeground) stopForeground(STOP_FOREGROUND_REMOVE)
        inForeground = false
        notificationManager().cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun updateSession() {
        val s = session ?: return
        val id = SignalVoicePlayer.currentId ?: return
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, SignalVoicePlayer.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.signal_voice_message))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (SignalVoicePlayer.durationOf(id) ?: 0).toLong())
                .build()
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SEEK_TO
                )
                .setState(
                    if (SignalVoicePlayer.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    SignalVoicePlayer.positionOf(id).toLong(),
                    if (SignalVoicePlayer.isPlaying) 1f else 0f
                )
                .build()
        )
    }

    private fun buildNotification(): Notification {
        val playing = SignalVoicePlayer.isPlaying
        val toggle = if (playing) {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                getString(R.string.signal_voice_pause), serviceIntent(ACTION_PAUSE, 1)
            ).build()
        } else {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_play),
                getString(R.string.signal_voice_play), serviceIntent(ACTION_PLAY, 2)
            ).build()
        }
        val stop = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString(R.string.signal_voice_stop), serviceIntent(ACTION_STOP, 3)
        ).build()

        val open = SignalVoicePlayer.threadKey.takeIf { it.isNotBlank() }?.let { key ->
            PendingIntent.getActivity(
                this, 4,
                SignalConversationsActivity.intentFor(this, key, SignalVoicePlayer.title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1)
        session?.let { style.setMediaSession(it.sessionToken) }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(SignalVoicePlayer.title.ifBlank { getString(R.string.signal_title) })
            .setContentText(getString(R.string.signal_voice_message))
            .setContentIntent(open)
            .setDeleteIntent(serviceIntent(ACTION_STOP, 5))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            // Upstream's choice: the conversation's name on the lock screen, as its own
            // notification provider sets it.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(toggle)
            .addAction(stop)
            .setStyle(style)
            .build()
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, VoiceNotePlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "signal_voice_notes"
        private const val NOTIFICATION_ID = 7202

        private const val ACTION_PLAY = "com.wanderwildwood.kotozute.signalvoice.PLAY"
        private const val ACTION_PAUSE = "com.wanderwildwood.kotozute.signalvoice.PAUSE"
        private const val ACTION_STOP = "com.wanderwildwood.kotozute.signalvoice.STOP"

        /** Bring the service up, or tell it to look again. Called by [SignalVoicePlayer]. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, VoiceNotePlaybackService::class.java)
                )
            } catch (e: Exception) {
                // The note still plays; it only stops when the screen locks, as it used to.
                Timber.w(e, "signal voice: could not start the playback service")
            }
        }

        /** Low and silent, as upstream's `VOICE_NOTES` channel: it is a control, not news. */
        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.signal_voice_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
