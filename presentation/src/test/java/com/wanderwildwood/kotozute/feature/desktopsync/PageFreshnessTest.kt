package com.wanderwildwood.kotozute.feature.desktopsync

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The list the page shows when someone comes back to it is the phone's list now.
 *
 * A socket that died without a close (a laptop asleep, a phone gone off its VPN) left the page
 * saying "live" and polling every 30 s, so coming back to the window showed the older rows first
 * and the newer ones some seconds later. The phone's first answer was always current; the page
 * was not asking.
 */
class PageFreshnessTest {

    private val page = File("src/main/assets/desktopsync/app.js").readText()
    private val server = File("src/main/java/com/wanderwildwood/kotozute/feature/desktopsync/DesktopSyncServer.kt").readText()

    @Test
    fun `the phone says it is there more often than the page gives up on it`() {
        val pingSeconds = Regex("""PING_INTERVAL_SECONDS = (\d+)L""").find(server)!!.groupValues[1].toLong()
        val silentMs = Regex("""const SOCKET_SILENT_MS = (\d+);""").find(page)!!.groupValues[1].toLong()
        // Two passes may be missed to a slow network before the socket is replaced.
        assertTrue("silent limit $silentMs ms vs ping every $pingSeconds s", silentMs > 2 * pingSeconds * 1000)
        assertTrue("the ping pass no longer says it is alive", server.contains("socket.send(ALIVE_PAYLOAD)"))
    }

    @Test
    fun `the page knows the alive message and does not refetch on it`() {
        assertTrue(ALIVE_PAYLOAD.contains("\"alive\""))
        assertTrue(page.contains("e.data.indexOf('\"alive\"') >= 0) return;"))
    }

    @Test
    fun `coming back to the window, the network or a new socket fetches the list`() {
        assertTrue(page.contains("window.addEventListener('focus', refreshOnReturn);"))
        assertTrue(page.contains("window.addEventListener('pageshow', refreshOnReturn);"))
        assertTrue(page.contains("window.addEventListener('online',"))
        val onOpen = page.substring(page.indexOf("ws.addEventListener('open'"), page.indexOf("ws.addEventListener('message'"))
        assertTrue("a socket that opens does not catch up", onOpen.contains("refresh();"))
    }

    @Test
    fun `a sleep or a quiet socket replaces the socket`() {
        val tick = page.substring(page.indexOf("let lastTickAt"), page.indexOf("function refreshOnReturn"))
        assertTrue(tick.contains("slept"))
        assertTrue(tick.contains("now - lastHeardAt > SOCKET_SILENT_MS"))
        assertTrue(tick.contains("restartSocket();"))
    }
}
