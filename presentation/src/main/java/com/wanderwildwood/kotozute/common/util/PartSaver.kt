package com.wanderwildwood.kotozute.common.util

import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.model.MmsPart
import timber.log.Timber
import kotlin.concurrent.thread

/**
 * Saves a picture, video or file from a text message where the person chooses, through
 * Android's own "save as" screen -- as a Signal attachment is saved (SignalThreadActivity).
 *
 * It used to be written straight into Pictures/Messaging, Movies/Messaging or
 * Download/Messaging without asking. `ACTION_CREATE_DOCUMENT` needs no storage permission,
 * and nothing lands anywhere the person did not pick.
 *
 * Made as an activity's field, so the result is registered before the activity starts.
 */
class PartSaver(private val activity: ComponentActivity) {

    private var pending: MmsPart? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { destination: Uri? ->
        val part = pending
        pending = null
        if (destination != null && part != null) write(part, destination)
    }

    fun save(part: MmsPart) {
        pending = part
        runCatching { launcher.launch(part.getBestFilename()) }
            .onFailure {
                pending = null
                Timber.w(it, "save part: no document picker")
                Toast.makeText(activity, R.string.signal_attachment_no_app, Toast.LENGTH_LONG).show()
            }
    }

    /** Off the main thread, and it says how it went either way. */
    private fun write(part: MmsPart, destination: Uri) {
        val source = part.getUri()
        thread(isDaemon = true) {
            val written = runCatching {
                val resolver = activity.contentResolver
                resolver.openInputStream(source)?.use { input ->
                    resolver.openOutputStream(destination)?.use { output -> input.copyTo(output) }
                        ?: throw java.io.IOException("nothing would open the destination")
                } ?: throw java.io.IOException("the part could not be read")
            }.onFailure { Timber.w(it, "save part: could not save") }.isSuccess
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    if (written) R.string.signal_attachment_saved else R.string.signal_attachment_save_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
