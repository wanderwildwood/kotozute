package com.wanderwildwood.kotozute.signalstore

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord
import org.whispersystems.signalservice.internal.storage.protos.ManifestRecord
import org.whispersystems.signalservice.internal.storage.protos.StorageRecord

/**
 * The two rules that stop a storage write damaging the account.
 *
 * ⛔ **Neither failure is local.** A storage record and the manifest that names it are read and
 * applied by *every* device on the account, so a write that drops an entry or re-encodes a
 * record without fields it did not understand destroys other clients' data — not this phone's.
 * That is why `docs/DECISION-storage-write.md` stages this at all, and why these are unit
 * tests rather than something to find in the field.
 */
class StorageWriteSafetyTest {

    private fun id(n: Int) = ManifestRecord.Identifier(
        raw = ByteArray(16) { n.toByte() }.toByteString(),
        type = ManifestRecord.Identifier.Type.CONTACT
    )

    /** A storage id is its bytes; see the note on `deleteIds` about comparing text. */
    private fun key(identifier: ManifestRecord.Identifier) = identifier.raw!!

    // --- unknown fields ---------------------------------------------------------------------

    /**
     * ⛔ **The rule step 0 exists for.** A record written by a newer Signal client carries
     * fields this build has never heard of. Amending must decode, set, and re-encode so those
     * bytes come back out; constructing a fresh record would silently erase them for every
     * device on the account.
     */
    @Test
    fun `a field this build does not understand survives an amend`() {
        // A StorageRecord holding a contact, plus a field number nothing here knows.
        //
        // ⚠ Hand-rolled protobuf, and the tag byte is fiddlier than it looks. Two traps, both
        // hit while writing this:
        //   - the low three bits are the **wire type**, so (field shl 3) or 0 is a varint and
        //     `or 2` is length-delimited, which then needs a length and that many bytes;
        //   - the tag is itself **varint-encoded**, so any value over 127 takes two bytes.
        //     Field 31 gives 248, whose high bit makes Wire read on into the next byte.
        // Field 15 as a varint is (15 shl 3) = 120, which fits in one byte and is outside the
        // schema. Both earlier attempts threw EOFException and looked like a fault in the code
        // under test rather than in the fixture.
        val base = StorageRecord(contact = ContactRecord(e164 = "+15555550100"))
        val unknownTag = 0x78.toByte()
        val unknownValue = 0x2A.toByte()
        val withUnknown = base.encode() + byteArrayOf(unknownTag, unknownValue)

        val amended = SignalStorageWriter.amend(
            withUnknown,
            SignalStorageWriter.Desired(muted = false, archived = false, blocked = true),
            now = 1_000L
        )

        // The decision was applied...
        val out = StorageRecord.ADAPTER.decode(amended)
        assertNotNull(out.contact)
        assertTrue("the amend did not take", out.contact!!.blocked)
        assertEquals("+15555550100", out.contact!!.e164)
        // ...and the bytes nobody here understands are still there.
        assertTrue(
            "the unknown field was dropped — this is the data loss step 0 prevents",
            amended.toList().windowed(2).any { it == listOf(unknownTag, unknownValue) }
        )
    }

    // --- the manifest arithmetic --------------------------------------------------------------

    @Test
    fun `a clean replacement passes`() {
        val before = listOf(id(1), id(2), id(3))
        val inserted = listOf(id(9))
        val after = listOf(id(1), id(3), id(9))
        assertNull(SignalStorageWriter.validate(before, after, inserted, setOf(key(id(2)))))
    }

    /**
     * ⛔ The one that matters most: an entry vanishing that this write never meant to replace.
     * That is how the account's call links, chat folders and sticker packs would be erased by
     * a naive "remote minus local" diff.
     */
    @Test
    fun `dropping an entry it was not replacing is refused`() {
        val before = listOf(id(1), id(2), id(3))
        val inserted = listOf(id(9))
        // id(3) simply disappears, and was never in the delete set.
        val after = listOf(id(1), id(9))
        val why = SignalStorageWriter.validate(before, after, inserted, setOf(key(id(2))))
        assertNotNull("a dropped record was allowed through", why)
        assertTrue(why!!, why.contains("not replacing") || why.contains("entries"))
    }

    @Test
    fun `the count having to add up catches a silent extra`() {
        val before = listOf(id(1), id(2))
        val inserted = listOf(id(9))
        val after = listOf(id(1), id(2), id(9), id(8))
        assertNotNull(SignalStorageWriter.validate(before, after, inserted, emptySet()))
    }

    @Test
    fun `naming the same id twice is refused`() {
        val before = listOf(id(1))
        val inserted = listOf(id(9))
        val after = listOf(id(9), id(9))
        assertNotNull(SignalStorageWriter.validate(before, after, inserted, setOf(key(id(1)))))
    }

    // --- what a write carries for mute and block ----------------------------------------------

    private val now = 1_000_000L

    @Test
    fun `a timed mute the account holds survives when this phone still calls it muted`() {
        val eightHours = now + 8 * 3_600_000L
        assertEquals(eightHours, SignalStorageWriter.mutedUntilFor(eightHours, muted = true, now = now))
    }

    @Test
    fun `muting here writes for ever, and unmuting writes zero`() {
        assertEquals(Long.MAX_VALUE, SignalStorageWriter.mutedUntilFor(0L, muted = true, now = now))
        assertEquals(0L, SignalStorageWriter.mutedUntilFor(Long.MAX_VALUE, muted = false, now = now))
    }

    @Test
    fun `a mute that has run out is left as it is when this phone says not muted`() {
        val expired = now - 1
        assertEquals(expired, SignalStorageWriter.mutedUntilFor(expired, muted = false, now = now))
    }

    @Test
    fun `an unset block keeps the account's, and archive is what this phone says`() {
        val raw = StorageRecord(contact = ContactRecord(e164 = "+15555550100", blocked = true)).encode()
        val out = StorageRecord.ADAPTER.decode(
            SignalStorageWriter.amend(raw, SignalStorageWriter.Desired(muted = false, archived = true), now)
        ).contact!!
        assertTrue("the account's block was overwritten", out.blocked)
        assertTrue(out.archived)
    }

    /** The control for the one above: the same record, nothing changed, decodes equal. */
    @Test
    fun `asking for what the account already holds changes nothing`() {
        val raw = StorageRecord(contact = ContactRecord(e164 = "+15555550100", archived = true)).encode()
        val amended = SignalStorageWriter.amend(raw, SignalStorageWriter.Desired(muted = false, archived = true), now)
        assertEquals(StorageRecord.ADAPTER.decode(raw), StorageRecord.ADAPTER.decode(amended))
    }
}
