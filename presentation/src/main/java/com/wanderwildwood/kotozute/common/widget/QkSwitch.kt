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
package com.wanderwildwood.kotozute.common.widget

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.util.Colors
import com.wanderwildwood.kotozute.injection.appComponent
import com.wanderwildwood.kotozute.util.Preferences
import javax.inject.Inject

class QkSwitch @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : SwitchCompat(context, attrs) {

    @Inject lateinit var colors: Colors
    @Inject lateinit var prefs: Preferences

    init {
        if (!isInEditMode) {
            appComponent.inject(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // MMD's switch is an outlined capsule that fills when on - never a tinted slab.
        // Set on the widget rather than per-layout so every switch in the app gets it, the
        // same reasoning as Colors.theme() forcing black at the source.
        // Tints are cleared explicitly: a surviving tint would repaint the drawables flat.
        trackTintList = null
        thumbTintList = null
        trackDrawable = ContextCompat.getDrawable(context, R.drawable.switch_track_mmd)
        thumbDrawable = ContextCompat.getDrawable(context, R.drawable.switch_thumb_mmd)
        switchMinWidth = (SWITCH_WIDTH_DP * resources.displayMetrics.density).toInt()
        thumbTextPadding = 0
    }

    companion object {
        private const val SWITCH_WIDTH_DP = 52  // MMD SwitchWidth
    }
}
