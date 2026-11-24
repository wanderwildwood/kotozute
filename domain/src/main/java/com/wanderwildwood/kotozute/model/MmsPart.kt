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
package com.wanderwildwood.kotozute.model

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import io.realm.RealmObject
import io.realm.RealmResults
import io.realm.annotations.Index
import io.realm.annotations.LinkingObjects
import io.realm.annotations.PrimaryKey

open class MmsPart : RealmObject() {

    @PrimaryKey var id: Long = 0
    @Index var messageId: Long = 0
    var type: String = ""
    var seq: Int = -1
    var name: String? = null
    var text: String? = null

    @LinkingObjects("parts")
    val messages: RealmResults<Message>? = null

    fun getUri(): Uri = Uri
        .Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority("mms")
        .encodedPath("part/$id")
        .build()

    /**
     * What to call this part when it is saved or shared: its own name where it has one, with an
     * extension from its type where that is missing, and `part-<id>` plus an extension where it
     * has no name at all. It used to be a bare "unknown", which nothing could open.
     * See [SavedFileNames].
     */
    fun getBestFilename(): String = SavedFileNames.nameFor(
        name, type, "part-$id"
    ) { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }

    fun getSummary(): String? = when {
        type == "application/smil" -> null
        type == "text/plain" -> text
        type.equals("text/x-vcard", ignoreCase = true) || type.equals("text/vcard", ignoreCase = true) -> "Contact card"
        type.startsWith("image") -> "Picture"
        type.startsWith("video") -> "Video"
        type.startsWith("audio") -> "Audio"
        else -> type.substring(type.indexOf('/') + 1)
    }

}