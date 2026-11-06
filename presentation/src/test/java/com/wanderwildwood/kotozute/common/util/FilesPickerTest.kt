package com.wanderwildwood.kotozute.common.util

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilesPickerTest {

    @Test fun aimedAtFilesOnlyWhenFilesAnswers() {
        assertEquals("com.wanderwildwood.tana", FilesPicker.packageFor(true))
        assertNull(FilesPicker.packageFor(false))
    }

    @Test fun readingAsksForNothingExtra() {
        assertEquals(0, FilesPicker.flagsFor(false))
    }

    @Test fun writingAsksForReadWriteAndKeeping() {
        val f = FilesPicker.flagsFor(true)
        val all = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        assertEquals(all, f)
    }
}
