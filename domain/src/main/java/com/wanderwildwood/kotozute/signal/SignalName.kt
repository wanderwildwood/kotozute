package com.wanderwildwood.kotozute.signal

/**
 * What to call somebody on the Signal rail when the account has not said.
 *
 * One place, because the app meets the same person in three lists -- the inbox, a new
 * message, a notification -- and a different fallback in each makes one person read as
 * several. The order is deliberate: a name, then their number, then enough of the service id
 * to tell two strangers apart.
 */
object SignalName {

    /**
     * How much of a service id to show when there is nothing else.
     *
     * Enough to distinguish two people, short enough not to read as a line of noise. The
     * whole thing is 36 characters and fills an inbox row edge to edge, which says nothing
     * to anybody and hides the message underneath it.
     */
    const val SHORT_SERVICE_ID = 8

    fun of(name: String, number: String, serviceId: String): String =
        of(name, number, username = "", serviceId = serviceId)

    /**
     * The same order Signal falls through, including the step this app did not have.
     *
     * A username sits **below** the phone number, which is Signal's order and not an obvious
     * one: someone who has shared their number has already told you who they are, while a
     * username is what is left when they have not. Above a service id, though -- a username is
     * something a person chose and can be recognised by, and eight characters of hexadecimal
     * is neither.
     */
    fun of(name: String, number: String, username: String, serviceId: String): String = when {
        name.isNotBlank() -> name
        number.isNotBlank() -> number
        username.isNotBlank() -> username
        else -> serviceId.take(SHORT_SERVICE_ID)
    }
}
