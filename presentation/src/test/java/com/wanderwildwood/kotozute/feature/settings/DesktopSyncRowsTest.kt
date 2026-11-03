package com.wanderwildwood.kotozute.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Paired browsers" and "Forget all browsers" show whenever there is something to forget. */
class DesktopSyncRowsTest {

    @Test
    fun `shown while Desktop Sync is on, paired or not`() {
        assertTrue(desktopSyncBrowserRowsShown(enabled = true, paired = 0))
        assertTrue(desktopSyncBrowserRowsShown(enabled = true, paired = 3))
    }

    @Test
    fun `shown while it is off if a browser is still paired`() {
        assertTrue(desktopSyncBrowserRowsShown(enabled = false, paired = 1))
        assertTrue(desktopSyncBrowserRowsShown(enabled = false, paired = 8))
    }

    @Test
    fun `hidden while it is off with nothing paired`() {
        assertFalse(desktopSyncBrowserRowsShown(enabled = false, paired = 0))
    }
}
