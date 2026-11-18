package com.wanderwildwood.kotozute.feature.signal

import com.wanderwildwood.kotozute.common.DarkBar
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.media.MediaPlayer
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.DarkMode
import com.wanderwildwood.kotozute.common.util.einkDialog
import com.wanderwildwood.kotozute.feature.gallery.ZoomPhotoView
import com.wanderwildwood.kotozute.repository.SignalRepository
import dagger.android.AndroidInjection
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * A view-once picture or video, shown once: upstream's `ViewOnceMessageActivity`.
 *
 * The screen is secure, so it cannot be captured, and the picture is spent the moment it is
 * read -- see [SignalRepository.openViewOnce] -- so leaving this screen, however it is left,
 * is the end of it. A tap closes it, as upstream's does.
 *
 * A video is played from memory. It used to be written to a file in the cache for `VideoView`,
 * which needs a path -- so a view-once video sat on disk in the clear while it showed, and
 * stayed there if the process died before the screen closed. `MediaPlayer` reads the bytes in
 * hand through a `MediaDataSource` instead, as the conversation's own GIF player does, and
 * nothing is written.
 */
class ViewOnceActivity : AppCompatActivity() {

    @Inject lateinit var signalRepo: SignalRepository

    private var player: MediaPlayer? = null
    private var videoStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val frame = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            setOnClickListener { finish() }
        }
        setContentView(frame)
        DarkBar.watch(this)

        // Restored after the process was put away: the picture was spent when it was first
        // shown, so there is nothing to show again.
        if (savedInstanceState != null) { finish(); return }

        val messageId = intent.getStringExtra(EXTRA_ID).orEmpty()
        thread(isDaemon = true) {
            val media = runCatching { signalRepo.openViewOnce(messageId) }.getOrNull()
            // Not read is not spent: a picture that is locked is still there to open later.
            val locked = media == null && runCatching { signalRepo.attachmentsLocked() }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (locked) {
                    einkDialog()
                        .setMessage(R.string.signal_attachments_locked)
                        .setPositiveButton(android.R.string.ok, null)
                        .setOnDismissListener { finish() }
                        .show()
                    return@runOnUiThread
                }
                if (media == null) {
                    Toast.makeText(this, R.string.signal_view_once_gone, Toast.LENGTH_SHORT).show()
                    finish()
                    return@runOnUiThread
                }
                show(frame, media)
            }
        }
    }

    private fun show(frame: FrameLayout, media: SignalRepository.ViewOnceMedia) {
        val fill = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (media.type.startsWith("video/")) {
            showVideo(frame, media.bytes)
            return
        }
        val bitmap = runCatching { BitmapFactory.decodeByteArray(media.bytes, 0, media.bytes.size) }.getOrNull()
        if (bitmap == null) {
            frame.addView(TextView(this).apply {
                setText(R.string.signal_view_once_unshowable)
                gravity = Gravity.CENTER
            }, fill)
            return
        }
        // Zooms like the gallery; a single tap still closes it.
        frame.addView(ZoomPhotoView(this).apply {
            DarkMode.keep(this)
            setImageBitmap(bitmap)
            setOnClickListener { finish() }
        }, fill)
    }

    /** Plays [bytes] on a surface of its own, fitted to the screen at the video's own shape. */
    private fun showVideo(frame: FrameLayout, bytes: ByteArray) {
        val surface = SurfaceView(this)
        frame.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
            )
        )
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Once: the bytes were spent when they were read, and this screen closes
                // rather than come back, so there is no second surface to play on.
                if (videoStarted) return
                videoStarted = true
                val started = runCatching {
                    player = MediaPlayer().apply {
                        setDataSource(SignalAttachment.BytesSource(bytes))
                        setDisplay(holder)
                        setOnVideoSizeChangedListener { _, width, height -> fit(surface, frame, width, height) }
                        setOnPreparedListener { it.start() }
                        setOnCompletionListener { finish() }
                        setOnErrorListener { _, _, _ -> unshowable(frame, surface); true }
                        prepareAsync()
                    }
                }.isSuccess
                if (!started) unshowable(frame, surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) = releasePlayer()
        })
    }

    /** The largest size of the video's own shape that fits the screen. */
    private fun fit(surface: SurfaceView, frame: FrameLayout, videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0 || frame.width <= 0 || frame.height <= 0) return
        val scale = minOf(frame.width.toFloat() / videoWidth, frame.height.toFloat() / videoHeight)
        surface.layoutParams = FrameLayout.LayoutParams(
            (videoWidth * scale).toInt().coerceAtLeast(1),
            (videoHeight * scale).toInt().coerceAtLeast(1),
            Gravity.CENTER
        )
    }

    private fun unshowable(frame: FrameLayout, surface: SurfaceView) {
        releasePlayer()
        if (isFinishing || isDestroyed) return
        frame.removeView(surface)
        frame.addView(
            TextView(this).apply {
                setText(R.string.signal_view_once_unshowable)
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.release() } }
        player = null
    }

    override fun onStop() {
        super.onStop()
        // Out of sight is closed: there is no coming back to a spent picture.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "messageId"

        fun intentFor(context: Context, messageId: String): Intent =
            Intent(context, ViewOnceActivity::class.java).putExtra(EXTRA_ID, messageId)
    }
}
