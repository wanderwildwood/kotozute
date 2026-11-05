package com.wanderwildwood.kotozute.common.util.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class LastRowLiftTest {

    @Test
    fun `the last conversation comes to rest above the compose button`() {
        // 56 dp button, 16 dp above the foot, 8 dp gap, the inbox's own 8 dp of bottom padding.
        assertEquals(72, lastRowLift(shown = true, buttonHeight = 56, buttonMargin = 16, gap = 8, listPaddingBottom = 8))
        // Signal's list has no bottom padding of its own.
        assertEquals(80, lastRowLift(shown = true, buttonHeight = 56, buttonMargin = 16, gap = 8, listPaddingBottom = 0))
    }

    @Test
    fun `with no button shown the last row ends at the foot`() {
        assertEquals(0, lastRowLift(shown = false, buttonHeight = 56, buttonMargin = 16, gap = 8, listPaddingBottom = 8))
        assertEquals(0, lastRowLift(shown = true, buttonHeight = 0, buttonMargin = 16, gap = 8, listPaddingBottom = 8))
    }

    @Test
    fun `a list padded deeper than the button needs no lift`() {
        assertEquals(0, lastRowLift(shown = true, buttonHeight = 56, buttonMargin = 16, gap = 8, listPaddingBottom = 120))
    }
}
