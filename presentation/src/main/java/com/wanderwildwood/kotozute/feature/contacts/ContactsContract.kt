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
package com.wanderwildwood.kotozute.feature.contacts

import com.wanderwildwood.kotozute.common.base.QkView
import com.wanderwildwood.kotozute.extensions.Optional
import com.wanderwildwood.kotozute.feature.compose.editing.ComposeItem
import com.wanderwildwood.kotozute.feature.compose.editing.PhoneNumberAction
import io.reactivex.Observable
import io.reactivex.subjects.Subject

interface ContactsContract : QkView<ContactsState> {

    val queryChangedIntent: Observable<CharSequence>
    val queryClearedIntent: Observable<*>
    val queryEditorActionIntent: Observable<Int>
    /**
     * Each time this screen comes to the front. The Signal side of the list is read when it
     * fires: somebody can fetch their contacts in Settings and come straight back here, and a
     * list read once at creation would still be the empty one.
     */
    val screenShownIntent: Subject<Unit>
    val composeItemPressedIntent: Subject<ComposeItem>
    val composeItemLongPressedIntent: Subject<ComposeItem>
    val phoneNumberSelectedIntent: Subject<Optional<Long>>
    val phoneNumberActionIntent: Subject<PhoneNumberAction>

    /**
     * The badge that crosses between the two address books.
     *
     * The same gesture the two conversation lists use. Signal people used to sit at the
     * bottom of this one list, after every contact on the phone, where reaching them meant
     * turning pages until they appeared -- findable by search, unbrowsable by hand.
     */
    val railSwitchIntent: Subject<Unit>

    fun clearQuery()
    fun openKeyboard()
    fun finish(result: HashMap<String, String?>)

    /** Leave for a Signal conversation instead of returning a recipient. */
    fun finishWithSignalThread(threadKey: String, title: String)

}
