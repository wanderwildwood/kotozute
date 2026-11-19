package org.signal.network.config

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The SOCKS routing every Signal OkHttp client is built with, tested against a real socket on
 * each side: a stand-in SOCKS5 proxy that records what it was asked for, and a stand-in web
 * server that counts what reached it directly.
 */
class SocksRoutingTest {

  private val closers = mutableListOf<ServerSocket>()

  @After
  fun tearDown() {
    closers.forEach { runCatching { it.close() } }
  }

  @Test
  fun `the proxy is a SOCKS proxy at an unresolved address`() {
    val proxy = SocksProxy("127.0.0.1", 9050).toJavaProxy()
    assertThat(proxy.type()).isEqualTo(Proxy.Type.SOCKS)
    val address = proxy.address() as InetSocketAddress
    assertThat(address.isUnresolved).isTrue()
    assertThat(address.hostString).isEqualTo("127.0.0.1")
    assertThat(address.port).isEqualTo(9050)
  }

  @Test
  fun `off, the selector answers as the client would have without it`() {
    val fallback = StubSelector(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("content.example", 443)))
    val selector = SocksRouting.Selector({ null }, fallback)
    assertThat(selector.select(URI.create("https://chat.signal.org"))).isEqualTo(fallback.answer)
  }

  @Test
  fun `on, the selector answers with the proxy and nothing else`() {
    val fallback = StubSelector(Proxy.NO_PROXY)
    val selector = SocksRouting.Selector({ SocksProxy("127.0.0.1", 9050) }, fallback)
    assertThat(selector.select(URI.create("https://chat.signal.org")))
      .containsExactly(SocksProxy("127.0.0.1", 9050).toJavaProxy())
  }

  @Test
  fun `a null source leaves the builder untouched`() {
    val builder = OkHttpClient.Builder()
    SocksRouting.apply(builder, null)
    val client = builder.build()
    assertThat(client.networkInterceptors.size).isEqualTo(0)
    assertThat(client.proxySelector === ProxySelector.getDefault() || ProxySelector.getDefault() == null).isTrue()
  }

  @Test
  fun `the proxy is handed the host name, never an address looked up here`() {
    val socks = FakeSocks()
    val client = SocksRouting.apply(OkHttpClient.Builder(), { SocksProxy("127.0.0.1", socks.port) })
      .retryOnConnectionFailure(false)
      .connectTimeout(5, TimeUnit.SECONDS)
      .build()

    // `.invalid` never resolves: had OkHttp looked it up locally, the call would fail before
    // the proxy saw anything.
    val failure = runCatching {
      client.newCall(Request.Builder().url("http://chat.signal.invalid/v1/x").build()).execute().close()
    }.exceptionOrNull()

    assertThat(failure is IOException).isTrue()
    assertThat(socks.requests).containsExactly("domain:chat.signal.invalid:80")
  }

  @Test
  fun `an unreachable proxy is a failed request, not a direct one`() {
    val web = FakeWeb()
    val dead = ServerSocket(0).also { it.close() }.localPort
    val client = SocksRouting.apply(OkHttpClient.Builder(), { SocksProxy("127.0.0.1", dead) })
      .connectTimeout(5, TimeUnit.SECONDS)
      .build()

    val failure = runCatching {
      client.newCall(Request.Builder().url("http://127.0.0.1:${web.port}/").build()).execute().close()
    }.exceptionOrNull()

    assertThat(failure is IOException).isTrue()
    assertThat(web.served.get()).isEqualTo(0)
  }

  @Test
  fun `a pooled direct connection is not reused once the proxy is on`() {
    val web = FakeWeb()
    val socks = FakeSocks()
    var current: SocksProxy? = null
    val client = SocksRouting.apply(OkHttpClient.Builder(), { current })
      .connectTimeout(5, TimeUnit.SECONDS)
      .build()
    val url = "http://127.0.0.1:${web.port}/"

    client.newCall(Request.Builder().url(url).build()).execute().use { assertThat(it.code).isEqualTo(200) }
    assertThat(web.served.get()).isEqualTo(1)
    assertThat(client.connectionPool.idleConnectionCount()).isEqualTo(1)

    current = SocksProxy("127.0.0.1", socks.port)
    // The first may be refused by the guard outright or retried by OkHttp; either way the
    // pooled connection is closed, and the next call has to open a new one.
    runCatching { client.newCall(Request.Builder().url(url).build()).execute().close() }
    runCatching { client.newCall(Request.Builder().url(url).build()).execute().close() }

    // Nothing more reached the web server directly; the new connections went to the proxy.
    assertThat(web.served.get()).isEqualTo(1)
    assertThat(socks.requests.isNotEmpty()).isTrue()
    assertThat(socks.requests.toSet()).isEqualTo(setOf("domain:127.0.0.1:${web.port}"))
  }

  @Test
  fun `turning the proxy off goes direct again`() {
    val web = FakeWeb()
    val socks = FakeSocks()
    var current: SocksProxy? = SocksProxy("127.0.0.1", socks.port)
    val client = SocksRouting.apply(OkHttpClient.Builder(), { current })
      .retryOnConnectionFailure(false)
      .connectTimeout(5, TimeUnit.SECONDS)
      .build()
    val url = "http://127.0.0.1:${web.port}/"

    runCatching { client.newCall(Request.Builder().url(url).build()).execute().close() }
    assertThat(web.served.get()).isEqualTo(0)

    current = null
    client.newCall(Request.Builder().url(url).build()).execute().use { assertThat(it.code).isEqualTo(200) }
    assertThat(web.served.get()).isEqualTo(1)
  }

  // --- stand-ins ---------------------------------------------------------------------------

  private class StubSelector(vararg proxies: Proxy) : ProxySelector() {
    val answer = proxies.toList()
    override fun select(uri: URI?): List<Proxy> = answer
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
  }

  /** A SOCKS5 server that records each CONNECT and refuses it (reply 0x05, "connection refused"). */
  private inner class FakeSocks {
    private val server = ServerSocket(0).also { closers += it }
    val port = server.localPort
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    init {
      thread(isDaemon = true) {
        while (!server.isClosed) {
          val socket = runCatching { server.accept() }.getOrNull() ?: break
          runCatching { handle(socket) }
          runCatching { socket.close() }
        }
      }
    }

    private fun handle(socket: Socket) {
      val input = socket.getInputStream()
      val output = socket.getOutputStream()
      check(input.read() == 5)
      val methods = input.read()
      input.readN(methods)
      output.write(byteArrayOf(5, 0))
      output.flush()
      check(input.read() == 5)
      check(input.read() == 1) // CONNECT
      input.read() // reserved
      val target = when (input.read()) {
        1 -> "ipv4:" + input.readN(4).joinToString(".") { (it.toInt() and 0xff).toString() }
        3 -> "domain:" + String(input.readN(input.read()), Charsets.US_ASCII)
        4 -> "ipv6:" + input.readN(16).size
        else -> "unknown"
      }
      val port = (input.read() shl 8) or input.read()
      requests += "$target:$port"
      output.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
      output.flush()
    }
  }

  /** An HTTP/1.1 server that answers every request with 200 and keeps the connection open. */
  private inner class FakeWeb {
    private val server = ServerSocket(0).also { closers += it }
    val port = server.localPort
    val served = AtomicInteger()

    init {
      thread(isDaemon = true) {
        while (!server.isClosed) {
          val socket = runCatching { server.accept() }.getOrNull() ?: break
          thread(isDaemon = true) {
            runCatching {
              val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
              val output = socket.getOutputStream()
              while (true) {
                val first = reader.readLine() ?: break
                if (first.isEmpty()) continue
                while (reader.readLine()?.isNotEmpty() == true) Unit
                served.incrementAndGet()
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray(Charsets.US_ASCII))
                output.flush()
              }
            }
            runCatching { socket.close() }
          }
        }
      }
    }
  }
}

private fun InputStream.readN(n: Int): ByteArray {
  val out = ByteArray(n)
  var read = 0
  while (read < n) {
    val r = read(out, read, n - read)
    if (r < 0) throw IOException("eof")
    read += r
  }
  return out
}
