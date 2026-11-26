package com.wanderwildwood.kotozute.interactor

import com.wanderwildwood.kotozute.manager.PermissionManager
import com.wanderwildwood.kotozute.repository.ConversationRepository
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.util.DeletedFolder
import io.reactivex.Flowable
import timber.log.Timber
import javax.inject.Inject

/**
 * Removes for good what has been in Deleted for 30 days, by the same final deletes a person's
 * "Delete now" uses. Run at start and once a day (DeletedFolderService). Whatever the setting:
 * turning it off does not empty the folder, and what is in it still goes when its time is up.
 */
class PurgeDeleted @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val permissionManager: PermissionManager,
    private val signalRepo: SignalRepository,
    private val updateBadge: UpdateBadge
) : Interactor<Unit>() {

    override fun buildObservable(params: Unit): Flowable<*> = Flowable.just(params)
        .doOnNext {
            val now = System.currentTimeMillis()
            // Only as the default SMS app. Any other app's delete from the phone's SMS store is
            // ignored without a word, so the texts would stay there and the next re-sync would
            // bring the conversation straight back. They wait in Deleted until it is the default.
            if (permissionManager.isDefaultSms()) {
                val due = conversationRepo.getDeletedConversationsSnapshot()
                    .filter { DeletedFolder.isDue(it.deletedAt, now) }
                    .map { it.id }
                if (due.isNotEmpty()) {
                    conversationRepo.deleteConversations(*due.toLongArray())
                    Timber.i("%d text conversation(s) removed after 30 days in Deleted", due.size)
                }
            }
            runCatching { signalRepo.purgeDeletedThreads(now) }
                .onFailure { Timber.w(it, "signal: Deleted is swept again on the next pass") }
        }
        .flatMap { updateBadge.buildObservable(Unit) }
}
