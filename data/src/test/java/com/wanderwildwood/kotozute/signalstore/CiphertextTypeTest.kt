package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Test
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.whispersystems.signalservice.internal.push.Envelope

/**
 * What kind of ciphertext an envelope carried, in libsignal's numbering.
 *
 * A retry receipt quotes this back so the sender can find the message it is about. The two
 * vocabularies do not line up by value -- an envelope's PREKEY_MESSAGE is 3 and libsignal's
 * PREKEY_TYPE is 3, but UNIDENTIFIED_SENDER is 6 while SENDERKEY_TYPE is 7 -- so a mapping
 * inferred from the enum order is wrong in exactly the case that matters most, sealed sender,
 * which is how nearly everything arrives.
 *
 * Wrong here is silent: the receipt sends, the sender matches nothing, the message is never
 * resent, and the only evidence is a conversation quietly missing a message.
 */
class CiphertextTypeTest {

    @Test
    fun `an ordinary message is a whisper message`() {
        assertEquals(
            CiphertextMessage.WHISPER_TYPE,
            SignalReceiver.ciphertextTypeOf(Envelope.Type.DOUBLE_RATCHET)
        )
    }

    @Test
    fun `the first message of a session is a prekey message`() {
        assertEquals(
            CiphertextMessage.PREKEY_TYPE,
            SignalReceiver.ciphertextTypeOf(Envelope.Type.PREKEY_MESSAGE)
        )
    }

    @Test
    fun `a sealed sender envelope is a sender key message`() {
        // The one that does not follow the numbering: 6 in, 7 out.
        assertEquals(
            CiphertextMessage.SENDERKEY_TYPE,
            SignalReceiver.ciphertextTypeOf(Envelope.Type.UNIDENTIFIED_SENDER)
        )
        assertEquals(7, CiphertextMessage.SENDERKEY_TYPE)
        assertEquals(6, Envelope.Type.UNIDENTIFIED_SENDER.value)
    }

    @Test
    fun `plaintext content keeps its own type`() {
        assertEquals(
            CiphertextMessage.PLAINTEXT_CONTENT_TYPE,
            SignalReceiver.ciphertextTypeOf(Envelope.Type.PLAINTEXT_CONTENT)
        )
    }

    @Test
    fun `anything else is treated as an ordinary message`() {
        // Including null: an envelope with no type is not a reason to skip asking for the
        // message again, and a whisper message is the likeliest thing it was.
        assertEquals(CiphertextMessage.WHISPER_TYPE, SignalReceiver.ciphertextTypeOf(null))
        assertEquals(
            CiphertextMessage.WHISPER_TYPE,
            SignalReceiver.ciphertextTypeOf(Envelope.Type.UNKNOWN)
        )
        assertEquals(
            CiphertextMessage.WHISPER_TYPE,
            SignalReceiver.ciphertextTypeOf(Envelope.Type.SERVER_DELIVERY_RECEIPT)
        )
    }
}
