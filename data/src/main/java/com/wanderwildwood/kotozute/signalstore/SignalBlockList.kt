package com.wanderwildwood.kotozute.signalstore

/**
 * Editing the account's blocked list, and deciding who a reaction is aimed at.
 *
 * Both are one-liners that are wrong in ways nothing reports. A blocked list is sent whole
 * and replaces what the account holds, so an edit that loses a name unblocks somebody — and
 * the only symptom is a message arriving months later from a person who was supposed to be
 * gone. A reaction names the message it is hung on by its author, and an author left empty
 * for our own messages was correct only while a bridge was filling it in.
 *
 * Kept here, away from the network and the database, so both can be tested.
 */
internal object SignalBlockList {

    /**
     * The list as it should stand after blocking or unblocking [aci].
     *
     * Everyone else is carried across untouched, which is the whole point: this is not a
     * delta, it is the replacement.
     */
    fun edit(
        held: List<SignalBlockStore.Blocked>,
        aci: String,
        blocked: Boolean,
        now: Long
    ): List<SignalBlockStore.Blocked> {
        val others = held.filterNot { it.aci.equals(aci, ignoreCase = true) }
        return if (blocked) others + SignalBlockStore.Blocked(aci, null, now) else others
    }

    /**
     * Whose message a reaction is hung on.
     *
     * Ours is named by this account's own service id. The bridge rail left it empty and let
     * signal-cli fill it in; on this rail nobody else knows, and an empty author is a
     * reaction Signal cannot place.
     */
    fun targetAuthor(
        outgoing: Boolean,
        senderUuid: String,
        senderNumber: String,
        selfAci: String
    ): String = when {
        outgoing -> selfAci
        senderUuid.isNotBlank() -> senderUuid
        else -> senderNumber
    }
}
