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
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.kotozute.BuildConfig
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.compose.EInkPanel
import com.wanderwildwood.kotozute.common.compose.monochrome
import com.wanderwildwood.kotozute.common.util.WebLinks

/**
 * What this app is, what it does with what you give it, and where the source is.
 *
 * A dialog rather than a screen of rows: About is not a setting, and it is one panel's worth of
 * text that nobody comes back to twice. The panel itself is Compose (AboutScreen), in the house
 * style; this class is only the window it hangs in, so the call sites can keep `.show()`.
 *
 * The window has no dimmed backdrop and no animation. On e-ink a scrim is a screenful of grey
 * that has to be painted and then taken away again; a white panel inside a black edge is one
 * partial refresh. The style it uses (EInkDialogWindow) is the same one every migrated dialog
 * will take.
 *
 * ComponentDialog rather than a plain Dialog: a ComposeView needs a ViewTreeLifecycleOwner on
 * its window, which an activity's own window has and a bare Dialog's does not -- and the crash
 * that costs is "ViewTreeLifecycleOwner not found", on the first frame after the dialog opens.
 */
class AboutDialog(
    private val activity: Activity,
    private val onVersionLongClick: () -> Unit,
) : ComponentDialog(activity, R.style.EInkDialogWindow) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = ComposeView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        setContentView(content)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        content.setContent {
            ThemeMMD(colorScheme = monochrome) {
                EInkPanel {
                    AboutPanel(
                        versionName = BuildConfig.VERSION_NAME,
                        // Upstream hid the logging switch behind a long press on the About row.
                        // The row is gone; the gesture moves to the line that names the version,
                        // where it has always lived in other apps.
                        onVersionLongClick = {
                            onVersionLongClick()
                            dismiss()
                        },
                        onLicences = { LicencesDialog(activity).show() },
                        // A real browser, never the WebView test shell some phones answer web
                        // addresses with; with no browser at all the address is copied and the
                        // dialog says so (WebLinks). Straight to the checkout, by the short
                        // square.link form the site itself links to, so a regenerated checkout
                        // follows it and a published app does not break.
                        onSite = { WebLinks.open(activity, Uri.parse("https://wanderthe.dev")) },
                        onLlama = { WebLinks.open(activity, Uri.parse("https://square.link/u/AGu8oT10")) },
                        onClose = { dismiss() },
                        modifier = Modifier.weight(1f, fill = false),
                        closeBelow = true,
                    )
                }
            }
        }
    }
}
