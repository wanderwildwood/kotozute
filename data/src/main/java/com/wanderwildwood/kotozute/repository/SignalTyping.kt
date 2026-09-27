package com.wanderwildwood.kotozute.repository

import android.os.Handler
import android.os.Looper
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Typing indicators, both ways.
 *
 * Signal Android's `TypingStatusRepository` for what others are doing and `TypingStatusSender`
 * for what we are, with their timings: somebody is shown typing for fifteen seconds after the
 * last word from them, and our own "typing" is sent on the first keystroke, again every ten
 * seconds while it goes on, and "stopped" three seconds after it pauses. Nothing is stored;
 * a phone that restarts has simply forgotten who was typing, as Signal has.
 *
 * [enabled] is the account's setting, and turns off both directions at once, as upstream's
 * does: somebody who does not share their own typing does not see anybody else's.
 */
internal class SignalTyping(
    private val enabled: () -> Boolean,
    /** Sends one "started" or "stopped" for a thread. Off the main thread; may block. */
    private val send: (threadKey: String, started: Boolean) -> Unit,
    /** Whether typing may be sent in a thread at all: not blocked, not Note to Self. */
    private val mayTell: (threadKey: String) -> Boolean
) {
    private val main = Handler(Looper.getMainLooper())
    private val sending = Executors.newSingleThreadExecutor { r -> Thread(r, "signal-typing").apply { isDaemon = true } }

    // ---- others ----

    /** Thread key -> who is typing there -> when that lapses. */
    private val typists = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val changed = PublishSubject.create<String>()

    /** Who is typing in [threadKey], by service id, now and whenever it changes. */
    fun typing(threadKey: String): Observable<List<String>> =
        changed.filter { it == threadKey }
            .startWith(threadKey)
            .map { now(threadKey) }
            .distinctUntilChanged()

    private fun now(threadKey: String): List<String> {
        val at = System.currentTimeMillis()
        return typists[threadKey].orEmpty().filterValues { it > at }.keys.sorted()
    }

    /** A typing message arrived. */
    fun received(threadKey: String, sender: String, started: Boolean) {
        if (!enabled()) return
        val map = typists.getOrPut(threadKey) { ConcurrentHashMap() }
        if (started) {
            map[sender] = System.currentTimeMillis() + RECIPIENT_TYPING_TIMEOUT
            // Re-read once it should have lapsed; a later refresh has pushed it further out
            // by then, and the re-read finds them still typing.
            main.postDelayed({ changed.onNext(threadKey) }, RECIPIENT_TYPING_TIMEOUT + 50)
        } else {
            map.remove(sender)
        }
        changed.onNext(threadKey)
    }

    /** A message arrived from [sender]: whatever they were typing, they have sent it. */
    fun messageFrom(threadKey: String, sender: String) {
        if (typists[threadKey]?.remove(sender) != null) changed.onNext(threadKey)
    }

    // ---- ours ----

    private class Timers(var start: Runnable? = null, var stop: Runnable? = null)
    private val ours = HashMap<String, Timers>()

    /** The reader changed the draft in [threadKey]. Main thread. */
    fun composing(threadKey: String) {
        if (!enabled() || !mayTell(threadKey)) return
        val timers = ours.getOrPut(threadKey) { Timers() }
        if (timers.start == null) {
            tell(threadKey, true)
            val refresh = object : Runnable {
                override fun run() {
                    tell(threadKey, true)
                    main.postDelayed(this, REFRESH_TYPING_TIMEOUT)
                }
            }
            main.postDelayed(refresh, REFRESH_TYPING_TIMEOUT)
            timers.start = refresh
        }
        timers.stop?.let { main.removeCallbacks(it) }
        val stop = Runnable { stopped(threadKey, notify = true) }
        main.postDelayed(stop, PAUSE_TYPING_TIMEOUT)
        timers.stop = stop
    }

    /**
     * The reader stopped: the draft was cleared, or they left. [notify] is false after a
     * send, as upstream's is -- the message itself tells the other side typing has ended.
     */
    fun stopped(threadKey: String, notify: Boolean) {
        val timers = ours[threadKey] ?: return
        timers.start?.let {
            main.removeCallbacks(it)
            if (notify) tell(threadKey, false)
        }
        timers.stop?.let { main.removeCallbacks(it) }
        ours.remove(threadKey)
    }

    private fun tell(threadKey: String, started: Boolean) {
        sending.execute { runCatching { send(threadKey, started) } }
    }

    private companion object {
        const val REFRESH_TYPING_TIMEOUT = 10_000L
        const val PAUSE_TYPING_TIMEOUT = 3_000L
        const val RECIPIENT_TYPING_TIMEOUT = 15_000L
    }
}
