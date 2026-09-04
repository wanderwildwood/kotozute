package com.wanderwildwood.kotozute.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing payload is the one piece of untrusted input in the setup flow, and what it
 * yields is the token and the certificate to pin. Something malformed must be refused
 * outright rather than half-accepted into a configuration that cannot work.
 */
class BridgeConfigTest {

    // Invented, and shaped like the real thing only so the parser is exercised on the
    // right lengths and alphabet. Never paste a working pairing link in here: the token is
    // the whole credential -- the bridge has no second factor and no account behind it --
    // and a test fixture is as public as the rest of the repository.
    private val token = "EXAMPLE0TOKEN0NOT0A0REAL0ONE0000000000000AA"
    private val fp = "0000000000000000000000000000000000000000000000000000000000000000"

    private fun payload(
        host: String = "192.168.1.50",
        port: String = "8422",
        t: String = token,
        f: String = fp
    ) = "kotozute-bridge://$host:$port/?token=$t&fp=$f"

    @Test
    fun `parses a well formed payload`() {
        val cfg = BridgeConfig.parse(payload())
        assertNotNull(cfg)
        assertEquals("192.168.1.50", cfg!!.host)
        assertEquals(8422, cfg.port)
        assertEquals(token, cfg.token)
        assertEquals(fp, cfg.fingerprint)
        assertTrue(cfg.isValid())
    }

    @Test
    fun `base url is https, because the pin is on a certificate`() {
        assertEquals("https://192.168.1.50:8422", BridgeConfig.parse(payload())!!.baseUrl)
    }

    @Test
    fun `accepts a hostname as readily as an address`() {
        assertEquals("bridge.home", BridgeConfig.parse(payload(host = "bridge.home"))!!.host)
    }

    @Test
    fun `a fingerprint with colons is accepted and normalised`() {
        val colonised = fp.chunked(2).joinToString(":")
        val cfg = BridgeConfig.parse(payload(f = colonised))
        assertNotNull(cfg)
        assertEquals(fp, cfg!!.fingerprint)
    }

    @Test
    fun `a lowercase fingerprint is normalised, since it is compared as text`() {
        val cfg = BridgeConfig.parse(payload(f = fp.lowercase()))
        assertEquals(fp, cfg!!.fingerprint)
    }

    @Test
    fun `refuses anything that is not a pairing payload`() {
        assertNull(BridgeConfig.parse("https://192.168.1.50:8422/?token=$token&fp=$fp"))
        assertNull(BridgeConfig.parse("not a link at all"))
        assertNull(BridgeConfig.parse(""))
        assertNull(BridgeConfig.parse("   "))
    }

    @Test
    fun `refuses a payload missing either half of what it must carry`() {
        assertNull(BridgeConfig.parse("kotozute-bridge://192.168.1.50:8422/?fp=$fp"))
        assertNull(BridgeConfig.parse("kotozute-bridge://192.168.1.50:8422/?token=$token"))
    }

    @Test
    fun `refuses a truncated fingerprint`() {
        // A short fingerprint would still be compared, and would match nothing -- but
        // silently pairing into something that can never connect is worse than refusing.
        assertNull(BridgeConfig.parse(payload(f = fp.take(40))))
    }

    @Test
    fun `refuses a token too short to be one`() {
        assertNull(BridgeConfig.parse(payload(t = "short")))
    }

    @Test
    fun `refuses an impossible port`() {
        assertNull(BridgeConfig.parse(payload(port = "0")))
        assertNull(BridgeConfig.parse(payload(port = "70000")))
        assertNull(BridgeConfig.parse(payload(port = "abc")))
    }

    @Test
    fun `tolerates surrounding whitespace, because this is pasted`() {
        assertNotNull(BridgeConfig.parse("  ${payload()}\n"))
    }

    @Test
    fun `parameter order does not matter`() {
        val swapped = "kotozute-bridge://192.168.1.50:8422/?fp=$fp&token=$token"
        val cfg = BridgeConfig.parse(swapped)
        assertNotNull(cfg)
        assertEquals(token, cfg!!.token)
    }
}
