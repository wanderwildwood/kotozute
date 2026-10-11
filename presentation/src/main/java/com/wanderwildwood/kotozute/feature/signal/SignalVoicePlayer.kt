package com.wanderwildwood.kotozute.feature.signal

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.wanderwildwood.kotozute.common.QkMediaPlayer
import timber.log.Timber
import java.io.File

/**
 * The one Signal voice note playing, or paused, anywhere in the app.
 *
 * Lives outside the conversation screen because playback has to outlive it: a note keeps
 * playing with the screen locked and after leaving the thread, the way upstream's does --
 * there it belongs to `VoiceNotePlaybackService`, and the conversation only holds a
 * controller onto it (`VoiceNoteMediaController`). Here [VoiceNotePlaybackService] keeps the
 * process in the foreground and carries the notification; this object is the player itself.
 *
 * Main thread only. Every change is announced through [Listener], which is all the rows and
 * the service ever learn from: neither keeps a copy of the state.
 *
 * ⚠ Plays through [QkMediaPlayer], the process-wide player the MMS side also uses. That is
 * deliberate -- one sound at a time across the whole app -- and it means the MMS player may
 * reset it underneath us. Its reset calls the completion listener, which is how this hears
 * about it; see [start].
 */
object SignalVoicePlayer {

    fun interface Listener {
        fun onVoiceStateChanged()
    }

    /** The attachment loaded into the player, playing or paused. Null when nothing is. */
    var currentId: String? = null
        private set

    /** Playing rather than paused. Only meaningful while [currentId] is set. */
    var isPlaying: Boolean = false
        private set

    /** The conversation it came from, for the notification's title and its tap. */
    var threadKey: String = ""
        private set
    var title: String = ""
        private set

    /** The cache copy being played, so it can be removed when playback ends or is replaced. */
    private var file: File? = null

    /**
     * Bumped whenever the loaded note changes, so a completion callback from an earlier one --
     * or from the MMS player, which inherits our listener on the platform player -- is ignored.
     */
    private var generation = 0

    /** How long each note played this session is, so a row can say so before it plays again. */
    private val durations = HashMap<String, Int>()

    /**
     * Where a note was left when another replaced it, or where it was dragged to before it
     * played. Upstream's row keeps its seek bar where it was when a different note starts
     * (`AudioView.onStop` rewinds only near the end or on auto-reset) and plays from there
     * next time (`Callbacks.onPlay(uri, progress)`); this is that, kept here rather than in a
     * recycled row.
     */
    private val positions = HashMap<String, Int>()

    private val listeners = LinkedHashSet<Listener>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    private fun announce() {
        // A copy: the service removes itself when playback ends, from inside this loop.
        listeners.toList().forEach { it.onVoiceStateChanged() }
    }

    fun isCurrent(id: String) = currentId == id

    fun durationOf(id: String): Int? = durations[id]

    /** Where [id] is, in milliseconds: the player's position when it is loaded, else remembered. */
    fun positionOf(id: String): Int =
        if (currentId == id) runCatching { QkMediaPlayer.currentPosition }.getOrDefault(0)
        else positions[id] ?: 0

    /**
     * Load [file] and play it from wherever it was last left. Whatever was loaded before is
     * stopped first and its place remembered.
     *
     * @return false when the platform would not play it; the file is deleted either way.
     */
    fun start(context: Context, id: String, file: File, threadKey: String, title: String): Boolean {
        replaceCurrent()
        val app = context.applicationContext
        val startAt = positions.remove(id) ?: 0
        val myGeneration = ++generation
        val ok = runCatching {
            QkMediaPlayer.reset()
            // Before setDataSource: the attributes are fixed at prepare. USAGE_MEDIA as
            // upstream's `VoiceNotePlayer` asks its audio focus with.
            QkMediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // ⚠ Without this the CPU sleeps a minute or so after the screen goes off and the
            // note stops mid-word, foreground service or not: a foreground service keeps the
            // process alive, not awake. MediaPlayer holds the lock only while it plays.
            QkMediaPlayer.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
            QkMediaPlayer.setDataSource(app, Uri.fromFile(file))
            QkMediaPlayer.prepare()
            QkMediaPlayer.setOnCompletionListener {
                // ⚠ Called synchronously from inside QkMediaPlayer.reset()/stop() as well as at
                // the natural end, so it must not touch the player itself -- a reset from
                // here would run inside somebody else's reset.
                if (Looper.myLooper() == Looper.getMainLooper()) ended(myGeneration)
                else main.post { ended(myGeneration) }
            }
            if (startAt in 1 until QkMediaPlayer.duration) QkMediaPlayer.seekTo(startAt)
            QkMediaPlayer.start()
        }.onFailure { Timber.w(it, "signal voice: could not play") }.isSuccess

        if (!ok) {
            generation++
            runCatching { QkMediaPlayer.reset() }
            file.delete()
            announce()
            return false
        }
        durations[id] = runCatching { QkMediaPlayer.duration }.getOrDefault(0)
        currentId = id
        isPlaying = true
        this.file = file
        this.threadKey = threadKey
        this.title = title
        requestFocus(app)
        VoiceNotePlaybackService.start(app)
        announce()
        return true
    }

    fun pause() {
        if (currentId == null || !isPlaying) return
        runCatching { QkMediaPlayer.pause() }
        isPlaying = false
        abandonFocus()
        announce()
    }

    fun resume(context: Context) {
        if (currentId == null || isPlaying) return
        if (runCatching { QkMediaPlayer.start() }.isFailure) {
            stop()
            return
        }
        isPlaying = true
        requestFocus(context.applicationContext)
        // Promoted again: a paused note's service is no longer in the foreground. See
        // [VoiceNotePlaybackService.onVoiceStateChanged].
        VoiceNotePlaybackService.start(context.applicationContext)
        announce()
    }

    /**
     * Jump to [ms]. On a note that is not loaded, remembered for when it plays -- only offered
     * when its length is known, which is after it has played once.
     */
    fun seekTo(id: String, ms: Int) {
        if (currentId == id) {
            runCatching { QkMediaPlayer.seekTo(ms) }
        } else {
            positions[id] = ms
        }
        announce()
    }

    /** Stop and unload, and start the note from the top next time. The service goes with it. */
    fun stop() {
        val id = currentId ?: return
        positions.remove(id)
        unload()
        runCatching { QkMediaPlayer.reset() }
        announce()
    }

    /** A different note is starting: remember where this one was, unless it was nearly done. */
    private fun replaceCurrent() {
        val id = currentId ?: return
        val at = positionOf(id)
        val length = durations[id] ?: 0
        // Upstream's `AudioView.onStop`: within a whisker of the end counts as finished.
        if (at > 0 && at < length - NEAR_END_MS) positions[id] = at else positions.remove(id)
        unload()
        // No announce: [start] announces once, with the new note in place.
    }

    private fun ended(myGeneration: Int) {
        if (myGeneration != generation) return
        val id = currentId ?: return
        // Finished, or taken over by the MMS player: either way the row starts again from
        // the top, as upstream's does at the end (`autoReset`).
        positions.remove(id)
        unload()
        announce()
    }

    private fun unload() {
        generation++
        currentId = null
        isPlaying = false
        file?.delete()
        file = null
        abandonFocus()
    }

    // -- Audio focus, as upstream's `VoiceNotePlayer`: transient, may duck, pause on loss. --

    private var focusRequest: AudioFocusRequest? = null

    private fun requestFocus(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    pause()
                }
            }, main)
            .build()
            .also { focusRequest = it }
        audioManager = am
        am.requestAudioFocus(request)
    }

    private var audioManager: AudioManager? = null

    private fun abandonFocus() {
        val request = focusRequest ?: return
        audioManager?.abandonAudioFocusRequest(request)
    }

    private const val NEAR_END_MS = 500
}
