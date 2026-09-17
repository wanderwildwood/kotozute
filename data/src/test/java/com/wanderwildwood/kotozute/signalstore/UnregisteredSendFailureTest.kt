package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.api.push.exceptions.NotFoundException
import org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException

/**
 * What a one-to-one send says when the person is no longer on Signal.
 *
 * A send to several recipients gets an `UnregisteredUserException` per recipient folded into
 * `SendMessageResult.unregisteredFailure`. A one-to-one send has nowhere to put it and
 * **throws** it instead -- `SignalServiceMessageSender:2067` rethrows rather than converting.
 *
 * Every catch in [SignalSender] used to turn that into `explain(t)`, whose last arm is
 * `t.message`. `UnregisteredUserException(id, cause)` is `super(cause)`, so `message` is the
 * *cause's* `toString()`: a fully-qualified Java class name, shown to somebody in a
 * conversation.
 *
 * This pins the shape of that message rather than the wording of the fix, because the wording
 * belongs to the app and the shape belongs to the library.
 */
class UnregisteredSendFailureTest {

    private val cause = NotFoundException("not found")

    @Test
    fun `the library's own message for it is not fit to show anybody`() {
        val e = UnregisteredUserException("aaaaaaaa-0000-4000-8000-000000000000", cause)
        val raw = e.message ?: ""
        assertTrue(
            "expected a class name in the raw message, got: $raw",
            raw.contains("org.whispersystems.signalservice")
        )
    }

    /**
     * `failed` handles this exception before `explain` is reached, and names the person. The arm
     * in `explain` is the backstop for a `catch` added later that calls it directly -- which is
     * what every catch in the file did until this was found. Both paths, so neither can regress.
     */
    @Test
    fun `explain never puts the library's class name in front of a person`() {
        val e = UnregisteredUserException("aaaaaaaa-0000-4000-8000-000000000000", cause)
        val shown = SignalSender.explain(e)
        assertFalse("explain leaked a class name: $shown", shown.contains("org.whispersystems"))
        assertTrue(shown, shown.contains("not on Signal any more"))
    }

    /**
     * ⚠ The accessor is named `getE164Number` and does not return one. It carries
     * `OutgoingPushMessageList.destination`, built at `SignalServiceMessageSender:2920` from
     * `recipient.getIdentifier()` -- a service id. Marking a contact by it works; parsing it as
     * a phone number would look right and mark nobody.
     */
    @Test
    fun `the misnamed accessor carries a service id, which is what a contact is marked by`() {
        val serviceId = "aaaaaaaa-0000-4000-8000-000000000000"
        val e = UnregisteredUserException(serviceId, cause)
        assertTrue(e.e164Number == serviceId)
        assertFalse("a service id must not be mistaken for a number", e.e164Number.startsWith("+"))
    }
}
