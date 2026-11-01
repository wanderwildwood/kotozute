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
package com.wanderwildwood.kotozute.feature.main

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
 * The main screen's bottom bar, in the house style: the three conversation filters and the
 * new-message action, drawn with MMD's navigation bar exactly as jimeikin draws its own.
 *
 * The filters used to be a row of pills in the toolbar. There, three labels and two icons had
 * to share a 480px bar and stepped down a size to fit; here each one has its own touch target
 * and its own icon, and the toolbar is left with the title, search and the cog.
 *
 * [firstLabel] is the first tab's name: it is "All" unless the lists are being kept apart, in
 * which case that tab holds people only and says so. [unread] marks with a dot, ahead of the
 * name, a tab holding something unread -- the same mark the pills carried, where an ellipsis
 * on a narrow tab could cut it off.
 */
@Composable
fun MainTabsBar(
    selectedIndex: Int,
    unread: Set<Int>,
    firstLabel: Int,
    onTabSelected: (Int) -> Unit,
    onCompose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBarMMD(
        // jimeikin's bar carries the same 2dp; every other dimension -- container height,
        // icon size, label height, the rule on top -- is NavigationBarMMD's own default.
        modifier = modifier.padding(bottom = 2.dp),
    ) {
        // The shop's own icon set (common/compose/Icons.kt), the same path data jimeikin
        // draws its own tabs with.
        val tabs = listOf(
            Triple(firstLabel, 0, Icons.Chat),
            Triple(R.string.filter_groups, 1, Icons.Group),
            Triple(R.string.filter_unknown, 2, Icons.QuestionMark),
        )
        tabs.forEach { (labelRes, index, imageVector) ->
            val selected = selectedIndex == index
            val label = stringResource(labelRes)
            NavigationBarItemMMD(
                selected = selected,
                onClick = { onTabSelected(index) },
                icon = {
                    // rememberVectorPainter feeding a plain Material Icon, so the glyph
                    // sizing stays NavigationBarItemDefaultsMMD's.
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

        // The compose action, which the old floating button used to carry. It is drawn as
        // a fourth tab but is not a filter: tapping it fires the new-message intent and
        // never moves the selected page, so it is always passed selected = false.
        NavigationBarItemMMD(
            selected = false,
            onClick = onCompose,
            icon = {
                Icon(
                    painter = rememberVectorPainter(image = Icons.Edit),
                    contentDescription = stringResource(R.string.tab_compose),
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
