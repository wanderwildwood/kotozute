package com.wanderwildwood.kotozute.feature.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A second tap on the overflow button landed on the menu's first entry, which was Mark all
 * read, and read the whole inbox while two other rows were chosen.
 */
class OverflowTapTest {

    @Test
    fun `an overflow entry chosen straight after the tap that opened the menu is stray`() {
        assertTrue(OverflowTap.isStray(fromOverflow = true, sinceLastTouchUp = 0))
        assertTrue(OverflowTap.isStray(fromOverflow = true, sinceLastTouchUp = 350))
        assertFalse(OverflowTap.isStray(fromOverflow = true, sinceLastTouchUp = OverflowTap.SETTLE_MS))
        assertFalse(OverflowTap.isStray(fromOverflow = true, sinceLastTouchUp = 2_000))
    }

    @Test
    fun `an icon on the bar always goes through`() {
        // The icon is chosen by the tap that has just lifted.
        assertFalse(OverflowTap.isStray(fromOverflow = false, sinceLastTouchUp = 0))
    }

    @Test
    fun `mark all read is not the entry under the overflow button`() {
        // Gradle runs unit tests from the module directory.
        val menu = File("src/main/res/menu/main.xml").readText()
        val ids = Regex("""android:id="@\+id/(\w+)"""").findAll(menu).map { it.groupValues[1] }.toList()
        assertEquals("markAllRead", ids.last())
    }
}
