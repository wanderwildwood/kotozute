package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.internal.storage.protos.ContactRecord
import org.whispersystems.signalservice.internal.storage.protos.StorageRecord

/**
 * A nickname set on this phone, on its way into the account's record.
 *
 * The shape is upstream's `StorageSyncModels`: `nickname` absent rather than empty when there is
 * none, and `note` an empty string rather than absent. Everything else on the record -- the
 * profile name, the address-book name, the block -- is the account's and must ride through.
 */
class NicknameStorageTest {

    private val now = 1_000L

    private fun record(contact: ContactRecord) = StorageRecord(contact = contact).encode()

    private fun amend(raw: ByteArray, nickname: SignalContactStore.Nickname?) =
        StorageRecord.ADAPTER.decode(
            SignalStorageWriter.amend(
                raw,
                SignalStorageWriter.Desired(muted = false, archived = false, nickname = nickname),
                now
            )
        ).contact!!

    @Test
    fun `a nickname set here goes into the record and nothing else of theirs moves`() {
        val raw = record(
            ContactRecord(
                aci = "11111111-1111-4111-8111-111111111111",
                givenName = "Ada", familyName = "Lovelace",
                systemGivenName = "", blocked = true
            )
        )
        val out = amend(raw, SignalContactStore.Nickname("Addie", "Byron", "met at the library"))

        assertEquals(ContactRecord.Name(given = "Addie", family = "Byron"), out.nickname)
        assertEquals("met at the library", out.note)
        // Their own profile name is theirs, not ours to rename.
        assertEquals("Ada", out.givenName)
        assertEquals("Lovelace", out.familyName)
        assertTrue("the account's block was lost", out.blocked)
        // And what this phone will now call them, read back the way a storage read does.
        assertEquals("Addie Byron", SignalStorageService.nameOf(out))
    }

    @Test
    fun `taking a nickname away leaves no nickname and an empty note`() {
        val raw = record(
            ContactRecord(
                givenName = "Ada", familyName = "Lovelace",
                nickname = ContactRecord.Name(given = "Addie", family = "Byron"),
                note = "met at the library"
            )
        )
        val out = amend(raw, SignalContactStore.Nickname(null, null, null))

        assertNull("an empty nickname was written instead of none", out.nickname)
        assertEquals("", out.note)
        assertEquals("Ada Lovelace", SignalStorageService.nameOf(out))
    }

    /** The control: a write with no nickname decision -- a mute -- leaves the account's alone. */
    @Test
    fun `a write that is not about the nickname keeps the account's`() {
        val held = ContactRecord.Name(given = "Addie", family = "Byron")
        val raw = record(ContactRecord(nickname = held, note = "met at the library"))
        val out = amend(raw, nickname = null)

        assertEquals(held, out.nickname)
        assertEquals("met at the library", out.note)
    }

    @Test
    fun `blank parts are trimmed, and a family name alone is still a nickname`() {
        assertNull(SignalStorageWriter.nicknameField(SignalContactStore.Nickname("  ", "", null)))
        assertEquals(
            ContactRecord.Name(given = "", family = "Byron"),
            SignalStorageWriter.nicknameField(SignalContactStore.Nickname(" ", " Byron ", null))
        )
    }

    /** What a storage read hands the store, so a nickname taken away has a name to fall to. */
    @Test
    fun `the name beneath a nickname is the address book's, then the profile's`() {
        val nicknamed = ContactRecord(
            givenName = "Ada", familyName = "Lovelace",
            nickname = ContactRecord.Name(given = "Addie", family = "Byron")
        )
        assertEquals("Addie Byron", SignalStorageService.nameOf(nicknamed))
        assertEquals("Ada Lovelace", SignalStorageService.nameOf(nicknamed.copy(nickname = null)))
        assertEquals(
            "Ada B",
            SignalStorageService.nameOf(
                nicknamed.copy(nickname = null, systemGivenName = "Ada", systemFamilyName = "B")
            )
        )
    }

    @Test
    fun `a nickname joins as a profile name does`() {
        assertEquals("Addie Byron", SignalContactStore.Nickname("Addie", "Byron", null).joined)
        assertEquals("Byron", SignalContactStore.Nickname(null, "Byron", "a note").joined)
        assertNull(SignalContactStore.Nickname("", " ", "a note only").joined)
    }
}
