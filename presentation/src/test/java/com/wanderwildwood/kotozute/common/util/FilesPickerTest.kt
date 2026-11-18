package com.wanderwildwood.kotozute.common.util

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun onlyFilesReleaseCertificateIsTrusted() {
        val release = FilesPicker.CERT_SHA256
        val other = "00".repeat(32)
        assertTrue(FilesPicker.trusted(listOf(release), debuggable = false))
        // An app that took Files' name with another key, or none we could read.
        assertFalse(FilesPicker.trusted(listOf(other), debuggable = false))
        assertFalse(FilesPicker.trusted(emptyList(), debuggable = false))
        // Signed with Files' key and another as well.
        assertFalse(FilesPicker.trusted(listOf(release, other), debuggable = false))
    }

    @Test fun debugKeysOnlyInADebugBuild() {
        val debug = FilesPicker.DEBUG_CERTS_SHA256.first()
        assertFalse(FilesPicker.trusted(listOf(debug), debuggable = false))
        assertTrue(FilesPicker.trusted(listOf(debug), debuggable = true))
        assertTrue(FilesPicker.trusted(listOf(FilesPicker.CERT_SHA256), debuggable = true))
    }
}
