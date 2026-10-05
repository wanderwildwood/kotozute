package com.wanderwildwood.kotozute.signalstore

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * GIF search, as Signal's GIF keyboard does it (forum #108).
 *
 * Upstream's `SignalGifKeyboardRepository` asks GIPHY for trending or matching GIFs, and
 * `GiphyMp4Repository` fetches the one chosen as GIPHY's MP4, which is sent as `video/mp4`
 * flagged GIF (see `com.wanderwildwood.kotozute.signal.Gifs`). Every request goes **through
 * Signal's content proxy**, `contentproxy.signal.org:443` -- an HTTP CONNECT proxy that only
 * opens tunnels to giphy.com. GIPHY sees the proxy's address, never this phone's, and the
 * proxy sees only a TLS tunnel to GIPHY, never what was searched for.
 *
 * The API key is Signal's own (`app/build.gradle.kts`, `GIPHY_API_KEY`), as Molly and the
 * other forks use it.
 */
object Giphy {

    /** Upstream's `BuildConfig.GIPHY_API_KEY`. */
    private const val API_KEY = "3o6ZsYH6U6Eri53TXy"

    /** Upstream's `CONTENT_PROXY_HOST` / `CONTENT_PROXY_PORT`. */
    private const val PROXY_HOST = "contentproxy.signal.org"
    private const val PROXY_PORT = 443

    /** Upstream's `ContentProxySelector.WHITELISTED_DOMAINS`. */
    private const val ALLOWED_DOMAIN = "giphy.com"

    /** Upstream's `GiphyImage.MAX_SIZE`: the largest rendition it will pick, 2 MB. */
    const val MAX_SIZE = 2L * 1024 * 1024

    /** One GIF as the picker needs it. */
    data class Gif(
        /** A still to show in the grid. */
        val stillUrl: String,
        /** What is sent: the largest MP4 under [MAX_SIZE]. */
        val mp4Url: String,
        val width: Int,
        val height: Int
    )

    data class Page(val gifs: List<Gif>, val hasMore: Boolean)

    /**
     * Upstream's `ContentProxySelector`: GIPHY goes through the proxy, and anything else is
     * refused outright rather than sent direct.
     */
    private object ContentProxySelector : ProxySelector() {
        private val content = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(PROXY_HOST, PROXY_PORT)))

        override fun select(uri: URI): List<Proxy> {
            val host = uri.host
            if (host != null && (host == ALLOWED_DOMAIN || host.endsWith(".$ALLOWED_DOMAIN"))) return content
            throw IllegalArgumentException("Tried to proxy a non-whitelisted domain.")
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    /**
     * Upstream's `ContentProxySafetyInterceptor`: an https URL on giphy.com, and a redirect
     * only to another. The proxy refuses other hosts too, but a redirect is followed before
     * the selector sees it, so it is checked here first.
     */
    private object SafetyInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            if (!allowed(chain.request().url)) {
                chain.call().cancel()
                throw IOException("Request was for a non-whitelisted domain!")
            }
            val response = chain.proceed(chain.request())
            if (response.isRedirect && !allowed(response.header("Location")?.toHttpUrlOrNull())) {
                response.close()
                chain.call().cancel()
                throw IOException("Tried to redirect to a non-whitelisted domain!")
            }
            return response
        }

        private fun allowed(url: HttpUrl?): Boolean =
            url != null && url.scheme == "https" && url.topPrivateDomain() == ALLOWED_DOMAIN
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxySelector(ContentProxySelector)
            .addNetworkInterceptor(SafetyInterceptor)
            .build()
    }

    /** Trending when [query] is blank, as upstream's keyboard opens; otherwise a search. */
    @Throws(IOException::class)
    fun search(query: String, offset: Int, limit: Int): Page {
        val trimmed = query.trim()
        val url = "https://api.giphy.com/v1/gifs/".toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment(if (trimmed.isEmpty()) "trending" else "search")
            .addQueryParameter("api_key", API_KEY)
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .apply { if (trimmed.isNotEmpty()) addQueryParameter("q", trimmed) }
            .build()
        val body = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code ${response.code}")
            response.body?.string() ?: throw IOException("empty response")
        }
        return parse(body, offset)
    }

    /** The bytes at [url], refused past [MAX_SIZE] -- the stills and the MP4 alike. */
    @Throws(IOException::class)
    fun fetch(url: String): ByteArray =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code ${response.code}")
            val body = response.body ?: throw IOException("empty response")
            if (body.contentLength() > MAX_SIZE) throw IOException("too large")
            val bytes = body.byteStream().readBytes()
            if (bytes.size > MAX_SIZE) throw IOException("too large")
            bytes
        }

    internal fun parse(json: String, offset: Int): Page {
        val root = org.json.JSONObject(json)
        val data = root.optJSONArray("data") ?: org.json.JSONArray()
        val total = root.optJSONObject("pagination")?.optInt("total_count") ?: 0
        val gifs = (0 until data.length()).mapNotNull { i ->
            val images = data.optJSONObject(i)?.optJSONObject("images") ?: return@mapNotNull null
            fun rendition(name: String) = images.optJSONObject(name)
            // Upstream's `GiphyImage.getMp4Data`: the largest of these whose MP4 fits.
            val mp4 = listOf("fixed_width", "fixed_height", "fixed_width_small", "fixed_height_small", "downsized_small")
                .mapNotNull(::rendition)
                .filter { it.optString("mp4").isNotBlank() }
                .map { it to it.optString("mp4_size").toLongOrNull().orZero() }
                .filter { (_, size) -> size in 1..MAX_SIZE }
                .maxByOrNull { (_, size) -> size }
                ?.first
                ?: return@mapNotNull null
            // Upstream's grid uses the small stills (100 px); the grid here is two across on
            // a panel with nothing animating, so the 200 px still is the one that reads.
            val still = listOf("fixed_width_still", "fixed_height_still", "fixed_width_small_still", "fixed_height_small_still")
                .mapNotNull(::rendition)
                .firstOrNull { it.optString("url").isNotBlank() }
                ?: return@mapNotNull null
            Gif(
                stillUrl = still.optString("url"),
                mp4Url = mp4.optString("mp4"),
                width = mp4.optString("width").toIntOrNull() ?: 0,
                height = mp4.optString("height").toIntOrNull() ?: 0
            )
        }
        return Page(gifs, hasMore = offset + data.length() < total)
    }

    private fun Long?.orZero() = this ?: 0L
}
