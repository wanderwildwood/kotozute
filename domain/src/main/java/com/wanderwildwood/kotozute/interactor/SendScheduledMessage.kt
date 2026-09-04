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
package com.wanderwildwood.kotozute.interactor

import android.content.Context
import android.net.Uri
import com.wanderwildwood.kotozute.compat.TelephonyCompat
import com.wanderwildwood.kotozute.extensions.mapNotNull
import com.wanderwildwood.kotozute.model.Attachment
import com.wanderwildwood.kotozute.repository.ScheduledMessageRepository
import com.wanderwildwood.kotozute.repository.SignalRepository
import io.reactivex.Flowable
import io.reactivex.rxkotlin.toFlowable
import io.realm.RealmList
import javax.inject.Inject

class SendScheduledMessage @Inject constructor(
    private val context: Context,
    private val scheduledMessageRepo: ScheduledMessageRepository,
    private val deleteScheduledMessagesInteractor: DeleteScheduledMessages,
    private val sendNewMessage: SendNewMessage,
    private val signalRepo: SignalRepository
) : Interactor<Long>() {

    override fun buildObservable(params: Long): Flowable<*> {
        return Flowable.just(params)
            .mapNotNull(scheduledMessageRepo::getScheduledMessage)
            // Read out of Realm before anything is sent or deleted. Below this point the
            // row may be gone, and a managed object outliving its row is the kind of
            // failure that only shows up on the device.
            .map { message -> Scheduled(message.signalThreadKey, message.body) }
            .flatMap { plan ->
                if (plan.signalThreadKey.isEmpty()) {
                    // SMS: the original path, unchanged.
                    return@flatMap smsSend(params)
                }
                // Signal: the same alarm and the same list, a different send. None of the
                // SMS machinery applies -- there is no thread id to look up, no subId to
                // pick, and sendAsGroup is a question Signal answered when the group was
                // made. Text only: an attachment is held as a data URI, and parking a
                // photo's worth of base64 in the database until Tuesday is not worth what
                // it buys.
                Flowable.fromCallable { signalRepo.send(plan.signalThreadKey, plan.body) }
                    .doOnNext { deleteScheduledMessagesInteractor.execute(listOf(params)) }
            }
    }

    private data class Scheduled(val signalThreadKey: String, val body: String)

    private fun smsSend(params: Long): Flowable<*> {
        return Flowable.just(params)
            .mapNotNull(scheduledMessageRepo::getScheduledMessage)
            .flatMap { message ->
                if (message.sendAsGroup) {
                    listOf(message)
                } else {
                    message.recipients.map { recipient -> message.copy(recipients = RealmList(recipient)) }
                }.toFlowable()
            }
            .map { message ->
                val threadId = TelephonyCompat.getOrCreateThreadId(context, message.recipients)
                val attachments = message.attachments.mapNotNull(Uri::parse).map { Attachment(context, it) }
                SendNewMessage.Params(
                    message.subId, threadId, message.recipients, message.body,
                    message.sendAsGroup, attachments
                )
            }
            .flatMap(sendNewMessage::buildObservable)
            .doOnNext { deleteScheduledMessagesInteractor.execute(listOf(params)) }
    }

}
