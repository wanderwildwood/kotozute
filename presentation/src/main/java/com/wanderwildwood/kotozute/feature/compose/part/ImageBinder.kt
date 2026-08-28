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
package com.wanderwildwood.kotozute.feature.compose.part

import android.content.Context
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.base.QkViewHolder
import com.wanderwildwood.kotozute.common.util.Colors
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.common.widget.BubbleImageView
import com.wanderwildwood.kotozute.extensions.isImage
import com.wanderwildwood.kotozute.extensions.isVideo
import com.wanderwildwood.kotozute.model.Message
import com.wanderwildwood.kotozute.model.MmsPart
import com.wanderwildwood.kotozute.util.GlideApp
import com.wanderwildwood.kotozute.util.tryOrNull
import javax.inject.Inject
import com.wanderwildwood.kotozute.databinding.MmsImagePreviewListItemBinding

class ImageBinder @Inject constructor(colors: Colors, private val context: Context) : PartBinder() {

    override val partLayout = R.layout.mms_image_preview_list_item
    override var theme = colors.theme()

    override fun canBindPart(part: MmsPart) = part.isImage() || part.isVideo()

    override fun bindPart(
        holder: QkViewHolder,
        part: MmsPart,
        message: Message,
        canGroupWithPrevious: Boolean,
        canGroupWithNext: Boolean
    ) {
        val binding = MmsImagePreviewListItemBinding.bind(holder.itemView)
        binding.video.setVisible(part.isVideo())
        holder.containerView.setOnClickListener { clicks.onNext(part.id) }

        binding.thumbnail.bubbleStyle = when {
            !canGroupWithPrevious && canGroupWithNext -> if (message.isMe()) BubbleImageView.Style.OUT_FIRST else BubbleImageView.Style.IN_FIRST
            canGroupWithPrevious && canGroupWithNext -> if (message.isMe()) BubbleImageView.Style.OUT_MIDDLE else BubbleImageView.Style.IN_MIDDLE
            canGroupWithPrevious && !canGroupWithNext -> if (message.isMe()) BubbleImageView.Style.OUT_LAST else BubbleImageView.Style.IN_LAST
            else -> BubbleImageView.Style.ONLY
        }

        tryOrNull(true) {
            GlideApp.with(context)
                .load(part.getUri())
                .fitCenter()
                .override(com.bumptech.glide.request.target.Target.SIZE_ORIGINAL)
                .into(binding.thumbnail)
        }
    }

}