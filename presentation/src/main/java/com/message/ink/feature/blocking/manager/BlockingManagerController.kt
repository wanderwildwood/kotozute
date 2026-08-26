package com.message.ink.feature.blocking.manager

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.View
import androidx.core.view.isInvisible
import com.jakewharton.rxbinding2.view.clicks
import com.message.ink.R
import com.message.ink.common.base.QkController
import com.message.ink.common.util.Colors
import com.message.ink.common.util.extensions.resolveThemeColor
import com.message.ink.injection.appComponent
import com.message.ink.util.Preferences
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.PublishSubject
import javax.inject.Inject
import com.message.ink.databinding.BlockingManagerControllerBinding

class BlockingManagerController : QkController<BlockingManagerView, BlockingManagerState, BlockingManagerPresenter>(),
    BlockingManagerView {

    private val binding get() = BlockingManagerControllerBinding.bind(containerView!!)

    @Inject lateinit var colors: Colors
    @Inject override lateinit var presenter: BlockingManagerPresenter

    private val activityResumedSubject: PublishSubject<Unit> = PublishSubject.create()

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
        layoutRes = R.layout.blocking_manager_controller
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.blocking_manager_title)
        showBackButton(true)

        val states = arrayOf(
                intArrayOf(android.R.attr.state_activated),
                intArrayOf(-android.R.attr.state_activated))

        val textTertiary = view.context.resolveThemeColor(android.R.attr.textColorTertiary)
        val imageTintList = ColorStateList(states, intArrayOf(colors.theme().theme, textTertiary))

        binding.qksms.action.imageTintList = imageTintList
        binding.callBlocker.action.imageTintList = imageTintList
        binding.callControl.action.imageTintList = imageTintList
        binding.shouldIAnswer.action.imageTintList = imageTintList
    }

    override fun onActivityResumed(activity: Activity) {
        activityResumedSubject.onNext(Unit)
    }

    override fun render(state: BlockingManagerState) {
        binding.qksms.action.setImageResource(getActionIcon(true))
        binding.qksms.action.isActivated = true
        binding.qksms.action.isInvisible = state.blockingManager != Preferences.BLOCKING_MANAGER_QKSMS

        binding.callBlocker.action.setImageResource(getActionIcon(state.callBlockerInstalled))
        binding.callBlocker.action.isActivated = state.callBlockerInstalled
        binding.callBlocker.action.isInvisible = state.blockingManager != Preferences.BLOCKING_MANAGER_CB
                && state.callBlockerInstalled

        binding.callControl.action.setImageResource(getActionIcon(state.callControlInstalled))
        binding.callControl.action.isActivated = state.callControlInstalled
        binding.callControl.action.isInvisible = state.blockingManager != Preferences.BLOCKING_MANAGER_CC
                && state.callControlInstalled

        binding.shouldIAnswer.action.setImageResource(getActionIcon(state.siaInstalled))
        binding.shouldIAnswer.action.isActivated = state.siaInstalled
        binding.shouldIAnswer.action.isInvisible = state.blockingManager != Preferences.BLOCKING_MANAGER_SIA
                && state.siaInstalled
    }

    private fun getActionIcon(installed: Boolean): Int = when {
        !installed -> R.drawable.ic_chevron_right_black_24dp
        else -> R.drawable.ic_check_white_24dp
    }

    override fun activityResumed(): Observable<*> = activityResumedSubject
    override fun qksmsClicked(): Observable<*> = binding.qksms.clicks()
    override fun callBlockerClicked(): Observable<*> = binding.callBlocker.clicks()
    override fun callControlClicked(): Observable<*> = binding.callControl.clicks()
    override fun siaClicked(): Observable<*> = binding.shouldIAnswer.clicks()

    override fun showCopyDialog(manager: String): Single<Boolean> = Single.create { emitter ->
        AlertDialog.Builder(activity)
                .setTitle(R.string.blocking_manager_copy_title)
                .setMessage(resources?.getString(R.string.blocking_manager_copy_summary, manager))
                .setPositiveButton(R.string.button_continue) { _, _ -> emitter.onSuccess(true) }
                .setNegativeButton(R.string.button_cancel) { _, _ -> emitter.onSuccess(false) }
                .setCancelable(false)
                .show()
    }

}
