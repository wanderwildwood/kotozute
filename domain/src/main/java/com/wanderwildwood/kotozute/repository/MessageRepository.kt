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
import com.wanderwildwood.kotozute.model.Attachment
import com.wanderwildwood.kotozute.model.Message
import com.wanderwildwood.kotozute.model.MmsPart
import io.realm.RealmResults

interface MessageRepository {
    fun getMessages(threadId: Long, query: String = ""): RealmResults<Message>

    fun getMessages(threadId: Long, query: String = "", limit: Long): RealmResults<Message>

    fun getMessagesSync(threadId: Long, query: String = ""): RealmResults<Message>

    fun getMessage(messageId: Long): Message?

    fun getUnmanagedMessage(messageId: Long): Message?

    fun getMessages(messageIds: Collection<Long>): RealmResults<Message>

    fun getMessageForPart(id: Long): Message?

    fun getLastIncomingMessage(threadId: Long): RealmResults<Message>

    fun getUnreadCount(): Long

    fun getPart(id: Long): MmsPart?

    fun getPartsForConversation(threadId: Long): RealmResults<MmsPart>

    fun savePart(id: Long): Uri?

    fun getUnreadUnseenMessages(threadId: Long): RealmResults<Message>

    fun getUnreadMessages(threadId: Long): RealmResults<Message>

    fun markAllSeen(): Int

    fun markSeen(threadIds: Collection<Long>): Int

    fun markRead(threadIds: Collection<Long>): Int

    fun markUnread(threadIds: Collection<Long>): Int

    fun markSending(messageId: Long)

    fun markSent(messageId: Long)

    fun markFailed(messageId: Long, resultCode: Int): Boolean

    /**
     * Marks failed anything still claiming to be sending long after it could be.
     *
     * A message is marked sending before the send is attempted, so a send that never happens
     * leaves the row saying "Sending…" with nothing to do about it -- the retry the UI offers
     * is for a *failed* message, and an outbox row is not one. Nothing else ever revisits it,
     * so without this the message sits there for the life of the install.
     *
     * Run at startup. A send that is genuinely still in flight across a restart is finished by
     * its own PendingIntent, which survives the process and reports back; if that lands after
     * this has given up on it, markSent puts it right. Being early here is self-correcting and
     * being absent is not.
     *
     * @return how many were marked.
     */
    fun failStuckSends(now: Long = System.currentTimeMillis()): Int

    /**
     * Apply the far end's delivery/read reports to the messages they acknowledge. Cheap and
     * idempotent; safe to call on any event that might have brought a report in.
     */
    fun syncMmsReports()

    fun markDelivered(messageId: Long)

    fun markDeliveryFailed(messageId: Long, resultCode: Int)

    fun sendNewMessages(
        subId: Int, toAddresses: Collection<String>, body: String,
        attachments: Collection<Attachment>, sendAsGroup: Boolean, delayMs: Int = 0
    ): Collection<Message>

    fun sendMessage(message: Message): Collection<Message>

    /**
     * React to an SMS/MMS message with [emoji], or take this phone's reaction back off.
     *
     * An SMS reaction is itself a text message (`Loved “…”`) sent to the same conversation;
     * the phone at the other end turns it back into a reaction. Returns false when there is
     * nothing to send it about -- a message with no text, or no conversation to send into.
     */
    fun sendEmojiReaction(targetId: Long, emoji: String, remove: Boolean): Boolean

    /** The emoji this phone has put on a message, or "" for none. */
    fun myEmojiReaction(messageId: Long): String

    fun sendMessage(messageId: Long): Collection<Message>

    fun cancelDelayedSmsAlarm(messageId: Long)

    fun insertReceivedSms(subId: Int, address: String, body: String, sentTime: Long): Message

    fun deleteMessages(messageIds: Collection<Long>)

    fun getOldMessageCounts(maxAgeDays: Int): Map<Long, Int>

    fun deleteOldMessages(maxAgeDays: Int)

}
