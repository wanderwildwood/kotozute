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
package com.wanderwildwood.kotozute.common.compose

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * The house dialog panel: white with a black rim, 12dp corners, nothing translucent. The same
 * file in every app of this shop.
 *
 * Kept apart from [EInkDialog] because a dialog hosted as a real window (see AboutDialog) draws
 * the same panel inside a window it controls itself.
 */
@Composable
fun EInkPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            content = content,
        )
    }
}

/**
 * A dialog with no dimmed backdrop.
 *
 * The stock scrim is a translucent black over the whole window, which on E Ink is not a shadow
 * but a screenful of dithered grey: it repaints everything behind the dialog and leaves it muddy
 * on the way out. A plain white panel with a black rim costs one partial refresh instead.
 *
 * Material's own `AlertDialog` is wrong here twice over: it animates in, and it sizes itself to
 * its buttons, so a dialog asking a short question and one asking a long one read as two
 * different dialogs. This one is always the same width.
 *
 * MMD has no dialog of its own, which is why this is house rather than library. Everything
 * inside it should still be MMD: `TextMMD` for the words, `OutlinedButtonMMD` for a press.
 */
@Composable
fun EInkDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                // Dimming by nothing still counts as dimming: while a dimming window is up,
                // Android turns the status-bar icons white, and on the white bar they vanish.
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        EInkPanel(content = content)
    }
}
