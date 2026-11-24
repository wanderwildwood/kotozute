package com.wanderwildwood.kotozute.common.util

import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.model.MmsPart
import com.wanderwildwood.kotozute.model.SavedFileNames
import timber.log.Timber
import kotlin.concurrent.thread

/**
 * Saves a picture, video or file from a text message.
 *
 * A picture or a video goes straight into the Gallery (`Pictures/Messaging` or
 * `Movies/Messaging`, see [GallerySaver]) with its real type and an extension, so it shows up
 * where a person looks for photos, with no screen in between. Anything else (a PDF, a contact
 * card, audio) goes where the person chooses through Android's own "save as" screen, told the
 * file's real type rather than as any type. Before Android 10 a picture takes that screen too.
 * Neither path needs a storage permission.
 *
 * Made as an activity's field, so the result is registered before the activity starts.
 */
class PartSaver(private val activity: ComponentActivity) {

    private var pending: MmsPart? = null

    private val launcher = activity.registerForActivityResult(
        FilesCreateTypedDocument()
    ) { destination: Uri? ->
        val part = pending
        pending = null
        if (destination != null && part != null) write(part, destination)
    }

    fun save(part: MmsPart) {
        if (GallerySaver.canUse(part.type)) {
            saveToGallery(part)
            return
        }
        pending = part
        val type = if (SavedFileNames.isVague(part.type)) "*/*" else SavedFileNames.normalizeType(part.type)
        runCatching { launcher.launch(part.getBestFilename() to type) }
            .onFailure {
                pending = null
                Timber.w(it, "save part: no document picker")
                Toast.makeText(activity, R.string.signal_attachment_no_app, Toast.LENGTH_LONG).show()
            }
    }

    /** Off the main thread, and it says where it went, or that it did not work. */
    private fun saveToGallery(part: MmsPart) {
        val source = part.getUri()
        val name = part.getBestFilename()
        val type = part.type
        thread(isDaemon = true) {
            val saved = runCatching {
                activity.contentResolver.openInputStream(source)?.use { input ->
                    GallerySaver.save(activity, input, name, type)
                } ?: throw java.io.IOException("the part could not be read")
            }.onFailure { Timber.w(it, "save part: could not save to the Gallery") }.isSuccess
            activity.runOnUiThread {
                val message = if (saved) {
                    activity.getString(R.string.attachment_saved_to, GallerySaver.folderFor(type))
                } else {
                    activity.getString(R.string.signal_attachment_save_failed)
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }
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
