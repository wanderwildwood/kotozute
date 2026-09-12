package com.wanderwildwood.kotozute.repository

/**
 * Who this account can be written to, merged out of the two places that know.
 *
 * There have been two rails, and they learn about people differently. A bridge wrote a
 * thread for every contact signal-cli knew, so its directory is in Realm with no messages in
 * it. A linked device is told who people are by the primary's contacts sync and writes a
 * thread only once something has been said, so its directory is the contact store. One
 * account can have been both in its time, and neither source is the whole of it.
 *
 * Kept apart from the repository so the merging rules can be read, and tested, without a
 * Realm and an account.
 */
internal object SignalDirectory {

    /**
     * One person as one source knows them. [name] and [number] are blank where that source
     * has nothing to say -- which is the ordinary case for a linked device, where a name
     * only arrives if somebody's client has shared a profile key.
     */
    data class Row(val uuid: String, val name: String, val number: String)

    /** Shared with the inbox and the notifications; see [com.wanderwildwood.kotozute.signal.SignalName]. */
    const val SHORT_SERVICE_ID = com.wanderwildwood.kotozute.signal.SignalName.SHORT_SERVICE_ID

    /**
     * [threads] first: a thread's title is either a name this phone's own address book
     * supplied or one the bridge resolved, and both are nearer to what the reader calls this
     * person than whatever the primary's contacts sync happened to carry. [contacts] fills
     * the gaps and adds everyone who has never been written to, which is the whole point of
     * the list.
     *
     * [selfAci] is dropped from [contacts] but not from [threads]: Note to Self is a real
     * conversation and reads as itself, while the same account arriving from the contacts
     * sync would appear a second time under the account holder's own name, which reads as a
     * stranger who happens to share it.
     */
    /**
     * Whether to offer, once, to read the account's contact list.
     *
     * Three conditions, and all of them matter. [linkedDirectly] because a paired bridge
     * resolves names on its own side and has no use for the account's key material.
     * [storageKeyKnown] because once the list has been read there is nothing to offer.
     * [anyNamesKnown] because a phone whose primary answered the ordinary contacts sync is
     * already showing names, and offering to fetch something it has is noise.
     *
     * Kept here, away from the database and the account, because the cost of getting it
     * wrong is a dialog in front of somebody who did not need it -- which is the thing a
     * one-off prompt cannot afford to do.
     */
    fun shouldOfferContactFetch(
        linkedDirectly: Boolean,
        storageKeyKnown: Boolean,
        anyNamesKnown: Boolean
    ): Boolean = linkedDirectly && !storageKeyKnown && !anyNamesKnown

    /**
     * [nameForNumber] is the reader's own address book. Consulted only where neither source
     * supplied a name, and only where a number is known -- on a linked device that is the
     * difference between a list of service ids and a list of people.
     */
    fun merge(
        threads: List<Row>,
        contacts: List<Row>,
        selfAci: String?,
        nameForNumber: (String) -> String? = { null }
    ): List<SignalRepository.Person> {
        val names = mutableMapOf<String, String>()
        val numbers = mutableMapOf<String, String>()
        val known = linkedSetOf<String>()

        fun take(row: Row) {
            if (row.uuid.isBlank()) return
            known += row.uuid
            if (row.name.isNotBlank() && names[row.uuid].isNullOrBlank()) names[row.uuid] = row.name
            if (row.number.isNotBlank() && numbers[row.uuid].isNullOrBlank()) {
                numbers[row.uuid] = row.number
            }
        }

        threads.forEach(::take)
        contacts.forEach { row -> if (row.uuid != selfAci) take(row) }

        val named = mutableSetOf<String>()
        return known.map { uuid ->
            val number = numbers[uuid].orEmpty()
            // Whether anybody has a name for this person, from either side. Kept because it
            // decides the order, and because "the display name happens not to start with a
            // digit" is not the same question.
            val called = names[uuid]
                ?: number.ifBlank { uuid.takeIf { it.startsWith("+") }.orEmpty() }
                    .takeIf { it.isNotBlank() }?.let(nameForNumber)
            if (!called.isNullOrBlank()) named += "direct:$uuid"
            SignalRepository.Person(
                threadKey = "direct:$uuid",
                // Never blank. A name, then the number, then the service id shortened -- the
                // same order the inbox falls back through, so one person does not read as two
                // different people depending on which list they are met in.
                // A counterpart that is itself a number is one to look up too: those rows
                // have nothing else to go on, and a number is exactly what an address book
                // answers.
                name = com.wanderwildwood.kotozute.signal.SignalName.of(
                    name = called.orEmpty(),
                    number = number,
                    serviceId = uuid
                ),
                number = number
            )
        }
            // Ordered by whether anybody actually has a name for them, which the display name
            // cannot be asked: what stands in for a missing name is a phone number or a
            // shortened service id, and neither is distinguishable from a name by looking at
            // it -- a number beginning "+" is not even a digit to sort on.
            .sortedWith(
                // People with names first, then everyone else, each alphabetical.
                //
                // Sorting on the display name alone opened this list on every person nobody
                // has a name for, because a phone number sorts before letters -- a list meant
                // for finding somebody, showing first the rows nobody can recognise. They are
                // still here and still in order, underneath the people who can be told apart.
                compareBy<SignalRepository.Person> { it.threadKey !in named }
                    .thenBy { it.name.lowercase() }
            )
    }
}
