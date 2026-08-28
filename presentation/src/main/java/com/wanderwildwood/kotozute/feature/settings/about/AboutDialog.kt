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
package com.wanderwildwood.kotozute.feature.settings.about

import android.app.Activity
import android.content.DialogInterface
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.wanderwildwood.kotozute.BuildConfig
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.databinding.AboutDialogBinding

/**
 * What this app is, what it does with what you give it, and where the source is.
 *
 * A dialog rather than a screen of rows: About is not a setting, and it is one panel's worth of
 * text that nobody comes back to twice.
 */
class AboutDialog(context: Activity, onVersionLongClick: () -> Unit) : AlertDialog(context) {

    private val layout = AboutDialogBinding.inflate(LayoutInflater.from(context))

    init {
        setView(layout.root)
        layout.name.text = context.getString(R.string.about_name, BuildConfig.VERSION_NAME)
        // Upstream hid the logging switch behind a long press on the About row. The row is
        // gone; the gesture moves to the line that names the version, where it has always
        // lived in other apps.
        layout.name.setOnLongClickListener {
            onVersionLongClick()
            dismiss()
            true
        }
        setButton(DialogInterface.BUTTON_POSITIVE, context.getString(R.string.button_close)) { _, _ -> }
    }

}
