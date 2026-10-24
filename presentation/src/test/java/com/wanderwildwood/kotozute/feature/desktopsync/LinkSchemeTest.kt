package com.wanderwildwood.kotozute.feature.desktopsync

import org.junit.Assert.assertEquals
import org.junit.Test

/** The link follows the socket: https exactly when "Use HTTPS" is on, or the page will not load. */
class LinkSchemeTest {

    @Test
    fun `http when HTTPS is off`() {
        assertEquals("http://192.0.2.7:8420?token=abc", DesktopSyncService.linkUrl(false, "192.0.2.7", 8420, "abc"))
    }

    @Test
    fun `https when HTTPS is on`() {
        assertEquals("https://192.0.2.7:8420?token=abc", DesktopSyncService.linkUrl(true, "192.0.2.7", 8420, "abc"))
    }

    @Test
    fun `a path comes before the token`() {
        assertEquals(
            "https://100.64.0.9:8420/signal-link?token=abc",
            DesktopSyncService.linkUrl(true, "100.64.0.9", 8420, "abc", "/signal-link"),
        )
    }
}
