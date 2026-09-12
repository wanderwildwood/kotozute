package com.wanderwildwood.kotozute.signalstore

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Which field a contact record's account id is read from.
 *
 * Signal carries it twice: `aci` as the old hyphenated string, `aciBinary` as the raw
 * sixteen bytes. A modern primary writes only the second. Reading only the first dropped
 * 130 of one account's 201 contact records -- silently, because a record with no id was
 * skipped rather than counted -- and the person who reported it simply could not find a
 * friend who was demonstrably on Signal.
 *
 * The same trap, and the same fix, as `ContentNormalizer.destinationServiceIdOf`.
 */
class StorageContactAciTest {

    private val uuid = UUID.fromString("d6cd2de6-2397-4bf4-bb68-b29a6228fbbd")

    private fun bytes(id: UUID) = ByteBuffer.allocate(16)
        .putLong(id.mostSignificantBits)
        .putLong(id.leastSignificantBits)
        .array()
        .toByteString()

    @Test
    fun `the string field is read`() {
        assertEquals(
            uuid.toString(),
            SignalStorageService.aciOf(ContactRecord(aci = uuid.toString()))
        )
    }

    @Test
    fun `the binary field is read when the string is empty`() {
        // The case that was being dropped.
        assertEquals(
            uuid.toString(),
            SignalStorageService.aciOf(ContactRecord(aciBinary = bytes(uuid)))
        )
    }

    @Test
    fun `both fields present agree`() {
        assertEquals(
            uuid.toString(),
            SignalStorageService.aciOf(
                ContactRecord(aci = uuid.toString(), aciBinary = bytes(uuid))
            )
        )
    }

    @Test
    fun `a record naming no account is null, not an empty id`() {
        // Null is what the caller counts. An empty string would be stored as a contact
        // nobody can be addressed by, which is how a directory fills with rows that
        // cannot be written to.
        assertNull(SignalStorageService.aciOf(ContactRecord()))
        assertNull(SignalStorageService.aciOf(ContactRecord(aci = "")))
        assertNull(SignalStorageService.aciOf(ContactRecord(aci = "not-a-uuid")))
    }

    @Test
    fun `a phone-number identity is not mistaken for an account`() {
        // A PNI-only record has a real address on it, but not one this app keys people by.
        // It must not come back from aciOf, or the row is unaddressable.
        val record = ContactRecord(pni = "PNI:$uuid")
        assertNull(SignalStorageService.aciOf(record))
        assertEquals("PNI:$uuid", SignalStorageService.pniOf(record))
    }
}
