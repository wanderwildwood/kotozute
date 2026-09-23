package com.wanderwildwood.kotozute.feature.desktopsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launcher's text, pinned. The Exec line below is exactly what desktop-file-validate
 * accepted and what GLib's own parsing turned back into the intended script, the address
 * arriving whole with its ?token= intact. The old single-quoted script failed validation.
 */
class DesktopEntryTest {

    private val url = "http://100.97.1.2:8443?token=ABC123"

    @Test
    fun execLineIsTheValidatedOne() {
        val exec = desktopEntryFor(url).lines().single { it.startsWith("Exec=") }
        assertEquals(
            """Exec=sh -c "for b in brave-browser brave chromium chromium-browser google-chrome google-chrome-stable microsoft-edge vivaldi; do command -v \\${'$'}b >/dev/null 2>&1 && exec \\${'$'}b --app='http://100.97.1.2:8443?token=ABC123'; done; for f in com.brave.Browser com.google.Chrome org.chromium.Chromium com.microsoft.Edge com.vivaldi.Vivaldi; do flatpak info \\${'$'}f >/dev/null 2>&1 && exec flatpak run \\${'$'}f --app='http://100.97.1.2:8443?token=ABC123'; done; exec xdg-open 'http://100.97.1.2:8443?token=ABC123'"""",
            exec
        )
    }

    @Test
    fun percentIsDoubledBecauseItStartsAFieldCode() {
        val exec = desktopEntryFor("http://h:1?token=A%20B").lines().single { it.startsWith("Exec=") }
        assertTrue(exec.contains("A%%20B"))
        assertTrue(!exec.contains("A%20B"))
    }

    @Test
    fun endsWithANewline() {
        assertTrue(desktopEntryFor(url).endsWith("Terminal=false\n"))
    }
}
