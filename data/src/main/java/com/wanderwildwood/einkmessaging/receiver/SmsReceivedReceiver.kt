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
package com.wanderwildwood.einkmessaging.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony.Sms
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.android.AndroidInjection
import com.wanderwildwood.einkmessaging.repository.MessageRepository
import com.wanderwildwood.einkmessaging.worker.ReceiveSmsWorker
import com.wanderwildwood.einkmessaging.worker.ReceiveSmsWorker.Companion.INPUT_DATA_KEY_MESSAGE_ID
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import javax.inject.Inject

class SmsReceivedReceiver : BroadcastReceiver() {
    @Inject lateinit var messageRepo: MessageRepository

    override fun onReceive(context: Context, intent: Intent) {
        AndroidInjection.inject(this, context)

        Sms.Intents.getMessagesFromIntent(intent)?.let { messages ->
            // blockingGet() here parked the main thread on a database write for every
            // single incoming SMS -- a broadcast receiver gets ~10 seconds before the
            // system declares an ANR, and this one was spending it waiting on Realm.
            // goAsync() keeps the receiver alive while the work runs on io instead.
            // (Ported from QUIK 7edea6d0.)
            val pendingResult = goAsync()

            // reduce list of messages to single message and save in db
            Single.just(messages)
                .observeOn(Schedulers.io())
                .map {
                    Timber.v("onReceive() new sms")  // here so runs on io thread

                    messageRepo.insertReceivedSms(
                        intent.extras?.getInt("subscription", -1) ?: -1,
                        messages[0].displayOriginatingAddress,
                        messages.mapNotNull { it.displayMessageBody }.reduce { body, new -> body + new },
                        messages[0].timestampMillis
                    ).id
                }
                .subscribe({ messageId ->
                    // start worker with message id as param
                    WorkManager.getInstance(context).enqueue(
                        OneTimeWorkRequestBuilder<ReceiveSmsWorker>()
                            .setInputData(workDataOf(INPUT_DATA_KEY_MESSAGE_ID to messageId))
                            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                            .build()
                    )
                    pendingResult.finish()
                }, { error ->
                    // finish() on both paths, or the receiver is held open until the
                    // system times it out.
                    Timber.e(error, "error receiving new sms")
                    pendingResult.finish()
                })
        }
    }

}