package com.wanderwildwood.kotozute.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What is cleared after the locked screen is the placeholder's four names and nothing else.
 * The cases that matter are the files that must still be there afterwards.
 */
class LockedPlaceholderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val mustSurvive = listOf(
        "default.realm",
        "default.realm.lock",
        "default.realm.note",
        "default.realm.unreadable-1794866400000",
        "default.realm.pre-encryption",
        "locked-placeholder.realm.unreadable-1",
        "locked-placeholder.realm.bak",
        "xlocked-placeholder.realm",
        "signal-protocol.db"
    )

    @Test
    fun theNamesAreExactlyTheFour() {
        assertEquals(
            listOf(
                "locked-placeholder.realm",
                "locked-placeholder.realm.lock",
                "locked-placeholder.realm.note",
                "locked-placeholder.realm.management"
            ),
            LockedPlaceholder.FILE_NAMES
        )
    }

    @Test
    fun thePlaceholderGoesAndEverythingElseStays() {
        val dir = folder.root
        mustSurvive.forEach { File(dir, it).writeText("keep") }
        File(dir, "default.realm.management").mkdir()
        File(dir, "default.realm.management/access_control.lock").writeText("keep")
        listOf("locked-placeholder.realm", "locked-placeholder.realm.lock", "locked-placeholder.realm.note")
            .forEach { File(dir, it).writeText("placeholder") }
        File(dir, "locked-placeholder.realm.management").mkdir()
        File(dir, "locked-placeholder.realm.management/access_control.lock").writeText("placeholder")

        assertEquals(4, LockedPlaceholder.remove(dir))

        LockedPlaceholder.FILE_NAMES.forEach { assertFalse(it, File(dir, it).exists()) }
        mustSurvive.forEach { assertEquals(it, "keep", File(dir, it).readText()) }
        assertTrue(File(dir, "default.realm.management/access_control.lock").exists())
    }

    @Test
    fun nothingThereIsNothingRemoved() {
        val dir = folder.root
        File(dir, "default.realm").writeText("keep")
        assertEquals(0, LockedPlaceholder.remove(dir))
        assertEquals("keep", File(dir, "default.realm").readText())
    }
}
