package com.wanderwildwood.kotozute.repository

/**
 * Which messages of an SMS backup a restore writes: only the ones the phone does not already
 * hold. Restoring onto a phone that still has its messages, or restoring the same file twice,
 * would otherwise give every conversation each message twice.
 *
 * A message is the same message when its address, time, box (received, sent, ...) and text are
 * the same. Those are what Android's SMS provider keeps for every message and what a backup
 * carries; the provider's own ids differ from phone to phone and cannot be used.
 */
object SmsRestore {

    data class Key(val address: String, val date: Long, val type: Int, val body: String)

    fun key(address: String?, date: Long, type: Int, body: String?) =
            Key(address(address.orEmpty()), date, type, body.orEmpty())

    /**
     * A number is compared on its last ten digits, so "+1 555-010-0001" on one phone and
     * "5550100001" on another are one sender. A name the network gave instead of a number (a
     * bank, a carrier) is compared as written, ignoring case.
     */
    fun address(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        return if (digits.length >= 7 && digits.length * 2 >= raw.count { !it.isWhitespace() }) {
            digits.takeLast(10)
        } else {
            raw.trim().lowercase()
        }
    }

    /**
     * The messages of [backup] to write, in their order. Left out: any already on the phone
     * ([existing]), any the file repeats, and any with no text, which is what an older backup
     * made of a picture message (MMS) it could not hold.
     */
    fun toWrite(
            backup: List<BackupRepositoryImpl.BackupMessage>,
            existing: Set<Key>
    ): List<BackupRepositoryImpl.BackupMessage> {
        val seen = HashSet(existing)
        return backup.filter { m ->
            m.body.isNotEmpty() && seen.add(key(m.address, m.date, m.type, m.body))
        }
    }
}
