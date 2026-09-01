package com.wanderwildwood.kotozute.signal

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class BridgeState(
    val account: String,
    val signalConnected: Boolean,
    val signalError: String,
    val maxSeq: Long
)

data class BridgeThread(
    val threadKey: String,
    val kind: String,
    val title: String,
    val lastTs: Long,
    val unread: Int,
    /** Needed to pair a Signal thread with the SMS thread for the same person. */
    val counterpartNumber: String
)

data class BridgeMessage(
    val id: String,
    val seq: Long,
    val threadKey: String,
    val ts: Long,
    val senderUuid: String,
    val senderNumber: String,
    val outgoing: Boolean,
    val body: String,
    val groupId: String,
    val quoteTs: Long,
    val read: Boolean,
    val source: String,
    /** The bridge's attachment array, kept as JSON; the app only reads it to draw a row. */
    val attachmentsJson: String
)

/**
 * Talks to a kotozute-bridge. Every call carries the bearer token, and the TLS trust
 * is a pin on the bridge's own certificate -- see [pinnedClient].
 */
class BridgeClient(private val config: BridgeConfig) {

    private val client: OkHttpClient = pinnedClient(config.fingerprint)

    private fun authed(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${config.token}")

    fun state(): BridgeState {
        val o = getJson("${config.baseUrl}/v1/state")
        return BridgeState(
            account = o.optString("account"),
            signalConnected = o.optBoolean("signalConnected"),
            signalError = o.optString("signalError"),
            maxSeq = o.optLong("maxSeq")
        )
    }

    fun threads(): List<BridgeThread> {
        val arr = getJson("${config.baseUrl}/v1/threads").optJSONArray("threads") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BridgeThread(
                threadKey = o.optString("threadKey"),
                kind = o.optString("kind"),
                title = o.optString("title"),
                lastTs = o.optLong("lastTs"),
                unread = o.optInt("unread"),
                counterpartNumber = o.optString("counterpartNumber")
            )
        }
    }

    /** Everything after [sinceSeq]. This is how a phone that has been away catches up. */
    fun changes(sinceSeq: Long, limit: Int = 200): Pair<List<BridgeMessage>, Long> {
        val o = getJson("${config.baseUrl}/v1/changes?sinceSeq=$sinceSeq&limit=$limit")
        val arr = o.optJSONArray("messages") ?: JSONArray()
        return (0 until arr.length()).map { parseMessage(arr.getJSONObject(it)) } to o.optLong("maxSeq")
    }

    /** Returns the Signal timestamp of the sent message. Throws if it could not be sent. */
    fun send(threadKey: String, message: String): Long {
        val body = JSONObject().put("message", message)
            .toString().toRequestBody(JSON)
        val req = authed("${config.baseUrl}/v1/threads/${enc(threadKey)}/send").post(body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val reason = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw IOException("send failed (${resp.code}): ${reason ?: text.take(120)}")
            }
            return JSONObject(text).optLong("timestamp")
        }
    }

    fun markRead(threadKey: String, upToTs: Long) {
        val body = JSONObject().put("upToTs", upToTs).toString().toRequestBody(JSON)
        val req = authed("${config.baseUrl}/v1/threads/${enc(threadKey)}/read").post(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("markRead failed (${resp.code})")
        }
    }

    /**
     * Holds the bridge's Server-Sent Events stream open and calls [onMessage] for each
     * message. Blocking: run it on a background thread. Closing the returned handle, or
     * any network error, ends it -- the caller is expected to reconnect and pass its
     * cursor again, which is why a dropped connection can never lose a message.
     */
    fun openEvents(
        sinceSeq: Long,
        onMessage: (BridgeMessage) -> Unit,
        onClosed: (Throwable?) -> Unit
    ): Closeable {
        val req = authed("${config.baseUrl}/v1/events?sinceSeq=$sinceSeq")
            .header("Accept", "text/event-stream")
            .build()
        val call = client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // the stream is meant to stay open
            .build()
            .newCall(req)

        val thread = Thread({
            var err: Throwable? = null
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("events failed (${resp.code})")
                    val source = resp.body?.source() ?: throw IOException("no body")
                    var data = StringBuilder()
                    while (!Thread.currentThread().isInterrupted) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                            line.isEmpty() && data.isNotEmpty() -> {
                                runCatching { onMessage(parseMessage(JSONObject(data.toString()))) }
                                data = StringBuilder()
                            }
                            // ": ping" keepalives and "event:" lines need no handling
                        }
                    }
                }
            } catch (t: Throwable) {
                err = t
            } finally {
                onClosed(err)
            }
        }, "signal-bridge-events")
        thread.isDaemon = true
        thread.start()

        return Closeable {
            call.cancel()
            thread.interrupt()
        }
    }

    /**
     * Fetches an attachment's bytes. Goes through the same pinned client as everything
     * else, which is why the app cannot simply hand the URL to an image loader.
     */
    fun fetchAttachment(id: String): ByteArray {
        val req = authed("${config.baseUrl}/v1/attachments/${enc(id)}").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("attachment ${resp.code}")
            return resp.body?.bytes() ?: throw IOException("empty attachment")
        }
    }

    private fun getJson(url: String): JSONObject {
        client.newCall(authed(url).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("${resp.code} from $url")
            return JSONObject(text)
        }
    }

    private fun parseMessage(o: JSONObject) = BridgeMessage(
        id = o.optString("id"),
        seq = o.optLong("seq"),
        threadKey = o.optString("threadKey"),
        ts = o.optLong("ts"),
        senderUuid = o.optString("senderUuid"),
        senderNumber = o.optString("senderNumber"),
        outgoing = o.optBoolean("outgoing"),
        body = o.optString("body"),
        groupId = o.optString("groupId"),
        quoteTs = o.optLong("quoteTs"),
        read = o.optBoolean("read"),
        source = o.optString("source", "live"),
        attachmentsJson = o.optJSONArray("attachments")?.toString() ?: ""
    )

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Trusts exactly one certificate, by SHA-256 of its DER encoding.
         *
         * The bridge is self-signed, so the platform trust store would reject it and
         * disabling verification would accept anything. Pinning is the only option that
         * actually proves we are talking to the bridge we paired with.
         */
        fun pinnedClient(fingerprintHex: String): OkHttpClient {
            val want = fingerprintHex.replace(":", "").uppercase()
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    throw CertificateException("client auth not supported")

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val leaf = chain.firstOrNull() ?: throw CertificateException("empty chain")
                    val got = MessageDigest.getInstance("SHA-256")
                        .digest(leaf.encoded)
                        .joinToString("") { "%02X".format(it) }
                    if (got != want) {
                        throw CertificateException("bridge certificate does not match the paired one")
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
            return OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, tm)
                // The certificate is pinned, so the hostname it was issued for is not
                // what establishes identity -- and a bridge is commonly reached by an IP
                // that its self-signed cert was not built for.
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
