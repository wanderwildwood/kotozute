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
package com.message.ink.feature.main

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewStub
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import com.message.ink.R
import com.message.ink.common.Navigator
import com.message.ink.common.androidxcompat.drawerOpen
import com.message.ink.common.base.QkThemedActivity
import com.message.ink.common.util.extensions.autoScrollToStart
import com.message.ink.common.util.extensions.dismissKeyboard
import com.message.ink.common.util.extensions.resolveThemeColor
import com.message.ink.common.util.extensions.scrapViews
import com.message.ink.common.util.extensions.setBackgroundTint
import com.message.ink.common.util.extensions.setTint
import com.message.ink.common.util.extensions.setVisible
import com.message.ink.common.widget.TextInputDialog
import com.message.ink.feature.blocking.BlockingDialog
import com.message.ink.feature.conversations.ConversationItemTouchCallback
import com.message.ink.feature.conversations.ConversationsAdapter
import com.message.ink.repository.SyncRepository
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject
import com.message.ink.databinding.MainActivityBinding
import android.widget.ProgressBar
import com.message.ink.common.widget.QkTextView

class MainActivity : QkThemedActivity(), MainView {

    private val binding by lazy { MainActivityBinding.inflate(layoutInflater) }

    @Inject lateinit var blockingDialog: BlockingDialog
    @Inject lateinit var disposables: CompositeDisposable
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var conversationsAdapter: ConversationsAdapter
    @Inject lateinit var drawerBadgesExperiment: DrawerBadgesExperiment
    @Inject lateinit var searchAdapter: SearchAdapter
    @Inject lateinit var itemTouchCallback: ConversationItemTouchCallback
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    override val onNewIntentIntent: Subject<Intent> = PublishSubject.create()
    override val activityResumedIntent: Subject<Boolean> = PublishSubject.create()
    override val queryChangedIntent by lazy { binding.toolbarSearch.textChanges() }
    override val composeIntent by lazy { binding.compose.clicks() }
    override val settingsIntent by lazy { binding.settingsIcon.clicks() }
    override val drawerToggledIntent: Observable<Boolean> by lazy {
        binding.drawerLayout.drawerOpen(Gravity.START)
    }
    override val homeIntent: Subject<Unit> = PublishSubject.create()
    override val navigationIntent: Observable<NavItem> by lazy {
        Observable.merge(listOf(
                backPressedSubject,
                binding.drawer.inbox.clicks().map { NavItem.INBOX },
                binding.drawer.archived.clicks().map { NavItem.ARCHIVED },
                binding.drawer.backup.clicks().map { NavItem.BACKUP },
                binding.drawer.scheduled.clicks().map { NavItem.SCHEDULED },
                binding.drawer.blocking.clicks().map { NavItem.BLOCKING },
                binding.drawer.settings.clicks().map { NavItem.SETTINGS }))
    }
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
    override val filterChangedIntent: Subject<Int> = PublishSubject.create()
    override val dismissRatingIntent by lazy { binding.drawer.rateDismiss.clicks() }
    override val rateIntent by lazy { binding.drawer.rateOkay.clicks() }
    override val conversationsSelectedIntent by lazy { conversationsAdapter.selectionChanges }
    override val confirmDeleteIntent: Subject<List<Long>> = PublishSubject.create()
    override val renameConversationIntent: Subject<String> = PublishSubject.create()
    override val swipeConversationIntent by lazy { itemTouchCallback.swipes }
    override val undoArchiveIntent: Subject<Unit> = PublishSubject.create()
    override val snackbarButtonIntent: Subject<Unit> = PublishSubject.create()

    private val viewModel by lazy {
        ViewModelProviders.of(this, viewModelFactory)[MainViewModel::class.java]
    }
    private val toggle by lazy {
        ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.main_drawer_open_cd,
            0
        )
    }
    private val itemTouchHelper by lazy { ItemTouchHelper(itemTouchCallback) }
    private val progressAnimator by lazy {
        ObjectAnimator.ofInt(findViewById<ProgressBar?>(R.id.syncingProgress), "progress", 0, 0)
    }
    private val backPressedSubject: Subject<NavItem> = PublishSubject.create()

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        viewModel.bindView(this)
        onNewIntentIntent.onNext(intent)

        (binding.snackbar as? ViewStub)?.setOnInflateListener { _, _ ->
            findViewById<QkTextView?>(R.id.snackbarButton).clicks()
                    .autoDisposable(scope(Lifecycle.Event.ON_DESTROY))
                    .subscribe(snackbarButtonIntent)
        }

        (binding.syncing as? ViewStub)?.setOnInflateListener { _, _ ->
            findViewById<ProgressBar?>(R.id.syncingProgress)?.let {
                it.progressTintList = ColorStateList.valueOf(theme.blockingFirst().theme)
                it.indeterminateTintList = ColorStateList.valueOf(theme.blockingFirst().theme)
            }
        }

        toggle.syncState()
        toggle.isDrawerIndicatorEnabled = false
        binding.toolbar.navigationIcon = null
        binding.toolbar.setNavigationOnClickListener {
            dismissKeyboard()
            homeIntent.onNext(Unit)
        }

        // Handle search icon click to show search input
        binding.searchIcon.setOnClickListener {
            showSearchMode()
        }

        // Handle search back button click
        binding.searchBack.setOnClickListener {
            hideSearchMode()
        }

        // Handle search input focus to show/hide cursor
        binding.toolbarSearch.setOnFocusChangeListener { _, hasFocus ->
            binding.toolbarSearch.isCursorVisible = hasFocus
        }

        itemTouchCallback.adapter = conversationsAdapter
        conversationsAdapter.autoScrollToStart(binding.recyclerView)

        // Don't allow clicks to pass through the drawer layout
        binding.drawer.root.clicks().autoDisposable(scope()).subscribe()

        // Set the theme color tint to the recyclerView, progressbar, and FAB
        theme
                .autoDisposable(scope())
                .subscribe { theme ->
                    // Set the color for the drawer icons
                    val states = arrayOf(
                            intArrayOf(android.R.attr.state_activated),
                            intArrayOf(-android.R.attr.state_activated))

                    ColorStateList(states, intArrayOf(theme.theme,
                        resolveThemeColor(android.R.attr.textColorSecondary)
                    ))
                        .let { tintList ->
                            binding.drawer.inboxIcon.imageTintList = tintList
                            binding.drawer.archivedIcon.imageTintList = tintList
                        }

                    // Miscellaneous views
                    findViewById<ProgressBar?>(R.id.syncingProgress)?.progressTintList = ColorStateList.valueOf(theme.theme)
                    findViewById<ProgressBar?>(R.id.syncingProgress)?.indeterminateTintList = ColorStateList.valueOf(theme.theme)
                    binding.drawer.rateIcon.setTint(theme.theme)
                    // Removed compose background tint to show white with black outline
                    // Compose icon tint is set in XML
                }

        // Setup filter tabs
        setupFilterTabs()
    }

    private fun setupFilterTabs() {
        binding.filterAll.setOnClickListener {
            selectFilterTab(0)
            filterChangedIntent.onNext(0)
        }
        binding.filterGroups.setOnClickListener {
            selectFilterTab(1)
            filterChangedIntent.onNext(1)
        }
        binding.filterUnknown.setOnClickListener {
            selectFilterTab(2)
            filterChangedIntent.onNext(2)
        }
    }

    private fun selectFilterTab(filter: Int) {
        // Reset all tabs
        binding.filterAll.setBackgroundResource(android.R.color.transparent)
        binding.filterAll.setTextColor(android.graphics.Color.BLACK)
        binding.filterGroups.setBackgroundResource(android.R.color.transparent)
        binding.filterGroups.setTextColor(android.graphics.Color.BLACK)
        binding.filterUnknown.setBackgroundResource(android.R.color.transparent)
        binding.filterUnknown.setTextColor(android.graphics.Color.BLACK)

        // Select the active tab
        when (filter) {
            0 -> {
                binding.filterAll.setBackgroundResource(R.drawable.filter_tab_selected)
                binding.filterAll.setTextColor(android.graphics.Color.WHITE)
            }
            1 -> {
                binding.filterGroups.setBackgroundResource(R.drawable.filter_tab_selected)
                binding.filterGroups.setTextColor(android.graphics.Color.WHITE)
            }
            2 -> {
                binding.filterUnknown.setBackgroundResource(R.drawable.filter_tab_selected)
                binding.filterUnknown.setTextColor(android.graphics.Color.WHITE)
            }
        }
    }

    fun updateFilterTabSelection(filter: Int) {
        selectFilterTab(filter)
    }

    override fun onNewIntent(intent: Intent?) =
        intent?.let {
            super.onNewIntent(intent)
            it.run(onNewIntentIntent::onNext)
        } ?: Unit

    override fun render(state: MainState) {
        if (state.hasError) {
            finish()
            return
        }

        val addContact = when (state.page) {
            is Inbox -> state.page.addContact
            is Archived -> state.page.addContact
            else -> false
        }

        val markPinned = when (state.page) {
            is Inbox -> state.page.markPinned
            is Archived -> state.page.markPinned
            else -> true
        }

        val markRead = when (state.page) {
            is Inbox -> state.page.markRead
            is Archived -> state.page.markRead
            else -> true
        }

        val selectedConversations = when (state.page) {
            is Inbox -> state.page.selected
            is Archived -> state.page.selected
            else -> 0
        }

        // Only show search when in Searching state
        val searchVisible = state.page is Searching

        binding.searchContainer.setVisible(searchVisible)
        binding.toolbarContent.setVisible(!searchVisible)

        // toolbarTitle's visibility is decided after the `when (state.page)` block below,
        // which is what actually populates the title.

        // Show/hide filter tabs - only visible on Inbox page when not searching and no selection
        val showFilterTabs = state.page is Inbox && !searchVisible && selectedConversations == 0
        binding.filterTabs.setVisible(showFilterTabs)

        // Hide compose button when search is active
        if (searchVisible) {
            binding.compose.visibility = View.GONE
        } else if (state.page is Inbox || state.page is Archived) {
            binding.compose.visibility = View.VISIBLE
        } else {
            binding.compose.visibility = View.GONE
        }

        binding.toolbar.menu.apply {
            findItem(R.id.select_all)?.isVisible =
                (conversationsAdapter.itemCount > 1) && selectedConversations != 0
            findItem(R.id.archive)?.isVisible =
                state.page is Inbox && selectedConversations != 0
            findItem(R.id.unarchive)?.isVisible =
                state.page is Archived && selectedConversations != 0
            findItem(R.id.delete)?.isVisible = selectedConversations != 0
            findItem(R.id.add)?.isVisible = addContact && selectedConversations != 0
            findItem(R.id.pin)?.isVisible = markPinned && selectedConversations != 0
            findItem(R.id.unpin)?.isVisible = !markPinned && selectedConversations != 0
            findItem(R.id.read)?.isVisible = ( markRead && selectedConversations != 0 ) ||
                    selectedConversations > 1
            findItem(R.id.unread)?.isVisible = ( !markRead && selectedConversations != 0 ) ||
                    selectedConversations > 1
            findItem(R.id.block)?.isVisible = selectedConversations != 0
            findItem(R.id.rename)?.isVisible = selectedConversations == 1
        }

        binding.drawer.rateLayout.setVisible(false) // Disabled "Enjoying QUIK?" popup

        conversationsAdapter.emptyView = binding.empty.takeIf {
            state.page is Inbox || state.page is Archived
        }
        searchAdapter.emptyView = binding.empty.takeIf { state.page is Searching }

        when (state.page) {
            is Inbox -> {
                showBackButton(state.page.selected > 0)
                // Only show title when items are selected, otherwise no title
                title = when (state.page.selected != 0) {
                    true -> getString(R.string.main_title_selected, state.page.selected)
                    false -> ""
                }
                if (binding.recyclerView.adapter !== conversationsAdapter)
                    binding.recyclerView.adapter = conversationsAdapter
                conversationsAdapter.filterMode = state.page.filter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(binding.recyclerView)
                binding.empty.setText(R.string.inbox_empty_text)
                // Update filter tab selection
                selectFilterTab(state.page.filter)
            }

            is Searching -> {
                showBackButton(true)
                if (binding.recyclerView.adapter !== searchAdapter) binding.recyclerView.adapter = searchAdapter
                searchAdapter.data = state.page.data ?: listOf()
                itemTouchHelper.attachToRecyclerView(null)
                binding.empty.setText(R.string.inbox_search_empty_text)
            }

            is Archived -> {
                showBackButton(state.page.selected > 0)
                // Unlike Inbox, Archived has no filter pill of its own, so it keeps a
                // title when nothing is selected to say where you are.
                title = when (state.page.selected != 0) {
                    true -> getString(R.string.main_title_selected, state.page.selected)
                    false -> getString(R.string.title_archived)
                }
                if (binding.recyclerView.adapter !== conversationsAdapter)
                    binding.recyclerView.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(null)
                binding.empty.setText(R.string.archived_empty_text)
            }

            else -> {}
        }

        // Only take up toolbar space when there's actually a title to show — the view is
        // weighted, so leaving it VISIBLE-but-empty would shove the filter pills rightwards.
        // Never write toolbarTitle.text directly: QkActivity owns that view and mirrors the
        // Activity `title` into it, re-applying on layout, so direct writes get clobbered.
        binding.toolbarTitle.setVisible(!searchVisible && !title.isNullOrEmpty())

        binding.drawer.inbox.isActivated = state.page is Inbox
        binding.drawer.archived.isActivated = state.page is Archived

        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START) && !state.drawerOpen)
            binding.drawerLayout.closeDrawer(GravityCompat.START, false)
        else if (!binding.drawerLayout.isDrawerVisible(GravityCompat.START) && state.drawerOpen)
            binding.drawerLayout.openDrawer(GravityCompat.START, false)

        when (state.syncing) {
            is SyncRepository.SyncProgress.Idle -> {
                binding.syncing.isVisible = false
                binding.snackbar.isVisible = (!state.defaultSms ||
                        !state.smsPermission ||
                        !state.contactPermission ||
                        !state.notificationPermission)
            }

            is SyncRepository.SyncProgress.Running -> {
                binding.syncing.isVisible = true
                findViewById<ProgressBar?>(R.id.syncingProgress).max = state.syncing.max
                progressAnimator.apply {
                    setIntValues(findViewById<ProgressBar?>(R.id.syncingProgress).progress, state.syncing.progress)
                }.start()
                findViewById<ProgressBar?>(R.id.syncingProgress).isIndeterminate = state.syncing.indeterminate
                binding.snackbar.isVisible = false
            }
        }

        when {
            !state.defaultSms -> {
                findViewById<QkTextView?>(R.id.snackbarTitle)?.setText(R.string.main_default_sms_title)
                findViewById<QkTextView?>(R.id.snackbarMessage)?.setText(R.string.main_default_sms_message)
                findViewById<QkTextView?>(R.id.snackbarButton)?.setText(R.string.main_default_sms_change)
            }

            !state.smsPermission -> {
                findViewById<QkTextView?>(R.id.snackbarTitle)?.setText(R.string.main_permission_required)
                findViewById<QkTextView?>(R.id.snackbarMessage)?.setText(R.string.main_permission_sms)
                findViewById<QkTextView?>(R.id.snackbarButton)?.setText(R.string.main_permission_allow)
            }

            !state.contactPermission -> {
                findViewById<QkTextView?>(R.id.snackbarTitle)?.setText(R.string.main_permission_required)
                findViewById<QkTextView?>(R.id.snackbarMessage)?.setText(R.string.main_permission_contacts)
                findViewById<QkTextView?>(R.id.snackbarButton)?.setText(R.string.main_permission_allow)
            }

            !state.notificationPermission -> {
                findViewById<QkTextView?>(R.id.snackbarTitle)?.setText(R.string.main_permission_required)
                findViewById<QkTextView?>(R.id.snackbarMessage)?.setText(R.string.main_permission_notifications)
                findViewById<QkTextView?>(R.id.snackbarButton)?.setText(R.string.main_permission_allow)
            }
        }
    }

    override fun onResume() =
        super.onResume().also { activityResumedIntent.onNext(true) }

    override fun onPause() =
        super.onPause().also { activityResumedIntent.onNext(false) }

    override fun onDestroy() =
        super.onDestroy().also { disposables.dispose() }

    override fun showBackButton(show: Boolean) =
        toggle.let {
            it.onDrawerSlide(binding.drawer.root, if (show) 1f else 0f)
            it.drawerArrowDrawable.color = when (show) {
                true -> resolveThemeColor(android.R.attr.textColorSecondary)
                false -> resolveThemeColor(android.R.attr.textColorPrimary)
            }
        }

    override fun requestDefaultSms() =
        navigator.showDefaultSmsDialog(this)

    override fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            permissions += Manifest.permission.POST_NOTIFICATIONS

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 0)
    }

    override fun clearSearch() {
        dismissKeyboard()
        binding.toolbarSearch.text = null
    }

    override fun clearSelection() = conversationsAdapter.clearSelection()

    override fun toggleSelectAll() = conversationsAdapter.toggleSelectAll()

    override fun themeChanged() = binding.recyclerView.scrapViews()

    override fun showBlockingDialog(conversations: List<Long>, block: Boolean) {
        blockingDialog.show(this, conversations, block)
    }

    override fun showDeleteDialog(conversations: List<Long>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.dialog_delete_message,
                    conversations.size,
                    conversations.size
                )
            )
            .setPositiveButton(R.string.button_delete) { _, _ -> confirmDeleteIntent.onNext(conversations) }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun showRenameDialog(conversationName: String) =
        TextInputDialog(
            this,
            getString(R.string.info_name),
            renameConversationIntent::onNext
        )
            .setText(conversationName)
            .show()

    override fun showArchivedSnackbar(countConversationsArchived: Int, isArchiving: Boolean) =
        Snackbar.make(
            binding.drawerLayout,
            if (isArchiving) {
                resources.getQuantityString(R.plurals.toast_archived, countConversationsArchived, countConversationsArchived)
            } else {
                resources.getQuantityString(R.plurals.toast_unarchived, countConversationsArchived, countConversationsArchived)
            },
            if (countConversationsArchived < 10) Snackbar.LENGTH_LONG
            else Snackbar.LENGTH_INDEFINITE
        ).let {
            it.setAction(R.string.button_undo) { undoArchiveIntent.onNext(Unit) }
            it.setActionTextColor(colors.theme().theme)
            it.show()
        }

    override fun onCreateOptionsMenu(menu: Menu?) =
        menu?.let {
            menuInflater.inflate(R.menu.main, it)
            super.onCreateOptionsMenu(it)
        } ?: false

    override fun onOptionsItemSelected(item: MenuItem) =
        optionsItemIntent.onNext(item.itemId).let { true }

    private fun showSearchMode() {
        binding.toolbarContent.visibility = View.GONE
        binding.searchContainer.visibility = View.VISIBLE
        binding.toolbarSearch.requestFocus()
        binding.toolbarSearch.isCursorVisible = true
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.toolbarSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSearchMode() {
        binding.searchContainer.visibility = View.GONE
        binding.toolbarContent.visibility = View.VISIBLE
        binding.toolbarSearch.text = null
        binding.toolbarSearch.isCursorVisible = false
        dismissKeyboard()
    }

    override fun onBackPressed() {
        // If search is visible, hide it and show the toolbar content
        if (binding.searchContainer.visibility == View.VISIBLE) {
            hideSearchMode()
        } else {
            backPressedSubject.onNext(NavItem.BACK)
        }
    }

    override fun drawerToggled(opened: Boolean) {
        if (opened) {
            dismissKeyboard()
            if (!binding.drawer.inbox.isInTouchMode)
                binding.drawer.inbox.requestFocus()
        }
    }
}
