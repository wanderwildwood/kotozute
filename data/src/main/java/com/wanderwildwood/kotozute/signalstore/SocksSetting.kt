package com.wanderwildwood.kotozute.signalstore

import android.content.SharedPreferences
import com.wanderwildwood.kotozute.util.Preferences
import org.signal.libsignal.net.Network
import org.signal.network.config.SocksProxy
import timber.log.Timber
import java.io.IOException

/**
 * The SOCKS5 proxy setting, as Settings stores it, and what it means for each connection.
 *
 * Modelled on Molly's "SOCKS proxy and Tor support": one switch and an address, Orbot's
 * 127.0.0.1:9050 offered by default. When it is on, every connection to Signal goes through it
 * -- the chat sockets, contact discovery, PIN recovery and key transparency through libsignal,
 * and every OkHttp client (REST, CDNs, storage, provisioning, SVR2, GIFs) through
 * [org.signal.network.config.SocksRouting]. Names are resolved by the proxy, never here.
 *
 * ⛔ **Fails closed.** A proxy that is on but cannot be reached is a Signal that cannot be
 * reached -- the ordinary "not connected" state -- and never a quiet fallback to a direct
 * connection. An address that is on but unusable (which Settings does not let through, but a
 * stored value is not to be trusted) blocks everything rather than being ignored.
 */
data class SocksSetting(val enabled: Boolean, val host: String, val port: Int) {

    sealed interface Route {
        /** No proxy: every connection is made as it always was. */
        data object Direct : Route
        data class Socks(val proxy: SocksProxy) : Route
        /** On, but the address is unusable. Nothing may connect. */
        data object Blocked : Route
    }

    fun route(): Route = when {
        !enabled -> Route.Direct
        isValidHost(host) && isValidPort(port) -> Route.Socks(SocksProxy(normalizeHost(host), port))
        else -> Route.Blocked
    }

    companion object {
        val OFF = SocksSetting(false, Preferences.SIGNAL_PROXY_DEFAULT_HOST, Preferences.SIGNAL_PROXY_DEFAULT_PORT)

        /**
         * libsignal's name for SOCKS5 with the lookup done by the proxy (`socks5h`, as curl
         * spells it). Plain `socks5` would resolve Signal's host names on this phone first,
         * which is the DNS leak this setting exists to close.
         */
        const val LIBSIGNAL_SCHEME = "socks5h"

        /**
         * Where every connection goes while the setting is on but unusable: the discard port on
         * this phone, which nothing listens on, so every attempt fails at once. Molly's
         * `DUMMY_PROXY`, for the same reason.
         */
        val BLACKHOLE = SocksProxy("127.0.0.1", 9)

        private val HOSTNAME = Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*\\.?$")
        private val IPV6 = Regex("^[0-9A-Fa-f:.]+$")

        /** A host name, an IPv4 address or an IPv6 address (bracketed or not). Nothing else. */
        fun isValidHost(raw: String): Boolean {
            val host = normalizeHost(raw)
            if (host.isEmpty() || host.length > 253) return false
            if (':' in host) return IPV6.matches(host) && host.count { it == ':' } >= 2
            return HOSTNAME.matches(host)
        }

        fun isValidPort(port: Int): Boolean = port in 1..65535

        /** The port as typed, or null for anything that is not a usable port. */
        fun parsePort(text: String): Int? = text.trim().toIntOrNull()?.takeIf(::isValidPort)

        /** Trimmed, and an IPv6 address out of its brackets: both libsignal and Java take it bare. */
        fun normalizeHost(raw: String): String = raw.trim().removeSurrounding("[", "]")

        fun read(prefs: SharedPreferences): SocksSetting = SocksSetting(
            prefs.getBoolean(Preferences.SIGNAL_PROXY_ENABLED, false),
            prefs.getString(Preferences.SIGNAL_PROXY_HOST, null) ?: Preferences.SIGNAL_PROXY_DEFAULT_HOST,
            prefs.getInt(Preferences.SIGNAL_PROXY_PORT, Preferences.SIGNAL_PROXY_DEFAULT_PORT)
        )
    }
}

/**
 * Points libsignal's connections -- both chat sockets, CDSI, SVR, key transparency -- at
 * [route]. New connections only: libsignal leaves an open one alone, so the caller restarts
 * the sockets after a change.
 */
internal fun Network.applySocks(route: SocksSetting.Route) {
    when (route) {
        SocksSetting.Route.Direct -> clearProxy()
        SocksSetting.Route.Blocked -> {
            Timber.w("signal proxy: on, but the address is unusable; refusing every connection")
            setInvalidProxy()
        }
        is SocksSetting.Route.Socks -> try {
            setProxy(SocksSetting.LIBSIGNAL_SCHEME, route.proxy.host, route.proxy.port, null, null)
            Timber.i("signal proxy: libsignal through SOCKS5 port %d", route.proxy.port)
        } catch (e: IOException) {
            // Not a fallback to direct: upstream's rule for a proxy the person chose.
            Timber.w(e, "signal proxy: libsignal refused the address; refusing every connection")
            setInvalidProxy()
        }
    }
}
