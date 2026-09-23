package com.wanderwildwood.kotozute.repository

/**
 * A group could not be made, and [why] says which way.
 *
 * Each of these means something different to the person making the group, which is why they
 * are told apart at all; see `SignalGroups.create`. A kind rather than a sentence so the screen
 * can say it in the reader's language -- the words are in `SignalWording`.
 *
 * An [IllegalStateException] because that is what these were before, and a caller that catches
 * by that type must go on catching them.
 */
class GroupNotMade(val why: Why) : IllegalStateException(why.name) {

    enum class Why {
        /** This phone holds no account to make it as. */
        NOT_LINKED,

        /** This account has no profile credential, and a group cannot be made without one. */
        NO_PROFILE,

        /** Nobody but the creator was chosen. */
        NOBODY_ELSE,

        /** The group's first state could not be built. */
        NOT_PUT_TOGETHER,

        /** The server would not issue group credentials for this account. */
        NOT_AUTHORIZED,

        /** The server would not take the new group. */
        SERVER_REFUSED,

        /** The group exists on the server, but its id would not derive here. */
        UNADDRESSABLE
    }
}
