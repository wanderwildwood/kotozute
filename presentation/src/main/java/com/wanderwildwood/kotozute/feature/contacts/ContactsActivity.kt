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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProviders
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.editorActions
import com.jakewharton.rxbinding2.widget.textChanges
import dagger.android.AndroidInjection
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.Navigator
import com.wanderwildwood.kotozute.common.ViewModelFactory
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.common.util.extensions.hideKeyboard
import com.wanderwildwood.kotozute.common.util.extensions.showKeyboard
import com.wanderwildwood.kotozute.common.widget.QkDialog
import com.wanderwildwood.kotozute.extensions.Optional
import com.wanderwildwood.kotozute.feature.compose.editing.ComposeItem
import com.wanderwildwood.kotozute.feature.compose.editing.ComposeItemAdapter
import com.wanderwildwood.kotozute.feature.compose.editing.PhoneNumberAction
import com.wanderwildwood.kotozute.feature.compose.editing.PhoneNumberPickerAdapter
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject
import com.wanderwildwood.kotozute.databinding.ContactsActivityBinding
import com.wanderwildwood.kotozute.common.util.extensions.turnsAPageOnSwipe

class ContactsActivity : QkThemedActivity(), ContactsContract {

    private val binding by lazy { ContactsActivityBinding.inflate(layoutInflater) }

    companion object {
        const val SHARING_KEY = "sharing"
        const val CHIPS_KEY = "chips"
        const val SIGNAL_THREAD_KEY = "signalThreadKey"
        const val SIGNAL_THREAD_TITLE = "signalThreadTitle"
    }

    @Inject lateinit var contactsAdapter: ComposeItemAdapter
    @Inject lateinit var phoneNumberAdapter: PhoneNumberPickerAdapter
    @Inject lateinit var viewModelFactory: ViewModelFactory
    @Inject lateinit var navigator: Navigator

    override val queryChangedIntent: Observable<CharSequence> by lazy { binding.search.textChanges() }
    override val queryClearedIntent: Observable<*> by lazy { binding.cancel.clicks() }
    override val queryEditorActionIntent: Observable<Int> by lazy { binding.search.editorActions() }
    override val composeItemPressedIntent: Subject<ComposeItem> by lazy { contactsAdapter.clicks }
    override val composeItemLongPressedIntent: Subject<ComposeItem> by lazy { contactsAdapter.longClicks }
    override val phoneNumberSelectedIntent: Subject<Optional<Long>> by lazy { phoneNumberAdapter.selectedItemChanges }
    override val phoneNumberActionIntent: Subject<PhoneNumberAction> = PublishSubject.create()

    private val viewModel by lazy { ViewModelProviders.of(this, viewModelFactory)[ContactsViewModel::class.java] }

    private val phoneNumberDialog by lazy {
        QkDialog(this).apply {
            titleRes = R.string.compose_number_picker_title
            adapter = phoneNumberAdapter
            positiveButton = R.string.compose_number_picker_always
            positiveButtonListener = { phoneNumberActionIntent.onNext(PhoneNumberAction.ALWAYS) }
            negativeButton = R.string.compose_number_picker_once
            negativeButtonListener = { phoneNumberActionIntent.onNext(PhoneNumberAction.JUST_ONCE) }
            cancelListener = { phoneNumberActionIntent.onNext(PhoneNumberAction.CANCEL) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        showBackButton(true)
        viewModel.bindView(this)

        binding.contacts.adapter = contactsAdapter
        // Asked for on the forum alongside the conversation list, and the same reasoning
        // applies: this is a long list on a panel that redraws in full.
        binding.contacts.turnsAPageOnSwipe()

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigator.showMainActivity()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    override fun render(state: ContactsState) {
        binding.cancel.isVisible = state.query.length > 1

        contactsAdapter.data = state.composeItems

        if (state.selectedContact != null && !phoneNumberDialog.isShowing) {
            phoneNumberAdapter.data = state.selectedContact.numbers
            phoneNumberDialog.subtitle = state.selectedContact.name
            phoneNumberDialog.show()
        } else if (state.selectedContact == null && phoneNumberDialog.isShowing) {
            phoneNumberDialog.dismiss()
        }
    }

    override fun clearQuery() {
        binding.search.text = null
    }

    override fun openKeyboard() {
        binding.search.postDelayed({
            binding.search.showKeyboard()
        }, 200)
    }

    override fun finish(result: HashMap<String, String?>) {
        binding.search.hideKeyboard()
        val intent = Intent().putExtra(CHIPS_KEY, result)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    /**
     * Handed back to the composer rather than opened here, so that the composer can stand
     * aside as it does for the rail badge: a Signal conversation reached this way has the
     * conversation list behind it, not an empty new message nobody asked to keep.
     */
    override fun finishWithSignalThread(threadKey: String, title: String) {
        binding.search.hideKeyboard()
        val intent = Intent()
                .putExtra(SIGNAL_THREAD_KEY, threadKey)
                .putExtra(SIGNAL_THREAD_TITLE, title)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

}
