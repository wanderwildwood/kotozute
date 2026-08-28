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
package com.wanderwildwood.kotozute.receiver

import android.content.Context
import android.content.Intent
import com.android.mms.transaction.PushReceiver
import com.wanderwildwood.kotozute.repository.MessageRepository
import dagger.android.AndroidInjection
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * WAP push is how the far end's answers arrive: M-Delivery.ind when a message lands, and
 * M-Read-Orig.ind when it is read. PushReceiver persists each as its own row and updates its
 * thread id, but never touches the sent message it acknowledges -- so without this, a report
 * changes nothing a user can see.
 *
 * The scan runs after a delay because PushReceiver does the persisting on its own executor:
 * returning from super.onReceive() means the work was queued, not that the row exists. It is
 * idempotent, and SyncMessages runs it too, so losing the race costs a delay and not the flag.
 */
class MmsWapPushReceiver : PushReceiver() {

    @Inject lateinit var messageRepo: MessageRepository

    override fun onReceive(context: Context, intent: Intent) {
        // Receiving MMS at all runs through here, so it goes first and is never put at risk by
        // anything below it: every part of the report scan is contained, and the worst a failure
        // can cost is a message that does not show "Read".
        super.onReceive(context, intent)

        runCatching {
            AndroidInjection.inject(this, context)

            // A receiver gets about ten seconds before the system calls it an ANR, so the wait
            // stays well inside that, and none of it happens on the main thread.
            val pendingResult = goAsync()

            Single.timer(PERSIST_GRACE_SECONDS, TimeUnit.SECONDS, Schedulers.io())
                .subscribe { _ ->
                    runCatching { messageRepo.syncMmsReports() }
                        .onFailure { error -> Timber.w(error, "could not apply mms reports") }
                    pendingResult.finish()
                }
        }.onFailure { error -> Timber.w(error, "could not schedule the mms report scan") }
    }

    companion object {
        private const val PERSIST_GRACE_SECONDS = 5L
    }

}
