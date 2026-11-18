package com.wanderwildwood.kotozute.interactor

import java.util.Collections

/**
 * Scheduled messages open in the compose screen for editing, in this process.
 *
 * ⚠ While one is open it is not sent, by its alarm or by the catch-up that sends past-due
 * messages (which scheduling the edited copy runs). The original stays on the schedule until
 * the edited copy is on its way, so a compose screen that fails or is left loses nothing; but
 * sent during the edit, the original and then the edited copy would both have gone. Once the
 * edit ends without a copy, the catch-up runs again and the original goes as it would have.
 *
 * In memory on purpose: a process that dies mid-edit forgets the edit, and the original's
 * alarm sends it as though the edit had never been started.
 */
object ScheduledEdits {

    private val open: MutableSet<Long> = Collections.synchronizedSet(HashSet())

    fun begin(id: Long) {
        if (id >= 0) open.add(id)
    }

    fun end(id: Long) {
        open.remove(id)
    }

    fun isOpen(id: Long): Boolean = id in open
}
