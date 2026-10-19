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
        val canAddContact: Boolean = false
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
