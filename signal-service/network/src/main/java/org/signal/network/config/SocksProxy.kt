package org.signal.network.config

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * A SOCKS5 proxy every connection to Signal is to go through -- Orbot's port, for Tor.
 *
 * Not upstream's: Signal Android has no SOCKS setting. Modelled on Molly's "SOCKS proxy and Tor
 * support" (`Networking.kt`, `SocksProxy.java`), with one difference that matters: Molly
 * resolves names itself, over DNS-over-HTTPS through the proxy, and hands the proxy an
 * address. Here the proxy is handed the **name** (an unresolved address, which OkHttp passes on
 * as a SOCKS5 domain-name request), so no lookup of a Signal host ever leaves the phone by
 * itself, and nothing depends on a DoH server being reachable through the proxy.
 */
data class SocksProxy(val host: String, val port: Int) {

  /** Unresolved on purpose: the proxy does the lookup, not this phone's resolver. */
  fun toJavaProxy(): Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port))

  override fun toString(): String = "SocksProxy($host:$port)"
}

/**
 * Where the current proxy setting is read, at the moment a connection is made.
 *
 * A source rather than a value so that a client built before the setting changed follows it:
 * the clients here are built once and kept, and rebuilding every one of them on a change is a
 * list of call sites that would one day miss one.
 */
fun interface SocksProxySource {
  /** The proxy to use now, or null for none. */
  fun current(): SocksProxy?
}

/**
 * Puts an OkHttp client on [SocksProxySource]'s proxy.
 *
 * Two parts, and both are needed:
 *
 * - **a proxy selector** that answers with the proxy while one is set, and with whatever the
 *   client would have used otherwise ([fallback], else the system's) while none is. OkHttp asks
 *   it for every new connection, so turning the proxy on or off takes effect at the next one.
 *   With a SOCKS answer OkHttp never resolves the host itself; it passes the name on.
 *   It also never falls back to a direct connection when the proxy cannot be reached: the
 *   only route on offer is the proxy, and a failed one is a failed request.
 *
 * - **a guard** for the connections that already exist. A pooled connection opened directly a
 *   moment before the proxy was switched on would otherwise be reused for its idle lifetime,
 *   which is exactly the leak the setting exists to prevent. The guard closes such a connection
 *   before a byte of the request is written and fails the request; a retry opens a new one,
 *   through the proxy.
 *
 * A proxied connection that outlives the proxy being switched off is left alone: it leaks
 * nothing, and it ages out of the pool on its own.
 */
object SocksRouting {

  @JvmStatic
  @JvmOverloads
  fun apply(builder: OkHttpClient.Builder, source: SocksProxySource?, fallback: ProxySelector? = null): OkHttpClient.Builder {
    if (source == null) {
      return builder
    }
    return builder
      .proxySelector(Selector(source, fallback))
      .addNetworkInterceptor(Guard(source))
  }

  internal class Selector(private val source: SocksProxySource, private val fallback: ProxySelector?) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
      source.current()?.let { return listOf(it.toJavaProxy()) }
      val base = fallback ?: getDefault() ?: return listOf(Proxy.NO_PROXY)
      return base.select(uri)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
      // Only the fallback's own failures are its business; the SOCKS proxy is not its to mark.
      if (source.current() == null) {
        (fallback ?: getDefault())?.connectFailed(uri, sa, ioe)
      }
    }
  }

  internal class Guard(private val source: SocksProxySource) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
      val want = source.current()
      if (want != null) {
        val connection = chain.connection()
        val proxy = connection?.route()?.proxy
        if (proxy == null || proxy.type() != Proxy.Type.SOCKS || proxy.address() != want.toJavaProxy().address()) {
          runCatching { connection?.socket()?.close() }
          throw IOException("connection predates the SOCKS proxy setting; closed unused")
        }
      }
      return chain.proceed(chain.request())
    }
  }
}
