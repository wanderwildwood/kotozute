package com.wanderwildwood.kotozute.manager

import android.net.Uri
import androidx.core.net.toFile
import androidx.test.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a Signal voice note actually records, in the format Signal's own clients expect.
 *
 * This cannot be a unit test. The encoder configuration is the whole of what is being checked
 * -- AAC in ADTS at 44.1 kHz mono, 32 kbps -- and `MediaRecorder` accepts or refuses a
 * combination only on a real device. A JVM test would assert that the constants are the
 * constants, which says nothing about whether a phone can encode with them.
 *
 * ⚠ **The failure this exists for is silent.** `MediaRecorderManager.startRecording` catches
 * everything and returns `Uri.EMPTY`, so a configuration this device cannot honour produces
 * no crash, no log and no recording -- only a composer that never gains an attachment.
 * Asserting on the *bytes* is the only way to tell "recorded" from "appeared to record".
 *
 * The emulator has no real microphone, so what is captured is silence. That is deliberately
 * not what is asserted: the question is whether the encoder ran and wrote a well-formed
 * stream, not what was in the room.
 *
 * ⛔ **THIS HAS NOT RUN YET, and neither has any other instrumented test in this module.**
 * `androidx.test:runner` is pinned at `1.1.0-alpha3` (inherited from QKSMS, 2018), which
 * reaches for `android.test.suitebuilder.annotation.Suppress` -- removed from the platform
 * after API 28. On the Kompakt's API 31 the runner dies with `NoClassDefFoundError` before
 * the first test starts. Confirmed as pre-existing by running `PhoneNumberUtilsTest`, which
 * fails identically, so this is not a fault in the test below.
 *
 * Repairing it means `androidx.test:runner:1.5.2` + `androidx.test.ext:junit:1.1.5`, which
 * this repo's **dependency verification** then refuses until nine artifacts are added to
 * `gradle/verification-metadata.xml`. That is a supply-chain decision, so it is left for a
 * person to make rather than taken here. ⚠ Until it is made, a run of this file reports
 * "0 tests" or a crash -- neither of which is a pass.
 */
@RunWith(AndroidJUnit4::class)
class SignalVoiceNoteRecordingTest {

    private val context = InstrumentationRegistry.getTargetContext()

    @Test
    fun aSignalVoiceNoteRecordsAsAdtsAac() {
        val started = MediaRecorderManager.startRecording(
            context,
            format = MediaRecorderManager.Format.SIGNAL_AAC
        )
        assertNotEquals("the recorder would not start with Signal's AAC settings", Uri.EMPTY, started)

        // Long enough for the encoder to write frames. Below roughly this, AAC/ADTS yields a
        // zero-length file -- the reason the composer refuses a very short tap.
        Thread.sleep(1500)
        val stopped = MediaRecorderManager.stopRecording()
        assertNotEquals("stopping the recorder lost the file", Uri.EMPTY, stopped)

        val file = stopped.toFile()
        try {
            assertTrue("no recording was written", file.exists())
            assertTrue("the recording is empty", file.length() > 0)
            assertTrue("the Signal suffix changed", file.name.endsWith(".aac"))

            // An ADTS frame opens with a twelve-bit syncword, 0xFFF: the whole first byte and
            // the top four bits of the second. Checked rather than trusting the extension,
            // because the extension is something this code chose and the bytes are what
            // Signal's other clients are actually handed.
            val header = file.readBytes()
            assertEquals("not an ADTS stream", 0xFF, header[0].toInt() and 0xFF)
            assertEquals("not an ADTS syncword", 0xF0, header[1].toInt() and 0xF0)
        } finally {
            file.delete()
        }
    }

    /**
     * The control, and the reason this file is not only the test above.
     *
     * If the recorder quietly ignored the format and wrote its MMS default, every assertion
     * about "a recording exists" would still pass -- AMR writes a file too. So the two have
     * to be shown to differ, and to differ in the way claimed: AMR announces itself in ASCII
     * and ADTS does not, so neither test can pass with the other's output.
     */
    @Test
    fun theMmsFormatIsStillAmrAndIsNotWhatSignalGets() {
        val started = MediaRecorderManager.startRecording(
            context,
            format = MediaRecorderManager.Format.MMS_AMR_NB
        )
        assertNotEquals("the recorder would not start with the MMS settings", Uri.EMPTY, started)
        Thread.sleep(1500)
        val file = MediaRecorderManager.stopRecording().toFile()

        try {
            assertTrue("no recording was written", file.length() > 0)
            assertEquals(
                "the MMS format is no longer AMR",
                "#!AMR\n",
                String(file.readBytes(), 0, 6, Charsets.US_ASCII)
            )
            assertTrue(
                "the MMS suffix changed",
                file.name.endsWith(MediaRecorderManager.AUDIO_FILE_SUFFIX)
            )
        } finally {
            file.delete()
        }
    }

    /**
     * Every format's suffix is one the cache sweep matches on.
     *
     * A recording lives in the cache until the sweep removes it, and the sweep used to list
     * suffixes by hand -- so a format whose suffix was forgotten would leak recordings for
     * ever on a phone with very little room, silently.
     */
    @Test
    fun eachFormatCarriesItsOwnSuffixAndTheSweepKnowsThemAll() {
        assertEquals(".aac", MediaRecorderManager.Format.SIGNAL_AAC.suffix)
        assertEquals(".amr", MediaRecorderManager.Format.MMS_AMR_NB.suffix)
        MediaRecorderManager.Format.values().forEach {
            assertTrue(
                "${it.name} writes ${it.suffix}, which the cache sweep would never delete",
                MediaRecorderManager.ALL_AUDIO_FILE_SUFFIXES.contains(it.suffix)
            )
        }
    }
}
