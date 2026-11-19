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
package com.wanderwildwood.kotozute.repository

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.wanderwildwood.kotozute.model.BackupFile
import io.reactivex.Observable

interface BackupRepository {

    sealed class Progress(val running: Boolean = false, val indeterminate: Boolean = true) {
        class Idle : Progress()
        class Parsing : Progress(true)
        class Running(val max: Int, val count: Int) : Progress(true, false)
        class Saving : Progress(true)
        class Syncing : Progress(true)
        class Finished : Progress(true, false)
        /** The backup could not be written, or the file to restore could not be read. */
        class Failed(val restore: Boolean) : Progress(true, false)
    }

    fun getDefaultBackupPath(): String

    fun getBackupDocumentTree(): DocumentFile?

    fun getBackupPathUriForPicker(): Uri

    fun persistBackupDirectory(directory: Uri)

    /** Writes a backup and returns the name of the file, or null if none was written. */
    fun performBackup(): String?

    fun isBackupRunning(): Boolean

    /**
     * Deletes this app's own older backups from the backup folder, keeping the newest [keep]
     * and [justWritten]. Nothing else in the folder is touched.
     */
    fun pruneBackups(keep: Int, justWritten: String)

    fun getBackupProgress(): Observable<Progress>

    fun parseBackup(uri: Uri): BackupFile

    fun performRestore(uri: Uri)

    fun getRestoreProgress(): Observable<Progress>

    fun stopRestore()

}
