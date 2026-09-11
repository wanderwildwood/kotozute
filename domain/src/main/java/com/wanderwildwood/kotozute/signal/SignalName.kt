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

    fun of(name: String, number: String, serviceId: String): String = when {
        name.isNotBlank() -> name
        number.isNotBlank() -> number
        else -> serviceId.take(SHORT_SERVICE_ID)
    }
}
