package com.wanderwildwood.kotozute.feature.desktopsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rules Desktop Sync took over from Memo's stricter server, each with the old behaviour it
 * replaces shown to be refused: one shared token, a token in a link, any address with "VPN only"
 * off, no content policy, and coming back by itself after a reboot.
 */
class DesktopSyncSecurityTest {

    private var clock = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun browsers(initial: String = "", save: (String) -> Unit = {}) =
        PairedBrowsers(now = { clock }, initial = initial, save = save)

    // ---------------------------------------------------------------- one token per browser

    @Test
    fun `each browser gets a token of its own`() {
        val b = browsers()
        val one = b.issue("Firefox on Linux")
        val two = b.issue("Chrome on Windows")
        assertNotEquals("two browsers were handed the same token", one, two)
        assertNotNull(b.check(one))
        assertNotNull(b.check(two))
        assertNotEquals(b.check(one), b.check(two))
        assertEquals(64, one.length)
    }

    @Test
    fun `forgetting one browser stops its token and leaves the other working`() {
        val b = browsers()
        val one = b.issue("Firefox on Linux")
        val two = b.issue("Chrome on Windows")
        var closed: Set<String> = emptySet()
        b.onRemoved = { closed = it }
        val id = b.check(one)!!
        assertTrue(b.remove(id))
        assertNull("a forgotten browser's token still worked", b.check(one))
        assertNotNull("forgetting one browser stopped another", b.check(two))
        assertEquals(setOf(id), closed)
        assertEquals(listOf("Chrome on Windows"), b.list().map { it.label })
    }

    @Test
    fun `forget all stops every token`() {
        val b = browsers()
        val one = b.issue("a")
        val two = b.issue("b")
        b.removeAll()
        assertNull(b.check(one))
        assertNull(b.check(two))
        assertTrue(b.list().isEmpty())
    }

    @Test
    fun `the phone keeps a hash, never the token, and reads it back`() {
        var saved = ""
        val b = browsers(save = { saved = it })
        val token = b.issue("Firefox on Linux")
        assertFalse("the token was written down on the phone", saved.contains(token))
        assertTrue(saved.contains(PairedBrowsers.hash(token)))
        val again = browsers(initial = saved)
        assertNotNull("a pairing did not survive a restart of the app", again.check(token))
    }

    @Test
    fun `a browser unused for 30 days is forgotten`() {
        val b = browsers()
        val token = b.issue("a")
        clock += PairedBrowsers.EXPIRES_MS + 1
        assertNull(b.check(token))
    }

    @Test
    fun `at most eight browsers, the longest unused goes first`() {
        val b = browsers()
        val first = b.issue("first")
        repeat(PairedBrowsers.MAX_BROWSERS) { clock += 1; b.issue("n$it") }
        assertNull(b.check(first))
        assertEquals(PairedBrowsers.MAX_BROWSERS, b.list().size)
    }

    @Test
    fun `labels cannot carry control characters or run long`() {
        assertEquals("Firefox", cleanLabel("Fire\tfox\n"))
        assertEquals(PairedBrowsers.LABEL_MAX, cleanLabel("x".repeat(100)).length)
        assertEquals("Firefox on Linux", browserLabel("Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"))
    }

    // ------------------------------------------------------- the old shared token, carried over

    @Test
    fun `the old shared token is no longer accepted as a browser's token`() {
        val b = browsers()
        b.importLegacy("TESTTESTTESTTESTTEST0000", "Paired before this update")
        assertNull("the shared token still opened everything", b.check("TESTTESTTESTTESTTEST0000"))
    }

    @Test
    fun `a browser holding the shared token trades it for its own`() {
        val b = browsers()
        b.importLegacy("TESTTESTTESTTESTTEST0000", "Paired before this update")
        val own = b.exchangeLegacy("TESTTESTTESTTESTTEST0000", "Firefox on Linux")
        assertNotNull(own)
        assertNotEquals("TESTTESTTESTTESTTEST0000", own)
        assertNotNull(b.check(own))
        // Typed by hand once, so its case never mattered.
        assertNotNull(b.exchangeLegacy("testtesttesttesttest0000", "Chrome on Linux"))
        assertNull(b.exchangeLegacy("WRONGWRONGWRONGWRONG0000", "x"))
        assertNull("an ordinary token was traded as if it were the shared one", b.exchangeLegacy(own, "x"))
    }

    @Test
    fun `the shared token can be forgotten, and runs out 30 days after the update however used`() {
        val b = browsers()
        b.importLegacy("TESTTESTTESTTESTTEST0000", "Paired before this update")
        assertTrue(b.list().single().legacy)
        clock += 29 * day
        assertNotNull(b.exchangeLegacy("TESTTESTTESTTESTTEST0000", "a"))
        clock += 2 * day
        assertNull("the shared token outlived its 30 days", b.exchangeLegacy("TESTTESTTESTTESTTEST0000", "b"))

        val c = browsers()
        c.importLegacy("TESTTESTTESTTESTTEST0000", "Paired before this update")
        c.remove(PairedBrowsers.LEGACY_ID)
        assertNull(c.exchangeLegacy("TESTTESTTESTTESTTEST0000", "a"))
    }

    // ---------------------------------------------------------------- no token in a URL

    private val noQuery = emptyMap<String, List<String>>()

    @Test
    fun `a token in the query string is ignored`() {
        val query = mapOf("token" to listOf("abc123"))
        assertNull("a ?token= was accepted", presentedToken("GET", emptyMap(), query, 8420))
        assertNull("a ?token= was accepted", presentedToken("POST", emptyMap(), query, 8420))
    }

    @Test
    fun `the header is taken for any request`() {
        assertEquals("abc123", presentedToken("GET", mapOf("authorization" to "Bearer abc123"), noQuery, 8420))
        assertEquals("abc123", presentedToken("POST", mapOf("authorization" to "Bearer abc123"), noQuery, 8420))
        assertNull(presentedToken("GET", mapOf("authorization" to "Basic abc123"), noQuery, 8420))
    }

    @Test
    fun `the cookie is taken for a GET only, and only this port's`() {
        val cookie = mapOf("cookie" to "theme=dark; kotozute_8420=abc123")
        assertEquals("abc123", presentedToken("GET", cookie, noQuery, 8420))
        assertNull("a change rode on the cookie", presentedToken("POST", cookie, noQuery, 8420))
        assertNull("another port's cookie was taken", presentedToken("GET", cookie, noQuery, 8421))
    }

    @Test
    fun `the cookie cannot be read by the page and never leaves for another site`() {
        val c = sessionCookie(8420, "abc123", secure = false)
        assertTrue(c.startsWith("kotozute_8420=abc123;"))
        assertTrue(c.contains("HttpOnly"))
        assertTrue(c.contains("SameSite=Strict"))
        assertFalse(c.contains("Secure"))
        assertTrue(sessionCookie(8420, "abc123", secure = true).contains("; Secure"))
        assertTrue(clearedSessionCookie(8420, false).contains("Max-Age=0"))
    }

    @Test
    fun `the links the phone shows carry no token`() {
        val url = DesktopSyncService.linkUrl(false, "192.0.2.7", 8420)
        assertEquals("http://192.0.2.7:8420", url)
        assertFalse(url.contains("token"))
        assertEquals("https://100.64.0.9:8420/signal-link", DesktopSyncService.linkUrl(true, "100.64.0.9", 8420, "/signal-link"))
    }

    @Test
    fun `a websocket is taken only from the page's own origin`() {
        assertTrue(sameOrigin("http://192.168.1.20:8420", "192.168.1.20:8420"))
        assertTrue(sameOrigin("https://[fd7a:115c:a1e0::1]:8420", "[fd7a:115c:a1e0::1]:8420"))
        assertFalse("another site's page opened a socket", sameOrigin("http://example.com", "192.168.1.20:8420"))
        assertFalse(sameOrigin("http://192.168.1.20:8000", "192.168.1.20:8420"))
        assertFalse(sameOrigin(null, "192.168.1.20:8420"))
        assertFalse(sameOrigin("null", "192.168.1.20:8420"))
    }

    // ------------------------------------------------------ private addresses with VPN only off

    @Test
    fun `with VPN only off, private addresses are let in`() {
        listOf(
            "127.0.0.1", "::1", "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.20",
            "169.254.3.4", "100.64.0.1", "100.127.255.1", "fe80::1", "fd12:3456::1", "fc00::1",
            "fe80::1%wlan0", "::ffff:192.168.1.20",
        ).forEach { assertTrue("$it was refused", peerAllowed(it, vpnOnly = false, vpnNets = emptyList())) }
    }

    @Test
    fun `with VPN only off, public addresses are still refused`() {
        listOf(
            "8.8.8.8", "1.1.1.1", "172.32.0.1", "172.15.0.1", "100.128.0.1", "100.63.0.1",
            "192.169.0.1", "2001:db8::1", "2606:4700::1111", "::ffff:8.8.8.8", "fec0::1",
        ).forEach { assertFalse("$it was let in", peerAllowed(it, vpnOnly = false, vpnNets = emptyList())) }
    }

    @Test
    fun `anything that is not a literal address is refused, without a lookup`() {
        listOf(null, "", "localhost", "router.lan", "deadbeef.cafe", "10.0.0", "999.1.1.1")
            .forEach { assertFalse("$it was let in", peerAllowed(it, vpnOnly = false, vpnNets = emptyList())) }
    }

    @Test
    fun `with VPN only on, the home network is still refused`() {
        assertFalse(peerAllowed("192.168.1.20", vpnOnly = true, vpnNets = emptyList()))
        assertTrue(peerAllowed("100.64.0.53", vpnOnly = true, vpnNets = emptyList()))
    }

    // ---------------------------------------------------------------------------- headers

    @Test
    fun `every answer says no caching, no sniffing, no referrer, no framing`() {
        assertEquals("no-store", SECURITY_HEADERS["Cache-Control"])
        assertEquals("nosniff", SECURITY_HEADERS["X-Content-Type-Options"])
        assertEquals("no-referrer", SECURITY_HEADERS["Referrer-Policy"])
        assertEquals("DENY", SECURITY_HEADERS["X-Frame-Options"])
        assertEquals("same-origin", SECURITY_HEADERS["Cross-Origin-Resource-Policy"])
        assertFalse(SECURITY_HEADERS.keys.any { it.startsWith("Access-Control") })
    }

    @Test
    fun `the page's content policy lets nothing inline run`() {
        val csp = pageCsp("n0nce", "192.168.1.20:8420")
        assertTrue(csp.startsWith("default-src 'none'"))
        assertTrue(csp.contains("script-src 'self';"))
        assertTrue(csp.contains("style-src 'nonce-n0nce'"))
        assertTrue(csp.contains("frame-ancestors 'none'"))
        assertTrue(csp.contains("base-uri 'none'"))
        assertTrue(csp.contains("form-action 'none'"))
        assertTrue(csp.contains("connect-src 'self' ws://192.168.1.20:8420 wss://192.168.1.20:8420"))
        assertFalse(csp.contains("unsafe-inline"))
        assertFalse(csp.contains("unsafe-eval"))
        // A Host header that is not a host is left out rather than written into the policy.
        assertFalse(pageCsp("n", "evil.example; script-src *").contains("evil"))
        assertTrue(linkPageCsp("n").startsWith("default-src 'none'"))
    }

    @Test
    fun `the page's one style block carries the nonce the policy names`() {
        val html = File("src/main/assets/desktopsync/index.html").readText()
        assertTrue(html.contains("<style nonce=\"__NONCE__\">"))
        assertFalse("an inline script would be refused", Regex("<script(?![^>]*\\bsrc=)").containsMatchIn(html))
    }

    // ------------------------------------------------- once on, on until the user switches it off

    @Test
    fun `a reboot and an update both bring Desktop Sync back`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val receiver = Regex("""<receiver\s+android:name="\.feature\.desktopsync\.DesktopSyncRestartReceiver"[\s\S]*?</receiver>""")
            .find(manifest)?.value
        assertTrue("no restart receiver for Desktop Sync", receiver != null)
        assertTrue(receiver!!.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(receiver.contains("android.intent.action.MY_PACKAGE_REPLACED"))
    }

    @Test
    fun `the service is sticky and only the user's stop turns the switch off`() {
        val source = File("src/main/java/com/wanderwildwood/kotozute/feature/desktopsync/DesktopSyncService.kt").readText()
        assertTrue(source.contains("return START_STICKY"))
        assertFalse(source.contains("START_NOT_STICKY"))
        fun body(name: String): String {
            val start = source.indexOf("fun $name(")
            assertTrue("no $name", start >= 0)
            return source.substring(start, source.indexOf("\n    }\n", start))
        }
        val off = "desktopSyncEnabled.set(false)"
        assertFalse("the system ending the service turns the switch off", body("onDestroy").contains(off))
        assertFalse("a sticky restart turns the switch off", body("onStartCommand").contains(off))
        assertTrue("Stop no longer turns the switch off", body("stopRelay").contains(off))
        // The one other place: a start that could not bind, with the reason kept for Settings.
        assertEquals(2, Regex(Regex.escape(off)).findAll(source).count())
        assertTrue(source.contains("desktopSyncFailure.set(failure"))
    }
}
