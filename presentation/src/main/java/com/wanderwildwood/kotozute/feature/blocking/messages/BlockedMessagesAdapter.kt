/*
 * Copyright (C) 2019 Moez Bhatti <moez.bhatti@gmail.com>
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
package com.wanderwildwood.kotozute.feature.blocking.messages

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.base.QkRealmAdapter
import com.wanderwildwood.kotozute.common.base.QkBindingViewHolder
import com.wanderwildwood.kotozute.common.util.DateFormatter
import com.wanderwildwood.kotozute.common.util.extensions.resolveThemeColor
import com.wanderwildwood.kotozute.model.Conversation
import com.wanderwildwood.kotozute.util.Preferences
import io.reactivex.subjects.PublishSubject
import javax.inject.Inject
import com.wanderwildwood.kotozute.databinding.BlockedListItemBinding

class BlockedMessagesAdapter @Inject constructor(
    private val context: Context,
    private val dateFormatter: DateFormatter
) : QkRealmAdapter<Conversation, QkBindingViewHolder<BlockedListItemBinding>>() {

    val clicks: PublishSubject<Long> = PublishSubject.create()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkBindingViewHolder<BlockedListItemBinding> {
        val binding = BlockedListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val view = binding.root

        if (viewType == 0) {
            binding.title.setTypeface(binding.title.typeface, Typeface.BOLD)
            binding.date.setTypeface(binding.date.typeface, Typeface.BOLD)
            binding.date.setTextColor(view.context.resolveThemeColor(android.R.attr.textColorPrimary))
        }

        return QkBindingViewHolder(binding).apply {
            view.setOnClickListener {
                val conversation = getItem(adapterPosition) ?: return@setOnClickListener
                when (toggleSelection(conversation.id, false)) {
                    true -> view.isActivated = isSelected(conversation.id)
                    false -> clicks.onNext(conversation.id)
                }
            }
            view.setOnLongClickListener {
                val conversation = getItem(adapterPosition) ?: return@setOnLongClickListener true
                toggleSelection(conversation.id)
                view.isActivated = isSelected(conversation.id)
                true
            }
        }
    }

    override fun onBindViewHolder(holder: QkBindingViewHolder<BlockedListItemBinding>, position: Int) {
        val conversation = getItem(position) ?: return

        holder.containerView.isActivated = isSelected(conversation.id)

        holder.binding.avatars.recipients = conversation.recipients
        holder.binding.title.collapseEnabled = conversation.recipients.size > 1
        holder.binding.title.text = conversation.getTitle()
        holder.binding.date.text = dateFormatter.getConversationTimestamp(conversation.date)

        holder.binding.blocker.text = when (conversation.blockingClient) {
            Preferences.BLOCKING_MANAGER_CC -> context.getString(R.string.blocking_manager_call_control_title)
            Preferences.BLOCKING_MANAGER_SIA -> context.getString(R.string.blocking_manager_sia_title)
            else -> null
        }

        holder.binding.reason.text = conversation.blockReason
        holder.binding.blocker.isVisible = holder.binding.blocker.text.isNotEmpty()
        holder.binding.reason.isVisible = holder.binding.blocker.text.isNotEmpty()
    }

    override fun getItemViewType(position: Int): Int {
        val conversation = getItem(position)
        return if (conversation?.unread == false) 1 else 0
    }

}
