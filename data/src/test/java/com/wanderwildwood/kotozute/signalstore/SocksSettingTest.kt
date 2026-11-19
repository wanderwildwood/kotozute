package com.wanderwildwood.kotozute.signalstore

import android.content.SharedPreferences
import com.wanderwildwood.kotozute.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.signal.network.config.SocksProxy

/**
 * The SOCKS proxy setting: what an address is allowed to be, and what each state routes to.
 *
 * The routing is the part that must not be wrong in the quiet direction. Off has to be no proxy
 * at all (every connection as it always was); on with a good address has to be that address;
 * and on with a bad one has to block, never fall through to direct.
 */
class SocksSettingTest {

    @Test
    fun `off routes direct whatever the address says`() {
        assertEquals(SocksSetting.Route.Direct, SocksSetting(false, "127.0.0.1", 9050).route())
        assertEquals(SocksSetting.Route.Direct, SocksSetting(false, "", -1).route())
        assertEquals(SocksSetting.Route.Direct, SocksSetting.OFF.route())
    }

    @Test
    fun `the default is Orbot's port and off`() {
        assertFalse(SocksSetting.OFF.enabled)
        assertEquals("127.0.0.1", SocksSetting.OFF.host)
        assertEquals(9050, SocksSetting.OFF.port)
    }

    @Test
    fun `on with a good address routes through it`() {
        assertEquals(
            SocksSetting.Route.Socks(SocksProxy("127.0.0.1", 9050)),
            SocksSetting(true, "127.0.0.1", 9050).route()
        )
        assertEquals(
            SocksSetting.Route.Socks(SocksProxy("proxy.example.org", 1080)),
            SocksSetting(true, "  proxy.example.org ", 1080).route()
        )
        assertEquals(
            SocksSetting.Route.Socks(SocksProxy("::1", 9050)),
            SocksSetting(true, "[::1]", 9050).route()
        )
    }

    @Test
    fun `on with a bad address blocks rather than going direct`() {
        assertEquals(SocksSetting.Route.Blocked, SocksSetting(true, "", 9050).route())
        assertEquals(SocksSetting.Route.Blocked, SocksSetting(true, "127.0.0.1", 0).route())
        assertEquals(SocksSetting.Route.Blocked, SocksSetting(true, "127.0.0.1", 65536).route())
        assertEquals(SocksSetting.Route.Blocked, SocksSetting(true, "bad host", 9050).route())
    }

    @Test
    fun `the blackhole is a port nothing answers on, on this phone`() {
        assertEquals("127.0.0.1", SocksSetting.BLACKHOLE.host)
        assertEquals(9, SocksSetting.BLACKHOLE.port)
    }

    @Test
    fun `libsignal is asked to resolve names at the proxy`() {
        // socks5 would look Signal's hosts up on this phone first: the DNS leak.
        assertEquals("socks5h", SocksSetting.LIBSIGNAL_SCHEME)
    }

    @Test
    fun `hosts that are allowed`() {
        listOf(
            "127.0.0.1", "localhost", "proxy.example.org", "proxy.example.org.", "a-b.c",
            "10.0.0.2", "::1", "[::1]", "fe80::1", "2001:db8::2", " 127.0.0.1 ",
            "abcdefghijklmnopqrstuvwxyz234567.onion"
        ).forEach { assertTrue(it, SocksSetting.isValidHost(it)) }
    }

    @Test
    fun `hosts that are refused`() {
        listOf(
            "", "   ", "bad host", "-leading.dash", "trailing-.dash", "two..dots", "http://127.0.0.1",
            "127.0.0.1:9050", "user@host", "host/path", "a".repeat(254), ":", "[]", "}"
        ).forEach { assertFalse(it, SocksSetting.isValidHost(it)) }
    }

    @Test
    fun `ports from 1 to 65535 and nothing else`() {
        assertEquals(9050, SocksSetting.parsePort("9050"))
        assertEquals(9050, SocksSetting.parsePort(" 9050 "))
        assertEquals(1, SocksSetting.parsePort("1"))
        assertEquals(65535, SocksSetting.parsePort("65535"))
        assertNull(SocksSetting.parsePort("0"))
        assertNull(SocksSetting.parsePort("65536"))
        assertNull(SocksSetting.parsePort("-1"))
        assertNull(SocksSetting.parsePort(""))
        assertNull(SocksSetting.parsePort("90 50"))
        assertNull(SocksSetting.parsePort("port"))
        assertNull(SocksSetting.parsePort("99999999999"))
    }

    @Test
    fun `an IPv6 address comes out of its brackets`() {
        assertEquals("::1", SocksSetting.normalizeHost("[::1]"))
        assertEquals("127.0.0.1", SocksSetting.normalizeHost(" 127.0.0.1\n"))
    }

    @Test
    fun `read takes the stored keys and falls back to the defaults`() {
        val prefs = mock(SharedPreferences::class.java)
        `when`(prefs.getBoolean(eq(Preferences.SIGNAL_PROXY_ENABLED), anyBoolean())).thenReturn(true)
        `when`(prefs.getString(Preferences.SIGNAL_PROXY_HOST, null)).thenReturn("10.0.0.2")
        `when`(prefs.getInt(eq(Preferences.SIGNAL_PROXY_PORT), anyInt())).thenReturn(1080)
        assertEquals(SocksSetting(true, "10.0.0.2", 1080), SocksSetting.read(prefs))

        val empty = mock(SharedPreferences::class.java)
        `when`(empty.getBoolean(eq(Preferences.SIGNAL_PROXY_ENABLED), anyBoolean())).thenAnswer { it.arguments[1] }
        `when`(empty.getString(Preferences.SIGNAL_PROXY_HOST, null)).thenReturn(null)
        `when`(empty.getInt(eq(Preferences.SIGNAL_PROXY_PORT), anyInt())).thenAnswer { it.arguments[1] }
        assertEquals(SocksSetting.OFF, SocksSetting.read(empty))
    }

    @Test
    fun `the setting is off unless somebody turned it on`() {
        assertEquals("signalProxyEnabled", Preferences.SIGNAL_PROXY_ENABLED)
        assertEquals("127.0.0.1", Preferences.SIGNAL_PROXY_DEFAULT_HOST)
        assertEquals(9050, Preferences.SIGNAL_PROXY_DEFAULT_PORT)
    }
}
