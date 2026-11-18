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
import com.wanderwildwood.kotozute.manager.NotificationManager
import com.wanderwildwood.kotozute.model.Attachment
import com.wanderwildwood.kotozute.repository.ScheduledMessageRepository
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.util.Preferences
import io.reactivex.Flowable
import io.reactivex.rxkotlin.toFlowable
import io.realm.RealmList
import javax.inject.Inject

class SendScheduledMessage @Inject constructor(
    private val context: Context,
    private val scheduledMessageRepo: ScheduledMessageRepository,
    private val deleteScheduledMessagesInteractor: DeleteScheduledMessages,
    private val sendNewMessage: SendNewMessage,
    private val signalRepo: SignalRepository,
    private val notificationManager: NotificationManager,
    private val prefs: Preferences
) : Interactor<Long>() {

    override fun buildObservable(params: Long): Flowable<*> {
        return Flowable.just(params)
            .mapNotNull(scheduledMessageRepo::getScheduledMessage)
            // Read out of Realm before anything is sent or deleted. Below this point the
            // row may be gone, and a managed object outliving its row is the kind of
            // failure that only shows up on the device.
            .map { message -> Scheduled(message.signalThreadKey, message.body, message.date) }
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
                //
                // A send that fails with nothing kept in the conversation keeps its row: the
                // Scheduled list is the only place it can be sent again from. It is not left
                // silent -- a notification says so -- and it is not sent again behind the
                // person's back: the catch-up in [UpdateScheduledMessageAlarms] passes it over
                // until they send or change it. One failure does not stop the other due
                // messages behind it.
                //
                // Two failures are not that. A message that went but could not be filed has
                // gone, and one refused for a changed safety number is already in the
                // conversation, marked not sent, with the notice that sends it. Keeping the
                // row for either would send it twice. See [ScheduledSignalResult].
                Flowable.fromCallable {
                    val failure = runCatching { signalRepo.send(plan.signalThreadKey, plan.body) }.exceptionOrNull()
                    val key = ScheduledSignalResult.key(params, plan.date)
                    val outcome = ScheduledSignalResult.of(failure)
                    when (outcome) {
                        ScheduledSignalResult.Outcome.SENT -> {
                            forgetNotSent(key)
                            deleteScheduledMessagesInteractor.execute(listOf(params))
                        }
                        ScheduledSignalResult.Outcome.WAITING_IN_THREAD -> {
                            forgetNotSent(key)
                            deleteScheduledMessagesInteractor.execute(listOf(params))
                            notificationManager.notifyScheduledWaitingInThread(params, plan.signalThreadKey)
                        }
                        ScheduledSignalResult.Outcome.NOT_SENT -> {
                            prefs.scheduledSignalNotSent.set(prefs.scheduledSignalNotSent.get() + key)
                            notificationManager.notifyScheduledNotSent(params)
                        }
                    }
                    outcome == ScheduledSignalResult.Outcome.SENT
                }
            }
    }

    private data class Scheduled(val signalThreadKey: String, val body: String, val date: Long)

    private fun forgetNotSent(key: String) {
        val notSent = prefs.scheduledSignalNotSent.get()
        if (key in notSent) prefs.scheduledSignalNotSent.set(notSent - key)
    }

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
