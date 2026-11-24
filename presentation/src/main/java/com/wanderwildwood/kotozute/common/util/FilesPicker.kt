package com.wanderwildwood.kotozute.common.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import java.security.MessageDigest

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
 * A request is addressed to Files only when the app holding its name is Files: signed with its
 * release certificate ([CERT_SHA256]) and nothing else. On a phone without Files any app could
 * take the name and be handed the files and folders the person picks; then the stock screen is used.
 *
 * The app's manifest must let it see Files, or Android 11+ answers "not installed" and the
 * stock screen is used every time:
 *
 *     <queries><package android:name="com.wanderwildwood.tana" /></queries>
 */
object FilesPicker {

    const val PACKAGE = "com.wanderwildwood.tana"

    /** SHA-256 of the certificate Files' releases are signed with, lower-case hex. */
    const val CERT_SHA256 = "2b3995b71fe50c7514d975b8df753b1ea9695e695c3c4d1f1ed83d64c81afe86"

    /**
     * The debug keys test builds are signed with (the build box's and the desk machine's). Files
     * signed with one of them is asked only by a debug build of this app, never by a release.
     */
    val DEBUG_CERTS_SHA256 = setOf(
        "1e7e9cc0ea37a725d0eaf622187792c425dc0351a3e228ef462f8eaacbb9db06",
        "9ae26e87553000817b4185b15d9047845e87871d8f5204dac77b82183fee248b",
    )

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

    /** True when Files is installed, is the real Files, and has a screen for exactly this request. */
    fun answers(context: Context, request: Intent): Boolean {
        val aimed = Intent(request).setPackage(PACKAGE)
        return try {
            context.packageManager.resolveActivity(aimed, 0) != null && isFiles(context)
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * Whether the app holding Files' name is Files: signed with its release certificate and
     * nothing else. A debuggable build of this app also takes Files signed with a debug key.
     */
    fun isFiles(context: Context): Boolean {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return trusted(signers(context.packageManager, PACKAGE), debuggable)
    }

    /** Whether certificates [signers] (SHA-256, lower-case hex) are Files' alone; see [isFiles]. */
    fun trusted(signers: List<String>, debuggable: Boolean): Boolean {
        val certs = if (debuggable) DEBUG_CERTS_SHA256 + CERT_SHA256 else setOf(CERT_SHA256)
        return signers.isNotEmpty() && signers.all { it in certs }
    }

    /** The SHA-256 of each certificate [pkg] is signed with, lower-case hex; none when it cannot be read. */
    @Suppress("DEPRECATION")
    private fun signers(pm: PackageManager, pkg: String): List<String> = runCatching {
        val raw = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        raw.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }.getOrDefault(emptyList())

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
