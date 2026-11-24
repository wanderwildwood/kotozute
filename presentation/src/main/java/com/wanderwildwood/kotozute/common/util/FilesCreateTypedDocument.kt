package com.wanderwildwood.kotozute.common.util

import android.content.Context
import android.content.Intent

/**
 * Name a new file of a known type; the app then writes into the returned address.
 *
 * [FilesCreateDocument] fixes its type when it is made, which is why a saved picture used to be
 * offered as any type at all: nothing then knew what it was. Here the input is the name and the type
 * together, so the picker is told the real type of each file.
 */
class FilesCreateTypedDocument : androidx.activity.result.contract.ActivityResultContract<Pair<String, String>, android.net.Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent {
        val (name, type) = input
        val request = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType(type)
            .putExtra(Intent.EXTRA_TITLE, name)
        return FilesPicker.aim(context, request, writable = true)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): android.net.Uri? =
        intent.takeIf { resultCode == android.app.Activity.RESULT_OK }?.data
}
