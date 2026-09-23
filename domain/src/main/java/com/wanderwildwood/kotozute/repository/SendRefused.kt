package com.wanderwildwood.kotozute.repository

/**
 * Why a Signal send did not happen, decided where it happened and worded where it is shown.
 *
 * ⚠ **What went wrong and how to say it are two jobs, and they used to be one.** The send path
 * decided the reason and wrote the English sentence in the same breath, so the sentence reached
 * the screen from a layer with no `Context` -- and could only ever be English. The data layer
 * now says which of these happened, with whatever detail the words need, and the screen turns it
 * into the reader's language. Nothing here is meant to be read by a person; the wording lives in
 * the presentation module's `SignalWording`, next to the string resources it reads.
 *
 * A name is carried as it is held, and null where only an id is: the screen words the sentence
 * without a name rather than show hexadecimal.
 */
sealed interface SendFailure {

    /** The server wants the account to prove it is a person (a 428). */
    data class ProofRequired(val retryAfterSeconds: Long) : SendFailure

    /** The server is limiting how fast this account sends, as a thrown refusal. */
    data class RateLimited(val retryAfterSeconds: Long) : SendFailure

    /** The server no longer knows this device. */
    data object Unlinked : SendFailure

    /** The server will not talk to this version of the app. */
    data object VersionRefused : SendFailure

    /** The server refused the message itself; sending it again will not change that. */
    data object ServerRejected : SendFailure

    /** A thrown "not on Signal", where there was no contact store to name them from. */
    data object TheyLeft : SendFailure

    /**
     * Anything the sender has no words of its own for. [detail] is the exception's own text, or
     * its type where it had none, and is shown as it is, exactly as before.
     */
    data class Unexplained(val detail: String) : SendFailure

    /** The recipient's safety number changed. */
    data class SafetyNumberChanged(val name: String?) : SendFailure

    /** The service says the recipient is not on Signal any more. */
    data class NotOnSignal(val name: String?) : SendFailure

    /** The send never reached Signal. */
    data object Unreachable : SendFailure

    /** Their pre-key bundle will not open. */
    data class KeyUnusable(val name: String?) : SendFailure

    /**
     * Rate limited, as a returned result. [waitMillis] is how long the server said to wait, or
     * null where it did not say.
     */
    data class TooFast(val waitMillis: Long?) : SendFailure

    /** The returned form of [ProofRequired], which carries no wait. */
    data object ProofNeeded : SendFailure

    /** A failed result naming none of the reasons above. */
    data object ServerSilent : SendFailure

    /** A resend was asked for a message the resend log no longer holds. */
    data object NoLongerHeld : SendFailure

    /** A group with nobody in it this device can reach. */
    data object NoReachableMembers : SendFailure

    /** Every member of a group failed; [count] is how many were tried. */
    data class NobodyReached(val count: Int) : SendFailure

    /** The primary did not take a request this device sent it. */
    data object PrimaryRefusedRequest : SendFailure

    /** The body is longer than any recipient will accept. */
    data object TooLong : SendFailure

    /** An attachment could not be read before sending; [detail] is the exception's text. */
    data class AttachmentUnprepared(val detail: String) : SendFailure

    /** This phone's signed pre-keys are stale and could not be replaced. */
    data object KeysStale : SendFailure

    /** There is no sealed-sender certificate to send with. */
    data object NoCertificate : SendFailure

    /** This account has been removed from the group. */
    data object NotInGroup : SendFailure

    /** The group no longer exists. */
    data object GroupEnded : SendFailure

    /** The group's state could not be fetched. */
    data object GroupUnreachable : SendFailure

    /** An announcement group, and this account is not one of its admins. */
    data object AdminsOnly : SendFailure

    /** A conversation keyed by a phone number, which has no Signal address to reply to. */
    data object NumberOnly : SendFailure

    /** An attachment offered to a group send, which this app cannot do yet. */
    data object AttachmentsToGroup : SendFailure

    /** A group conversation that has no master key on it yet. */
    data object NoGroupKey : SendFailure
}

/**
 * A send that did not happen, and [failure] says why.
 *
 * An [IllegalStateException] because that is what every one of these was before, and a caller
 * that catches by that type must go on catching them. Its message is the kind, for a log line;
 * a screen asks `SignalWording` for the words.
 */
class SendRefused(val failure: SendFailure) : IllegalStateException(failure.toString())
