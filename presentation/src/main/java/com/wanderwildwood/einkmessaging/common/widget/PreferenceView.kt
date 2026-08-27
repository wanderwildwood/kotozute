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
package com.wanderwildwood.einkmessaging.common.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.LinearLayoutCompat
import com.wanderwildwood.einkmessaging.R
import com.wanderwildwood.einkmessaging.common.util.extensions.resolveThemeAttribute
import com.wanderwildwood.einkmessaging.common.util.extensions.resolveThemeColorStateList
import com.wanderwildwood.einkmessaging.common.util.extensions.setVisible
import com.wanderwildwood.einkmessaging.databinding.PreferenceViewBinding
import com.wanderwildwood.einkmessaging.injection.appComponent

class PreferenceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayoutCompat(context, attrs) {

    // The row's widget is inflated into widgetFrame from whatever layout the caller asked for, so
    // it is looked up rather than held: a row with no widget, or one whose widget is not a switch,
    // simply has none.
    val checkbox: QkSwitch get() = findViewById(R.id.checkbox)
    val titleView: QkTextView get() = findViewById(R.id.titleView)
    val summaryView: QkTextView get() = findViewById(R.id.summaryView)

    private var layout: PreferenceViewBinding

    val titleTextView: TextView get() = layout.titleView

    var title: String? = null
        set(value) {
            field = value

            if (isInEditMode) {
                findViewById<TextView>(R.id.titleView).text = value
            } else {
                layout.titleView.text = value
            }
        }

    var summary: String? = null
        set(value) {
            field = value


            if (isInEditMode) {
                findViewById<TextView>(R.id.summaryView).run {
                    text = value
                    setVisible(value?.isNotEmpty() == true)
                }
            } else {
                layout.summaryView.text = value
                layout.summaryView.setVisible(value?.isNotEmpty() == true)
            }
        }

    init {
        if (!isInEditMode) {
            appComponent.inject(this)
        }

        layout = PreferenceViewBinding.inflate(LayoutInflater.from(context), this)
        setBackgroundResource(context.resolveThemeAttribute(R.attr.selectableItemBackground))
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        layout.icon.imageTintList = context.resolveThemeColorStateList(android.R.attr.textColorSecondary)
        layout.chevron.imageTintList = context.resolveThemeColorStateList(android.R.attr.textColorSecondary)

        context.obtainStyledAttributes(attrs, R.styleable.PreferenceView).run {
            title = getString(R.styleable.PreferenceView_title)
            summary = getString(R.styleable.PreferenceView_summary)

            // If there's a custom view used for the preference's widget, inflate it.
            // Rows with no custom widget (e.g. a Switch) are plain navigation, so they
            // get a chevron to signal that, matching the stock Kompakt SMS app.
            val hasWidget = getResourceId(R.styleable.PreferenceView_widget, -1) != -1
            layout.chevron.setVisible(!hasWidget)
            getResourceId(R.styleable.PreferenceView_widget, -1).takeIf { it != -1 }?.let { id ->
                View.inflate(context, id, layout.widgetFrame)
            }

            // If an icon is being used, set up the icon view
            getResourceId(R.styleable.PreferenceView_icon, -1).takeIf { it != -1 }?.let { id ->
                layout.icon.setVisible(true)
                layout.icon.setImageResource(id)
            }

            recycle()
        }
    }

}
