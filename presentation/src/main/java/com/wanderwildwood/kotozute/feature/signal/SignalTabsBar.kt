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
package com.wanderwildwood.kotozute.feature.signal

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.nav_bar.NavigationBarMMD
import com.mudita.mmd.components.nav_bar.NavigationBarItemMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.compose.Icons

/**
 * The Signal rail's bottom bar: the two filters the pills in the top bar used to carry, and
 * the new-message action the floating button carried. It is the same bar, in the same place,
 * as the text list's, so the two rails of one app no longer navigate in two different ways.
 *
 * [firstLabel] is the first tab's name: "All", or "People" when groups are kept to their own
 * tab. [unread] marks a tab holding something unread with a dot ahead of its name, exactly as
 * MainTabsBar does. [showCompose] is false on the archive shelf, where a new conversation
 * would land behind the screen that started it.
 */
@Composable
fun SignalTabsBar(
    selectedIndex: Int,
    unread: Set<Int>,
    firstLabel: Int,
    showCompose: Boolean,
    onTabSelected: (Int) -> Unit,
    onCompose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBarMMD(
        // The same 2dp the text list and jimeikin carry; every other dimension is
        // NavigationBarMMD's own default.
        modifier = modifier.padding(bottom = 2.dp),
    ) {
        val tabs = listOf(
            Triple(firstLabel, 0, Icons.Chat),
            Triple(R.string.filter_groups, 1, Icons.Group),
        )
        tabs.forEach { (labelRes, index, imageVector) ->
            val selected = selectedIndex == index
            val label = stringResource(labelRes)
            NavigationBarItemMMD(
                selected = selected,
                onClick = { onTabSelected(index) },
                icon = {
                    Icon(
                        painter = rememberVectorPainter(image = imageVector),
                        contentDescription = label,
                    )
                },
                label = {
                    TextMMD(
                        text = if (index in unread) {
                            stringResource(R.string.filter_tab_unread, label)
                        } else {
                            label
                        },
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
        }

        if (showCompose) {
            // An action, not a filter: it opens the Signal address book and never moves
            // the selected tab.
            NavigationBarItemMMD(
                selected = false,
                onClick = onCompose,
                icon = {
                    Icon(
                        painter = rememberVectorPainter(image = Icons.Edit),
                        contentDescription = stringResource(R.string.signal_new_conversation_cd),
                    )
                },
                label = {
                    TextMMD(
                        text = stringResource(R.string.tab_compose),
                        fontWeight = FontWeight.Normal,
                    )
                },
            )
        }
    }
}
