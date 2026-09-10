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

    /**
     * How much of a service id to show when there is no name and no number for someone.
     * Enough to tell two people apart in a list, short enough not to read as a message.
     */
    const val SHORT_SERVICE_ID = 8

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
    fun merge(
        threads: List<Row>,
        contacts: List<Row>,
        selfAci: String?
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

        return known.map { uuid ->
            val number = numbers[uuid].orEmpty()
            SignalRepository.Person(
                threadKey = "direct:$uuid",
                // Never blank. A name, then the number, then the service id shortened -- the
                // same order the inbox falls back through, so one person does not read as two
                // different people depending on which list they are met in.
                name = names[uuid] ?: number.ifBlank { uuid.take(SHORT_SERVICE_ID) },
                number = number
            )
        }.sortedBy { person -> person.name.lowercase() }
    }
}
