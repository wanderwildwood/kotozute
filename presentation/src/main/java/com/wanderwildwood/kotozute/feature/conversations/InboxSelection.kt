package com.wanderwildwood.kotozute.feature.conversations

/**
 * What the inbox's selection holds, and what its bar should offer.
 *
 * Both rails are selectable: a text row by its telephony thread id, a Signal row by its
 * negative stable id (see [InboxItem.signalStableId]). The ids alone cannot reach a Signal
 * thread -- the stable id is a hash -- so each selected row is copied here, once, as the
 * selection changes, while its live Realm object can still be read.
 */
object InboxSelection {

    /** One selected row, read off the list while it was alive. */
    data class Row(
        /** The adapter's stable id: positive for a text row, negative for a Signal one. */
        val id: Long,
        /** The Signal thread, for a Signal row; null for a text row. */
        val signalKey: String? = null,
        /** The text conversation a Signal row also stands for, when the person has one. */
        val joinedId: Long? = null,
        val pinned: Boolean = false,
        val muted: Boolean = false,
        val unread: Boolean = false,
        /** One text conversation with one person who is not in the address book. */
        val canAddContact: Boolean = false,
        /** A Signal row whose text half spoke last, so a tap opens the text conversation. */
        val textIsNewer: Boolean = false
    ) {
        val isSignal: Boolean get() = signalKey != null
    }

    /** The selection, one list per rail, the way each rail's code has to be called. */
    data class Split(
        val sms: List<Long>,
        val signal: List<String>,
        /** Text conversations joined to the selected Signal rows. */
        val joined: List<Long>
    )

    /**
     * Sorts [ids] by rail. A Signal id with no row in [rows] is dropped rather than guessed
     * at: the row has gone since it was selected, and acting on "whatever hashes to this"
     * would be acting on nothing anybody chose.
     */
    fun split(ids: Collection<Long>, rows: Collection<Row>): Split {
        val byId = rows.associateBy { it.id }
        val signalRows = ids.filter { InboxItem.isSignalId(it) }.mapNotNull { byId[it] }
            .filter { it.isSignal }
        return Split(
            sms = ids.filter { it > 0 },
            signal = signalRows.mapNotNull { it.signalKey },
            joined = signalRows.mapNotNull { it.joinedId }.filter { it > 0 }
        )
    }

    /** Where Mark unread lands: these text conversations, and these Signal threads. */
    data class UnreadTargets(val sms: List<Long>, val signal: List<String>)

    /**
     * Mark unread puts back one message per row, the newest one from the other person, and
     * on a Signal row that stands for a text conversation too it has to pick a half.
     *
     * The half a tap opens comes first, because opening it is what reads the mark again; a
     * mark left on the half that does not open would keep the row bold after it was read.
     * When that half has nothing from the other person -- somebody who has Signal but only
     * ever texts back -- the other half takes it. Only when neither has anything from them
     * does the mark go on the half that opens, which is all it could do before.
     *
     * Marking only the Signal half was the bug: a person joined by number whose Signal
     * thread held nothing but our own messages had no message there to mark unread, and the
     * text half was never touched, so Mark unread did nothing at all.
     */
    fun unreadTargets(
        ids: Collection<Long>,
        rows: Collection<Row>,
        textHasIncoming: (Long) -> Boolean,
        signalHasIncoming: (String) -> Boolean
    ): UnreadTargets {
        val byId = rows.associateBy { it.id }
        val sms = mutableListOf<Long>()
        val signal = mutableListOf<String>()
        ids.forEach { id ->
            if (id > 0) {
                sms += id
                return@forEach
            }
            val row = byId[id]?.takeIf { it.isSignal } ?: return@forEach
            val key = row.signalKey!!
            val joined = row.joinedId?.takeIf { it > 0 }
            if (joined == null) {
                signal += key
                return@forEach
            }
            val textFirst = row.textIsNewer
            val firstHas = if (textFirst) textHasIncoming(joined) else signalHasIncoming(key)
            val useText = when {
                firstHas -> textFirst
                (if (textFirst) signalHasIncoming(key) else textHasIncoming(joined)) -> !textFirst
                else -> textFirst
            }
            if (useText) sms += joined else signal += key
        }
        return UnreadTargets(sms.distinct(), signal.distinct())
    }

    /** What the selection bar offers for [rows]. */
    data class Offer(
        /** Pin, rather than Unpin. */
        val pin: Boolean,
        /** Mute, rather than Unmute. */
        val mute: Boolean,
        /** Mark read, rather than Mark unread; with more than one row both are shown. */
        val read: Boolean,
        val addContact: Boolean,
        /** Rename is a text conversation's local name; a Signal thread has none to set. */
        val rename: Boolean
    )

    /**
     * Offers whichever the selection mostly is not, for pin and mute alike, with a tie going
     * to pinning and muting -- the rule the bar has always used for text rows.
     */
    fun offer(rows: List<Row>): Offer = Offer(
        pin = mostlyNot(rows.map { it.pinned }),
        mute = mostlyNot(rows.map { it.muted }),
        read = when (rows.size) {
            0 -> false
            1 -> rows[0].unread
            else -> true
        },
        addContact = rows.size == 1 && rows[0].canAddContact,
        rename = rows.size == 1 && !rows[0].isSignal
    )

    private fun mostlyNot(flags: List<Boolean>): Boolean =
        flags.count { !it } >= flags.count { it }
}
