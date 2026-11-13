/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.wanderwildwood.kotozute.common.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File
import com.wanderwildwood.kotozute.R

class ClipboardUtils {
    companion object {
        fun copy(context: Context, string: String) {
            try {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("SMS", string))
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    if ((e is RuntimeException) &&
                        e.message?.startsWith("android.os.TransactionTooLargeException") == true
                    ) R.string.clipboard_too_large_to_copy
                    else R.string.clipboard_unable_to_copy_to,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        /**
         * Puts a picture on the clipboard, as the address of its bytes rather than as words.
         *
         * [uri] must come from one of this app's providers that grants read access (the MMS
         * part provider, or the cache `FileProvider`), so that whatever it is pasted into,
         * here or in another app, can read it.
         *
         * @return whether it went on.
         */
        fun copyPicture(context: Context, uri: Uri): Boolean = try {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newUri(context.contentResolver, "picture", uri))
            true
        } catch (e: Exception) {
            false
        }

        /**
         * What a Paste would bring in when the clipboard holds pictures or files rather than
         * words: the content addresses on it. Empty when it holds text, or nothing.
         *
         * An entry that carries words as well as an address is left to the ordinary paste:
         * the words are what somebody copying it saw.
         */
        fun pastedContent(context: Context): List<Uri> = try {
            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            (0 until (clip?.itemCount ?: 0))
                .map { clip!!.getItemAt(it) }
                .filter { it.text.isNullOrEmpty() && it.uri?.scheme == "content" }
                .map { it.uri }
        } catch (e: Exception) {
            emptyList()
        }

        /**
         * A pasted picture that belongs to another app, copied into this app's cache.
         *
         * Android lends the address on the clipboard only until something else is copied. An
         * SMS reads its attachments when it is sent, which may be after that, and would then
         * go without its picture and say nothing. This app's own addresses need no copy.
         *
         * Disk work: not for the main thread. Null if it could not be read.
         */
        fun keepPasted(context: Context, uri: Uri): Uri? {
            if (uri.authority?.startsWith(context.packageName) == true) return uri
            return try {
                val type = context.contentResolver.getType(uri)
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "bin"
                val file = File(context.cacheDir, "pasted-${System.currentTimeMillis()}.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                } ?: return null
                Uri.fromFile(file)
            } catch (e: Exception) {
                null
            }
        }
    }

}
