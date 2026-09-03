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

import com.wanderwildwood.kotozute.model.Recipient
import com.wanderwildwood.kotozute.model.ScheduledMessage
import io.realm.RealmList
import io.realm.RealmResults

interface ScheduledMessageRepository {

    /**
     * Saves a scheduled message
     */
    fun saveScheduledMessage(
        date: Long,
        subId: Int,
        recipients: List<String>,
        sendAsGroup: Boolean,
        body: String,
        attachments: List<String>,
        conversationId: Long
    ): ScheduledMessage

    /**
     * Updates scheduled messages with new uris
     */
    fun updateScheduledMessage(scheduledMessage: ScheduledMessage)

    /**
     * Returns all of the scheduled messages, sorted chronologically
     */
    fun getScheduledMessages(): RealmResults<ScheduledMessage>

    /**
     * The same list, detached and with the Realm closed behind it.
     *
     * getScheduledMessages hands back managed objects from a Realm it never closes, which
     * is survivable for a presenter that lives as long as its screen and a leak per call
     * for anything else -- the relay answers each request on a fresh worker thread.
     */
    fun getScheduledMessagesSnapshot(): List<ScheduledMessage>

    /**
     * Returns the scheduled message with the given [id]
     */
    fun getScheduledMessage(id: Long): ScheduledMessage?

    /**
     * Returns all scheduled messages with the given [conversationId]
     */
    fun getScheduledMessagesForConversation(conversationId: Long): RealmResults<ScheduledMessage>

    /**
     * Deletes the scheduled message with the given [id]
     */
    fun deleteScheduledMessage(id: Long)

    /**
     * Delete multiple scheduled messages by id list
     */
    fun deleteScheduledMessages(ids: List<Long>)

    /**
     * Get a list of all scheduled message ids (in scheduled date order)
     */
    fun getAllScheduledMessageIdsSnapshot(): List<Long>

}
