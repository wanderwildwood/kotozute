package com.wanderwildwood.kotozute.repository

/**
 * A send that did not happen, and whose message is **in the conversation**, marked not sent with
 * [failure] as the reason, where it can be sent again.
 *
 * ⚠ The difference from a bare failure is what the screen does with its composer. A failure
 * that arrives as this means the message is already in the thread, so the composer is cleared;
 * any other failure means nothing was written down, and the text must stay where it was typed.
 *
 * This used to be the other way round: a failed send removed its own row and left the text in
 * the composer, which on a phone with no working account read as a message that simply
 * vanished. Upstream never removes it either: the message stays and is marked failed
 * (`IndividualSendJob.onFailure` -> `MessageTable.markAsSentFailed`).
 *
 * A refusal for a changed safety number is **not** wrapped in this: it is kept in the thread
 * too, but its own type is what screens catch to offer the choice. See [IdentityNotice].
 */
class NotSent(val failure: SendFailure, cause: Throwable) : Exception(cause.message, cause)
