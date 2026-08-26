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
package com.message.ink.feature.settings.swipe

import android.view.View
import androidx.core.view.isVisible
import com.jakewharton.rxbinding2.view.clicks
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import com.message.ink.R
import com.message.ink.common.QkDialog
import com.message.ink.common.base.QkController
import com.message.ink.common.util.Colors
import com.message.ink.common.util.extensions.animateLayoutChanges
import com.message.ink.common.util.extensions.setBackgroundTint
import com.message.ink.common.util.extensions.setTint
import com.message.ink.injection.appComponent
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject
import com.message.ink.databinding.SwipeActionsControllerBinding

class SwipeActionsController : QkController<SwipeActionsView, SwipeActionsState, SwipeActionsPresenter>(), SwipeActionsView {

    private val binding get() = SwipeActionsControllerBinding.bind(view!!)

    @Inject override lateinit var presenter: SwipeActionsPresenter
    @Inject lateinit var actionsDialog: QkDialog
    @Inject lateinit var colors: Colors

    /**
     * Allows us to subscribe to [actionClicks] more than once
     */
    private val actionClicks: Subject<SwipeActionsView.Action> = PublishSubject.create()

    init {
        appComponent.inject(this)
        layoutRes = R.layout.swipe_actions_controller

        actionsDialog.adapter.setData(R.array.settings_swipe_actions)
    }

    override fun onViewCreated() {
        colors.theme().let { theme ->
            binding.rightIcon.setBackgroundTint(theme.theme)
            binding.rightIcon.setTint(theme.textPrimary)
            binding.leftIcon.setBackgroundTint(theme.theme)
            binding.leftIcon.setTint(theme.textPrimary)
        }

        binding.right.postDelayed({ binding.right?.animateLayoutChanges = true }, 100)
        binding.left.postDelayed({ binding.left?.animateLayoutChanges = true }, 100)

        Observable.merge(
                binding.right.clicks().map { SwipeActionsView.Action.RIGHT },
                binding.left.clicks().map { SwipeActionsView.Action.LEFT })
                .autoDisposable(scope())
                .subscribe(actionClicks)
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.settings_swipe_actions)
        showBackButton(true)
    }

    override fun actionClicks(): Observable<SwipeActionsView.Action> = actionClicks

    override fun actionSelected(): Observable<Int> = actionsDialog.adapter.menuItemClicks

    override fun showSwipeActions(selected: Int) {
        actionsDialog.adapter.selectedItem = selected
        activity?.let(actionsDialog::show)
    }

    override fun render(state: SwipeActionsState) {
        binding.rightIcon.isVisible = state.rightIcon != 0
        binding.rightIcon.setImageResource(state.rightIcon)
        binding.rightLabel.text = state.rightLabel

        binding.leftIcon.isVisible = state.leftIcon != 0
        binding.leftIcon.setImageResource(state.leftIcon)
        binding.leftLabel.text = state.leftLabel
    }

}