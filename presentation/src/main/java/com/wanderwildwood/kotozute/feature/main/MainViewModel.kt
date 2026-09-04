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

import androidx.recyclerview.widget.ItemTouchHelper
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.Navigator
import com.wanderwildwood.kotozute.common.base.QkViewModel
import com.wanderwildwood.kotozute.extensions.mapNotNull
import com.wanderwildwood.kotozute.interactor.DeleteConversations
import com.wanderwildwood.kotozute.interactor.MarkAllSeen
import com.wanderwildwood.kotozute.interactor.MarkArchived
import com.wanderwildwood.kotozute.interactor.MarkPinned
import com.wanderwildwood.kotozute.interactor.MarkRead
import com.wanderwildwood.kotozute.interactor.MarkUnarchived
import com.wanderwildwood.kotozute.interactor.MarkUnpinned
import com.wanderwildwood.kotozute.interactor.MarkUnread
import com.wanderwildwood.kotozute.interactor.MigratePreferences
import com.wanderwildwood.kotozute.interactor.SpeakThreads
import com.wanderwildwood.kotozute.interactor.SyncContacts
import com.wanderwildwood.kotozute.interactor.SyncMessages
import com.wanderwildwood.kotozute.listener.ContactAddedListener
import com.wanderwildwood.kotozute.manager.PermissionManager
import com.wanderwildwood.kotozute.model.Conversation
import com.wanderwildwood.kotozute.model.EmojiSyncNeeded
import com.wanderwildwood.kotozute.model.SyncLog
import com.wanderwildwood.kotozute.repository.ConversationRepository
import io.realm.RealmResults
import com.wanderwildwood.kotozute.repository.EmojiReactionRepository
import com.wanderwildwood.kotozute.repository.MessageRepository
import com.wanderwildwood.kotozute.repository.SyncRepository
import com.wanderwildwood.kotozute.feature.conversations.InboxItem
import com.wanderwildwood.kotozute.model.SignalThread
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.util.Preferences
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.schedulers.Schedulers
import io.realm.Realm
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class MainViewModel @Inject constructor(
    contactAddedListener: ContactAddedListener,
    markAllSeen: MarkAllSeen,
    migratePreferences: MigratePreferences,
    syncRepository: SyncRepository,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val deleteConversations: DeleteConversations,
    private val markArchived: MarkArchived,
    private val markPinned: MarkPinned,
    private val markRead: MarkRead,
    private val markUnarchived: MarkUnarchived,
    private val markUnpinned: MarkUnpinned,
    private val markUnread: MarkUnread,
    private val speakThreads: SpeakThreads,
    private val navigator: Navigator,
    private val permissionManager: PermissionManager,
    private val prefs: Preferences,
    private val reactions: EmojiReactionRepository,
    private val signalRepo: SignalRepository,
    private val syncContacts: SyncContacts,
    private val syncMessages: SyncMessages
) : QkViewModel<MainView, MainState>(
    MainState(page = Inbox(
        filter = prefs.conversationFilter.get()
    ))
) {
    private var lastArchivedThreadIds = listOf<Long>(0)

    // The inbox is merged from two live Realm collections. Signal cannot share Conversation
    // -- a full sync empties that table -- so the rails are joined here and re-merged
    // whenever either side changes.
    private var smsResults: RealmResults<Conversation>? = null
    private var signalResults: RealmResults<SignalThread>? = null
    private var showingArchived = false

    private val smsListener =
        io.realm.RealmChangeListener<RealmResults<Conversation>> { refreshInbox() }
    private val signalListener =
        io.realm.RealmChangeListener<RealmResults<SignalThread>> { refreshInbox() }

    private fun bindInbox(archived: Boolean): List<InboxItem> {
        showingArchived = archived
        smsResults?.removeAllChangeListeners()
        smsResults = conversationRepo.getConversations(prefs.unreadAtTop.get(), archived)
            .also { it.addChangeListener(smsListener) }
        signalResults?.removeAllChangeListeners()
        signalResults = signalRepo.getThreads(archived)
            .also { it.addChangeListener(signalListener) }
        val items = mergedInbox()
        newState {
            when (val p = page) {
                is Inbox -> copy(page = p.copy(data = items))
                is Archived -> copy(page = p.copy(data = items))
                else -> this
            }
        }
        return items
    }

    /**
     * Search both rails, in the order the SMS side already used: everything that matched by
     * name first, then everything that matched inside a conversation, most matches first.
     *
     * Signal is searched only when it is on and the two lists are woven. Kept apart, this
     * list is the SMS list and returning Signal hits in it would contradict the setting.
     */
    private fun searchBothRails(query: CharSequence): List<InboxSearchResult> {
        val sms = conversationRepo.searchConversations(query).map(InboxSearchResult::Sms)
        if (!prefs.signalEnabled.get() || !prefs.signalWeave.get()) return sms
        val signal = signalRepo.searchThreads(query.toString()).map {
            InboxSearchResult.Signal(it.thread, it.messages, it.snippet)
        }
        return (sms + signal).sortedWith(compareBy({ it.messages > 0 }, { -it.messages }))
    }

    private fun markEverythingRead() {
        val items = mergedInbox().filter(::isUnread)
        val smsIds = items.filterIsInstance<InboxItem.Sms>().map { it.conversation.id }
        if (smsIds.isNotEmpty()) markRead.execute(smsIds)
        // Signal threads are marked up to now rather than by id: the bridge's read receipt
        // is "everything in this thread up to this moment", which is what this means.
        val now = System.currentTimeMillis()
        items.filterIsInstance<InboxItem.Signal>().forEach { item ->
            signalRepo.markRead(item.thread.threadKey, now)
        }
    }

    private fun mergedInbox(): List<InboxItem> {
        val sms = smsResults
            ?.takeIf { it.isValid && it.isLoaded }
            ?.map { InboxItem.Sms(it) }
            .orEmpty()
        // The archive shelf shows archived Signal threads, not none. Offering to archive
        // one while having nowhere to see it again would simply lose the conversation.
        val signal = when {
            !prefs.signalEnabled.get() -> emptyList()
            // Kept out of this list on purpose; they have their own, reached by the badge.
            !prefs.signalWeave.get() -> emptyList()
            else -> signalResults
                ?.takeIf { it.isValid && it.isLoaded }
                ?.map { InboxItem.Signal(it) }
                .orEmpty()
        }
        // Pinned first on both rails, then newest. The SMS list has always sorted this
        // way; before this the merged list dropped the pin the moment Signal was woven in.
        return (sms + signal).sortedWith(
            compareByDescending<InboxItem> { it.pinned }.thenByDescending { it.sortDate }
        )
    }

    /** Unread on either rail; the two carry it differently. */
    private fun isUnread(item: InboxItem): Boolean = when (item) {
        is InboxItem.Sms -> item.conversation.unread
        is InboxItem.Signal -> item.thread.unread > 0
    }

    private fun refreshInbox() {
        val items = mergedInbox()
        val anyUnread = items.any(::isUnread)
        // One emission, not two. Every state change redraws the list, and on e-ink a
        // needless full-panel repaint is the expensive kind of mistake.
        newState {
            when (val p = page) {
                is Inbox -> copy(hasUnread = anyUnread, page = p.copy(data = items))
                is Archived -> copy(hasUnread = anyUnread, page = p.copy(data = items))
                else -> copy(hasUnread = anyUnread)
            }
        }
    }

    init {
        // The initial state cannot build this: the list is merged from two collections.
        newState { copy(page = Inbox(filter = prefs.conversationFilter.get(), data = bindInbox(false))) }

        disposables += prefs.signalEnabled.asObservable()
                .subscribe { refreshInbox() }

        disposables += prefs.signalWeave.asObservable()
                .subscribe { refreshInbox() }

        disposables += io.reactivex.Observable.combineLatest(
                prefs.signalEnabled.asObservable(),
                prefs.signalWeave.asObservable()
        ) { enabled, weave -> enabled && !weave }
                .distinctUntilChanged()
                .subscribe { separate -> newState { copy(separateSignalList = separate) } }

        // Bind whichever shelf the page is showing. Leaving Archived by a route that does
        // not rebind used to leave its rows on an inbox that no longer contained them;
        // following the page is one rule instead of one per exit path.
        disposables += state
                .map { it.page is Archived }
                .distinctUntilChanged()
                .subscribe { archived -> if (archived != showingArchived) bindInbox(archived) }

        disposables += deleteConversations
        disposables += markAllSeen
        disposables += markArchived
        disposables += markUnarchived
        disposables += migratePreferences
        disposables += syncContacts
        disposables += syncMessages

        // Show the syncing UI
        disposables += syncRepository.syncProgress
                .sample(16, TimeUnit.MILLISECONDS)
                .distinctUntilChanged()
                .subscribe { syncing -> newState { copy(syncing = syncing) } }

        // Show the rating UI
        // Migrate the preferences from 2.7.3
        migratePreferences.execute(Unit)


        // If we have all permissions and we've never run a sync, run a sync. This will be the case
        // when upgrading from 2.7.3, or if the app's data was cleared
        val lastSync = Realm.getDefaultInstance().use { realm -> realm.where(SyncLog::class.java)?.max("date") ?: 0 }
        if (lastSync == 0 && permissionManager.isDefaultSms() && permissionManager.hasReadSms() && permissionManager.hasContacts()) {
            syncMessages.execute(Unit)
        }

        // This is only used when we update to a version that newly supports emoji reactions
        Realm.getDefaultInstance().executeTransactionAsync { realm ->
            val emojiSyncNeeded = realm.where(EmojiSyncNeeded::class.java).findFirst()
            if (emojiSyncNeeded != null) {
                reactions.deleteAndReparseAllEmojiReactions(realm)
                emojiSyncNeeded.deleteFromRealm()
            }
        }

        // Sync contacts when we detect a change
        if (permissionManager.hasContacts()) {
            disposables += contactAddedListener.listen()
                    .debounce(1, TimeUnit.SECONDS)
                    .subscribeOn(Schedulers.io())
                    .subscribe { syncContacts.execute(Unit) }
        }

        markAllSeen.execute(Unit)
    }

    override fun bindView(view: MainView) {
        super.bindView(view)

        // The SMS permissions are granted by the role, not by us -- dumpsys shows them
        // GRANTED_BY_ROLE on the default app -- so taking the role is still the best first
        // question and the only one worth asking here. But it used to be the ONLY question:
        // this was a single `when`, and on a phone where we are not the default app its
        // first branch swallowed the second, so we never asked for READ_SMS or contacts
        // either. Then the first tap on a conversation read the SMS provider without them
        // and the SecurityException took the whole app down. Hence the second ask below,
        // once the role screen has closed.
        if (!permissionManager.isDefaultSms()) {
            view.requestDefaultSms()
        } else if (!permissionManager.hasReadSms() || !permissionManager.hasContacts()) {
            view.requestPermissions()
        }


        // when unreadAtTop preference changes, reload the model view data to refresh view
        prefs.unreadAtTop.asObservable()
            .skip(1)
            .debounce(400, TimeUnit.MILLISECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .withLatestFrom(state) { _, state ->
                if (state.page is Inbox)
                    newState {
                        copy(page = Inbox(filter = prefs.conversationFilter.get(), data = bindInbox(false)))
                    }
                else if (state.page is Archived)
                    newState {
                        copy(page = Inbox(data = bindInbox(false)))
                    }
            }
            .autoDisposable(view.scope())
            .subscribe()

        // If the default SMS state changes, reflect it in the State
        view.activityResumedIntent
            .filter { resumed -> resumed }
            .observeOn(Schedulers.io())
            .map { permissionManager.isDefaultSms() }
            .distinctUntilChanged()
            .doOnNext { defaultSms -> newState { copy(defaultSms = defaultSms) } }
            .autoDisposable(view.scope())
            .subscribe()

        // Ask for the permissions ourselves if the role didn't bring them. The first resume
        // is our own, underneath the role screen we just opened, so it is skipped; the next
        // one is the user coming back from it. Asked once per screen, because a second
        // dialog after a considered "no" is nagging, and the banner along the bottom is
        // still there to be tapped.
        var askedForPermissions = false
        view.activityResumedIntent
            .filter { resumed -> resumed }
            .skip(1)
            .filter { !askedForPermissions }
            .filter { !permissionManager.hasReadSms() || !permissionManager.hasContacts() }
            .doOnNext { askedForPermissions = true }
            .autoDisposable(view.scope())
            .subscribe { view.requestPermissions() }

        // If the SMS Permission state changes, reflect it in the State
        view.activityResumedIntent
            .filter { resumed -> resumed }
            .observeOn(Schedulers.io())
            .map { permissionManager.hasReadSms() }
            .distinctUntilChanged()
            .doOnNext { smsPermission -> newState { copy(smsPermission = smsPermission) } }
            .autoDisposable(view.scope())
            .subscribe()

        // If the Contacts Permission state changes, reflect it in the State
        view.activityResumedIntent
            .filter { resumed -> resumed }
            .observeOn(Schedulers.io())
            .map { permissionManager.hasContacts() }
            .distinctUntilChanged()
            .doOnNext { contactPermission -> newState { copy(contactPermission = contactPermission) } }
            .autoDisposable(view.scope())
            .subscribe()

        // If the Notifications Permission state changes, reflect it in the State
        view.activityResumedIntent
            .filter { resumed -> resumed }
            .observeOn(Schedulers.io())
            .map { permissionManager.hasNotifications() }
            .distinctUntilChanged()
            .doOnNext { notificationPermission -> newState { copy(notificationPermission = notificationPermission) } }
            .autoDisposable(view.scope())
            .subscribe()

        // If we go from not having all SMS permissions to having them, sync messages
        view.activityResumedIntent
            .filter { resumed -> resumed }
            .observeOn(Schedulers.io())
            .map { permissionManager.isDefaultSms() && permissionManager.hasReadSms() && permissionManager.hasContacts() }
            .distinctUntilChanged()
            .skip(1)
            .filter { hasAllPermissions -> hasAllPermissions }
            .autoDisposable(view.scope())
            .subscribe { syncMessages.execute(Unit) }

        // Launch screen from intent
        view.onNewIntentIntent
                .autoDisposable(view.scope())
                .subscribe { intent ->
                    when {
                        intent.getBooleanExtra("showArchived", false) -> {
                            newState { copy(page = Archived(data = bindInbox(true))) }
                        }
                        intent.getStringExtra("screen") == "compose" -> {
                            navigator.showConversation(intent.getLongExtra("threadId", 0))
                        }
                        intent.getStringExtra("screen") == "blocking" -> {
                            navigator.showBlockedConversations()
                        }
                    }
                }

        view.queryChangedIntent
                .debounce(200, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .map { query -> query.trim() }
                .withLatestFrom(state) { query, state ->
                    if (query.isEmpty() && state.page is Searching) {
                        newState { copy(page = Inbox(filter = prefs.conversationFilter.get(), data = bindInbox(false))) }
                    }
                    query
                }
                .filter { query -> query.length >= 2 }
                .distinctUntilChanged()
                .doOnNext {
                    newState {
                        val page = (page as? Searching) ?: Searching()
                        copy(page = page.copy(loading = true))
                    }
                }
                .observeOn(Schedulers.io())
                .map(::searchBothRails)
                .autoDisposable(view.scope())
                .subscribe { data -> newState { copy(page = Searching(loading = false, data = data)) } }

        view.activityResumedIntent
                .filter { resumed -> !resumed }
                .switchMap {
                    // Take until the activity is resumed
                    prefs.keyChanges
                            .filter { key -> key.contains("theme") }
                            .map { true }
                            .mergeWith(prefs.autoColor.asObservable().skip(1))
                            .doOnNext { view.themeChanged() }
                            .takeUntil(view.activityResumedIntent.filter { resumed -> resumed })
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.composeIntent
                .autoDisposable(view.scope())
                .subscribe { navigator.showCompose() }

        view.settingsIntent
                .autoDisposable(view.scope())
                .subscribe { navigator.showSettings() }

        // Handle filter changes
        view.filterChangedIntent
                .doOnNext { filter ->
                    prefs.conversationFilter.set(filter)
                    newState {
                        val page = (page as? Inbox) ?: Inbox()
                        copy(page = page.copy(filter = filter, data = mergedInbox()))
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        // Where every back goes from anywhere that is not the inbox. Written once because
        // the arrow and the hardware key have to agree, and they stopped agreeing the last
        // time this was two copies.
        fun showInbox() {
            val filter = prefs.conversationFilter.get()
            newState {
                copy(page = Inbox(filter = filter, data = mergedInbox()))
            }
        }

        view.homeIntent
                .withLatestFrom(state) { _, state ->
                    when {
                        state.page is Searching -> view.clearSearch()
                        state.page is Inbox && state.page.selected > 0 -> view.clearSelection()
                        state.page is Archived && state.page.selected > 0 -> view.clearSelection()
                        // The back arrow Archived draws in its corner, and it goes to the
                        // inbox. It used to go to Settings, on the reasoning that Settings is
                        // where Archived is reached from — which is true and still made a trap:
                        // Settings opens Archived, Archived went back to Settings, and there was
                        // no way to the messages from either. Archived is a page of this screen
                        // rather than a child of Settings, so back out of it is back to the list.
                        state.page is Archived -> showInbox()
                        else -> Unit
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        // The back press, and nothing else. The other destinations this used to carry were the
        // drawer's rows, and they are reached from the Settings screen now.
        view.navigationIntent
                .withLatestFrom(state) { _, state ->
                    when {
                        state.page is Searching -> view.clearSearch()
                        state.page is Inbox && state.page.selected > 0 -> view.clearSelection()
                        state.page is Archived && state.page.selected > 0 -> view.clearSelection()
                        // Archived included: the same place the arrow in the corner goes. A
                        // screen whose two backs disagree is a defect, and one whose two backs
                        // agree on somewhere you cannot leave is a worse one.
                        state.page !is Inbox -> showInbox()
                        // Already at the root with nothing to dismiss: let the system have it.
                        else -> newState { copy(hasError = true) }
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        // Mark all read, both rails. The SMS side goes through the same interactor a
        // selection would, so notifications and the badge are updated the one way; the
        // Signal side goes straight to its repository.
        //
        // Stays on the main thread. The inbox is RealmResults belonging to this thread's
        // Realm, and reading it anywhere else throws "Realm accessed from incorrect
        // thread" -- which, with no onError handler, is swallowed into System.err and
        // leaves a menu item that silently does nothing. Both writers move themselves off:
        // the interactor schedules its own work, and SignalRepository.markRead runs off
        // the looper internally.
        view.optionsItemIntent
            .filter { itemId -> itemId == R.id.markAllRead }
            .autoDisposable(view.scope())
            .subscribe { markEverythingRead() }

        view.optionsItemIntent
            .filter { itemId -> itemId == R.id.select_all }
            .autoDisposable(view.scope())
            .subscribe { view.toggleSelectAll() }

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.archive }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markArchived.execute(conversations)
                    lastArchivedThreadIds = conversations.toList()
                    view.showArchivedSnackbar(lastArchivedThreadIds.count(), true)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.unarchive }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markUnarchived.execute(conversations.toList())
                    view.showArchivedSnackbar(conversations.count(), false)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.delete }
                .filter { permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() } }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    view.showDeleteDialog(conversations)
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.add }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations -> conversations }
                .doOnNext { view.clearSelection() }
                .filter { conversations -> conversations.size == 1 }
                .map { conversations -> conversations.first() }
                .mapNotNull(conversationRepo::getConversation)
                .map { conversation -> conversation.recipients }
                .mapNotNull { recipients -> recipients[0]?.address?.takeIf { recipients.size == 1 } }
                .doOnNext(navigator::addContact)
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.pin }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markPinned.execute(conversations.toList())
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.unpin }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markUnpinned.execute(conversations.toList())
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        // Muting a conversation is turning its notifications off, which this app has
        // always been able to do -- it was three screens down, under the conversation's
        // own notification settings, where nobody would think to look for something they
        // wanted to do in a hurry. Same switch, reachable from the selection.
        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.mute || itemId == R.id.unmute }
                .withLatestFrom(view.conversationsSelectedIntent) { itemId, conversations ->
                    val notify = itemId == R.id.unmute
                    conversations.forEach { id -> prefs.notifications(id).set(notify) }
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.read }
                .filter { permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() } }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markRead.execute(conversations.toList())
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.unread }
                .filter { permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() } }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    markUnread.execute(conversations.toList())
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
                .filter { itemId -> itemId == R.id.block }
                .withLatestFrom(view.conversationsSelectedIntent) { _, conversations ->
                    view.showBlockingDialog(conversations.toList(), true)
                    view.clearSelection()
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.optionsItemIntent
            .filter { itemId -> itemId == R.id.rename }
            .withLatestFrom(view.conversationsSelectedIntent) { _, conversationIds -> conversationIds.first() }
            .mapNotNull { conversationId -> conversationRepo.getConversation(conversationId) }
            .autoDisposable(view.scope())
            .subscribe { conversation -> view.showRenameDialog(conversation.name) }

        view.conversationsSelectedIntent
                .withLatestFrom(state) { selection, state ->
                    val conversations = selection.mapNotNull(conversationRepo::getConversation)
                    val add = conversations.firstOrNull()
                            ?.takeIf { conversations.size == 1 }
                            ?.takeIf { conversation -> conversation.recipients.size == 1 }
                            ?.recipients?.first()
                            ?.takeIf { recipient -> recipient.contact == null } != null
                    val pin = conversations.sumBy { if (it.pinned) -1 else 1 } >= 0
                    // Offer whichever the selection mostly is not, the same way pin does.
                    val mute = conversations.sumBy {
                        if (prefs.notifications(it.id).get()) 1 else -1
                    } >= 0
                    val read = when (conversations.size) {
                        0    -> false
                        1    -> conversations[0].unread
                        else -> true
                    }
                    val selected = selection.size

                    when (state.page) {
                        is Inbox -> {
                            val page = state.page.copy(addContact = add, markPinned = pin, markMuted = mute, markRead = read, selected = selected)
                            newState { copy(page = page) }
                        }

                        is Archived -> {
                            val page = state.page.copy(addContact = add, markPinned = pin, markMuted = mute, markRead = read, selected = selected)
                            newState { copy(page = page) }
                        }

                        is Searching -> {} // Ignore
                        else -> {}
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        // Delete the conversation
        view.confirmDeleteIntent
                .autoDisposable(view.scope())
                .subscribe { conversations ->
                    deleteConversations.execute(conversations.toList())
                    view.clearSelection()
                }

        view.renameConversationIntent
            .withLatestFrom(view.conversationsSelectedIntent) { newConversationName, selectedConversationIds ->
                Pair(newConversationName, selectedConversationIds.first())
            }
            .doOnNext { view.clearSelection() }
            .map { newNameAndConversationId ->
                conversationRepo.setConversationName(
                    newNameAndConversationId.second,
                    newNameAndConversationId.first
                )
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
            }
            .flatMapCompletable { it }
            .autoDisposable(view.scope())
            .subscribe()

        view.swipeConversationIntent
                .autoDisposable(view.scope())
                .subscribe { (threadId, direction) ->
                    val action =
                        if (direction == ItemTouchHelper.RIGHT) prefs.swipeRight.get()
                        else prefs.swipeLeft.get()
                    when (action) {
                        Preferences.SWIPE_ACTION_ARCHIVE ->
                            markArchived.execute(listOf(threadId)) {
                                lastArchivedThreadIds = listOf(threadId)
                                view.showArchivedSnackbar(1, true)
                            }
                        Preferences.SWIPE_ACTION_DELETE ->
                            view.showDeleteDialog(listOf(threadId))
                        Preferences.SWIPE_ACTION_BLOCK ->
                            view.showBlockingDialog(listOf(threadId), true)
                        Preferences.SWIPE_ACTION_CALL -> {
                            (
                                messageRepo.getMessagesSync(threadId).lastOrNull { !it.isMe() }
                                    ?.address // most recent non-me msg address
                                ?: conversationRepo.getConversation(threadId)
                                    ?.recipients?.firstOrNull()?.address  // first recipient in convo
                            )?.let(navigator::makePhoneCall)
                        }
                        Preferences.SWIPE_ACTION_READ -> markRead.execute(listOf(threadId))
                        Preferences.SWIPE_ACTION_UNREAD -> markUnread.execute(listOf(threadId))
                        Preferences.SWIPE_ACTION_SPEAK -> speakThreads.execute(listOf(threadId))
                    }
                }

        view.undoArchiveIntent
                .autoDisposable(view.scope())
                .subscribe {
                    markUnarchived.execute(lastArchivedThreadIds.toList())
                    lastArchivedThreadIds = listOf()
                }

        view.snackbarButtonIntent
                .withLatestFrom(state) { _, state ->
                    when {
                        !state.defaultSms -> view.requestDefaultSms()
                        !state.smsPermission -> view.requestPermissions()
                        !state.contactPermission -> view.requestPermissions()
                        !state.notificationPermission -> {
                            if (prefs.hasAskedForNotificationPermission.get()) {
                                navigator.showPermissions()
                            } else {
                                prefs.hasAskedForNotificationPermission.set(true)
                                view.requestPermissions()
                            }
                        }
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()
    }

}