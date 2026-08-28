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

import com.wanderwildwood.kotozute.manager.QkTransaction
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Telephony
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import android.webkit.MimeTypeMap
import androidx.core.content.contentValuesOf
import com.android.mms.transaction.MmsMessageSender
import com.google.android.mms.ContentType
import com.google.android.mms.pdu_alt.PduHeaders
import com.wanderwildwood.kotozute.mms.MmsReport
import com.wanderwildwood.kotozute.mms.mmsReportVerdicts
import com.klinker.android.send_message.SmsManagerFactory
import com.wanderwildwood.kotozute.common.util.extensions.now
import com.wanderwildwood.kotozute.compat.TelephonyCompat
import com.wanderwildwood.kotozute.extensions.anyOf
import com.wanderwildwood.kotozute.extensions.insertOrUpdate
import com.wanderwildwood.kotozute.extensions.isImage
import com.wanderwildwood.kotozute.extensions.isVideo
import com.wanderwildwood.kotozute.extensions.map
import com.wanderwildwood.kotozute.extensions.resourceExists
import com.wanderwildwood.kotozute.manager.ActiveConversationManager
import com.wanderwildwood.kotozute.manager.KeyManager
import com.wanderwildwood.kotozute.mapper.CursorToMessage
import com.wanderwildwood.kotozute.mapper.CursorToPart
import com.wanderwildwood.kotozute.model.Attachment
import com.wanderwildwood.kotozute.model.Conversation
import com.wanderwildwood.kotozute.model.Message
import com.wanderwildwood.kotozute.model.Message.Companion.TYPE_MMS
import com.wanderwildwood.kotozute.model.Message.Companion.TYPE_SMS
import com.wanderwildwood.kotozute.model.MmsPart
import com.wanderwildwood.kotozute.receiver.MessageDeliveredReceiver
import com.wanderwildwood.kotozute.receiver.MessageSentReceiver
import com.wanderwildwood.kotozute.receiver.SendDelayedMessageReceiver
import com.wanderwildwood.kotozute.receiver.SendDelayedMessageReceiver.Companion.MESSAGE_ID_EXTRA
import com.wanderwildwood.kotozute.util.ImageUtils
import com.wanderwildwood.kotozute.util.PhoneNumberUtils
import com.wanderwildwood.kotozute.util.Preferences
import com.wanderwildwood.kotozute.util.tryOrNull
import io.realm.Case
import io.realm.Realm
import io.realm.RealmList
import io.realm.RealmResults
import io.realm.Sort
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
open class MessageRepositoryImpl @Inject constructor(
    private val activeConversationManager: ActiveConversationManager,
    private val context: Context,
    private val messageIds: KeyManager,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val prefs: Preferences,
    private val syncRepository: SyncRepository,
    private val reactions: EmojiReactionRepository,
    private val cursorToMessage: CursorToMessage,
    private val cursorToPart: CursorToPart,
) : MessageRepository {

    companion object {
        const val TELEPHONY_UPDATE_CHUNK_SIZE = 200
    }

    private fun getMessagesBase(threadId: Long, query: String) =
        Realm.getDefaultInstance()
            .where(Message::class.java)
            .equalTo("threadId", threadId)
            .equalTo("isEmojiReaction", false)
            .let {
                when (query.isEmpty()) {
                    true -> it
                    false -> it
                        .beginGroup()
                        .contains("body", query, Case.INSENSITIVE)
                        .or()
                        .contains("parts.text", query, Case.INSENSITIVE)
                        .endGroup()
                }
            }
            .sort("date")

    override fun getMessages(threadId: Long, query: String): RealmResults<Message> =
        getMessagesBase(threadId, query).findAllAsync()

    override fun getMessages(threadId: Long, query: String, limit: Long): RealmResults<Message> {
        // To get the most recent N messages in ascending order (for display):
        // 1. Get all message IDs sorted descending (newest first)
        // 2. Take the first N (most recent)
        // 3. Query those IDs sorted ascending (oldest first in that subset)
        val realm = Realm.getDefaultInstance()
        val allMessages = getMessagesBase(threadId, query)
            .sort("date", Sort.DESCENDING)
            .findAll()

        if (allMessages.isEmpty() || allMessages.size <= limit) {
            // If we have fewer messages than the limit, just return all in ascending order
            return getMessagesBase(threadId, query).findAllAsync()
        }

        // Get the IDs of the most recent N messages
        val recentMessageIds = allMessages.take(limit.toInt()).map { it.id }.toLongArray()

        // Query for those specific messages in ascending order
        return realm
            .where(Message::class.java)
            .anyOf("id", recentMessageIds)
            .sort("date", Sort.ASCENDING)
            .findAllAsync()
    }

    override fun getMessagesSync(threadId: Long, query: String): RealmResults<Message> =
        getMessagesBase(threadId, query).findAll()

    override fun getMessage(messageId: Long) =
        Realm.getDefaultInstance()
            .where(Message::class.java)
            .equalTo("id", messageId)
            .findFirst()

    override fun getUnmanagedMessage(messageId: Long) =
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.where(Message::class.java).equalTo("id", messageId).findFirst()?.let(realm::copyFromRealm)
        }

    override fun getMessages(messageIds: Collection<Long>): RealmResults<Message> =
        Realm.getDefaultInstance()
            .where(Message::class.java)
            .anyOf("id", messageIds.toLongArray())
            .findAll()

    override fun getMessageForPart(id: Long) =
        Realm.getDefaultInstance()
            .where(Message::class.java)
            .equalTo("parts.id", id)
            .findFirst()

    override fun getLastIncomingMessage(threadId: Long): RealmResults<Message> =
        Realm.getDefaultInstance()
            .where(Message::class.java)
            .equalTo("threadId", threadId)
            .beginGroup()
            .beginGroup()
            .equalTo("type", TYPE_SMS)
            .`in`("boxId", arrayOf(Sms.MESSAGE_TYPE_INBOX, Sms.MESSAGE_TYPE_ALL))
            .endGroup()
            .or()
            .beginGroup()
            .equalTo("type", TYPE_MMS)
            .`in`("boxId", arrayOf(Mms.MESSAGE_BOX_INBOX, Mms.MESSAGE_BOX_ALL))
            .endGroup()
            .endGroup()
            .sort("date", Sort.DESCENDING)
            .findAll()

    override fun getUnreadCount() =
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.where(Conversation::class.java)
                .equalTo("archived", false)
                .equalTo("blocked", false)
                .equalTo("lastMessage.read", false)
                .count()
        }

    override fun getPart(id: Long) =
        Realm.getDefaultInstance()
            .where(MmsPart::class.java)
            .equalTo("id", id)
            .findFirst()

    override fun getPartsForConversation(threadId: Long): RealmResults<MmsPart> =
        Realm.getDefaultInstance()
            .where(MmsPart::class.java)
            .equalTo("messages.threadId", threadId)
            .beginGroup()
            .contains("type", "image/")
            .or()
            .contains("type", "video/")
            .endGroup()
            .sort("id", Sort.DESCENDING)
            .findAllAsync()

    override fun savePart(id: Long): Uri? {
        val part = getPart(id) ?: return null

        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(part.type)
            ?: return null
        // fileDateAndTime is divided by 1000 in order to remove the extra 0's after date and time
        // This way the file name isn't so long.
        val fileDateAndTime = (part.messages?.first()?.date)?.div(1000)
        val fileName = "QUIK_${part.type.split("/").last()}_$fileDateAndTime.$extension"

        val values = contentValuesOf(
            MediaStore.MediaColumns.DISPLAY_NAME to fileName,
            MediaStore.MediaColumns.MIME_TYPE to part.type,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            values.put(
                MediaStore.MediaColumns.RELATIVE_PATH, when {
                    part.isImage() -> "${Environment.DIRECTORY_PICTURES}/QUIK"
                    part.isVideo() -> "${Environment.DIRECTORY_MOVIES}/QUIK"
                    else -> "${Environment.DIRECTORY_DOWNLOADS}/QUIK"
                }
            )
        }

        val contentUri = when {
            part.isImage() -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            part.isVideo() -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                MediaStore.Downloads.EXTERNAL_CONTENT_URI

            else -> MediaStore.Files.getContentUri("external")
        }

        val uri = context.contentResolver.insert(contentUri, values)
        Timber.v("Saving $fileName (${part.type}) to $uri")

        uri?.let {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                context.contentResolver.openInputStream(part.getUri())?.use { inputStream ->
                    inputStream.copyTo(outputStream, 1024)
                }
            }
            Timber.v("Saved $fileName (${part.type}) to $uri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(
                    uri,
                    contentValuesOf(MediaStore.MediaColumns.IS_PENDING to 0),
                    null,
                    null
                )
                Timber.v("Marked $uri as not pending")
            }
        }

        return uri
    }

    override fun getUnreadUnseenMessages(threadId: Long): RealmResults<Message> =
        Realm.getDefaultInstance()
            .also { it.refresh() }
            .where(Message::class.java)
            .equalTo("seen", false)
            .equalTo("read", false)
            .equalTo("threadId", threadId)
            .sort("date")
            .findAll()

    override fun getUnreadMessages(threadId: Long): RealmResults<Message> =
        Realm.getDefaultInstance()
            .where(Message::class.java)
            .equalTo("read", false)
            .equalTo("threadId", threadId)
            .sort("date")
            .findAll()

    // marks all messages in threads as read and/or seen in the native provider
    private fun telephonyMarkSeenRead(
        seen: Boolean?,
        read: Boolean?,
        threadIds: Collection<Long>,
    ): Int {
        if (((seen == null) && (read == null)) || threadIds.isEmpty())
            return -1

        var countUpdated = 0

        // 'read' can be modified at the conversation level which updates all messages
        read?.let {
            tryOrNull(true) {
                // chunked so where clause doesn't get too long if there are many threads
                threadIds.forEach {
                    countUpdated += context.contentResolver.update(
                        ContentUris.withAppendedId(
                            Telephony.MmsSms.CONTENT_CONVERSATIONS_URI,
                            it
                        ),
                        contentValuesOf(Sms.READ to read),
                        "${Sms.READ} = ${if (read) 0 else 1}",
                        null
                    )
                }
            }
        }

        seen?.let {
            // 'seen' has to be modified at the messages level
            threadIds.chunked(TELEPHONY_UPDATE_CHUNK_SIZE).forEach {
                // chunked for smaller where clause size
                val values = contentValuesOf(Sms.SEEN to seen)
                val whereClause = "${Sms.SEEN} = ${if (seen) 0 else 1} " +
                        "and ${Sms.THREAD_ID} in (${it.joinToString(",")})"

                // sms messages
                tryOrNull(true) {
                    countUpdated += context.contentResolver.update(
                        Sms.CONTENT_URI, values, whereClause, null
                    )
                }

                // mms messages
                tryOrNull(true) {
                    countUpdated += context.contentResolver.update(
                        Mms.CONTENT_URI, values, whereClause, null
                    )
                }
            }
        }

        return countUpdated  // a mix of convo and message updates, so not overly useful. meh
    }

    override fun markAllSeen() =
        mutableSetOf<Long>().let { threadIds ->
            Realm.getDefaultInstance().use { realm ->
                realm.where(Message::class.java)
                    .equalTo("seen", false)
                    .findAll()
                    .takeIf { it.isNotEmpty() }
                    ?.let { messages ->
                        realm.executeTransaction {
                            messages.forEach {
                                it.seen = true
                                threadIds += it.threadId
                            }
                        }
                    }
            }.run {
                telephonyMarkSeenRead(true, null, threadIds)
            }
        }

    override fun markSeen(threadIds: Collection<Long>) =
        Realm.getDefaultInstance().use { realm ->
            realm.where(Message::class.java)
                .anyOf("threadId", threadIds.toLongArray())
                .equalTo("seen", false)
                .findAll()
                .let { messages ->
                    realm.executeTransaction {
                        messages.forEach { it.seen = true }
                    }
                }
        }.run {
            telephonyMarkSeenRead(true, null, threadIds)
        }

    override fun markRead(threadIds: Collection<Long>) =
        threadIds.takeIf { it.isNotEmpty() }
            ?.let {
                answerReadReports(threadIds)

                Realm.getDefaultInstance()?.use { realm ->
                    realm.where(Message::class.java)
                        .anyOf("threadId", threadIds.toLongArray())
                        .beginGroup()
                        .equalTo("read", false)
                        .or()
                        .equalTo("seen", false)
                        .endGroup()
                        .findAll()
                        .let { messages ->
                            realm.executeTransaction {
                                messages.forEach { it.seen = true; it.read = true }
                            }
                        }
                }.run {
                    telephonyMarkSeenRead(seen = true, read = true, threadIds = threadIds)
                }
            }
            ?: 0

    override fun markUnread(threadIds: Collection<Long>) =
        threadIds.takeIf { it.isNotEmpty() }
            ?.let {
                Realm.getDefaultInstance()?.use { realm ->
                    val conversations = realm.where(Conversation::class.java)
                        .anyOf("id", threadIds.toLongArray())
                        .equalTo("lastMessage.read", true)
                        .findAll()

                    realm.executeTransaction {
                        conversations.forEach { it.lastMessage?.read = false }
                    }
                }.run {
                    telephonyMarkSeenRead(null, false, threadIds)
                }
            }
            ?: 0

    /**
     * Send a read receipt for every message about to be marked read whose sender asked for one.
     *
     * Only ever an answer: a receipt goes back to someone who requested it, and only while the
     * user has the setting on. Runs before the flags flip, because "not read yet" is what keeps
     * it to one receipt per message -- there is no other record of having sent one.
     *
     * A receipt is persisted to the outbox before the transaction service is asked to send it,
     * so if the service can't be started from wherever we are (marking read from a notification
     * happens in the background, where API 26+ refuses), it goes out with the next transaction
     * rather than being lost. The receipt is an M-Read-Rec.ind, message type 135 -- 134 is the
     * delivery report, which this does not send. Either way the conversation view only admits
     * types 128, 130 and 132, so a queued receipt never shows up as a message in the thread.
     */
    private fun answerReadReports(threadIds: Collection<Long>) {
        if (!prefs.readReceipts.get())
            return

        Realm.getDefaultInstance()?.use { realm ->
            realm.where(Message::class.java)
                .anyOf("threadId", threadIds.toLongArray())
                .equalTo("read", false)
                .equalTo("type", TYPE_MMS)
                .equalTo("boxId", Mms.MESSAGE_BOX_INBOX)
                .equalTo("readReportString", PduHeaders.VALUE_YES.toString())
                .findAll()
                .map { message -> message.address to message.contentId }
                .filter { (address, _) -> address.isNotEmpty() }
        }?.forEach { (address, contentId) ->
            getMmsMessageId(contentId)?.let { messageId ->
                tryOrNull {
                    MmsMessageSender.sendReadRec(
                        context, address, messageId, PduHeaders.READ_STATUS_READ
                    )
                }
            }
        }
    }

    /**
     * Pull the far end's answers -- "delivered" and "read" -- onto the messages they answer.
     *
     * Neither ever lands on the sent message by itself. When the other handset reports, its
     * M-Delivery.ind / M-Read-Orig.ind PDU is persisted by PushReceiver as *its own row* in
     * content://mms/inbox, and the sent message's own DELIVERY_REPORT/READ_REPORT columns are
     * left alone -- on an outgoing message those two only ever meant "I asked for a report",
     * which is why mapping them (as CursorToMessageImpl does) can never show an answer.
     *
     * The only thing tying a report back to what it acknowledges is the MMS Message-ID header,
     * so that is the join: report row -> Mms.MESSAGE_ID -> the SEND_REQ row carrying it -> the
     * realm message with that contentId.
     *
     * These report rows are invisible to everything else in the app: the sync reads
     * content://mms-sms/complete-conversations, and that view admits only message types 128,
     * 130 and 132, never 134 or 136. So they have to be queried for deliberately, here.
     *
     * Idempotent by design -- it re-reads every report each time rather than tracking which
     * ones it has already applied, because setting a flag that is already set costs nothing
     * and reports are rare enough that the scan stays cheap.
     */
    override fun syncMmsReports() {
        // 134 = M-Delivery.ind, 136 = M-Read-Orig.ind.
        val reports = tryOrNull {
            context.contentResolver.query(
                Mms.CONTENT_URI,
                // Both status columns: a delivery report answers in Mms.STATUS and a read
                // report in Mms.READ_STATUS, and neither fills in the other's.
                arrayOf(Mms.MESSAGE_ID, Mms.MESSAGE_TYPE, Mms.STATUS, Mms.READ_STATUS),
                "${Mms.MESSAGE_TYPE} IN (?, ?)",
                arrayOf(
                    PduHeaders.MESSAGE_TYPE_DELIVERY_IND.toString(),
                    PduHeaders.MESSAGE_TYPE_READ_ORIG_IND.toString()
                ),
                null
            )?.use { cursor ->
                generateSequence { cursor.takeIf { it.moveToNext() } }
                    .mapNotNull { row ->
                        val messageId = row.getString(0)?.takeIf(String::isNotEmpty)
                            ?: return@mapNotNull null
                        MmsReport(
                            messageId = messageId,
                            messageType = row.getInt(1),
                            status = row.getInt(2),
                            readStatus = row.getInt(3)
                        )
                    }
                    .toList()
            }
        } ?: return

        if (reports.isEmpty())
            return

        // Folding the rows into one verdict each is the part with the awkward cases in it,
        // so it lives in mmsReportVerdicts() where it can be tested without a phone.
        val verdicts = mmsReportVerdicts(reports)

        if (verdicts.isEmpty())
            return

        // Message-ID -> the content id of the SEND_REQ row it acknowledges. Same join
        // PushReceiver.findThreadId() makes, asking for the row id instead of the thread id.
        val contentIds = verdicts.keys.mapNotNull { messageId ->
            tryOrNull {
                context.contentResolver.query(
                    Mms.CONTENT_URI,
                    arrayOf(Mms._ID),
                    "${Mms.MESSAGE_ID} = ? AND ${Mms.MESSAGE_TYPE} = ?",
                    arrayOf(messageId, PduHeaders.MESSAGE_TYPE_SEND_REQ.toString()),
                    null
                )?.use { cursor ->
                    cursor.takeIf { it.moveToFirst() }?.getLong(0)
                }
            }?.let { contentId -> messageId to contentId }
        }.toMap()

        if (contentIds.isEmpty())
            return

        Realm.getDefaultInstance()?.use { realm ->
            realm.executeTransaction {
                contentIds.forEach { (messageId, contentId) ->
                    val verdict = verdicts[messageId] ?: return@forEach

                    realm.where(Message::class.java)
                        .equalTo("type", TYPE_MMS)
                        .equalTo("contentId", contentId)
                        .findAll()
                        .forEach { message ->
                            if (verdict.delivered) message.mmsDelivered = true
                            if (verdict.read) message.mmsReadByRecipient = true
                        }
                }
            }
        }
    }

    /**
     * The MMS Message-ID header, which is what a read receipt has to name. It isn't one of the
     * columns we sync into realm, so read it back off the provider row.
     */
    private fun getMmsMessageId(contentId: Long): String? =
        tryOrNull {
            context.contentResolver.query(
                ContentUris.withAppendedId(Mms.CONTENT_URI, contentId),
                arrayOf(Mms.MESSAGE_ID), null, null, null
            )?.use { cursor ->
                cursor.takeIf { it.moveToFirst() }
                    ?.getString(0)
                    ?.takeIf { messageId -> messageId.isNotEmpty() }
            }
        }

    private fun syncProviderMessage(uri: Uri, sendAsGroup: Boolean): Message? {
        // if uri doesn't have valid type
        val type = when {
            uri.toString().contains(TYPE_MMS) -> TYPE_MMS
            uri.toString().contains(TYPE_SMS) -> TYPE_SMS
            else -> return null
        }

        // if uri doesn't have a valid id, fail
        val contentId = tryOrNull(false) { ContentUris.parseId(uri) } ?: return null

        val stableUri = when (type) {
            TYPE_MMS -> ContentUris.withAppendedId(Mms.CONTENT_URI, contentId)
            else -> ContentUris.withAppendedId(Sms.CONTENT_URI, contentId)
        }

        return context.contentResolver.query(
            stableUri, null, null, null, null
        )?.use { cursor ->
            // if there are no rows, return null. else, move to the first row
            if (!cursor.moveToFirst())
                return null

            cursorToMessage.map(Pair(cursor, CursorToMessage.MessageColumns(cursor))).apply {
                this.sendAsGroup = sendAsGroup

                if (isMms()) {
                    parts = RealmList<MmsPart>().apply {
                        addAll(
                            cursorToPart.getPartsCursor(contentId)
                                ?.map { cursorToPart.map(it) }
                                .orEmpty()
                        )
                    }
                }

                insertOrUpdate()
            }
        }
    }

    override fun sendNewMessages(
        subId: Int, toAddresses: Collection<String>, body: String,
        attachments: Collection<Attachment>, sendAsGroup: Boolean, delayMs: Int
    ): Collection<Message> {
        Timber.v("sending message(s)")

        val parts = mutableListOf<com.google.android.mms.MMSPart>()

        if (attachments.isNotEmpty()) {
            Timber.v("has attachments")
            val smsManager = subId.takeIf { it != -1 }
                ?.let(SmsManagerFactory::createSmsManager)
                ?: SmsManager.getDefault()

            val maxWidth = smsManager.carrierConfigValues
                .getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH)
                .takeIf { prefs.mmsSize.get() == -1 }
                ?: Int.MAX_VALUE

            val maxHeight = smsManager.carrierConfigValues
                .getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT)
                .takeIf { prefs.mmsSize.get() == -1 }
                ?: Int.MAX_VALUE

            var remainingBytes = when (prefs.mmsSize.get()) {
                -1 -> smsManager.carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE)
                0 -> Int.MAX_VALUE
                else -> prefs.mmsSize.get() * 1024
            } * 0.9 // Ugly, but buys us a bit of wiggle room

            remainingBytes -= body.takeIf { it.isNotEmpty() }?.toByteArray()?.size ?: 0

            // Attach those that can't be compressed (ie. everything but images)
            parts += attachments
                // filter in non-images only
                .filter { !it.isImage(context) }
                // filter in only items that exist (user may have deleted the file)
                .filter { it.uri.resourceExists(context) }
                .map {
                    remainingBytes -= it.getResourceBytes(context).size
                    val part = com.google.android.mms.MMSPart().apply {
                        MimeType = it.getType(context)
                        Name = it.getName(context)
                        Data = it.getResourceBytes(context)
                    }

                    // release the attachment hold on the image bytes so the GC can reclaim
                    it.releaseResourceBytes()

                    part
                }

            val imageBytesByAttachment = attachments
                // filter in images only
                .filter { it.isImage(context) }
                // filter in only items that exist (user may have deleted the file)
                .filter { it.uri.resourceExists(context) }
                .associateWith {
                    when (it.getType(context) == "image/gif") {
                        true -> ImageUtils.getScaledGif(context, it.uri, maxWidth, maxHeight)
                        false -> ImageUtils.getScaledImage(context, it.uri, maxWidth, maxHeight)
                    }
                }
                .toMutableMap()

            val imageByteCount = imageBytesByAttachment.values.sumOf { it.size }
            if (imageByteCount > remainingBytes) {
                imageBytesByAttachment.forEach { (attachment, originalBytes) ->
                    val uri = attachment.uri
                    val maxBytes = originalBytes.size / imageByteCount.toFloat() * remainingBytes

                    // Get the image dimensions
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(
                        context.contentResolver.openInputStream(uri),
                        null,
                        options
                    )
                    val width = options.outWidth
                    val height = options.outHeight
                    val aspectRatio = width.toFloat() / height.toFloat()

                    var attempts = 0
                    var scaledBytes = originalBytes

                    while (scaledBytes.size > maxBytes) {
                        // Estimate how much we need to scale the image down by. If it's still
                        // too big, we'll need to try smaller and smaller values
                        val scale = maxBytes / originalBytes.size * (0.9 - attempts * 0.2)
                        if (scale <= 0) {
                            Timber.w(
                                "Failed to compress ${
                                    originalBytes.size / 1024
                                }Kb to ${maxBytes.toInt() / 1024}Kb"
                            )
                            return@forEach
                        }

                        val newArea = scale * width * height
                        val newWidth = sqrt(newArea * aspectRatio).toInt()
                        val newHeight = (newWidth / aspectRatio).toInt()

                        attempts++
                        scaledBytes = when (attachment.getType(context) == "image/gif") {
                            true -> ImageUtils.getScaledGif(
                                context, attachment.uri, newWidth, newHeight
                            )

                            false -> ImageUtils.getScaledImage(
                                context, attachment.uri, newWidth, newHeight
                            )
                        }

                        Timber.d(
                            "Compression attempt $attempts: ${
                                scaledBytes.size / 1024
                            }/${maxBytes.toInt() / 1024}Kb ($width*$height -> $newWidth*${
                                newHeight
                            })"
                        )

                        // release the attachment hold on the image bytes so the GC can reclaim
                        attachment.releaseResourceBytes()
                    }

                    Timber.v(
                        "Compressed ${originalBytes.size / 1024}Kb to ${
                            scaledBytes.size / 1024
                        }Kb with a target size of ${
                            maxBytes.toInt() / 1024
                        }Kb in $attempts attempts"
                    )
                    imageBytesByAttachment[attachment] = scaledBytes
                }
            }

            imageBytesByAttachment.forEach { (attachment, bytes) ->
                parts += com.google.android.mms.MMSPart().apply {
                    MimeType =
                        if (attachment.getType(context) == "image/gif") ContentType.IMAGE_GIF
                        else ContentType.IMAGE_JPEG
                    Name = attachment.getName(context)
                    Data = bytes
                }
            }
        }

        Timber.v("create os provider message")

        // 3 stage sending process - stage 1, create records in os provider
        val group = (sendAsGroup && (toAddresses.size > 1))
        val messageUri = QkTransaction.createMessage(
            context, subId, body, prefs.signature.get(),
            toAddresses.map(phoneNumberUtils::normalizeNumber).toTypedArray(),
            parts, group, prefs.longAsMms.get(), prefs.unicode.get(),
            prefs.delivery.get(), prefs.readReceipts.get()
        )

        if (messageUri == Uri.EMPTY) {
            Timber.v("create os provider message failed")
            return listOf()
        }

        val message = syncProviderMessage(messageUri, group)
        if (message == null) {
            Timber.v("sync message failed for uri $messageUri")
            return listOf()
        }

        Timber.v("created message id ${message.id} from uri $messageUri")

        if (delayMs > 0) {  // if delaying
            val sendTime = (now() + delayMs)

            // set delay time on the db message
            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction {
                    realm.copyToRealmOrUpdate(message.apply { date = sendTime })
                }
            }

            // create alarm that will trigger sending the message
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, sendTime, getIntentForDelayedSms(message.id)
                )

            Timber.v("set ${delayMs}ms delay for message id ${message.id}")

            return listOf(message)
        }

        // send now (message will be exploded, as required, and all sent)
        return sendMessage(message)
    }

    override fun sendMessage(message: Message): Collection<Message> {
        val retVal = mutableListOf<Message>()

        tryOrNull(true) {
            // explode message if needed
            val explodedMessages = QkTransaction.explodeMessage(
                context, message.getUri(), message.sendAsGroup
            ).filter { explodedMessageUri -> (explodedMessageUri != Uri.EMPTY) }

            // if multiple messages to send, create each and recurse to send
            if (explodedMessages.size > 1) {
                explodedMessages.forEach { explodedMessageUri ->
                    val childMessage = syncProviderMessage(explodedMessageUri, message.sendAsGroup)
                    if (childMessage != null) {
                        Timber.v("created message id ${childMessage.id} from uri $explodedMessageUri")
                        retVal.addAll(sendMessage(childMessage))
                    }
                    else
                        Timber.e("sync failed for uri $explodedMessageUri")
                }

                // mark original message as sent
                markSent(message.id)
            } else {
                markSending(message.id)

                // individual message to send, send it
                val sentIntent = Intent(context, MessageSentReceiver::class.java)
                    .putExtra(MessageSentReceiver.EXTRA_QUIK_MESSAGE_ID, message.id)

                val deliveryIntent =
                    if (prefs.delivery.get())
                        Intent(context, MessageDeliveredReceiver::class.java)
                            .putExtra(MessageDeliveredReceiver.EXTRA_QUIK_MESSAGE_ID, message.id)
                    else null

                // use values from os provider to resend the message, except subId
                if (!QkTransaction.sendMessage(context, message.getUri(), sentIntent, deliveryIntent))
                    Timber.e("message id ${message.id} not sent by smsmms")
            }

            retVal.add(message)
        }

        return retVal
    }

    override fun sendMessage(messageId: Long) =
        getMessage(messageId)
            ?.let { message -> sendMessage(message) }
            ?: listOf()

    override fun cancelDelayedSmsAlarm(messageId: Long) =
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .cancel(getIntentForDelayedSms(messageId))

    private fun getIntentForDelayedSms(messageId: Long) =
        PendingIntent.getBroadcast(
            context,
            messageId.toInt(),
            Intent(context, SendDelayedMessageReceiver::class.java)
                .putExtra(MESSAGE_ID_EXTRA, messageId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    override fun insertReceivedSms(subId: Int, address: String, body: String, sentTime: Long)
    : Message {
        val threadId = TelephonyCompat.getOrCreateThreadId(context, address)

        // A message that lands while its conversation is already on screen has been read the
        // moment it arrives. Realm records that below, and the provider has to be told the same
        // thing here: nothing marks this row read later (markRead only runs when a conversation
        // is opened, and this one never gets opened "again"), so it would otherwise stay unread
        // in the system database forever and keep any unread badge lit. The MMS path avoids this
        // by calling markRead, which writes both stores.
        val alreadyRead = activeConversationManager.getActiveConversation() == threadId

        // insert the message to the native content provider
        val values = contentValuesOf(
            Sms.ADDRESS to address,
            Sms.BODY to body,
            Sms.DATE_SENT to sentTime,
            Sms.THREAD_ID to threadId,
            Sms.READ to if (alreadyRead) 1 else 0,
            Sms.SEEN to if (alreadyRead) 1 else 0
        )

        if (prefs.canUseSubId.get())
            values.put(Sms.SUBSCRIPTION_ID, subId)

        val providerContentId = context.contentResolver.insert(Sms.Inbox.CONTENT_URI, values)
            ?.let { insertedUri -> ContentUris.parseId(insertedUri) }
            ?: 0

        // insert the message to Realm
        val message = Message().apply {
            id = messageIds.newId()

            this.address = address
            this.body = body
            this.dateSent = sentTime
            this.threadId = threadId
            this.subId = subId

            date = System.currentTimeMillis()

            contentId = providerContentId
            boxId = Sms.MESSAGE_TYPE_INBOX
            type = TYPE_SMS
            read = alreadyRead
            seen = alreadyRead
        }

        Realm.getDefaultInstance().use { realm ->
            var managedMessage: Message? = null
            realm.executeTransaction { managedMessage = realm.copyToRealmOrUpdate(message) }

            managedMessage?.let { savedMessage ->
                val parsedReaction = reactions.parseEmojiReaction(body)
                if (parsedReaction != null) {
                    val targetMessage = reactions.findTargetMessage(
                        savedMessage.threadId,
                        parsedReaction.originalMessage,
                        realm
                    )
                    realm.executeTransaction {
                        reactions.saveEmojiReaction(
                            savedMessage,
                            parsedReaction,
                            targetMessage,
                            realm,
                        )
                    }
                }
            }
        }

        return message
    }

    /**
     * Marks the message as sending, in case we need to retry sending it
     */
    override fun markSending(messageId: Long) =
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()

            realm.where(Message::class.java)
                .equalTo("id", messageId)
                .findFirst()
                ?.let { message ->
                    // Update the message in realm
                    realm.executeTransaction {
                        message.boxId = when (message.isSms()) {
                            true -> Sms.MESSAGE_TYPE_OUTBOX
                            false -> Mms.MESSAGE_BOX_OUTBOX
                        }
                    }

                    // Update the message in the native ContentProvider
                    context.contentResolver.update(
                        message.getUri(),
                        when (message.isSms()) {
                            true -> contentValuesOf(Sms.TYPE to Sms.MESSAGE_TYPE_OUTBOX)
                            false -> contentValuesOf(Mms.MESSAGE_BOX to Mms.MESSAGE_BOX_OUTBOX)
                        },
                        null,
                        null
                    )
                }
            Unit
        }

    override fun markSent(messageId: Long) {
        Timber.v("mark message id $messageId as sent")

        Realm.getDefaultInstance().use { realm ->
            realm.refresh()

            realm.where(Message::class.java).equalTo("id", messageId).findFirst()
                ?.let { message ->
                    if (message.isSms()) {
                        // update the message in realm
                        realm.executeTransaction { message.boxId = Sms.MESSAGE_TYPE_SENT }

                        // Update the message in the native ContentProvider
                        context.contentResolver.update(
                            message.getUri(),
                            contentValuesOf(Sms.TYPE to Sms.MESSAGE_TYPE_SENT),
                            null,
                            null
                        )
                    } else {
                        // update the message in realm
                        realm.executeTransaction { message.boxId = Mms.MESSAGE_BOX_SENT }

                        // Update the message in the native ContentProvider
                        context.contentResolver.update(
                            message.getUri(),
                            contentValuesOf(Mms.MESSAGE_BOX to Mms.MESSAGE_BOX_SENT),
                            null,
                            null
                        )
                    }
                }
        }
    }

    override fun markFailed(messageId: Long, resultCode: Int) =
        Realm.getDefaultInstance().use { realm ->
            Timber.v("mark message id $messageId as failed. code $resultCode")

            realm.refresh()

            realm.where(Message::class.java).equalTo("id", messageId).findFirst()
                ?.let { message ->
                    if (message.isSms()) {
                        if (message.boxId != Sms.MESSAGE_TYPE_FAILED) {
                            // Update the message in realm
                            realm.executeTransaction {
                                message.boxId = Sms.MESSAGE_TYPE_FAILED
                                message.errorCode = resultCode
                            }

                            // Update the message in the native ContentProvider
                            context.contentResolver.update(
                                message.getUri(),
                                contentValuesOf(
                                    Sms.TYPE to Sms.MESSAGE_TYPE_FAILED,
                                    Sms.ERROR_CODE to resultCode,
                                ),
                                null,
                                null
                            )
                            true
                        } else false
                    } else {  // mms
                        if (message.boxId != Mms.MESSAGE_BOX_FAILED) {
                            // Update the message in realm
                            realm.executeTransaction {
                                message.boxId = Mms.MESSAGE_BOX_FAILED
                                message.errorCode = resultCode
                            }

                            // Update the message in the native ContentProvider
                            context.contentResolver.update(
                                message.getUri(),
                                contentValuesOf(
                                    Mms.MESSAGE_BOX to Mms.MESSAGE_BOX_FAILED
                                ),
                                null,
                                null
                            )

                            // TODO this query isn't able to find any results
                            // Need to figure out why the message isn't appearing in the PendingMessages Uri,
                            // so that we can properly assign the error type
                            context.contentResolver.update(
                                Telephony.MmsSms.PendingMessages.CONTENT_URI,
                                contentValuesOf(
                                    Telephony.MmsSms.PendingMessages.ERROR_TYPE to Telephony.MmsSms.ERR_TYPE_GENERIC_PERMANENT
                                ),
                                "${Telephony.MmsSms.PendingMessages.MSG_ID} = ?",
                                arrayOf(message.id.toString())
                            )
                            true
                        } else false
                    }
            } ?: false
        }

    override fun markDelivered(messageId: Long) =
        Realm.getDefaultInstance().use { realm ->
            Timber.v("mark message id $messageId as delivered")

            realm.refresh()

            realm.where(Message::class.java)
                .equalTo("id", messageId)
                .findFirst()
                ?.let { message ->
                    // Update the message in realm
                    realm.executeTransaction {
                        message.deliveryStatus = Sms.STATUS_COMPLETE
                        message.dateSent = System.currentTimeMillis()
                        message.read = true
                    }

                    // Update the message in the native ContentProvider
                    context.contentResolver.update(
                        message.getUri(),
                        contentValuesOf(
                            Sms.STATUS to Sms.STATUS_COMPLETE,
                            Sms.DATE_SENT to System.currentTimeMillis(),
                            Sms.READ to true,
                        ),
                        null,
                        null
                    )
                }
            Unit
        }

    override fun markDeliveryFailed(messageId: Long, resultCode: Int) =
        Realm.getDefaultInstance().use { realm ->
            Timber.v("mark message id $messageId as delivery failed result code $resultCode")

            realm.refresh()

            realm.where(Message::class.java)
                .equalTo("id", messageId)
                .findFirst()
                ?.let { message ->
                    // Update the message in realm
                    realm.executeTransaction {
                        message.deliveryStatus = Sms.STATUS_FAILED
                        message.dateSent = System.currentTimeMillis()
                        message.read = true
                        message.errorCode = resultCode
                    }

                    // Update the message in the native ContentProvider
                    context.contentResolver.update(
                        message.getUri(),
                        contentValuesOf(
                            Sms.STATUS to Sms.STATUS_FAILED,
                            Sms.DATE_SENT to System.currentTimeMillis(),
                            Sms.READ to true,
                            Sms.ERROR_CODE to resultCode,
                        ),
                        null,
                        null
                    )
                }
            Unit
        }

    override fun deleteMessages(messageIds: Collection<Long>) =
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()

            realm.where(Message::class.java)
                .anyOf("id", messageIds.toLongArray())
                .findAll()
                ?.let { messages ->
                    messages.mapNotNull { message ->
                        val uri = message.getUri()
                        if (uri != Uri.EMPTY)
                            context.contentResolver.delete(uri, null, null)
                    }

                    realm.executeTransaction { messages.deleteAllFromRealm() }
                } ?: Unit
        }

    override fun getOldMessageCounts(maxAgeDays: Int) =
        Realm.getDefaultInstance().use { realm ->
            realm.where(Message::class.java)
                .lessThan(
                    "date",
                    now() - TimeUnit.DAYS.toMillis(maxAgeDays.toLong())
                )
                .findAll()
                .groupingBy { message -> message.threadId }
                .eachCount()
        }

    override fun deleteOldMessages(maxAgeDays: Int) =
        Realm.getDefaultInstance().use { realm ->
            val messages = realm.where(Message::class.java)
                .lessThan(
                    "date",
                    now() - TimeUnit.DAYS.toMillis(maxAgeDays.toLong())
                )
                .findAll()

            val uris = messages.map { it.getUri() }

            realm.executeTransaction { messages.deleteAllFromRealm() }

            uris.forEach {
                uri -> context.contentResolver.delete(uri, null, null)
            }
        }
}

