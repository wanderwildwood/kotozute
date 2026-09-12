package com.wanderwildwood.kotozute.signalstore

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.internal.push.Envelope
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Which field an envelope's service ids are read from.
 *
 * Same trap as [StorageContactAciTest] and as the sent transcript: the string field is the
 * old one, a modern server fills only the binary twin. Two things were reading the string
 * alone -- the sender of a delivery receipt, which went blank and threw the receipt away,
 * and the test for a PNI-addressed envelope, which then decrypted against the ACI store and
 * failed as if the message were corrupt.
 */
class EnvelopeServiceIdTest {

    private val uuid = UUID.fromString("d6cd2de6-2397-4bf4-bb68-b29a6228fbbd")

    private fun aciBytes(id: UUID) = ByteBuffer.allocate(16)
        .putLong(id.mostSignificantBits)
        .putLong(id.leastSignificantBits)
        .array()
        .toByteString()

    /** A PNI on the wire is the same sixteen bytes behind a one-byte marker. */
    private fun pniBytes(id: UUID) = ByteBuffer.allocate(17)
        .put(0x01)
        .putLong(id.mostSignificantBits)
        .putLong(id.leastSignificantBits)
        .array()
        .toByteString()

    @Test
    fun `the sender is read from the string field`() {
        assertEquals(
            uuid.toString(),
            SignalReceiver.senderOf(Envelope(sourceServiceId = uuid.toString()))
        )
    }

    @Test
    fun `the sender is read from the binary field when the string is empty`() {
        // The case that was dropping delivery receipts.
        assertEquals(
            uuid.toString(),
            SignalReceiver.senderOf(Envelope(sourceServiceIdBinary = aciBytes(uuid)))
        )
    }

    @Test
    fun `an envelope naming no sender is null`() {
        assertNull(SignalReceiver.senderOf(Envelope()))
    }

    @Test
    fun `a pni destination is recognised from the string field`() {
        assertTrue(addressedToPni(destination = "PNI:$uuid"))
    }

    @Test
    fun `a pni destination is recognised from the binary field`() {
        // The case that was decrypting against the wrong store.
        assertTrue(SignalReceiver.addressedToPni(Envelope(destinationServiceIdBinary = pniBytes(uuid))))
    }

    @Test
    fun `an aci destination is not a pni`() {
        assertTrue(addressedToPni(destination = "PNI:$uuid"))
        assertFalse(addressedToPni(destination = uuid.toString()))
        assertFalse(SignalReceiver.addressedToPni(Envelope(destinationServiceIdBinary = aciBytes(uuid))))
        // No destination at all is not a PNI either -- it must fall to the ACI store, which
        // is what every ordinary envelope needs.
        assertFalse(SignalReceiver.addressedToPni(Envelope()))
    }

    private fun addressedToPni(destination: String) =
        SignalReceiver.addressedToPni(Envelope(destinationServiceId = destination))
}
