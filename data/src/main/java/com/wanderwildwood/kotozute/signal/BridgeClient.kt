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

/** A contact's safety number and whether their key is still the accepted one. */
data class BridgeIdentity(val safetyNumber: String, val trustLevel: String) {
    /** The key changed and has not been accepted since -- the case worth shouting about. */
    val changed: Boolean get() = trustLevel == "UNTRUSTED"
    val verified: Boolean get() = trustLevel == "TRUSTED_VERIFIED"
}

/** One device on the Signal account. Id 1 is the primary; the rest are linked. */
data class BridgeDevice(val id: Int, val name: String, val created: Long) {
    val isPrimary: Boolean get() = id == 1
}

/** Who the bridge is signed in as, and what else is attached to that account. */
data class BridgeAccount(
    val number: String,
    val selfUuid: String,
    val devices: List<BridgeDevice>
)

data class BridgeState(
    /** Identifies the bridge's store. A change means its sequence numbers restarted. */
    val instance: String,
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
    val attachmentsJson: String,

    /**
     * When this copy must be gone, in ms, or 0 for never. The bridge sends it; the phone
     * has to honour it independently, because the bridge deletes only its own row and the
     * phone's copy is the one the user can still read.
     */
    val expiresAt: Long = 0,
    val expiresInSeconds: Long = 0,
    /** Signal intends this to be opened once. Its attachment is never stored. */
    val viewOnce: Boolean = false,

    /** Set only on a reaction row, which points at another message rather than being one. */
    val reactionEmoji: String = "",
    val reactionTarget: String = "",
    val reactionRemove: Boolean = false
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
            instance = o.optString("instance"),
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
    fun send(threadKey: String, message: String, attachments: List<String> = emptyList()): Long {
        val json = JSONObject().put("message", message)
        if (attachments.isNotEmpty()) {
            json.put("attachments", org.json.JSONArray(attachments))
        }
        val body = json.toString().toRequestBody(JSON)
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

    fun markRead(threadKey: String, upToTs: Long, sendReceipts: Boolean = false) {
        val body = JSONObject()
            .put("upToTs", upToTs)
            .put("sendReceipts", sendReceipts)
            .toString().toRequestBody(JSON)
        val req = authed("${config.baseUrl}/v1/threads/${enc(threadKey)}/read").post(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("markRead failed (${resp.code})")
        }
    }

    /**
     * Who this bridge is signed in as, and which devices are on the account. Device 1 is
     * the primary; anything else is linked, and a linked device cannot register, change
     * the number, set a registration lock, or transfer the account.
     */
    fun account(): BridgeAccount {
        val o = getJson("${config.baseUrl}/v1/account")
        val devices = mutableListOf<BridgeDevice>()
        val arr = o.optJSONArray("devices")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                devices += BridgeDevice(
                    id = d.optInt("id"),
                    name = d.optString("name").orEmpty(),
                    created = d.optLong("createdTimestamp")
                )
            }
        }
        return BridgeAccount(
            number = o.optString("number").orEmpty(),
            selfUuid = o.optString("selfUuid").orEmpty(),
            devices = devices.sortedBy { it.id }
        )
    }

    /**
     * The safety number for a one-to-one thread, and whether the other end's key is still
     * the one that was accepted. Blank when the two have never exchanged a message.
     */
    fun identity(threadKey: String): BridgeIdentity {
        val o = getJson("${config.baseUrl}/v1/threads/${enc(threadKey)}/identity")
        return BridgeIdentity(
            safetyNumber = o.optString("safetyNumber").orEmpty(),
            trustLevel = o.optString("trustLevel").orEmpty()
        )
    }

    /**
     * Block or unblock the other party of a thread, on the Signal account rather than only
     * here, so it holds on every device. Throws on failure: a block that quietly did not
     * happen would leave someone believing they had stopped hearing from a person.
     */
    /**
     * Put an emoji on a message, or take it off.
     *
     * The message is named the way Signal names one -- whoever wrote it and the moment they
     * sent it -- rather than by the id this bridge invented, which Signal has never heard
     * of. In a group the author is not the thread's other party, so it has to be passed
     * rather than inferred.
     */
    fun react(
        threadKey: String, emoji: String, targetAuthor: String,
        targetTimestamp: Long, remove: Boolean
    ) {
        val body = JSONObject()
            .put("emoji", emoji)
            .put("targetAuthor", targetAuthor)
            .put("targetTimestamp", targetTimestamp)
            .put("remove", remove)
            .toString().toRequestBody(JSON)
        val req = authed("${config.baseUrl}/v1/threads/${enc(threadKey)}/react").post(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("reaction failed (${resp.code})")
        }
    }

    fun setBlocked(threadKey: String, blocked: Boolean) {
        val body = JSONObject().put("blocked", blocked).toString().toRequestBody(JSON)
        val req = authed("${config.baseUrl}/v1/threads/${enc(threadKey)}/block").post(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("block failed (${resp.code})")
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
        // Longer than the bridge's keepalive, not infinite. An infinite read timeout looks
        // right for a stream meant to stay open, and means a connection that dies without
        // saying so -- a restart with no clean close, a NAT timeout, a dropped network --
        // blocks this read forever: nothing reconnects and the phone goes quiet until the
        // app is restarted. The bridge pings every 25s, so silence past that is death.
        val call = client.newBuilder()
            .readTimeout(SSE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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

    // internal, not private: dropping the expiry fields here is precisely the bug that
    // shipped, so it is worth a test that reads the wire shape directly.
    internal fun parseMessage(o: JSONObject) = BridgeMessage(
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
        attachmentsJson = o.optJSONArray("attachments")?.toString() ?: "",
        expiresAt = o.optLong("expiresAt"),
        expiresInSeconds = o.optLong("expiresInSeconds"),
        viewOnce = o.optBoolean("viewOnce"),
        reactionEmoji = o.optString("reactionEmoji"),
        reactionTarget = o.optString("reactionTarget"),
        reactionRemove = o.optBoolean("reactionRemove")
    )

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        /** Two of the bridge's 25s keepalives, so one lost ping is not a disconnect. */
        private const val SSE_READ_TIMEOUT_SECONDS = 60L

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
