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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Telephony
import androidx.core.content.contentValuesOf
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.squareup.moshi.Moshi
import com.wanderwildwood.kotozute.common.util.extensions.now
import com.wanderwildwood.kotozute.model.BackupFile
import com.wanderwildwood.kotozute.model.Conversation
import com.wanderwildwood.kotozute.model.Message
import com.wanderwildwood.kotozute.util.Preferences
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import io.realm.Realm
import okio.buffer
import okio.source
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Timer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.schedule

/** How long a failed backup or restore says so before the screen goes back to normal. */
private const val FAILED_SHOWN_MS = 4000L

@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val context: Context,
    private val moshi: Moshi,
    private val prefs: Preferences,
    private val syncRepo: SyncRepository
) : BackupRepository {

    data class Backup(
        val messageCount: Int = 0,
        val messages: List<BackupMessage> = listOf()
    )

    /**
     * Simpler version of [Backup] which allows us to read certain fields from the backup without
     * needing to parse the entire file
     */
    data class BackupMetadata(
        val messageCount: Int = 0
    )

    data class BackupMessage(
        val type: Int,
        val address: String,
        val date: Long,
        val dateSent: Long,
        val read: Boolean,
        val status: Int,
        val body: String,
        val protocol: Int,
        val serviceCenter: String?,
        val locked: Boolean,
        val subId: Int
    )

    // Subjects to emit our progress events to
    private val backupProgress: Subject<BackupRepository.Progress> =
            BehaviorSubject.createDefault(BackupRepository.Progress.Idle())
    private val restoreProgress: Subject<BackupRepository.Progress> =
            BehaviorSubject.createDefault(BackupRepository.Progress.Idle())

    @Volatile private var stopFlag: Boolean = false

    /**
     * Only ever a starting point for the folder picker: once a directory has been chosen it
     * is remembered as a document tree, and that choice is what backups are written to.
     * Renaming this therefore moves where the picker opens, not where anyone's existing
     * backups live.
     */
    override fun getDefaultBackupPath(): String {
        return "${Environment.getExternalStorageDirectory()}/Messaging/Backups"
    }

    override fun getBackupDocumentTree(): DocumentFile? {
        return prefs.backupDirectory.get()
                .takeIf { uri -> uri != Uri.EMPTY }
                ?.let { uri -> DocumentFile.fromTreeUri(context, uri) }
                ?.takeIf { dir -> dir.exists() && dir.canRead() && dir.canWrite() }
    }

    override fun getBackupPathUriForPicker(): Uri {
        return prefs.backupDirectory.get().takeIf { uri -> uri != Uri.EMPTY }
                ?: getDefaultBackupPath().toUri()
    }

    override fun persistBackupDirectory(directory: Uri) {
        context.contentResolver.takePersistableUriPermission(directory,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.backupDirectory.set(directory)

        Timber.v("Updated backup directory: $directory")
    }

    override fun performBackup(): String? {
        // If a backup or restore is already running, don't do anything
        if (isBackupOrRestoreRunning()) return null

        var messageCount: Int

        // Map all the messages into our object we'll use for the Json mapping
        val backupMessages = Realm.getDefaultInstance().use { realm ->
            // Get the messages from realm
            // SMS only. A picture message (MMS) has its text and pictures in parts this format
            // cannot carry, and an older backup that took them anyway brought each one back as an
            // empty text message.
            // Not what is in Deleted: a backup is one more place a deleted conversation would
            // outlive its 30 days, and restoring it would bring it back with no say.
            val deleted = realm.where(Conversation::class.java)
                    .greaterThan("deletedAt", 0L)
                    .findAll()
                    .map { it.id }
                    .toTypedArray()
            val messages = realm.where(Message::class.java)
                    .equalTo("type", Message.TYPE_SMS)
                    .let { query ->
                        // `in` with nothing in it is refused.
                        if (deleted.isEmpty()) query else query.not().`in`("threadId", deleted)
                    }
                    .sort("date").findAll().createSnapshot()
            messageCount = messages.size

            // Map the messages to the new format
            messages.mapIndexed { index, message ->
                // Update the progress
                backupProgress.onNext(BackupRepository.Progress.Running(messageCount, index))
                messageToBackupMessage(message)
            }
        }

        // Update the status, and set the progress to be indeterminate since we can no longer calculate progress
        backupProgress.onNext(BackupRepository.Progress.Saving())

        // Convert the data to json
        val adapter = moshi.adapter(Backup::class.java).indent("\t")
        val json = adapter.toJson(Backup(messageCount, backupMessages)).toByteArray()

        // Encrypted under the backup code (BackupCode). No code, no backup: a plain copy of
        // every text in a folder other apps can read is what this replaced.
        val sealed = try {
            com.wanderwildwood.kotozute.backup.BackupCode.encrypt(context, json)
        } catch (e: Exception) {
            Timber.w(e, "backup: not encrypted, so not written")
            backupProgress.onNext(BackupRepository.Progress.Failed(restore = false))
            Timer().schedule(FAILED_SHOWN_MS) { backupProgress.onNext(BackupRepository.Progress.Idle()) }
            return null
        } finally {
            java.util.Arrays.fill(json, 0)
        }

        var created: DocumentFile? = null
        try {
            val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(now())
            created = getBackupDocumentTree()
                    // `.bin`, not a name of our own: file pickers decide a file's type from its
                    // extension, and one they do not know (".kzb" was tried, 2026-11-19) is
                    // hidden from the Restore picker even though it asks for binary files.
                    ?.createFile("application/octet-stream", "backup-$timestamp.bin")
            val inputStream = created
                    ?.let { file -> context.contentResolver.openOutputStream(file.uri) }
                    ?: throw Exception("Failed to open output stream")

            inputStream.use { stream ->
                stream.write(sealed)
            }
        } catch (e: Exception) {
            Timber.w(e)
            // Say so, and leave no half-written file behind to be found when it is needed.
            runCatching { created?.delete() }
            backupProgress.onNext(BackupRepository.Progress.Failed(restore = false))
            Timer().schedule(FAILED_SHOWN_MS) { backupProgress.onNext(BackupRepository.Progress.Idle()) }
            return null
        }

        // Mark the task finished, and set it as Idle a second later
        backupProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { backupProgress.onNext(BackupRepository.Progress.Idle()) }
        return created?.name
    }

    override fun isBackupRunning(): Boolean = isBackupOrRestoreRunning()

    override fun pruneBackups(keep: Int, justWritten: String) {
        val files = getBackupDocumentTree()?.listFiles() ?: return
        val entries = files.mapNotNull { file ->
            file.name?.let { name -> BackupPruning.Entry(name, file.lastModified()) }
        }
        val doomed = BackupPruning.toDelete(entries, keep, justWritten).toSet()
        // Re-check the name on each file itself: the same list the selection was made from,
        // and only ever a file that matches this app's own backup name.
        files.filter { file ->
            val name = file.name
            name != null && name in doomed && BackupPruning.OWN_BACKUP_NAME.matches(name) && file.isFile
        }.forEach { file ->
            Timber.v("Deleting old backup ${file.name}")
            runCatching { file.delete() }.onFailure { Timber.w(it) }
        }
    }

    private fun messageToBackupMessage(message: Message): BackupMessage = BackupMessage(
            type = message.boxId,
            address = message.address,
            date = message.date,
            dateSent = message.dateSent,
            read = message.read,
            status = message.deliveryStatus,
            body = message.body,
            protocol = 0,
            serviceCenter = null,
            locked = message.locked,
            subId = message.subId
    )

    override fun getBackupProgress(): Observable<BackupRepository.Progress> = backupProgress

    @SuppressLint("Recycle") // InputStream is closed by Okio BufferedSource
    override fun parseBackup(uri: Uri): BackupFile {
        val adapter = moshi.adapter(BackupMetadata::class.java)

        val file = DocumentFile.fromSingleUri(context, uri)
                ?: throw IllegalArgumentException("Couldn't open backup file")

        val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Couldn't open backup file")
        // Throws BackupCode.Needed or BackupCrypto.WrongCode for an encrypted file this
        // phone's code does not open; the screen says to enter the other phone's code.
        val metadata = adapter.fromJson(String(com.wanderwildwood.kotozute.backup.BackupCode.open(context, bytes), Charsets.UTF_8))
                ?: throw IllegalArgumentException("Couldn't parse backup file")

        return BackupFile(file.lastModified(), metadata.messageCount)
    }

    override fun performRestore(uri: Uri) {
        // If a backupFile or restore is already running, don't do anything
        if (isBackupOrRestoreRunning()) return

        // A stop asked for after the last restore had already ended must not stop this one.
        stopFlag = false
        restoreProgress.onNext(BackupRepository.Progress.Parsing())

        val adapter = moshi.adapter(Backup::class.java)
        val backup = try {
            DocumentFile.fromSingleUri(context, uri)
                    ?.let { file -> context.contentResolver.openInputStream(file.uri) }
                    ?.use { it.readBytes() }
                    ?.let { com.wanderwildwood.kotozute.backup.BackupCode.open(context, it) }
                    ?.let { adapter.fromJson(String(it, Charsets.UTF_8)) }
        } catch (e: Exception) {
            Timber.w(e)
            null
        }
        if (backup == null) {
            // A damaged or unreadable file: say so and end, rather than sit at "Parsing" for good.
            restoreProgress.onNext(BackupRepository.Progress.Failed(restore = true))
            Timer().schedule(FAILED_SHOWN_MS) { restoreProgress.onNext(BackupRepository.Progress.Idle()) }
            return
        }

        // Only what the phone does not already hold, so restoring over messages still here, or
        // restoring one file twice, doubles nothing.
        // If the phone's messages cannot be read, nothing is written: without them there is no
        // telling which messages are already here.
        val existing = try {
            existingSms()
        } catch (e: Exception) {
            Timber.w(e)
            restoreProgress.onNext(BackupRepository.Progress.Idle())
            return
        }
        val toWrite = backup.messages?.let { SmsRestore.toWrite(it, existing) }.orEmpty()
        val messageCount = toWrite.size
        var errorCount = 0

        toWrite.forEachIndexed { index, message ->
            if (stopFlag) {
                stopFlag = false
                restoreProgress.onNext(BackupRepository.Progress.Idle())
                return
            }

            // Update the progress
            restoreProgress.onNext(BackupRepository.Progress.Running(messageCount, index))

            try {
                val values = contentValuesOf(
                        Telephony.Sms.TYPE to message.type,
                        Telephony.Sms.ADDRESS to message.address,
                        Telephony.Sms.DATE to message.date,
                        Telephony.Sms.DATE_SENT to message.dateSent,
                        Telephony.Sms.READ to message.read,
                        Telephony.Sms.SEEN to 1,
                        Telephony.Sms.STATUS to message.status,
                        Telephony.Sms.BODY to message.body,
                        Telephony.Sms.PROTOCOL to message.protocol,
                        Telephony.Sms.SERVICE_CENTER to message.serviceCenter,
                        Telephony.Sms.LOCKED to message.locked
                )

                if (prefs.canUseSubId.get()) {
                    values.put(Telephony.Sms.SUBSCRIPTION_ID, message.subId)
                }

                context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
            } catch (e: Exception) {
                Timber.w(e)
                errorCount++
            }
        }

        if (errorCount > 0) {
            Timber.w(Exception("Failed to backup $errorCount/$messageCount messages"))
        }

        // Sync the messages
        restoreProgress.onNext(BackupRepository.Progress.Syncing())
        syncRepo.syncMessages()

        // Mark the task finished, and set it as Idle a second later
        restoreProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { restoreProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    /** Every SMS on the phone now, as [SmsRestore] compares them. Throws if they cannot be read. */
    private fun existingSms(): Set<SmsRestore.Key> {
        val keys = HashSet<SmsRestore.Key>()
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.BODY)
        val cursor = context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, null, null, null)
                ?: throw IllegalStateException("The SMS provider gave no cursor")
        cursor.use { c ->
            while (c.moveToNext()) {
                keys += SmsRestore.key(c.getString(0), c.getLong(1), c.getInt(2), c.getString(3))
            }
        }
        return keys
    }

    override fun getRestoreProgress(): Observable<BackupRepository.Progress> = restoreProgress

    override fun stopRestore() {
        stopFlag = true
    }

    private fun isBackupOrRestoreRunning(): Boolean {
        return backupProgress.blockingFirst().running || restoreProgress.blockingFirst().running
    }

}