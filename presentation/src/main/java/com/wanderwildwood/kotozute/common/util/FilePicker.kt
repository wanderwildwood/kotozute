package com.wanderwildwood.kotozute.common.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

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
