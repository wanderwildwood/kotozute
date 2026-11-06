package com.wanderwildwood.kotozute.common.util

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Ask Files (tana) for "open a file", "choose a file", "save a file" and "choose a folder",
 * and the phone's own screen for them when Files is not installed. Nothing here needs Files:
 * every contract below IS the standard one, with one change -- when Files answers the request,
 * the request is addressed to it by package, so the e-ink screen opens at once instead of a
 * chooser or Android's DocumentsUI.
 *
 * Use them where `ActivityResultContracts.X` was used, with the same input and output:
 *
 *     registerForActivityResult(FilesOpenDocument()) { uri -> ... }
 *     registerForActivityResult(FilesCreateDocument("text/plain")) { uri -> ... }
 *
 * The app's manifest must let it see Files, or Android 11+ answers "not installed" and the
 * stock screen is used every time:
 *
 *     <queries><package android:name="com.wanderwildwood.tana" /></queries>
 */
object FilesPicker {

    const val PACKAGE = "com.wanderwildwood.tana"

    /** The package a request is addressed to: Files when it answers the request, else nobody. */
    fun packageFor(filesAnswers: Boolean): String? = if (filesAnswers) PACKAGE else null

    /**
     * The grant flags to ask for a document with. Reading is always granted. A document that
     * will be written back, or found again after a restart, asks for both, as the stock screen
     * does when the request carries them.
     */
    fun flagsFor(writable: Boolean): Int =
        if (writable) {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        } else {
            0
        }

    /** True when Files is installed and has a screen for exactly this request. */
    fun answers(context: Context, request: Intent): Boolean {
        val aimed = Intent(request).setPackage(PACKAGE)
        return try {
            context.packageManager.resolveActivity(aimed, 0) != null
        } catch (e: RuntimeException) {
            false
        }
    }

    /** [request] addressed to Files if Files answers it, else as it is. */
    fun aim(context: Context, request: Intent, writable: Boolean = false): Intent {
        val flags = flagsFor(writable)
        if (flags != 0) request.addFlags(flags)
        packageFor(answers(context, request))?.let { request.setPackage(it) }
        return request
    }
}

/** Open one document. [writable]: it will be written back (or kept across restarts). */
class FilesOpenDocument(private val writable: Boolean = false) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        FilesPicker.aim(context, super.createIntent(context, input), writable)
}

/** Open several documents at once. */
class FilesOpenMultiple(private val writable: Boolean = false) : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        FilesPicker.aim(context, super.createIntent(context, input), writable)
}

/** Choose a file to read once (an attachment, an import); the older "get content" request. */
class FilesGetContent : ActivityResultContracts.GetContent() {
    override fun createIntent(context: Context, input: String): Intent =
        FilesPicker.aim(context, super.createIntent(context, input))
}

/** Name a new file; the app then writes into the returned address. */
class FilesCreateDocument(mimeType: String) : ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent =
        FilesPicker.aim(context, super.createIntent(context, input), writable = true)
}

/** Choose a folder. The result is a tree address, to be used with DocumentFile. */
class FilesOpenTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: android.net.Uri?): Intent =
        FilesPicker.aim(context, super.createIntent(context, input))
}
