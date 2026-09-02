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
package com.wanderwildwood.kotozute.feature.main

import androidx.core.content.ContextCompat
import android.graphics.Color
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
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.Navigator
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.common.util.extensions.autoScrollToStart
import com.wanderwildwood.kotozute.common.util.extensions.dismissKeyboard
import com.wanderwildwood.kotozute.common.util.extensions.resolveThemeColor
import com.wanderwildwood.kotozute.common.util.extensions.scrapViews
import com.wanderwildwood.kotozute.common.util.extensions.setBackgroundTint
import com.wanderwildwood.kotozute.common.util.extensions.setTint
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.common.widget.TextInputDialog
import com.wanderwildwood.kotozute.feature.blocking.BlockingDialog
import com.wanderwildwood.kotozute.feature.conversations.ConversationItemTouchCallback
import com.wanderwildwood.kotozute.feature.conversations.ConversationsAdapter
import com.wanderwildwood.kotozute.repository.SyncRepository
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject
import com.wanderwildwood.kotozute.databinding.MainActivityBinding
import android.widget.ProgressBar
import com.wanderwildwood.kotozute.common.widget.QkTextView
import com.wanderwildwood.kotozute.common.util.extensions.turnsAPageOnSwipe

class MainActivity : QkThemedActivity(), MainView {

    private val binding by lazy { MainActivityBinding.inflate(layoutInflater) }

    @Inject lateinit var blockingDialog: BlockingDialog
    @Inject lateinit var disposables: CompositeDisposable
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var conversationsAdapter: ConversationsAdapter
    @Inject lateinit var searchAdapter: SearchAdapter
    @Inject lateinit var itemTouchCallback: ConversationItemTouchCallback
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    override val onNewIntentIntent: Subject<Intent> = PublishSubject.create()
    override val activityResumedIntent: Subject<Boolean> = PublishSubject.create()
    override val queryChangedIntent by lazy { binding.toolbarSearch.textChanges() }
    override val composeIntent by lazy { binding.compose.clicks() }
    override val settingsIntent by lazy { binding.settingsIcon.clicks() }
    override val homeIntent: Subject<Unit> = PublishSubject.create()
    // Only the back press now. Archived, Backup, Scheduled, Blocking and Settings are
    // reached from the Settings screen, which the toolbar opens directly.
    override val navigationIntent: Observable<NavItem> by lazy { backPressedSubject }
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
    override val filterChangedIntent: Subject<Int> = PublishSubject.create()
    override val conversationsSelectedIntent by lazy { conversationsAdapter.selectionChanges }
    override val confirmDeleteIntent: Subject<List<Long>> = PublishSubject.create()
    override val renameConversationIntent: Subject<String> = PublishSubject.create()
    override val swipeConversationIntent by lazy { itemTouchCallback.swipes }
    override val undoArchiveIntent: Subject<Unit> = PublishSubject.create()
    override val snackbarButtonIntent: Subject<Unit> = PublishSubject.create()

    private val viewModel by lazy {
        ViewModelProviders.of(this, viewModelFactory)[MainViewModel::class.java]
    }
    private val itemTouchHelper by lazy { ItemTouchHelper(itemTouchCallback) }
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

        binding.toolbar.navigationIcon = null
        binding.toolbar.setNavigationOnClickListener {
            dismissKeyboard()
            homeIntent.onNext(Unit)
        }

        // Handle search icon click to show search input
        binding.searchIcon.setOnClickListener {
            showSearchMode()
        }

        binding.railBadge.setOnClickListener { navigator.showSignalConversations() }

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

        binding.recyclerView.turnsAPageOnSwipe()

        // Set the theme color tint to the recyclerView, progressbar, and FAB
        theme
                .autoDisposable(scope())
                .subscribe { theme ->
                    // Miscellaneous views
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

        // The crossing to the Signal list, offered only when there is a separate one to
        // cross to. Woven, a Signal thread is already in this list and the badge is on its
        // row instead.
        binding.railBadge.setVisible(state.separateSignalList && state.page is Inbox)

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
        // Only when there is something to do; see the note in main.xml about the width an
        // overflow button costs the filter tabs.
        binding.toolbar.menu.findItem(R.id.markAllRead)?.isVisible =
            state.hasUnread && state.page is Inbox && selectedConversations == 0

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

        // Archived gets a back arrow, and it goes to the messages. It had one that went to
        // Settings instead, on the reasoning that Settings is where Archived is reached
        // from -- and since Settings opens Archived, the two screens shut behind you with
        // no way to the messages from either.
        val archived = state.page is Archived
        binding.toolbar.navigationIcon = when {
            archived -> ContextCompat.getDrawable(this, R.drawable.ic_arrow_back_black_24dp)
                ?.apply { setTint(Color.BLACK) }
            else -> null
        }
        binding.settingsIcon.setVisible(!archived)

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
                // The count, not a bar sliding towards it. A moving bar on e-ink is a smear
                // and a redraw per step; the number it was approximating is the useful part,
                // and it is only shown once the sync knows how much there is to do.
                findViewById<QkTextView?>(R.id.syncingLabel)?.text = when {
                    state.syncing.indeterminate || state.syncing.max <= 0 ->
                        getString(R.string.main_syncing)
                    else -> getString(
                        R.string.main_syncing_count, state.syncing.progress, state.syncing.max
                    )
                }
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

    /**
     * The conversation list is the root screen and has never drawn a back arrow: the toolbar's
     * navigation icon is null and the drawer indicator that used to animate here is gone with
     * the drawer. Overridden to nothing so the base class does not put one there.
     */
    override fun showBackButton(show: Boolean) = Unit

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
            // Was the drawer layout, which was the root; the root is the list screen itself now.
            binding.root,
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
        // Take the keyboard down FIRST, while the field that raised it is still focused and on
        // screen. Afterwards is too late: setting the container GONE moves focus off the
        // now-invisible field, so window.currentFocus is null by then -- and dismissKeyboard()
        // is `currentFocus?.let { ... }`, which silently does nothing rather than failing. The
        // search closed, the filters came back, and the keyboard just stayed up.
        //
        // Asking the view for its own window token avoids the question entirely: it stays valid
        // whether or not the view still holds focus.
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.toolbarSearch.windowToken, 0)
        binding.toolbarSearch.clearFocus()

        binding.searchContainer.visibility = View.GONE
        binding.toolbarContent.visibility = View.VISIBLE
        binding.toolbarSearch.text = null
        binding.toolbarSearch.isCursorVisible = false
    }

    override fun onBackPressed() {
        // If search is visible, hide it and show the toolbar content
        if (binding.searchContainer.visibility == View.VISIBLE) {
            hideSearchMode()
        } else {
            backPressedSubject.onNext(NavItem.BACK)
        }
    }

}
