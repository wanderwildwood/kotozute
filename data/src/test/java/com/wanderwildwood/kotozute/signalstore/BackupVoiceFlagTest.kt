package com.wanderwildwood.kotozute.signalstore

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the voice-message flag out of an exported attachment.
 *
 * ⛔ **There are two voice-message flags in Signal and they are not the same flag.** This is
 * the trap the whole file exists for:
 *
 * | | wire (`AttachmentPointer.Flags`) | backup (`MessageAttachment.Flag`) |
 * |---|---|---|
 * | shape | **bitfield**, combinable | **mutually exclusive** |
 * | VOICE_MESSAGE | 1 | 1 |
 * | BORDERLESS | 2 | 2 |
 * | GIF | **8** | **3** |
 *
 * `Backup.proto:792-804` states it outright -- "explicitly mutually exclusive. Note the
 * different raw values". They agree on the two values anybody would spot-check and disagree
 * on the third, which is exactly how one reader gets used for both.
 *
 * The consequence of getting it wrong is silent and one-directional: masking the backup value
 * the way the wire value must be masked reads a backup **GIF** (3 = 0b11, bit 0 set) as a
 * voice message. An imported GIF would grow a play button and no error anywhere.
 */
class BackupVoiceFlagTest {

    @Test
    fun `the backup voice message flag is recognised by name`() {
        assertTrue(SignalHistoryImporter.isVoiceMessageFlag("VOICE_MESSAGE"))
    }

    @Test
    fun `and by number, for an export that wrote the raw enum`() {
        assertTrue(SignalHistoryImporter.isVoiceMessageFlag(1))
        assertTrue(SignalHistoryImporter.isVoiceMessageFlag("1"))
    }

    /**
     * ⛔ The one that matters. Backup GIF is **3**, whose low bit is set, so any masked read
     * -- `(flag and 1) != 0`, which is correct for the wire flag -- passes this value.
     */
    @Test
    fun `a backup GIF is not a voice message, though masking would say it is`() {
        val backupGif = 3
        assertTrue("the premise changed; check Backup.proto", (backupGif and 1) != 0)
        assertFalse(SignalHistoryImporter.isVoiceMessageFlag(backupGif))
        assertFalse(SignalHistoryImporter.isVoiceMessageFlag("GIF"))
    }

    @Test
    fun `borderless and none are not voice messages either`() {
        assertFalse(SignalHistoryImporter.isVoiceMessageFlag(2))
        assertFalse(SignalHistoryImporter.isVoiceMessageFlag("BORDERLESS"))
        assertFalse(SignalHistoryImporter.isVoiceMessageFlag(0))
        assertFalse(SignalHistoryImporter.isVoiceMessageFlag("NONE"))
    }

    /** An export with no flag at all is the ordinary case and must not throw. */
    @Test
    fun `an absent flag is not a voice message`() {
        assertFalse(SignalHistoryImporter.isVoiceMessageFlag(null))
        assertFalse(SignalHistoryImporter.isVoiceMessageFlag(JSONObject.NULL))
        assertFalse(SignalHistoryImporter.isVoiceMessageFlag(""))
        assertFalse(SignalHistoryImporter.isVoiceMessageFlag(JSONObject()))
    }

    /**
     * The two ends agree: what the exporter writes is what the importer recognises.
     *
     * They are in different files and only agree by intent, and the exporter writes a name
     * while the importer accepts several shapes -- so the specific string actually used is
     * worth pinning rather than assuming.
     */
    @Test
    fun `what the exporter writes is what the importer reads`() {
        assertTrue(
            SignalHistoryImporter.isVoiceMessageFlag(
                SignalHistoryExporter.BACKUP_FLAG_VOICE_MESSAGE
            )
        )
    }

    /**
     * ⚠ The wire reader must NOT accept the backup's GIF, and the backup reader must not be
     * handed wire values. Pinned together so a future tidy-up that "unifies" the two readers
     * fails here rather than in somebody's thread.
     */
    @Test
    fun `the wire reader and the backup reader disagree about three, and should`() {
        // Wire: 3 is VOICE_MESSAGE|BORDERLESS, so it genuinely is a voice note.
        assertTrue(ContentNormalizer.isVoiceNote(3))
        // Backup: 3 is GIF, and is not.
        assertFalse(SignalHistoryImporter.isVoiceMessageFlag(3))
    }
}
