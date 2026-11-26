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

import com.wanderwildwood.kotozute.manager.NotificationManager
import com.wanderwildwood.kotozute.repository.ConversationRepository
import com.wanderwildwood.kotozute.util.DeletedFolder
import com.wanderwildwood.kotozute.util.Preferences
import io.reactivex.Flowable
import javax.inject.Inject

/**
 * Delete, as the person asks for it: into Deleted while "Keep deleted conversations" is on,
 * gone at once while it is off (see [DeletedFolder.holdsDelete]). The final delete on its own
 * is [DeleteConversationsNow].
 */
class DeleteConversations @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val notificationManager: NotificationManager,
    private val prefs: Preferences,
    private val updateBadge: UpdateBadge
) : Interactor<List<Long>>() {

    override fun buildObservable(params: List<Long>): Flowable<*> {
        return Flowable.just(params.toLongArray())
                .doOnNext { threadIds ->
                    if (DeletedFolder.holdsDelete(prefs.keepDeleted.get())) {
                        conversationRepo.markDeleted(threadIds.toList())
                    } else {
                        conversationRepo.deleteConversations(*threadIds)
                    }
                }
                .doOnNext { threadIds -> threadIds.forEach(notificationManager::update) }
                .flatMap { updateBadge.buildObservable(Unit) } // Update the badge
    }

}
