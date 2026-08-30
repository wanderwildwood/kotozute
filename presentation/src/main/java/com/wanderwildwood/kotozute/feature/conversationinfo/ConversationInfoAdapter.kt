package com.wanderwildwood.kotozute.feature.conversationinfo

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.jakewharton.rxbinding2.view.clicks
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.base.QkAdapter
import com.wanderwildwood.kotozute.common.base.QkViewHolder
import com.wanderwildwood.kotozute.common.util.Colors
import com.wanderwildwood.kotozute.common.widget.PreferenceView
import com.wanderwildwood.kotozute.common.util.extensions.setTint
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.extensions.isVideo
import com.wanderwildwood.kotozute.feature.conversationinfo.ConversationInfoItem.*
import com.wanderwildwood.kotozute.util.GlideApp
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject
import com.wanderwildwood.kotozute.databinding.ConversationRecipientListItemBinding
import com.wanderwildwood.kotozute.databinding.ConversationInfoSettingsBinding
import com.wanderwildwood.kotozute.databinding.ConversationMediaListItemBinding

class ConversationInfoAdapter @Inject constructor(
    private val context: Context,
    private val colors: Colors
) : QkAdapter<ConversationInfoItem, QkViewHolder>() {

    val recipientClicks: Subject<Long> = PublishSubject.create()
    val recipientLongClicks: Subject<Long> = PublishSubject.create()
    val nameClicks: Subject<Unit> = PublishSubject.create()
    val notificationClicks: Subject<Unit> = PublishSubject.create()
    val markUnreadClicks: Subject<Unit> = PublishSubject.create()
    val archiveClicks: Subject<Unit> = PublishSubject.create()
    val blockClicks: Subject<Unit> = PublishSubject.create()
    val deleteClicks: Subject<Unit> = PublishSubject.create()
    val mediaClicks: Subject<Long> = PublishSubject.create()

    /**
     * Deleting a conversation cannot be undone, so the row asks first -- on its own face,
     * rather than by opening a dialog over the screen it was tapped on. A dialog is two
     * full-panel repaints to ask one question; this is one row changing what it says, in
     * the place the answer belongs. It is the same shape the other five apps use.
     *
     * The question withdraws itself after a few seconds, so a row armed by a stray tap is
     * not left live for whoever picks the phone up next.
     */
    private fun armDelete(row: PreferenceView) {
        val ready = row.context.getString(R.string.info_delete)
        val asking = row.context.getString(R.string.info_delete_confirm)
        var armed = false
        val disarm = Runnable {
            armed = false
            row.title = ready
        }
        row.setOnClickListener {
            row.removeCallbacks(disarm)
            if (armed) {
                disarm.run()
                deleteClicks.onNext(Unit)
            } else {
                armed = true
                row.title = asking
                row.postDelayed(disarm, ARMED_MS)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> QkViewHolder(ConversationRecipientListItemBinding.inflate(inflater, parent, false).root).apply {
                itemView.setOnClickListener {
                    val item = getItem(adapterPosition) as? ConversationInfoRecipient
                    item?.value?.id?.run(recipientClicks::onNext)
                }

                itemView.setOnLongClickListener {
                    val item = getItem(adapterPosition) as? ConversationInfoRecipient
                    item?.value?.id?.run(recipientLongClicks::onNext)
                    true
                }

                // Theme picker click removed: every color now resolves to black
                // (e-ink can't render color), so "picking a color" here was a dead end.
            }

            1 -> ConversationInfoSettingsBinding.inflate(inflater, parent, false).let { settings ->
                QkViewHolder(settings.root).apply {
                    settings.groupName.clicks().subscribe(nameClicks)
                    settings.notifications.clicks().subscribe(notificationClicks)
                    settings.markUnread.clicks().subscribe(markUnreadClicks)
                    settings.archive.clicks().subscribe(archiveClicks)
                    settings.block.clicks().subscribe(blockClicks)
                    armDelete(settings.delete)
                }
            }

            2 -> QkViewHolder(ConversationMediaListItemBinding.inflate(inflater, parent, false).root).apply {
                itemView.setOnClickListener {
                    val item = getItem(adapterPosition) as? ConversationInfoMedia
                    item?.value?.id?.run(mediaClicks::onNext)
                }
            }

            else -> throw IllegalStateException()
        }
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ConversationInfoRecipient -> {
                val binding = ConversationRecipientListItemBinding.bind(holder.itemView)
                val recipient = item.value
                binding.avatar.setRecipient(recipient)

                binding.name.text = recipient.contact?.name ?: recipient.address

                binding.address.text = recipient.address
                binding.address.setVisible(recipient.contact != null)

                binding.add.setVisible(recipient.contact == null)

            }

            is ConversationInfoSettings -> {
                val binding = ConversationInfoSettingsBinding.bind(holder.itemView)
                binding.groupName.summary = item.name

                binding.notifications.isEnabled = !item.blocked

                binding.archive.isEnabled = !item.blocked
                binding.archive.title = context.getString(when (item.archived) {
                    true -> R.string.info_unarchive
                    false -> R.string.info_archive
                })

                binding.block.title = context.getString(when (item.blocked) {
                    true -> R.string.info_unblock
                    false -> R.string.info_block
                })
            }

            is ConversationInfoMedia -> {
                val binding = ConversationMediaListItemBinding.bind(holder.itemView)
                val part = item.value

                GlideApp.with(context)
                        .load(part.getUri())
                        .fitCenter()
                        .into(binding.thumbnail)

                binding.video.isVisible = part.isVideo()
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (data[position]) {
            is ConversationInfoRecipient -> 0
            is ConversationInfoSettings -> 1
            is ConversationInfoMedia -> 2
        }
    }

    override fun areItemsTheSame(old: ConversationInfoItem, new: ConversationInfoItem): Boolean {
        return when {
            old is ConversationInfoRecipient && new is ConversationInfoRecipient -> {
               old.value.id == new.value.id
            }

            old is ConversationInfoSettings && new is ConversationInfoSettings -> {
                true
            }

            old is ConversationInfoMedia && new is ConversationInfoMedia -> {
                old.value.id == new.value.id
            }

            else -> false
        }
    }


    private companion object {
        /** How long a tap on the delete row stays armed before it forgets it was asked. */
        const val ARMED_MS = 4000L
    }
}
