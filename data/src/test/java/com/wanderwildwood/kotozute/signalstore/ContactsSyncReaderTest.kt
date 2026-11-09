package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Test
import org.signal.core.models.ServiceId.ACI
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContact
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactAvatar
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactsInputStream
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactsOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Optional
import java.util.UUID

/**
 * A contacts sync where several people have a picture.
 *
 * The sync arrives as a download, not as bytes in memory, and a download refuses to be read
 * once it is closed. Every person after the first with a picture was being lost to exactly
 * that, so these tests read through a stream that behaves the same way.
 */
class ContactsSyncReaderTest {

    private val ada = UUID.fromString("0a0a0a0a-1111-4222-8333-444444444444")
    private val tomas = UUID.fromString("0b0b0b0b-1111-4222-8333-444444444444")
    private val bakery = UUID.fromString("0c0c0c0c-1111-4222-8333-444444444444")

    @Test
    fun `everybody after a picture is still read`() {
        val blob = blobOf(
            contact(ada, "Ada Whitlock", "+15550100001", avatar = byteArrayOf(1, 2, 3, 4, 5)),
            contact(tomas, "Tomas Reyes", "+15550100002", avatar = ByteArray(9000) { it.toByte() }),
            contact(bakery, "Whitlock Bakery", "+15550100003", avatar = null)
        )

        val found = ContactsSyncReader.read(ClosesForGood(ByteArrayInputStream(blob)))

        assertEquals(listOf(ada.toString(), tomas.toString(), bakery.toString()), found.map { it.serviceId })
        assertEquals(listOf("Ada Whitlock", "Tomas Reyes", "Whitlock Bakery"), found.map { it.name })
    }

    @Test
    fun `a picture nobody reads is stepped over`() {
        val blob = blobOf(
            contact(ada, "Ada Whitlock", null, avatar = byteArrayOf(9, 8, 7)),
            contact(tomas, "Tomas Reyes", null, avatar = null)
        )

        val input = DeviceContactsInputStream(ClosesForGood(ByteArrayInputStream(blob)))
        input.read()
        val second = input.read()

        assertEquals(Optional.of(ACI.from(tomas)), second.aci)
        assertEquals(Optional.of("Tomas Reyes"), second.name)
    }

    private fun contact(id: UUID, name: String, e164: String?, avatar: ByteArray?) = DeviceContact(
        Optional.of(ACI.from(id)),
        Optional.ofNullable(e164),
        Optional.of(name),
        Optional.ofNullable(avatar?.let { DeviceContactAvatar(ByteArrayInputStream(it), it.size.toLong(), "image/jpeg") }),
        Optional.empty(),
        Optional.empty(),
        Optional.empty()
    )

    private fun blobOf(vararg contacts: DeviceContact): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DeviceContactsOutputStream(bytes)
        contacts.forEach { out.write(it) }
        out.close()
        return bytes.toByteArray()
    }

    /** What a real download does: once closed, it does not read again. */
    private class ClosesForGood(wrapped: InputStream) : FilterInputStream(wrapped) {
        private var closed = false

        override fun read(): Int {
            if (closed) throw IOException("Stream Closed")
            return super.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Stream Closed")
            return super.read(b, off, len)
        }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
