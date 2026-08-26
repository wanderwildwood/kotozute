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
package com.message.ink.feature.blocking

import android.view.View
import com.bluelinelabs.conductor.RouterTransaction
import com.jakewharton.rxbinding2.view.clicks
import com.message.ink.R
import com.message.ink.common.QkChangeHandler
import com.message.ink.common.base.QkController
import com.message.ink.common.util.Colors
import com.message.ink.common.util.extensions.animateLayoutChanges
import com.message.ink.feature.blocking.manager.BlockingManagerController
import com.message.ink.feature.blocking.messages.BlockedMessagesController
import com.message.ink.feature.blocking.numbers.BlockedNumbersController
import com.message.ink.feature.blocking.filters.MessageContentFiltersController
import com.message.ink.injection.appComponent
import javax.inject.Inject
import com.message.ink.databinding.BlockingControllerBinding

class BlockingController : QkController<BlockingView, BlockingState, BlockingPresenter>(), BlockingView {

    private val binding get() = BlockingControllerBinding.bind(view!!)

    override val blockingManagerIntent by lazy { binding.blockingManager.clicks() }
    override val blockedNumbersIntent by lazy { binding.blockedNumbers.clicks() }
    override val messageContentFiltersIntent by lazy { binding.messageContentFilters.clicks() }
    override val blockedMessagesIntent by lazy { binding.blockedMessages.clicks() }
    override val dropClickedIntent by lazy { binding.drop.clicks() }
    override val blockNonContactsClickedIntent by lazy { binding.blockNonContacts.clicks() }

    @Inject lateinit var colors: Colors
    @Inject override lateinit var presenter: BlockingPresenter

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
        layoutRes = R.layout.blocking_controller
    }

    override fun onViewCreated() {
        super.onViewCreated()
        binding.parent.postDelayed({ binding.parent?.animateLayoutChanges = true }, 100)
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.blocking_title)
        showBackButton(true)
    }

    override fun render(state: BlockingState) {
        binding.blockingManager.summary = state.blockingManager
        binding.drop.checkbox.isChecked = state.dropEnabled
        binding.blockedMessages.isEnabled = !state.dropEnabled

        binding.blockNonContacts.checkbox.isChecked = state.blockNonContactsEnabled
        binding.blockNonContacts.isEnabled = state.usingBuiltInBlocking && state.canReadContacts
        binding.blockNonContacts.summary = when {
            !state.usingBuiltInBlocking -> activity?.getString(R.string.blocking_manager_title)
            !state.canReadContacts -> activity?.getString(R.string.blocking_non_contacts_no_permission)
            else -> activity?.getString(R.string.blocking_non_contacts_summary)
        }
    }

    override fun openBlockedNumbers() {
        router.pushController(RouterTransaction.with(BlockedNumbersController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun openMessageContentFilters() {
        router.pushController(RouterTransaction.with(MessageContentFiltersController())
            .pushChangeHandler(QkChangeHandler())
            .popChangeHandler(QkChangeHandler()))
    }

    override fun openBlockedMessages() {
        router.pushController(RouterTransaction.with(BlockedMessagesController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun openBlockingManager() {
        router.pushController(RouterTransaction.with(BlockingManagerController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

}
