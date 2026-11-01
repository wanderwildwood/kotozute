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

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.compose.EInkDialog
import com.wanderwildwood.kotozute.common.compose.PagedColumn
import com.wanderwildwood.kotozute.common.compose.monochrome
import androidx.compose.foundation.gestures.detectTapGestures

/**
 * What this app is, what it does with what you give it, and where the source is.
 *
 * The order is the order it has always had: privacy first, because what it sends is the part a
 * stranger would not assume of a texting app, then the terms, then the licences. The words page
 * rather than scroll, so the panel fits any screen at any text size; Close stays under them.
 *
 * [onVersionLongClick] is the debug log: the switch lives behind a long press on the line that
 * names the version, where other apps of the shop keep it.
 */
@Composable
fun AboutScreen(
    versionName: String,
    onVersionLongClick: () -> Unit,
    onLicences: () -> Unit,
    onSite: () -> Unit,
    onLlama: () -> Unit,
    onClose: () -> Unit,
) {
    ThemeMMD(colorScheme = monochrome) {
        EInkDialog(onDismiss = onClose) {
            AboutPanel(
                versionName = versionName,
                onVersionLongClick = onVersionLongClick,
                onLicences = onLicences,
                onSite = onSite,
                onLlama = onLlama,
                onClose = onClose,
                modifier = Modifier.weight(1f, fill = false),
                closeBelow = true,
            )
        }
    }
}

/**
 * The panel itself, without a dialog around it: the AboutDialog window already is one.
 *
 * [modifier] carries the height rule of whoever holds the pages; [closeBelow] draws the Close
 * button underneath them rather than inside the same column, which is only needed when this is
 * the whole of a dialog's content.
 */
@Composable
fun AboutPanel(
    versionName: String,
    onVersionLongClick: () -> Unit,
    onLicences: () -> Unit,
    onSite: () -> Unit,
    onLlama: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    closeBelow: Boolean = false,
) {
    PagedColumn(modifier.fillMaxWidth()) {
        item {
            TextMMD(
                text = stringResource(R.string.about_name, versionName),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onLongPress = { onVersionLongClick() })
                },
            )
        }

        item { Heading(stringResource(R.string.about_privacy_heading)) }
        item { Para(stringResource(R.string.about_privacy_where), top = 4.dp) }
        item { Para(stringResource(R.string.about_privacy_permissions)) }
        item { Para(stringResource(R.string.about_privacy_close)) }

        item { Heading(stringResource(R.string.about_terms_heading)) }
        item { Para(stringResource(R.string.about_terms), top = 4.dp) }
        item { Para(stringResource(R.string.about_fork)) }

        item { ActionRow(stringResource(R.string.about_licences), onLicences) }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // The site's name opens the site, the way the llama beside it opens its page.
                TextMMD(
                    text = stringResource(R.string.about_source),
                    style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onSite),
                )
                Spacer(Modifier.width(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        // Straight to the checkout: the Donate button on the site only leads
                        // there anyway, so the page in between is a press the reader does not
                        // need. The short square.link form is what the site itself links to,
                        // so a regenerated checkout follows it and the app does not break.
                        .clickable(onClick = onLlama),
                ) {
                    Image(
                        painter = painterResource(R.drawable.llama),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    TextMMD(
                        text = stringResource(R.string.about_llama),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }

    if (closeBelow) {
        Spacer(Modifier.height(14.dp))
        OutlinedButtonMMD(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            TextMMD(
                text = stringResource(R.string.button_close),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun Para(text: String, top: Dp = 10.dp) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = top),
    )
}

/** A line that opens something in this app rather than a web page: bold, on a 48dp row. */
@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
    ) {
        TextMMD(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}
