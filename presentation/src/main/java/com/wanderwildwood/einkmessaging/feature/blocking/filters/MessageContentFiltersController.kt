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
package com.wanderwildwood.einkmessaging.feature.blocking.filters

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.jakewharton.rxbinding2.view.clicks
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import com.wanderwildwood.einkmessaging.R
import com.wanderwildwood.einkmessaging.common.base.QkController
import com.wanderwildwood.einkmessaging.common.util.Colors
import com.wanderwildwood.einkmessaging.common.util.extensions.setBackgroundTint
import com.wanderwildwood.einkmessaging.common.util.extensions.setTint
import com.wanderwildwood.einkmessaging.common.widget.PreferenceView
import com.wanderwildwood.einkmessaging.injection.appComponent
import com.wanderwildwood.einkmessaging.model.MessageContentFilterData
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject
import com.wanderwildwood.einkmessaging.databinding.MessageContentFiltersControllerBinding
import com.wanderwildwood.einkmessaging.databinding.MessageContentFiltersAddDialogBinding

class MessageContentFiltersController : QkController<MessageContentFiltersView, MessageContentFiltersState,
        MessageContentFiltersPresenter>(), MessageContentFiltersView {

    private val binding get() = MessageContentFiltersControllerBinding.bind(containerView!!)

    @Inject override lateinit var presenter: MessageContentFiltersPresenter
    @Inject lateinit var colors: Colors

    private val adapter = MessageContentFiltersAdapter()
    private val saveFilterSubject: Subject<MessageContentFilterData> = PublishSubject.create()

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
        layoutRes = R.layout.message_content_filters_controller
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.message_content_filters_title)
        showBackButton(true)
    }

    override fun onViewCreated() {
        super.onViewCreated()
        binding.add.setBackgroundTint(colors.theme().theme)
        binding.add.setTint(colors.theme().textPrimary)
        adapter.emptyView = binding.empty
        binding.filters.adapter = adapter
    }

    override fun render(state: MessageContentFiltersState) {
        adapter.updateData(state.filters)
    }

    override fun removeFilter(): Observable<Long> = adapter.removeMessageContentFilter
    override fun addFilter(): Observable<*> = binding.add.clicks()
    override fun saveFilter(): Observable<MessageContentFilterData> = saveFilterSubject

    override fun showAddDialog() {
        val dialogBinding = MessageContentFiltersAddDialogBinding.inflate(LayoutInflater.from(activity))
        val layout = dialogBinding.root

        (0 until dialogBinding.addDialog.childCount)
            .map { index -> dialogBinding.addDialog.getChildAt(index) }
            .mapNotNull { view -> view as? PreferenceView }
            .map { preference -> preference.clicks().map { preference } }
            .let { Observable.merge(it) }
            .autoDisposable(scope())
            .subscribe {
                it.checkbox.isChecked = !it.checkbox.isChecked
                dialogBinding.caseSensitivity.isEnabled = !dialogBinding.regexp.checkbox.isChecked
            }

        val dialog = AlertDialog.Builder(activity!!)
                .setView(layout)
                .setPositiveButton(R.string.message_content_filters_dialog_create) { _, _ ->
                    var text = dialogBinding.input.text.toString();
                    if (!text.isBlank()) {
                        if (!dialogBinding.regexp.checkbox.isChecked) text = text.trim()
                        saveFilterSubject.onNext(
                            MessageContentFilterData(
                                text,
                                dialogBinding.caseSensitivity.checkbox.isChecked && !dialogBinding.regexp.checkbox.isChecked,
                                dialogBinding.regexp.checkbox.isChecked,
                                dialogBinding.contacts.checkbox.isChecked
                            )
                        )
                    }
                }
                .setNegativeButton(R.string.button_cancel) { _, _ -> }
        dialog.show()
    }

}
