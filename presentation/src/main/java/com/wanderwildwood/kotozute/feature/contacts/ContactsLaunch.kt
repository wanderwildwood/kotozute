package com.wanderwildwood.kotozute.feature.contacts

/**
 * What this screen was opened for, read off the intent that started it.
 *
 * One object rather than a Boolean per errand: the picker is reached from the SMS composer,
 * from a share, and now from the Signal rail, and the differences between those are worth
 * being able to read in one place.
 */
data class ContactsLaunch(
    /** Text is being shared into the SMS composer this screen returns to. */
    val sharing: Boolean = false,
    /** Open showing the Signal address book rather than the phone's. */
    val signal: Boolean = false
)
