package com.wanderwildwood.kotozute.common.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.wanderwildwood.kotozute.model.SavedFileNames
import timber.log.Timber
import java.io.IOException
import java.io.InputStream

/**
 * Puts a received picture or video where the phone's Gallery looks, through `MediaStore`.
 *
 * A picture saved through the "save as" screen lands wherever that screen points, usually
 * Downloads, which the Gallery does not list, and a file with no type is just "an attachment
 * nobody can open". `MediaStore` takes the bytes, the real type and a name with an extension,
 * and the Gallery shows it at once. From Android 10 on that needs **no storage permission**,
 * because the app only adds its own new file.
 *
 * Before Android 10 the same insert would need `WRITE_EXTERNAL_STORAGE`, which this app does
 * not ask for and will not for one button. There [canUse] is false and the caller keeps the
 * picker, as it does for every other kind of file.
 *
 * Pictures go to `Pictures/Messaging`, videos to `Movies/Messaging`: the folders Android
 * itself reserves for each, so the Gallery and a file manager agree on where they are.
 */
object GallerySaver {

    const val PICTURES_FOLDER = "Pictures/Messaging"
    const val MOVIES_FOLDER = "Movies/Messaging"

    /** Whether this type goes to the Gallery, and this phone lets it without a permission. */
    fun canUse(type: String?): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && SavedFileNames.isGalleryMedia(type)

    /** The folder, as shown to the person, that [type] is saved into. */
    fun folderFor(type: String?): String =
        if (SavedFileNames.isVideo(type)) MOVIES_FOLDER else PICTURES_FOLDER

    /**
     * Copies [input] into the Gallery as [name] and returns where it went, or throws.
     *
     * The file is hidden from other apps (`IS_PENDING`) until every byte is in, and removed
     * again if the copy fails, so a cut-short picture is never listed.
     *
     * Call it off the main thread. [name] should come from [SavedFileNames.nameFor].
     */
    fun save(context: Context, input: InputStream, name: String, type: String): Uri {
        val mime = SavedFileNames.normalizeType(type)
        val video = SavedFileNames.isVideo(mime)
        val collection = if (video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folderFor(mime))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IOException("the Gallery would not take it")
        try {
            val copied = resolver.openOutputStream(uri)?.use { input.copyTo(it) }
                ?: throw IOException("nothing would open the new file")
            if (copied <= 0L) throw IOException("the attachment is empty")
            resolver.update(
                uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
            )
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            Timber.w(e, "gallery save: could not save")
            throw e
        }
        return uri
    }
}
