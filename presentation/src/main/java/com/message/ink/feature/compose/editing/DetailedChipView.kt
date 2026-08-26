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
package com.message.ink.feature.compose.editing

import android.content.Context
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.RelativeLayout
import com.message.ink.R
import com.message.ink.common.util.Colors
import com.message.ink.common.util.extensions.setBackgroundTint
import com.message.ink.common.util.extensions.setTint
import com.message.ink.injection.appComponent
import com.message.ink.model.Recipient
import javax.inject.Inject
import android.view.LayoutInflater
import com.message.ink.databinding.ContactChipDetailedBinding

class DetailedChipView(context: Context) : RelativeLayout(context) {

    @Inject lateinit var colors: Colors

    private val binding = ContactChipDetailedBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        appComponent.inject(this)

        setOnClickListener { hide() }

        visibility = View.GONE

        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setRecipient(recipient: Recipient) {
        binding.avatar.setRecipient(recipient)
        binding.name.text = recipient.contact?.name?.takeIf { it.isNotBlank() } ?: recipient.address
        binding.info.text = recipient.address

        colors.theme(recipient).let { theme ->
            binding.card.setBackgroundTint(theme.theme)
            binding.name.setTextColor(theme.textPrimary)
            binding.info.setTextColor(theme.textTertiary)
            binding.delete.setTint(theme.textPrimary)
        }
    }

    fun show() {
        startAnimation(AlphaAnimation(0f, 1f).apply { duration = 200 })

        visibility = View.VISIBLE
        requestFocus()
        isClickable = true
    }

    fun hide() {
        startAnimation(AlphaAnimation(1f, 0f).apply { duration = 200 })

        visibility = View.GONE
        clearFocus()
        isClickable = false
    }

    fun setOnDeleteListener(listener: (View) -> Unit) {
        binding.delete.setOnClickListener(listener)
    }

}
