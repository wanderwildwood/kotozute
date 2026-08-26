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
package com.message.ink.feature.conversations

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.message.ink.R
import com.message.ink.common.Navigator
import com.message.ink.common.base.QkRealmAdapter
import com.message.ink.common.base.QkBindingViewHolder
import com.message.ink.common.util.Colors
import com.message.ink.common.util.DateFormatter
import com.message.ink.common.util.extensions.resolveThemeColor
import com.message.ink.common.util.extensions.setTint
import com.message.ink.model.Conversation
import com.message.ink.repository.ScheduledMessageRepository
import com.message.ink.util.PhoneNumberUtils
import io.reactivex.disposables.CompositeDisposable
import javax.inject.Inject
import com.message.ink.databinding.ConversationListItemBinding

class ConversationsAdapter @Inject constructor(
    private val colors: Colors,
    private val context: Context,
    private val dateFormatter: DateFormatter,
    private val scheduledMessageRepo: ScheduledMessageRepository,
    private val navigator: Navigator,
    private val phoneNumberUtils: PhoneNumberUtils
) : QkRealmAdapter<Conversation, QkBindingViewHolder<ConversationListItemBinding>>() {
    private val disposables = CompositeDisposable()

    // Filter mode: 0=All, 1=Groups, 2=Unknown (no saved contacts)
    var filterMode: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    // Cache filtered items for consistent access
    private var filteredItems: List<Conversation> = emptyList()
    private var lastDataVersion: Int = -1

    private fun updateFilteredItems() {
        val currentData = data ?: return
        if (!currentData.isLoaded || !currentData.isValid) {
            filteredItems = emptyList()
            return
        }

        filteredItems = when (filterMode) {
            1 -> currentData.filter { it.recipients.size > 1 } // Groups
            2 -> currentData.filter { conversation ->
                // Unknown: all recipients have no contact
                conversation.recipients.all { it.contact == null }
            }
            else -> currentData.toList() // All
        }
    }

    init {
        // This is how we access the threadId for the swipe actions
        setHasStableIds(true)
    }

    override fun getItemCount(): Int {
        updateFilteredItems()
        return if (filterMode == 0) super.getItemCount() else filteredItems.size
    }

    override fun getItem(index: Int): Conversation? {
        if (filterMode == 0) return super.getItem(index)
        if (index < 0 || index >= filteredItems.size) return null
        return filteredItems[index]
    }

    override fun getItemId(position: Int): Long {
        return getItem(position)?.id ?: -1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkBindingViewHolder<ConversationListItemBinding> {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = ConversationListItemBinding.inflate(layoutInflater, parent, false)
        val view = binding.root

        if (viewType == 1) {
            val textColorPrimary = parent.context.resolveThemeColor(android.R.attr.textColorPrimary)

            binding.title.setTypeface(binding.title.typeface, Typeface.BOLD)

            binding.snippet.setTypeface(binding.snippet.typeface, Typeface.BOLD)
            binding.snippet.setTextColor(textColorPrimary)
            binding.snippet.maxLines = 5

            binding.unread.isVisible = true

            binding.date.setTypeface(binding.date.typeface, Typeface.BOLD)
            binding.date.setTextColor(textColorPrimary)
        }

        return QkBindingViewHolder(binding).apply {
            view.setOnClickListener {
                val conversation = getItem(adapterPosition) ?: return@setOnClickListener
                when (toggleSelection(conversation.id, false)) {
                    true -> view.isActivated = isSelected(conversation.id)
                    false -> navigator.showConversation(conversation.id, null, conversation.getTitle())
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

    override fun onBindViewHolder(holder: QkBindingViewHolder<ConversationListItemBinding>, position: Int) {
        val conversation = getItem(position) ?: return

        // If the last message wasn't incoming, then the colour doesn't really matter anyway
        val lastMessage = conversation.lastMessage
        val recipient = when {
            conversation.recipients.size == 1 || lastMessage == null -> conversation.recipients.firstOrNull()
            else -> conversation.recipients.find { recipient ->
                phoneNumberUtils.compare(recipient.address, lastMessage.address)
            }
        }
        val theme = colors.theme(recipient).theme

        holder.containerView.isActivated = isSelected(conversation.id)

        holder.binding.avatars.recipients = conversation.recipients
        holder.binding.title.collapseEnabled = conversation.recipients.size > 1
        holder.binding.title.text = buildSpannedString {
            append(conversation.getTitle())
        }
        holder.binding.date.text = conversation.date.takeIf { it > 0 }?.let(dateFormatter::getConversationTimestamp)
        holder.binding.snippet.text = when {
            conversation.draft.isNotEmpty() -> context.getString(R.string.main_sender_draft, conversation.draft)
            conversation.me -> context.getString(R.string.main_sender_you, conversation.snippet)
            else -> conversation.snippet
        }

        // Make the preview in italics if draft
        if (conversation.draft.isNotEmpty()) holder.binding.snippet.setTypeface(null, Typeface.ITALIC)

        // Get Scheduled Messages
        val disposable = scheduledMessageRepo
            .getScheduledMessagesForConversation(conversation.id)
            .asFlowable()
            .toObservable()
            .subscribe { messages ->
                holder.binding.scheduled.isVisible = messages.isNotEmpty()
            }
        disposables.add(disposable)

        holder.binding.pinned.isVisible = conversation.pinned
        holder.binding.unread.setTint(0xFF000000.toInt())
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position)?.unread == false) 0 else 1
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        disposables.clear()
    }


}
