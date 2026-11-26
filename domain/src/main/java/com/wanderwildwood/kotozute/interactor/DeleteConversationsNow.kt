package com.wanderwildwood.kotozute.interactor

import com.wanderwildwood.kotozute.manager.NotificationManager
import com.wanderwildwood.kotozute.repository.ConversationRepository
import io.reactivex.Flowable
import javax.inject.Inject

/**
 * The final delete of text conversations, whatever the setting: "Delete now" in Deleted, and a
 * message request turned down. Realm rows and the phone's SMS store; nothing brings it back.
 */
class DeleteConversationsNow @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val notificationManager: NotificationManager,
    private val updateBadge: UpdateBadge
) : Interactor<List<Long>>() {

    override fun buildObservable(params: List<Long>): Flowable<*> {
        return Flowable.just(params.toLongArray())
                .doOnNext { threadIds -> conversationRepo.deleteConversations(*threadIds) }
                .doOnNext { threadIds -> threadIds.forEach(notificationManager::update) }
                .flatMap { updateBadge.buildObservable(Unit) }
    }

}
