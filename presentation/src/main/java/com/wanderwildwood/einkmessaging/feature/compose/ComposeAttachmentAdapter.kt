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
package com.wanderwildwood.einkmessaging.feature.compose

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.wanderwildwood.einkmessaging.R
import com.wanderwildwood.einkmessaging.common.base.QkAdapter
import com.wanderwildwood.einkmessaging.common.base.QkViewHolder
import com.wanderwildwood.einkmessaging.common.util.extensions.getDisplayName
import com.wanderwildwood.einkmessaging.extensions.getName
import com.wanderwildwood.einkmessaging.feature.extensions.LoadBestIconIntoImageView
import com.wanderwildwood.einkmessaging.feature.extensions.loadBestIconIntoImageView
import com.wanderwildwood.einkmessaging.model.Attachment
import ezvcard.Ezvcard
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject
import com.wanderwildwood.einkmessaging.databinding.AttachmentContactListItemBinding
import com.wanderwildwood.einkmessaging.databinding.ScheduledMessageImageListItemBinding


class ComposeAttachmentAdapter @Inject constructor(
    private val context: Context
) : QkAdapter<Attachment, QkViewHolder>() {

    companion object {
        private const val VIEW_TYPE_FILE = 0
        private const val VIEW_TYPE_CONTACT = 1
    }

    val attachmentDeleted: Subject<Attachment> = PublishSubject.create()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        val view =
            if (viewType == VIEW_TYPE_CONTACT) AttachmentContactListItemBinding.inflate(inflater, parent, false).root
            else ScheduledMessageImageListItemBinding.inflate(inflater, parent, false).root

        return QkViewHolder(view).apply {
            view.setOnClickListener {
                val attachment = getItem(adapterPosition)
                attachmentDeleted.onNext(attachment)
            }
        }
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        val attachment = getItem(position)

        if (attachment.isVCard(context)) {
            val contact = AttachmentContactListItemBinding.bind(holder.itemView)
            try {
                val displayName = Ezvcard.parse(
                    String(attachment.getResourceBytes(context))
                ).first().getDisplayName() ?: ""
                contact.name.text = displayName
                contact.name.isVisible = displayName.isNotEmpty()
            } catch (e: Exception) {
                // npe from Ezvcard first() call above can be thrown if resource bytes cannot
                // be retrieved from contact resource provider
                contact.vCardAvatar.setImageResource(android.R.drawable.ic_delete)
                contact.name.text = context.getString(R.string.attachment_missing)
                contact.name.isVisible = true
            }
            return
        }

        // set best image and text to use for icon
        val file = ScheduledMessageImageListItemBinding.bind(holder.itemView)
        when (attachment.uri.loadBestIconIntoImageView(context, file.thumbnail)) {
            LoadBestIconIntoImageView.Missing -> {
                file.fileName.text = context.getString(R.string.attachment_missing)
                file.fileName.visibility = View.VISIBLE
            }
            LoadBestIconIntoImageView.ActivityIcon,
            LoadBestIconIntoImageView.DefaultAudioIcon,
            LoadBestIconIntoImageView.GenericIcon -> {
                // generic style icon used, also show name
                file.fileName.text = attachment.uri.getName(context)
                file.fileName.visibility = View.VISIBLE
            }
            else -> file.fileName.visibility = View.GONE
        }
    }

    override fun getItemViewType(position: Int) = when (getItem(position).isVCard(context)) {
        true -> VIEW_TYPE_CONTACT
        else -> VIEW_TYPE_FILE
    }

}
