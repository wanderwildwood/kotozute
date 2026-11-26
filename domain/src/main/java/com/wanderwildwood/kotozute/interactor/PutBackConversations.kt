package com.wanderwildwood.kotozute.interactor

import com.wanderwildwood.kotozute.repository.ConversationRepository
import io.reactivex.Flowable
import javax.inject.Inject

/** Text conversations out of Deleted, back where they were. The badge counts them again. */
class PutBackConversations @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val updateBadge: UpdateBadge
) : Interactor<List<Long>>() {

    override fun buildObservable(params: List<Long>): Flowable<*> {
        return Flowable.just(params)
                .doOnNext { threadIds -> conversationRepo.markUndeleted(threadIds) }
                .flatMap { updateBadge.buildObservable(Unit) }
    }

}
