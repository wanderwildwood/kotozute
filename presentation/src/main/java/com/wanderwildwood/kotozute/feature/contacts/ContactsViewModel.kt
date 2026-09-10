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

import android.view.inputmethod.EditorInfo
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import com.wanderwildwood.kotozute.common.base.QkViewModel
import com.wanderwildwood.kotozute.extensions.mapNotNull
import com.wanderwildwood.kotozute.extensions.removeAccents
import com.wanderwildwood.kotozute.feature.compose.editing.ComposeItem
import com.wanderwildwood.kotozute.feature.compose.editing.PhoneNumberAction
import com.wanderwildwood.kotozute.filter.ContactFilter
import com.wanderwildwood.kotozute.filter.ContactGroupFilter
import com.wanderwildwood.kotozute.interactor.SetDefaultPhoneNumber
import com.wanderwildwood.kotozute.model.Contact
import com.wanderwildwood.kotozute.model.ContactGroup
import com.wanderwildwood.kotozute.model.Conversation
import com.wanderwildwood.kotozute.model.PhoneNumber
import com.wanderwildwood.kotozute.model.Recipient
import com.wanderwildwood.kotozute.repository.ContactRepository
import com.wanderwildwood.kotozute.repository.ConversationRepository
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.util.PhoneNumberUtils
import com.wanderwildwood.kotozute.util.Preferences
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.Observables
import io.reactivex.schedulers.Schedulers
import io.realm.RealmList
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.rx2.awaitFirst
import javax.inject.Inject

class ContactsViewModel @Inject constructor(
    sharing: Boolean,
    serializedChips: HashMap<String, String?>,
    private val contactFilter: ContactFilter,
    private val contactGroupFilter: ContactGroupFilter,
    private val contactsRepo: ContactRepository,
    private val conversationRepo: ConversationRepository,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val prefs: Preferences,
    private val setDefaultPhoneNumber: SetDefaultPhoneNumber,
    private val signalRepo: SignalRepository
) : QkViewModel<ContactsContract, ContactsState>(ContactsState()) {

    private val contactGroups: Observable<List<ContactGroup>> by lazy { contactsRepo.getUnmanagedContactGroups() }
    private val contacts: Observable<List<Contact>> by lazy { contactsRepo.getUnmanagedContacts() }
    private val recents: Observable<List<Conversation>> by lazy {
        if (sharing) conversationRepo.getUnmanagedConversations() else Observable.just(listOf())
    }
    private val starredContacts: Observable<List<Contact>> by lazy { contactsRepo.getUnmanagedContacts(true) }

    /**
     * Everyone reachable on Signal, which is otherwise a list nothing in the app offers: a
     * Signal conversation could only be started by picking someone this phone already had an
     * SMS thread with, so anyone who had never texted was out of reach until they wrote
     * first.
     *
     * Read once, on a worker: the directory is a contacts sync and a Realm read, and it does
     * not move while a name is being typed.
     *
     * Not while sharing. The shared text goes into the SMS composer this screen returns to,
     * and choosing a Signal person leaves that screen for the other rail -- offering it here
     * would be offering to drop what is being shared.
     */
    private val signalPeople: Observable<List<ComposeItem.SignalPerson>> by lazy {
        if (sharing || !prefs.signalEnabled.get()) {
            Observable.just(listOf())
        } else {
            Observable.fromCallable {
                signalRepo.people().map { person ->
                    ComposeItem.SignalPerson(person.threadKey, person.name, person.number)
                }
            }
                    .subscribeOn(Schedulers.io())
                    .onErrorReturnItem(listOf())
                    // Nothing, until it is read. Reading it opens the keystore and the
                    // encrypted store behind it, which is deliberately not done until it is
                    // wanted -- and combineLatest holds every other source until its slowest
                    // one has spoken, so without this the address book waits on Signal.
                    .startWith(listOf<ComposeItem.SignalPerson>())
        }
    }

    /** Chosen from the list; handled apart from the chips, which cannot hold one. */
    private val signalPersonPicked: Subject<ComposeItem.SignalPerson> = PublishSubject.create()

    private val selectedChips = Observable.just(serializedChips)
            .observeOn(Schedulers.io())
            .map { hashmap ->
                hashmap.map { (address, lookupKey) ->
                    Recipient(address = address, contact = lookupKey?.let(contactsRepo::getUnmanagedContact))
                }
            }

    private var shouldOpenKeyboard: Boolean = true

    override fun bindView(view: ContactsContract) {
        super.bindView(view)

        if (shouldOpenKeyboard) {
            view.openKeyboard()
            shouldOpenKeyboard = false
        }

        // Update the state's query, so we know if we should show the cancel button
        view.queryChangedIntent
                .autoDisposable(view.scope())
                .subscribe { query -> newState { copy(query = query.toString()) } }

        // Clear the query
        view.queryClearedIntent
                .autoDisposable(view.scope())
                .subscribe { view.clearQuery() }

        // Update the list of contact suggestions based on the query input, while also filtering out any contacts
        // that have already been selected
        Observables
                .combineLatest(
                        view.queryChangedIntent, recents, starredContacts, contactGroups, contacts, selectedChips,
                        signalPeople
                ) { query, recents, starredContacts, contactGroups, contacts, selectedChips, signalPeople ->
                    val composeItems = mutableListOf<ComposeItem>()
                    if (query.isBlank()) {
                        composeItems += recents
                                .filter { conversation ->
                                    conversation.recipients.any { recipient ->
                                        selectedChips.none { chip ->
                                            if (recipient.contact == null) {
                                                chip.address == recipient.address
                                            } else {
                                                chip.contact?.lookupKey == recipient.contact?.lookupKey
                                            }
                                        }
                                    }
                                }
                                .map(ComposeItem::Recent)

                        composeItems += starredContacts
                                .filter { contact -> selectedChips.none { it.contact?.lookupKey == contact.lookupKey } }
                                .map(ComposeItem::Starred)

                        composeItems += contactGroups
                                .filter { group ->
                                    group.contacts.any { contact ->
                                        selectedChips.none { chip -> chip.contact?.lookupKey == contact.lookupKey }
                                    }
                                }
                                .map(ComposeItem::Group)

                        composeItems += contacts
                                .filter { contact -> selectedChips.none { it.contact?.lookupKey == contact.lookupKey } }
                                .map(ComposeItem::Person)

                        // Last, and after the address book rather than mixed into it. Most
                        // of these people are in that list already under the other rail, and
                        // a list that answers "who can I text" should not be reordered by a
                        // second answer to a different question.
                        composeItems += signalPeople
                    } else {
                        // If the entry is a valid destination, allow it as a recipient
                        if (phoneNumberUtils.isPossibleNumber(query.toString())) {
                            val newAddress = phoneNumberUtils.formatNumber(query)
                            val newContact = Contact(numbers = RealmList(PhoneNumber(address = newAddress)))
                            composeItems += ComposeItem.New(newContact)
                        }

                        // Strip the accents from the query. This can be an expensive operation, so
                        // cache the result instead of doing it for each contact
                        val normalizedQuery = query.removeAccents()
                        composeItems += starredContacts
                                .asSequence()
                                .filter { contact -> selectedChips.none { it.contact?.lookupKey == contact.lookupKey } }
                                .filter { contact -> contactFilter.filter(contact, normalizedQuery) }
                                .map(ComposeItem::Starred)

                        composeItems += contactGroups
                                .asSequence()
                                .filter { group ->
                                    group.contacts.any { contact ->
                                        selectedChips.none { chip -> chip.contact?.lookupKey == contact.lookupKey }
                                    }
                                }
                                .filter { group -> contactGroupFilter.filter(group, normalizedQuery) }
                                .map(ComposeItem::Group)

                        composeItems += contacts
                                .asSequence()
                                .filter { contact -> selectedChips.none { it.contact?.lookupKey == contact.lookupKey } }
                                .filter { contact -> contactFilter.filter(contact, normalizedQuery) }
                                .map(ComposeItem::Person)

                        composeItems += signalPeople
                                .filter { person -> matches(person, query.toString(), normalizedQuery) }
                    }

                    composeItems
                }
                .subscribeOn(Schedulers.computation())
                .autoDisposable(view.scope())
                .subscribe { items -> newState { copy(composeItems = items) } }

        // Listen for ComposeItems being selected, and then send them off to the number picker dialog in case
        // the user needs to select a phone number
        view.queryEditorActionIntent
                .filter { actionId -> actionId == EditorInfo.IME_ACTION_DONE }
                .withLatestFrom(state) { _, state -> state }
                .mapNotNull { state -> state.composeItems.firstOrNull() }
                .mergeWith(view.composeItemPressedIntent)
                .map { composeItem -> composeItem to false }
                .mergeWith(view.composeItemLongPressedIntent.map { composeItem -> composeItem to true })
                // A Signal person is not a recipient: there is no chip that would send to
                // them, and the message goes out over the other rail entirely. Taken out of
                // the stream here rather than subscribed to separately, because a second
                // subscription to the editor action would replace the first one's listener
                // and the keyboard's done key would stop choosing anybody.
                .doOnNext { (composeItem, _) ->
                    (composeItem as? ComposeItem.SignalPerson)?.let(signalPersonPicked::onNext)
                }
                .filter { (composeItem, _) -> composeItem !is ComposeItem.SignalPerson }
                .observeOn(Schedulers.io())
                .map { (composeItem, force) ->
                    HashMap(composeItem.getContacts().associate { contact ->
                        if (contact.numbers.size == 1 || contact.getDefaultNumber() != null && !force) {
                            val address = contact.getDefaultNumber()?.address ?: contact.numbers[0]!!.address
                            address to contact.lookupKey
                        } else {
                            runBlocking {
                                newState { copy(selectedContact = contact) }
                                val action = view.phoneNumberActionIntent.awaitFirst()
                                newState { copy(selectedContact = null) }
                                val numberId = view.phoneNumberSelectedIntent.awaitFirst().value
                                val number = contact.numbers.find { number -> number.id == numberId }

                                if (action == PhoneNumberAction.CANCEL || number == null) {
                                    return@runBlocking null
                                }

                                if (action == PhoneNumberAction.ALWAYS) {
                                    val params = SetDefaultPhoneNumber.Params(contact.lookupKey, number.id)
                                    setDefaultPhoneNumber.execute(params)
                                }

                                number.address to contact.lookupKey
                            } ?: return@map hashMapOf<String, String?>()
                        }
                    })
                }
                .filter { result -> result.isNotEmpty() }
                .observeOn(AndroidSchedulers.mainThread())
                .autoDisposable(view.scope())
                .subscribe { result -> view.finish(result) }

        // Chosen on Signal: leave for their Signal conversation, which exists whether or not
        // anything has been said in it yet.
        signalPersonPicked
                .observeOn(AndroidSchedulers.mainThread())
                .autoDisposable(view.scope())
                .subscribe { person -> view.finishWithSignalThread(person.threadKey, person.name) }
    }

}

/**
 * Whether a Signal person answers to what has been typed. Their name is matched the way a
 * contact's is, and their number by its digits alone -- what is stored is E.164 and what
 * gets typed is rarely written the same way.
 */
internal fun matches(
    person: ComposeItem.SignalPerson,
    query: String,
    normalizedQuery: String
): Boolean {
    if (person.name.removeAccents().contains(normalizedQuery, ignoreCase = true)) return true
    val typed = query.filter { character -> character.isDigit() }
    return typed.isNotEmpty() && person.number.filter { character -> character.isDigit() }.contains(typed)
}
