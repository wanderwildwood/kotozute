package com.wanderwildwood.kotozute.feature.notificationprefs

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import com.wanderwildwood.kotozute.R

/**
 * Chooses one of the phone's own sounds in a list of the app's own: a press chooses it and plays
 * a few seconds of it, and Done closes the list. Android's picker is not used, because on a
 * Kompakt it has no OK button and a sound picked there is lost on going back.
 */
object SoundChooser {
    private const val PREVIEW_MS = 5_000L

    /** [current] null is silent; [onPick] is called at each press with the chosen sound, null for silent. */
    fun show(context: Context, ringtoneType: Int, title: CharSequence?, current: Uri?, showSilent: Boolean = true, onPick: (Uri?) -> Unit) {
        val titles = ArrayList<String>()
        val uris = ArrayList<Uri?>()
        if (showSilent) {
            titles.add(context.getString(R.string.sound_chooser_silent))
            uris.add(null)
        }
        val default = RingtoneManager.getDefaultUri(ringtoneType)
        titles.add(context.getString(R.string.sound_chooser_default))
        uris.add(default)
        val manager = RingtoneManager(context).apply { setType(ringtoneType) }
        manager.cursor?.use { c ->
            while (c.moveToNext()) {
                titles.add(c.getString(RingtoneManager.TITLE_COLUMN_INDEX))
                uris.add(manager.getRingtoneUri(c.position))
            }
        }

        var playing: Ringtone? = null
        val handler = Handler(Looper.getMainLooper())
        val stop = Runnable { playing?.stop() }
        fun preview(uri: Uri?) {
            handler.removeCallbacks(stop)
            playing?.stop()
            playing = uri?.let { u ->
                RingtoneManager.getRingtone(context, u)?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
                    play()
                }
            }
            if (playing != null) handler.postDelayed(stop, PREVIEW_MS)
        }

        val checked = uris.indexOfFirst { it == current }.let { if (it >= 0) it else if (current == null && showSilent) 0 else -1 }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(titles.toTypedArray(), checked) { _, which ->
                onPick(uris[which])
                preview(uris[which])
            }
            .setPositiveButton(R.string.sound_chooser_done, null)
            .setOnDismissListener { handler.removeCallbacks(stop); playing?.stop() }
            .show()
    }
}
