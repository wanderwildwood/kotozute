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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.compose.Icons

/**
 * The main toolbar's content, in the jimeikin style: what this screen is showing sits at the
 * left, the controls at the right end.
 *
 * Two states:
 * - Normal: the section's name on the left ("All", "Groups", the one conversation selected),
 *   search then the cog on the right.
 * - Search: the back arrow takes the name's place, a text field fills the middle, and the cog
 *   makes way -- the magnifier stays, because tapping it is how a search is performed.
 *
 * The row is 64dp because the AndroidX toolbar hosting it is: the selection menu (archive,
 * delete, and the rest) still lives in that toolbar, and moving it to Compose is a separate
 * job. Everything inside follows MMD: titleLarge for the name, 48dp touch targets, plain black
 * glyphs -- on e-ink a tinted icon is a grey smudge.
 */
@Composable
fun MainToolbarContent(
    title: String,
    searchMode: Boolean,
    query: String,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onBackClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    railLabel: String? = null,
    onRailClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Raised when the field comes on screen, and taken down again when search ends. The
    // effect has to live outside the `if` below: a hide written inside it would be cancelled
    // by the same state change that makes it worth running, and the keyboard would stay up
    // over a closed search -- the bug this replaces (see the note in the old hideSearchMode).
    LaunchedEffect(searchMode) {
        if (searchMode) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searchMode) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Back,
                    contentDescription = stringResource(R.string.abc_action_bar_up_description),
                    modifier = Modifier.size(24.dp),
                )
            }

            // The field and the rule under it: MMD draws a text field as white with one
            // line of ink beneath, and a bare field would be the only one in the app.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, end = 8.dp),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { innerTextField ->
                        Box {
                            if (query.isEmpty()) {
                                TextMMD(
                                    text = stringResource(R.string.main_search_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                HorizontalDividerMMD(thickness = 1.dp)
            }

            IconButton(
                onClick = onSearchClick,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Search,
                    contentDescription = stringResource(R.string.main_search_hint),
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            TextMMD(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The toolbar's own content inset is the 16dp; the row adds none, so the
                // title sits where jimeikin's does.
                modifier = Modifier.padding(end = 4.dp),
            )

            // Everything after this sits at the right end in every state, without the GONE
            // views the XML row used to lean on.
            Spacer(Modifier.weight(1f))

            // The crossing to the Signal list, while the two lists are kept apart. The badge
            // names the rail it stands on, as it does in a conversation, and the arrow says
            // it is a way across rather than a label.
            if (railLabel != null) {
                TextMMD(
                    text = railLabel,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onRailClick)
                        .padding(horizontal = 8.dp),
                )
            }

            IconButton(
                onClick = onSearchClick,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Search,
                    contentDescription = stringResource(R.string.main_search_hint),
                    modifier = Modifier.size(24.dp),
                )
            }

            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Settings,
                    contentDescription = stringResource(R.string.drawer_settings),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
