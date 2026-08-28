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
package com.wanderwildwood.kotozute.feature.blocking

data class BlockingState(
    val blockingManager: String = "",
    val dropEnabled: Boolean = false,
    val blockNonContactsEnabled: Boolean = false,
    val canReadContacts: Boolean = true,
    // The rule lives in the built-in blocking client, so it does nothing while a third-party
    // manager is selected. Better to say so than to leave a switch that silently has no effect.
    val usingBuiltInBlocking: Boolean = true
)
