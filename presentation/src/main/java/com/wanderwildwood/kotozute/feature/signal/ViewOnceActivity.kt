package com.wanderwildwood.kotozute.feature.signal

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.repository.SignalRepository
import dagger.android.AndroidInjection
import java.io.File
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * A view-once picture or video, shown once: upstream's `ViewOnceMessageActivity`.
 *
 * The screen is secure, so it cannot be captured, and the picture is spent the moment it is
 * read -- see [SignalRepository.openViewOnce] -- so leaving this screen, however it is left,
 * is the end of it. A tap closes it, as upstream's does.
 *
 * A video is played from a file in this app's cache, which exists only while this screen is
 * open: `VideoView` needs a path, and the file is deleted when the screen goes.
 */
class ViewOnceActivity : AppCompatActivity() {

    @Inject lateinit var signalRepo: SignalRepository

    private var videoFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val frame = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            setOnClickListener { finish() }
        }
        setContentView(frame)

        // Restored after the process was put away: the picture was spent when it was first
        // shown, so there is nothing to show again.
        if (savedInstanceState != null) { finish(); return }

        val messageId = intent.getStringExtra(EXTRA_ID).orEmpty()
        thread(isDaemon = true) {
            val media = runCatching { signalRepo.openViewOnce(messageId) }.getOrNull()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
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
            val file = File(cacheDir, "view-once-video").also { it.writeBytes(media.bytes) }
            videoFile = file
            val video = VideoView(this)
            frame.addView(video, FrameLayout.LayoutParams(fill).apply { gravity = Gravity.CENTER })
            video.setVideoPath(file.absolutePath)
            video.setOnCompletionListener { finish() }
            video.start()
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
        frame.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
        }, fill)
    }

    override fun onStop() {
        super.onStop()
        // Out of sight is closed: there is no coming back to a spent picture.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        videoFile?.delete()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "messageId"

        fun intentFor(context: Context, messageId: String): Intent =
            Intent(context, ViewOnceActivity::class.java).putExtra(EXTRA_ID, messageId)
    }
}
