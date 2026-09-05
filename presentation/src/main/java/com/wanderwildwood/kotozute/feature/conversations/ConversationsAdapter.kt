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
package com.wanderwildwood.kotozute.feature.conversations

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.Navigator
import com.wanderwildwood.kotozute.common.base.QkBindingViewHolder
import com.wanderwildwood.kotozute.common.util.Colors
import com.wanderwildwood.kotozute.common.util.DateFormatter
import com.wanderwildwood.kotozute.common.util.extensions.resolveThemeColor
import com.wanderwildwood.kotozute.common.util.extensions.setTint
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.databinding.ConversationListItemBinding
import com.wanderwildwood.kotozute.repository.ScheduledMessageRepository
import com.wanderwildwood.kotozute.util.PhoneNumberUtils
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

/**
 * The inbox, both rails.
 *
 * This used to be a RealmRecyclerViewAdapter over Conversation. It is a plain adapter now
 * because Signal threads are a different Realm class and cannot be merged in the database:
 * SyncRepositoryImpl.removeOldMessages() empties Conversation and Message on every full
 * sync, so Signal rows kept there would be destroyed by an ordinary re-sync.
 *
 * Only SMS rows are selectable and swipeable. Archive, delete, pin and multi-select are all
 * telephony operations keyed on a thread id; rather than pretend they apply to a Signal
 * thread, the row simply does not offer them.
 */
class ConversationsAdapter @Inject constructor(
    private val colors: Colors,
    private val context: Context,
    private val dateFormatter: DateFormatter,
    private val scheduledMessageRepo: ScheduledMessageRepository,
    private val navigator: Navigator,
    private val phoneNumberUtils: PhoneNumberUtils
) : RecyclerView.Adapter<QkBindingViewHolder<ConversationListItemBinding>>() {

    private val disposables = CompositeDisposable()

    val selectionChanges: Subject<List<Long>> = BehaviorSubject.create()
    private val selection = mutableListOf<Long>()

    var emptyView: View? = null
        set(value) {
            field = value
            updateEmptyView()
        }

    // Filter mode: 0=All, 1=Groups, 2=Unknown (no saved contacts)
    var filterMode: Int = 0
        set(value) {
            if (field != value) {
                field = value
                applyFilter()
            }
        }

    private var source: List<InboxItem> = emptyList()
    private var items: List<InboxItem> = emptyList()

    init {
        setHasStableIds(true)
    }

    /**
     * Replaces the whole list. This notifies wholesale rather than by range: positions come
     * from two independent Realm collections merged by date, so Realm's own fine-grained
     * notifications would be describing the wrong indices.
     */
    fun updateData(data: List<InboxItem>?) {
        source = data.orEmpty()
        applyFilter()
    }

    private fun applyFilter() {
        // Deleted rows are dropped before anything reads a field off them. A list built
        // from live Realm objects can already contain a corpse by the time it arrives.
        val source = source.filter { it.isValid }
        items = when (filterMode) {
            // A Signal group is still a group; a Signal thread always has a counterpart,
            // so it is never "unknown" in the sense the Unknown tab means.
            1 -> source.filter {
                when (it) {
                    is InboxItem.Sms -> it.conversation.recipients.size > 1
                    is InboxItem.Signal -> it.thread.kind == "group"
                }
            }
            2 -> source.filter {
                it is InboxItem.Sms && it.conversation.recipients.all { r -> r.contact == null }
            }
            else -> source
        }
        notifyDataSetChanged()
        updateEmptyView()
    }

    private fun updateEmptyView() {
        emptyView?.setVisible(items.isEmpty())
    }

    fun getItem(index: Int): InboxItem? = items.getOrNull(index)

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = getItem(position)?.stableId ?: -1

    override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
        // Asked during the layout pass a deletion sets off, before the rebuilt list has
        // reached the adapter, so the row here may already be gone from the database.
        is InboxItem.Sms -> if (item.isValid && item.conversation.unread) 1 else 0
        is InboxItem.Signal -> if (item.isValid && item.thread.unread > 0) 1 else 0
        null -> 0
    }

    // ---- selection: SMS only -------------------------------------------------

    private fun toggleSelection(id: Long, force: Boolean = true): Boolean {
        if (!force && selection.isEmpty()) return false
        if (!selection.remove(id)) selection.add(id)
        selectionChanges.onNext(selection)
        return true
    }

    private fun isSelected(id: Long): Boolean = selection.contains(id)

    fun clearSelection() {
        selection.clear()
        selectionChanges.onNext(selection)
        notifyDataSetChanged()
    }

    fun toggleSelectAll() {
        val selectable = items.filterIsInstance<InboxItem.Sms>().filter { it.isValid }
                .map { it.conversation.id }
        val needToSelectAll = selection.size != selectable.size
        selection.clear()
        if (needToSelectAll) selection.addAll(selectable)
        selectionChanges.onNext(selection)
        notifyDataSetChanged()
    }

    // ---- binding -------------------------------------------------------------

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): QkBindingViewHolder<ConversationListItemBinding> {
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
                when (val item = getItem(adapterPosition)?.takeIf { it.isValid }) {
                    is InboxItem.Sms -> {
                        val conversation = item.conversation
                        when (toggleSelection(conversation.id, false)) {
                            true -> view.isActivated = isSelected(conversation.id)
                            false -> navigator.showConversation(
                                conversation.id, null, conversation.getTitle()
                            )
                        }
                    }
                    is InboxItem.Signal -> {
                        // Not selectable, so a tap always opens it -- even mid-selection.
                        navigator.showSignalThread(item.thread.threadKey, signalTitle(item))
                    }
                    null -> Unit
                }
            }
            view.setOnLongClickListener {
                val item = (getItem(adapterPosition) as? InboxItem.Sms)?.takeIf { it.isValid }
                    ?: return@setOnLongClickListener true
                toggleSelection(item.conversation.id)
                view.isActivated = isSelected(item.conversation.id)
                true
            }
        }
    }

    private fun signalTitle(item: InboxItem.Signal): String = with(item.thread) {
        when {
            title.isNotBlank() -> title
            counterpartNumber.isNotBlank() -> counterpartNumber
            else -> threadKey.substringAfter(":")
        }
    }

    override fun onBindViewHolder(
        holder: QkBindingViewHolder<ConversationListItemBinding>,
        position: Int
    ) {
        // A row whose conversation was deleted a moment ago is drawn blank rather than
        // read from; the rebuilt list is already on its way and will remove it.
        when (val item = getItem(position)?.takeIf { it.isValid } ?: return blank(holder)) {
            is InboxItem.Sms -> bindSms(holder, item)
            is InboxItem.Signal -> bindSignal(holder, item)
        }
    }

    private fun blank(holder: QkBindingViewHolder<ConversationListItemBinding>) {
        holder.containerView.isActivated = false
        holder.binding.rail.isVisible = false
        holder.binding.title.text = null
        holder.binding.date.text = null
        holder.binding.snippet.text = null
        holder.binding.scheduled.isVisible = false
        holder.binding.pinned.isVisible = false
    }

    private fun bindSms(
        holder: QkBindingViewHolder<ConversationListItemBinding>,
        item: InboxItem.Sms
    ) {
        val conversation = item.conversation

        // If the last message wasn't incoming, then the colour doesn't really matter anyway
        val lastMessage = conversation.lastMessage
        val recipient = when {
            conversation.recipients.size == 1 || lastMessage == null -> conversation.recipients.firstOrNull()
            else -> conversation.recipients.find { recipient ->
                phoneNumberUtils.compare(recipient.address, lastMessage.address)
            }
        }
        colors.theme(recipient)

        holder.containerView.isActivated = isSelected(conversation.id)
        holder.binding.rail.isVisible = false

        // The avatar is gone from the row, so there is nothing to fill.
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

    private fun bindSignal(
        holder: QkBindingViewHolder<ConversationListItemBinding>,
        item: InboxItem.Signal
    ) {
        val thread = item.thread

        // Never selected: Signal rows are not part of the telephony selection.
        holder.containerView.isActivated = false
        holder.binding.rail.isVisible = true

        holder.binding.title.collapseEnabled = false
        holder.binding.title.text = buildSpannedString { append(signalTitle(item)) }
        holder.binding.date.text = thread.lastTs.takeIf { it > 0 }
            ?.let(dateFormatter::getConversationTimestamp)
        // Reads the way an SMS row does, including the "You:" prefix, so the two rails
        // differ by the rail marker alone rather than by how much they tell you.
        holder.binding.snippet.text = when {
            thread.snippet.isBlank() && thread.unread > 0 -> context.resources.getQuantityString(
                R.plurals.signal_unread, thread.unread, thread.unread
            )
            thread.snippetOutgoing && thread.snippet.isNotBlank() ->
                context.getString(R.string.main_sender_you, thread.snippet)
            else -> thread.snippet
        }
        holder.binding.scheduled.isVisible = false
        holder.binding.pinned.isVisible = false
        holder.binding.unread.setTint(0xFF000000.toInt())
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        disposables.clear()
    }
}
