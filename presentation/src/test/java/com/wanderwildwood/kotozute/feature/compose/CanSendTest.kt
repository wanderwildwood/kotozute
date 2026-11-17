package com.wanderwildwood.kotozute.feature.compose

import io.reactivex.Observable
import io.reactivex.rxkotlin.Observables
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * Editing a scheduled message opened Compose with the schedule already set, and the screen
 * ran out of memory in a few seconds. The canSend reducer wrote `scheduled` back from the state
 * it was reading, so a stale 0 and the real time took turns for ever.
 *
 * This builds the same store QkViewModel has (reducers observed on one queued thread, folded by
 * scan into a BehaviorSubject) and wires canSend into it the way ComposeViewModel does.
 */
class CanSendTest {

    private data class State(val scheduled: Long = 0, val attachments: Int = 0, val canSend: Boolean = false)

    /** QkViewModel's store on a queue that stands for the main thread. */
    private class Store {
        private val queue = ArrayDeque<Runnable>()
        private val main = Schedulers.from(Executor { queue.add(it) })
        private val reducers = PublishSubject.create<State.() -> State>()
        val state = BehaviorSubject.createDefault(State())
        var applied = 0
        var error: Throwable? = null

        init {
            reducers
                .observeOn(main)
                .scan(State()) { state, reducer ->
                    if (++applied > LIMIT) throw IllegalStateException("state never settles")
                    reducer(state)
                }
                .subscribe(state::onNext) { error = it }
        }

        fun newState(reducer: State.() -> State) = reducers.onNext(reducer)

        fun drain() {
            while (queue.isNotEmpty()) queue.poll()!!.run()
        }
    }

    private val time = 1_900_000_000_000L

    /** Opening Compose from Scheduled: the schedule is set in init, before the view binds. */
    private fun openEditingScheduled(wire: (Store, Observable<CharSequence>) -> Unit): Store {
        val store = Store()
        store.newState { copy(scheduled = time) }
        val text = BehaviorSubject.createDefault<CharSequence>("Lunch at noon, Ada?")
        wire(store, text)
        store.drain()
        return store
    }

    @Test
    fun `editing a scheduled message settles with the time kept and send offered`() {
        val store = openEditingScheduled { store, text ->
            canSendChanges(
                text,
                store.state.distinctUntilChanged { s -> s.attachments }.map { it.attachments }
            ).subscribe { canSend -> store.newState { copy(canSend = canSend) } }
        }

        assertEquals(null, store.error)
        assertTrue("took ${store.applied} reducers", store.applied < 10)
        assertEquals(time, store.state.value!!.scheduled)
        assertTrue(store.state.value!!.canSend)
    }

    @Test
    fun `the old reducer that wrote scheduled back never settles`() {
        // The wiring as it was in 1.35.42, kept here so this harness is shown to catch the loop.
        val store = openEditingScheduled { store, text ->
            Observables.combineLatest(
                text,
                store.state.distinctUntilChanged { s -> s.attachments }.map { it.attachments },
                store.state.distinctUntilChanged { s -> s.scheduled }.map { it.scheduled }
            ).subscribe {
                store.newState { copy(canSend = it.first.isNotBlank() || it.second > 0, scheduled = it.third) }
            }
        }

        assertTrue(store.error is IllegalStateException)
    }

    @Test
    fun `send is offered for text or an attachment, not for blank text alone`() {
        fun can(text: String, attachments: Int) =
            canSendChanges(Observable.just(text), Observable.just(attachments)).blockingFirst()

        assertTrue(can("hi", 0))
        assertTrue(can("", 1))
        assertFalse(can("  \n", 0))
    }

    private companion object {
        const val LIMIT = 1000
    }
}
