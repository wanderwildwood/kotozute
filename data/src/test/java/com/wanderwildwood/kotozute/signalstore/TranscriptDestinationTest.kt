package com.wanderwildwood.kotozute.signalstore

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SyncMessage
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Who a message we sent from another device was sent to.
 *
 * A transcript is the only record a linked device gets of its own account's sends, and the
 * recipient can be named in any of three places. Reading one of them and not the others does
 * not fail: it files our half of a conversation into a second thread, keyed by the recipient's
 * phone number, which cannot be replied to because a number is not a service id. That is what
 * a real account looked like before this -- their messages under their service id, ours under
 * their number, in two threads side by side.
 */
class TranscriptDestinationTest {

    private val theirAci = "11111111-2222-4333-8444-555555555555"

    private fun binary(uuid: String) = ByteBuffer.allocate(16).apply {
        val id = UUID.fromString(uuid)
        putLong(id.mostSignificantBits)
        putLong(id.leastSignificantBits)
    }.array().toByteString()

    @Test
    fun `the string field is read`() {
        val sent = SyncMessage.Sent(destinationServiceId = theirAci)
        assertEquals(theirAci, ContentNormalizer.destinationServiceIdOf(sent))
    }

    @Test
    fun `the binary field is read when the string one is absent`() {
        val sent = SyncMessage.Sent(destinationServiceIdBinary = binary(theirAci))
        assertEquals(theirAci, ContentNormalizer.destinationServiceIdOf(sent))
    }

    @Test
    fun `the delivery status names the recipient when neither destination field does`() {
        val sent = SyncMessage.Sent(
            destinationE164 = "+15550001",
            unidentifiedStatus = listOf(
                SyncMessage.Sent.UnidentifiedDeliveryStatus(destinationServiceId = theirAci)
            )
        )
        assertEquals(theirAci, ContentNormalizer.destinationServiceIdOf(sent))
    }

    @Test
    fun `a transcript naming nobody says so rather than guessing`() {
        val sent = SyncMessage.Sent(destinationE164 = "+15550001")
        assertEquals("", ContentNormalizer.destinationServiceIdOf(sent))
    }

    @Test
    fun `a number in the service id field is not a service id`() {
        val sent = SyncMessage.Sent(destinationServiceId = "+15550001")
        assertEquals("", ContentNormalizer.destinationServiceIdOf(sent))
    }
}
