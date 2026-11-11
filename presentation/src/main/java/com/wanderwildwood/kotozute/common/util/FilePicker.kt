package com.wanderwildwood.kotozute.common.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.util.Preferences

/**
 * Asking for files, from whichever app the reader chose in Settings.
 *
 * Every attach button asks Android for content (`ACTION_GET_CONTENT`), and Android answers
 * that with its own picker every time: it declares itself at a priority no ordinary app is
 * allowed to match, so no chooser appears and there is no "always" to press. A file manager
 * that can answer the same request is only reachable by naming it, which is what the setting
 * does. A chosen app that has gone, or cannot take this request, falls back to Android's.
 */
object FilePicker {

    /** Something that can hand over files: its package and what it is called. */
    data class Choice(val packageName: String, val label: String)

    /** [types] is one kind, or several joined by commas: pictures and videos together, say. */
    private fun request(types: String, several: Boolean): Intent {
        val kinds = types.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, several)
        return if (kinds.size > 1) {
            intent.setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, kinds.toTypedArray())
        } else {
            intent.setType(kinds.firstOrNull() ?: "*/*")
        }
    }

    /**
     * The apps on this phone that can hand over any kind of file, one row per app. Asked with
     * a plain file's type rather than "anything": a request for anything matches every app
     * that answers for one kind -- the gallery, the music player, the contacts -- and none of
     * those is a way to reach a file.
     */
    fun choices(context: Context): List<Choice> {
        val pm = context.packageManager
        return pm.queryIntentActivities(request("application/octet-stream", false), PackageManager.MATCH_DEFAULT_ONLY)
            .map { Choice(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
    }

    /** [choices] without Android's own picker, which is the empty choice and has its own row. */
    fun others(context: Context): List<Choice> =
        choices(context).filterNot {
            it.packageName == "com.android.documentsui" || it.packageName == "com.google.android.documentsui"
        }

    /**
     * Whether an attach button should offer the choice before it opens anything: only while
     * no choice was ever made, and only when there is something besides Android's own to choose.
     */
    fun shouldAsk(chosen: String, asked: Boolean, others: Int): Boolean =
        chosen.isBlank() && !asked && others > 0

    /**
     * The choice itself, as Settings shows it: Android's own picker first, then the others.
     * [then] runs after a row is chosen, not when the list is dismissed.
     */
    fun choose(activity: Activity, prefs: Preferences, then: () -> Unit = {}) {
        val others = others(activity)
        val labels = listOf(activity.getString(R.string.settings_file_picker_system)) + others.map { it.label }
        val current = others.indexOfFirst { it.packageName == prefs.filePicker.get() } + 1
        activity.einkDialog()
            .setTitle(R.string.settings_file_picker_title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                prefs.filePicker.set(if (which == 0) "" else others[which - 1].packageName)
                prefs.filePickerAsked.set(true)
                dialog.dismiss()
                then()
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    /**
     * Runs [then], which opens a picker -- after offering the choice, the first time, on a
     * phone that has another files app. The setting sits at the foot of a long page, and a
     * reader with Cabinet installed had no way to know it was there. Dismissing the list
     * attaches nothing and leaves the question for the next press.
     */
    fun askOnce(activity: Activity, prefs: Preferences, then: () -> Unit) {
        if (shouldAsk(prefs.filePicker.get(), prefs.filePickerAsked.get(), others(activity).size)) {
            choose(activity, prefs, then)
        } else {
            then()
        }
    }

    /** Pictures and files: input is the kinds wanted and whether several may be chosen. */
    class Contract(private val chosen: () -> String) : ActivityResultContract<Pair<String, Boolean>, List<Uri>>() {

        override fun createIntent(context: Context, input: Pair<String, Boolean>): Intent {
            val intent = request(input.first, input.second)
            val pkg = chosen()
            if (pkg.isNotBlank()) {
                val named = Intent(intent).setPackage(pkg)
                if (context.packageManager.queryIntentActivities(named, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()) {
                    return named
                }
            }
            return intent
        }

        override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
            if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
            val clip = intent.clipData
            if (clip != null && clip.itemCount > 0) {
                return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            }
            return listOfNotNull(intent.data)
        }
    }
}
