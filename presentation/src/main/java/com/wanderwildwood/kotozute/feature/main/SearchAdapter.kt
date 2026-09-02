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
package com.wanderwildwood.kotozute.feature.main

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.Navigator
import com.wanderwildwood.kotozute.common.base.QkAdapter
import com.wanderwildwood.kotozute.common.base.QkBindingViewHolder
import com.wanderwildwood.kotozute.common.util.Colors
import com.wanderwildwood.kotozute.common.util.DateFormatter
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.extensions.removeAccents
import com.wanderwildwood.kotozute.model.SearchResult
import com.wanderwildwood.kotozute.databinding.SearchListItemBinding
import com.wanderwildwood.kotozute.feature.conversations.InboxItem
import javax.inject.Inject

class SearchAdapter @Inject constructor(
    colors: Colors,
    private val context: Context,
    private val dateFormatter: DateFormatter,
    private val navigator: Navigator
) : QkAdapter<InboxSearchResult, QkBindingViewHolder<SearchListItemBinding>>() {

    private val highlightColor: Int by lazy { colors.theme().highlight }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkBindingViewHolder<SearchListItemBinding> {
        val binding = SearchListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return QkBindingViewHolder(binding).apply {
            itemView.setOnClickListener {
                when (val result = getItem(adapterPosition)) {
                    is InboxSearchResult.Sms -> navigator.showConversation(
                        result.result.conversation.id,
                        result.result.query.takeIf { result.messages > 0 },
                        result.result.conversation.getTitle()
                    )
                    // No query is passed through: the Signal thread has no in-thread search
                    // to hand it to yet, and a highlight that never appears is a promise
                    // the screen does not keep.
                    is InboxSearchResult.Signal ->
                        navigator.showSignalThread(result.thread.threadKey, result.thread.title)
                }
            }
        }
    }

    override fun onBindViewHolder(holder: QkBindingViewHolder<SearchListItemBinding>, position: Int) {
        val previous = data.getOrNull(position - 1)
        val result = getItem(position)

        holder.binding.resultsHeader.setVisible(result.messages > 0 && previous?.messages == 0)

        val query = queryOf(result)
        val title = SpannableString(titleOf(result))
        var index = title.removeAccents().indexOf(query, ignoreCase = true)

        while (index >= 0) {
            title.setSpan(BackgroundColorSpan(highlightColor), index, index + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            index = title.indexOf(query, index + query.length, true)
        }
        holder.binding.title.text = title

        // The avatar is gone from the row, so there is nothing to fill.

        when (result.messages == 0) {
            true -> {
                holder.binding.date.setVisible(true)
                when (result) {
                    is InboxSearchResult.Sms -> {
                        holder.binding.date.text =
                            dateFormatter.getConversationTimestamp(result.result.conversation.date)
                        holder.binding.snippet.text = when (result.result.conversation.me) {
                            true -> context.getString(
                                R.string.main_sender_you, result.result.conversation.snippet
                            )
                            false -> result.result.conversation.snippet
                        }
                    }
                    is InboxSearchResult.Signal -> {
                        holder.binding.date.text =
                            dateFormatter.getConversationTimestamp(result.thread.lastTs)
                        holder.binding.snippet.text = when (result.thread.snippetOutgoing) {
                            true -> context.getString(
                                R.string.main_sender_you, result.thread.snippet
                            )
                            false -> result.thread.snippet
                        }
                    }
                }
            }

            false -> {
                holder.binding.date.setVisible(false)
                holder.binding.snippet.text = context.resources.getQuantityString(
                    R.plurals.main_message_results, result.messages, result.messages
                )
            }
        }

        // Which rail the hit is on. Without it a result list drawn from both rails does not
        // say which conversation you are about to open, and the two can share a name.
        holder.binding.rail.setVisible(result is InboxSearchResult.Signal)
    }

    private fun queryOf(result: InboxSearchResult): String = when (result) {
        is InboxSearchResult.Sms -> result.result.query
        // The Signal side does not carry the query on the hit; the highlight below simply
        // finds nothing, which is right for a thread that matched only by name.
        is InboxSearchResult.Signal -> lastQuery
    }

    private fun titleOf(result: InboxSearchResult): String = when (result) {
        is InboxSearchResult.Sms -> result.result.conversation.getTitle()
        is InboxSearchResult.Signal -> result.thread.title
    }

    /** The query the current results were produced for, for highlighting Signal titles. */
    var lastQuery: String = ""


    private fun idOf(result: InboxSearchResult): Long = when (result) {
        is InboxSearchResult.Sms -> result.result.conversation.id
        is InboxSearchResult.Signal -> InboxItem.signalStableId(result.thread.threadKey)
    }

    override fun areItemsTheSame(old: InboxSearchResult, new: InboxSearchResult): Boolean {
        return idOf(old) == idOf(new) && old.messages > 0 == new.messages > 0
    }

    override fun areContentsTheSame(old: InboxSearchResult, new: InboxSearchResult): Boolean {
        return queryOf(old) == queryOf(new) &&
                idOf(old) == idOf(new) &&
                old.messages == new.messages
    }
}
