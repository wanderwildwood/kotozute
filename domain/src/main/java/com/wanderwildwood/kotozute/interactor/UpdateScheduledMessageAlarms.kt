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

import com.wanderwildwood.kotozute.manager.AlarmManager
import com.wanderwildwood.kotozute.repository.ScheduledMessageRepository
import com.wanderwildwood.kotozute.util.Preferences
import io.reactivex.Flowable
import javax.inject.Inject

class UpdateScheduledMessageAlarms @Inject constructor(
    private val alarmManager: AlarmManager,
    private val scheduledMessageRepo: ScheduledMessageRepository,
    private val sendScheduledMessage: SendScheduledMessage,
    private val prefs: Preferences
) : Interactor<Unit>() {

    override fun buildObservable(params: Unit): Flowable<*> {
        return Flowable.just(params)
                .map { scheduledMessageRepo.getScheduledMessages() } // Get all the scheduled messages
                .map { it.map { message -> Pair(message.id, message.date) } } // Map the data we need out of Realm
                .map { messages ->
                    // A Signal message that was tried and did not go waits in Scheduled for the
                    // person; sending it again here would be doing it behind their back. Rows
                    // that are gone are forgotten, so the list only ever names what is there.
                    val keys = messages.map { (id, date) -> ScheduledSignalResult.key(id, date) }.toSet()
                    val notSent = prefs.scheduledSignalNotSent.get()
                    val stillThere = notSent.intersect(keys)
                    if (stillThere.size != notSent.size) prefs.scheduledSignalNotSent.set(stillThere)
                    messages.filter { (id, date) -> ScheduledSignalResult.key(id, date) !in stillThere }
                }
                .flatMap { messages -> Flowable.fromIterable(messages) } // Turn the list into a stream
                .filter { (id, date) ->
                    // A future message gets an alarm. A past-due one is sent here and gets none:
                    // an alarm in the past fires at once and would send it a second time.
                    val due = date < System.currentTimeMillis()
                    if (!due) alarmManager.setAlarm(date, alarmManager.getScheduledMessageIntent(id))
                    due
                }
                .flatMap { (id, _) -> sendScheduledMessage.buildObservable(id) } // Send them
    }

}