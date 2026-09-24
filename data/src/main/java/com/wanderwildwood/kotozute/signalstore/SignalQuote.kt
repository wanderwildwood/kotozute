package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage

/**
 * The message a reply quotes, as this app holds it.
 *
 * Signal's `PushSendJob.getQuoteFor` sends the quoted message's sent timestamp, its
 * author, its text and at most one attachment's type and name -- which is what a
 * recipient's client draws in the reply and how it finds the original. The thumbnail is
 * left out: upstream uploads one, and it is optional on the wire.
 */
data class SignalQuote(
    val sentAt: Long,
    /** A service id; this account's own when quoting one of ours. */
    val author: String,
    val text: String,
    val attachmentType: String? = null,
    val attachmentName: String? = null
) {
    /**
     * Null when the author is not a service id -- upstream sends no quote at all then,
     * rather than one that points at nobody.
     */
    fun toQuote(): SignalServiceDataMessage.Quote? {
        val who = ServiceId.parseOrNull(author) ?: return null
        return SignalServiceDataMessage.Quote(
            id = sentAt,
            author = who,
            text = text,
            attachments = attachmentType?.let {
                listOf(
                    SignalServiceDataMessage.Quote.QuotedAttachment(
                        // Upstream's own fallback when a type is not known.
                        contentType = it.ifBlank { "image/jpeg" },
                        fileName = attachmentName?.takeIf { name -> name.isNotBlank() },
                        thumbnail = null
                    )
                )
            } ?: emptyList(),
            mentions = null,
            type = SignalServiceDataMessage.Quote.Type.NORMAL,
            bodyRanges = null
        )
    }
}
