/*
 * Desktop Sync relay: a small embedded HTTP + WebSocket server exposing this
 * device's SMS conversations to a browser dashboard over Tailscale. No relay
 * server involved — the desktop client talks directly to this port on the
 * Kompakt's Tailscale IP.
 *
 * Every request (HTTP and the WebSocket upgrade) must carry the pairing
 * token, since Tailscale restricts *who* can reach this port but not what
 * they can do once they're on the tailnet.
 */
package com.wanderwildwood.kotozute.feature.desktopsync

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.webkit.MimeTypeMap
import java.io.InputStream
import com.wanderwildwood.kotozute.compat.SubscriptionManagerCompat
import com.wanderwildwood.kotozute.model.Attachment
import com.wanderwildwood.kotozute.model.Conversation
import com.wanderwildwood.kotozute.model.Message
import com.wanderwildwood.kotozute.interactor.MarkRead
import com.wanderwildwood.kotozute.interactor.SendNewMessage
import com.wanderwildwood.kotozute.repository.ContactRepository
import com.wanderwildwood.kotozute.repository.ConversationRepository
import com.wanderwildwood.kotozute.repository.MessageRepository
import com.wanderwildwood.kotozute.feature.conversations.InboxItem
import com.wanderwildwood.kotozute.model.SignalMessage
import com.wanderwildwood.kotozute.model.SignalThread
import com.wanderwildwood.kotozute.repository.SignalRepository
import io.realm.Realm
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.TempFile
import fi.iki.elonen.NanoHTTPD.TempFileManager
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocketFrame
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.Collections

class DesktopSyncServer(
    port: Int,
    private val context: Context,
    private val token: String,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val contactRepository: ContactRepository,
    private val markRead: MarkRead,
    private val sendNewMessage: SendNewMessage,
    private val subscriptionManager: SubscriptionManagerCompat,
    private val signalRepository: SignalRepository,
    private val signalEnabled: () -> Boolean,
    private val tailscaleOnly: () -> Boolean,
) : NanoWSD(port) {

    /**
     * Whether this peer is allowed to talk to us at all, before the token is even looked at.
     * With "Tailscale only" on, anything that isn't a tailnet address is refused outright,
     * so a device sharing the home Wi-Fi cannot reach the dashboard even holding the token.
     */
    private fun peerAllowed(ip: String?): Boolean =
        !tailscaleOnly() || DesktopSyncService.isAllowedPeer(ip)

    private companion object {
        /**
         * `bytes=START-[END]` out of a Range header. Only the single-range form, which
         * is the only one a video element ever asks for.
         */
        val RANGE = Regex("bytes=(\\d*)-(\\d*)")

        /** How many of a thread's most recent messages to send to the browser. */
        const val MESSAGE_PAGE_SIZE = 300

        /** "No subscription named" -- what SmsManagerFactory reads as "use the default". */
        const val NO_SUB_ID = -1

        /** Ceiling on an explicit ?limit=, so one request can't try to serialize everything. */
        const val MESSAGE_MAX_LIMIT = 5000

        /** How often we ping connected browsers. */
        const val PING_INTERVAL_SECONDS = 30L

        /**
         * Drop a socket that hasn't answered a ping in this long (3 missed pings plus
         * slack). Without this a half-open connection lives forever: if the peer vanishes
         * without a FIN — browser killed, laptop slept, network switched — our ping still
         * "succeeds" into the local send buffer for minutes, so nothing ever detects it,
         * and NanoWSD's reader thread stays parked on a read that has no timeout.
         */
        const val DEAD_PEER_TIMEOUT_MS = 95_000L

        /** Multipart field prefix the browser attaches files under: attachment0, attachment1... */
        const val ATTACHMENT_FIELD = "attachment"

        /** MuditaOS puts the monochrome Noto Emoji here, under the colour font's name. */
        const val EMOJI_FONT_PATH = "/system/fonts/NotoColorEmoji.ttf"
    }

    private val openSockets = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<PushSocket, Boolean>())

    init {
        // SO_REUSEADDR so an auto-restore right after a process kill can rebind the
        // port immediately instead of failing while the old socket sits in TIME_WAIT.
        // Return the socket UNBOUND: NanoHTTPD binds it itself in ServerRunnable, and
        // binding here too fails the second bind with EADDRINUSE.
        setServerSocketFactory {
            java.net.ServerSocket().apply { reuseAddress = true }
        }

        // NanoHTTPD stages multipart uploads through temp files, and its default manager puts
        // them in java.io.tmpdir -- which on Android is not reliably writable by an app. Point
        // it at our own cache directory instead of finding out per device.
        setTempFileManagerFactory { CacheDirTempFileManager(context) }
    }

    /**
     * Temp files for multipart uploads, kept inside the app's cache. Everything created for one
     * request is deleted when that request ends, which is exactly why an upload has to be copied
     * somewhere we own before it can be sent -- see stageUpload().
     */
    private class CacheDirTempFileManager(context: Context) : TempFileManager {

        private val directory = java.io.File(context.cacheDir, "desktopsync-upload").apply { mkdirs() }
        private val files = mutableListOf<TempFile>()

        override fun createTempFile(filenameHint: String?): TempFile =
            CacheDirTempFile(directory).also { files.add(it) }

        override fun clear() {
            files.forEach { file -> runCatching { file.delete() } }
            files.clear()
        }
    }

    private class CacheDirTempFile(directory: java.io.File) : TempFile {

        private val file = java.io.File.createTempFile("upload-", "", directory)
        private val stream = java.io.FileOutputStream(file)

        override fun open(): java.io.OutputStream = stream

        override fun delete() {
            runCatching { stream.close() }
            file.delete()
        }

        override fun getName(): String = file.absolutePath
    }

    private val keepAlive = java.util.concurrent.Executors.newSingleThreadScheduledExecutor().apply {
        // Idle WebSockets can be dropped by the network in between messages; a periodic
        // ping keeps them (and any NAT/Tailscale state) alive. The same pass doubles as
        // dead-peer detection — see DEAD_PEER_TIMEOUT_MS for why a ping succeeding is not
        // evidence that anyone is still listening.
        scheduleWithFixedDelay({
            val now = System.currentTimeMillis()
            openSockets.toList().forEach { socket ->
                if (!socket.isOpen) {
                    openSockets.remove(socket)
                    return@forEach
                }
                if (now - socket.lastPongAt > DEAD_PEER_TIMEOUT_MS) {
                    Timber.i("Desktop Sync: dropping unresponsive WebSocket (no pong in " +
                            "${(now - socket.lastPongAt) / 1000}s)")
                    // Closing releases the parked reader thread and the socket with it.
                    runCatching { socket.close(CloseCode.GoingAway, "no pong", false) }
                    openSockets.remove(socket)
                    return@forEach
                }
                runCatching { socket.ping(byteArrayOf()) }.onFailure { openSockets.remove(socket) }
            }
        }, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** How many browsers are currently listening for push updates. */
    fun socketCount(): Int = openSockets.size

    /** Called whenever a conversation's data changes, to nudge connected browsers to refetch. */
    fun notifyChanged() {
        val payload = JSONObject().put("type", "changed").toString()
        openSockets.toList().forEach { socket ->
            runCatching { socket.send(payload) }.onFailure { openSockets.remove(socket) }
        }
    }

    override fun stop() {
        keepAlive.shutdownNow()
        super.stop()
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return PushSocket(handshake)
    }

    private inner class PushSocket(handshake: IHTTPSession) : WebSocket(handshake) {

        /** Last time this peer proved it's still there. Read from the keep-alive thread. */
        @Volatile var lastPongAt = System.currentTimeMillis()

        override fun onOpen() {
            if (!peerAllowed(handshakeRequest.remoteIpAddress)) {
                Timber.w("Desktop Sync: WebSocket rejected (peer not on the tailnet)")
                runCatching { close(CloseCode.PolicyViolation, "not on the tailnet", false) }
                return
            }
            val authed = tokenMatches(handshakeRequest.parameters["token"]?.firstOrNull())
            if (!authed) {
                Timber.w("Desktop Sync: WebSocket rejected (bad/missing token)")
                runCatching { close(CloseCode.PolicyViolation, "bad token", false) }
                return
            }
            lastPongAt = System.currentTimeMillis()
            openSockets.add(this)
            Timber.i("Desktop Sync: WebSocket connected (${openSockets.size} total)")
        }

        override fun onClose(code: CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            openSockets.remove(this)
        }

        override fun onMessage(message: WebSocketFrame) {
            // Clients don't send anything meaningful; this is a push-only channel.
        }

        override fun onPong(pong: WebSocketFrame) {
            // The only proof we get that the far end is still alive.
            lastPongAt = System.currentTimeMillis()
        }

        override fun onException(exception: IOException) {
            Timber.w(exception, "Desktop Sync WebSocket error")
            openSockets.remove(this)
        }
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')

        // Ahead of everything, including the two unauthenticated static assets: an
        // off-tailnet caller should not be able to tell a running relay from a closed
        // port by fetching index.html.
        if (!peerAllowed(session.remoteIpAddress)) {
            Timber.w("Desktop Sync: request refused (peer not on the tailnet)")
            return jsonResponse(Response.Status.FORBIDDEN, JSONObject().put("error", "not on the tailnet"))
        }

        // The static shell carries no message data, and the browser requests app.js
        // from a plain <script src> with no way to attach the token — so serve those
        // two unauthenticated. Everything touching actual messages is gated below.
        when (uri) {
            "", "/index.html" -> return serveAsset("index.html", "text/html")
            "/app.js" -> return serveAsset("app.js", "application/javascript")
            "/emoji.json" -> return serveAsset("emoji.json", "application/json")
            // Served unauthenticated for the same reason as app.js: a stylesheet's @font-face
            // cannot carry an Authorization header. Neither this nor the list says anything
            // about the messages -- one is a system font, the other is Unicode's own catalogue.
            "/emoji-font" -> return serveEmojiFont()
            // What makes the dashboard installable: a browser reading these will offer to
            // put it in the menu with its own window and icon, which is a great deal
            // friendlier than "bookmark this URL with a token in it".
            "/manifest.webmanifest" -> return serveManifest(session)
            "/icon.png" -> return serveAsset("icon.png", "image/png")
        }

        val authed = session.headers["authorization"] == "Bearer $token" ||
                tokenMatches(session.parameters["token"]?.firstOrNull())
        if (!authed) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().put("error", "bad token"))
        }

        // Realm only auto-refreshes on threads with a Looper. NanoHTTPD serves each
        // connection on a plain worker thread, and a browser reusing one keep-alive
        // connection lands on that same thread every time — so without an explicit
        // refresh it keeps serving whatever snapshot the thread first opened, forever.
        // (curl looked fine only because each new connection got a fresh thread.)
        runCatching { Realm.getDefaultInstance().use { it.refresh() } }

        val threadMessagesMatch = Regex("^/api/threads/(-?\\d+)/messages$").find(uri)
        val threadSendMatch = Regex("^/api/threads/(-?\\d+)/send$").find(uri)
        val threadReadMatch = Regex("^/api/threads/(-?\\d+)/read$").find(uri)
        val partMatch = Regex("^/api/parts/(\\d+)$").find(uri)

        return when {
            uri == "/api/threads" && session.method == Method.GET -> handleGetThreads()
            threadMessagesMatch != null && session.method == Method.GET ->
                handleGetMessages(threadMessagesMatch.groupValues[1].toLong(), session)
            threadSendMatch != null && session.method == Method.POST ->
                handleSend(session, threadSendMatch.groupValues[1].toLong())
            uri == "/api/compose" && session.method == Method.POST -> handleCompose(session)
            uri == "/desktop-entry" && session.method == Method.GET -> serveDesktopEntry()
            uri == "/api/contacts" && session.method == Method.GET -> handleContacts(session)
            uri == "/api/sims" && session.method == Method.GET -> handleSims()
            uri == "/api/thread-for" && session.method == Method.GET -> handleThreadFor(session)
            partMatch != null && session.method == Method.GET ->
                handlePart(partMatch.groupValues[1].toLong(), session)
            threadReadMatch != null && session.method == Method.POST ->
                handleMarkRead(threadReadMatch.groupValues[1].toLong())
            else -> jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "not found"))
        }
    }

    /**
     * The phone's own emoji font, straight off the system partition.
     *
     * MuditaOS ships Google's monochrome **Noto Emoji** here under the colour font's filename,
     * which is why emoji are clean line art on the Kompakt instead of dithered grey. Serving that
     * same file to the browser means the picker shows what the person holding the phone will
     * actually see, rather than whatever colour set the desktop happens to have.
     *
     * Read from the system rather than bundled: it is already on every Kompakt, so it costs the
     * APK nothing. It is SIL OFL 1.1, so shipping it would be allowed -- this is only cheaper.
     * If a future MuditaOS moves it, the browser falls back to its own emoji and nothing breaks.
     */
    private fun serveEmojiFont(): Response {
        val font = java.io.File(EMOJI_FONT_PATH)
        if (!font.canRead())
            return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "no emoji font"))

        val stream = runCatching { font.inputStream() }.getOrNull()
            ?: return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "font unreadable"))

        return newFixedLengthResponse(Response.Status.OK, "font/ttf", stream, font.length()).apply {
            // Two megabytes that never change: without this the browser refetches the whole font
            // on every reload of the dashboard.
            addHeader("Cache-Control", "public, max-age=604800")
        }
    }

    /**
     * The web app manifest, built here rather than shipped as a file because [start_url] has
     * to carry the pairing token: an installed window opens straight into the dashboard, and
     * without the token it would open to a refusal.
     */
    /**
     * Exact, or case-insensitive when the token holds no lowercase of its own.
     *
     * Tokens are generated from an uppercase alphabet, so case carries no information and
     * a link typed by hand should not fail on it. Tokens issued before that change are
     * mixed-case base64, where case does carry information, so those are compared exactly.
     */
    private fun tokenMatches(supplied: String?): Boolean {
        if (supplied == null) return false
        if (supplied == token) return true
        if (token.any { it.isLowerCase() }) return false
        return supplied.equals(token, ignoreCase = true)
    }

    private fun serveManifest(session: IHTTPSession): Response {
        val supplied = session.parameters["token"]?.firstOrNull()
        val start = if (tokenMatches(supplied)) "/?token=$token" else "/"
        val manifest = JSONObject().apply {
            put("name", "Messaging")
            put("short_name", "Messaging")
            put("description", "Text from your computer, through the phone.")
            put("start_url", start)
            put("scope", "/")
            put("display", "standalone")
            put("background_color", "#ffffff")
            put("theme_color", "#ffffff")
            put("icons", JSONArray().put(JSONObject().apply {
                put("src", "/icon.png")
                put("sizes", "512x512")
                put("type", "image/png")
                put("purpose", "any maskable")
            }))
        }
        return jsonResponse(Response.Status.OK, manifest).apply {
            mimeType = "application/manifest+json"
        }
    }

    /**
     * A Linux desktop entry with this phone's address and token already in it, so the
     * dashboard can be started from an applications menu like anything else. The browser
     * saves it; where it has to go afterwards is in the page's own instructions, because no
     * web page is allowed to write to ~/.local/share.
     */
    private fun serveDesktopEntry(): Response {
        val address = DesktopSyncService.findTailscaleAddress(context)
            ?: DesktopSyncService.findLanAddress(context)
            ?: "127.0.0.1"
        val url = "http://$address:$listeningPort?token=$token"
        // Prefers a Chromium browser in app mode, which gives a window with no tab strip or
        // address bar and its own entry in the menu and the switcher. Falls back to xdg-open,
        // so on a machine with neither Chrome nor Brave this still opens the right page in
        // whatever browser that person actually uses.
        val exec = "sh -c 'for b in brave-browser brave chromium chromium-browser " +
            "google-chrome google-chrome-stable microsoft-edge vivaldi; do " +
            "command -v \$b >/dev/null 2>&1 && exec \$b --app=\"$url\"; done; exec xdg-open \"$url\"'"
        val entry = """
            [Desktop Entry]
            Type=Application
            Name=Messaging
            Comment=Text from this computer, through the phone
            Exec=$exec
            Icon=messaging
            Categories=Network;InstantMessaging;
            Terminal=false
        """.trimIndent() + "\n"
        return newFixedLengthResponse(Response.Status.OK, "application/x-desktop", entry).apply {
            addHeader("Content-Disposition", "attachment; filename=\"messaging.desktop\"")
        }
    }

    private fun serveAsset(name: String, mimeType: String): Response {
        val stream = runCatching { context.assets.open("desktopsync/$name") }.getOrNull()
            ?: return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "asset missing"))
        return newChunkedResponse(Response.Status.OK, mimeType, stream)
    }


    // ---- the Signal rail -----------------------------------------------------

    /**
     * The Signal thread an id refers to, or null if the id is a telephony one.
     *
     * Ids are derived from the thread key rather than stored, so this walks the threads
     * and matches. There are dozens, not thousands, and the alternative is a second
     * identifier to keep in step with the one the inbox already uses.
     */
    private fun signalThreadFor(id: Long): SignalThread? {
        if (!InboxItem.isSignalId(id) || !signalEnabled()) return null
        return signalRepository.getThreadsSnapshot()
            .firstOrNull { InboxItem.signalStableId(it.threadKey) == id }
    }

    private fun signalThreadJson(t: SignalThread) = JSONObject().apply {
        put("id", InboxItem.signalStableId(t.threadKey))
        put("title", t.title.ifBlank { t.counterpartNumber.ifBlank { t.threadKey.substringAfter(":") } })
        put("snippet", if (t.snippetOutgoing && t.snippet.isNotBlank()) "You: " + t.snippet else t.snippet)
        put("date", t.lastTs)
        put("unread", t.unread > 0)
        put("rail", "signal")
    }

    private fun signalMessageJson(m: SignalMessage) = JSONObject().apply {
        // The desktop list keys on this; Signal's own id is a string, so derive a stable
        // number from it the same way thread ids are derived.
        put("id", InboxItem.signalStableId(m.id))
        put("body", m.body)
        put("date", m.date)
        put("isMe", m.outgoing)
        put("read", m.read)
        put("rail", "signal")
        if (m.attachments.isNotBlank() && m.attachments != "[]") {
            put("attachmentNote", "\uD83D\uDCCE Attachment")
        }
    }

    private fun handleGetThreads(): Response {
        val conversations = conversationRepository.getConversationsSnapshot(unreadAtTop = true)
        val array = JSONArray()
        val rows = mutableListOf<Pair<Long, JSONObject>>()
        conversations.filterNot { it.archived || it.blocked }.forEach { conversation ->
            rows += conversation.date to conversationJson(conversation)
        }
        if (signalEnabled()) {
            signalRepository.getThreadsSnapshot()
                .filterNot { it.archived }
                .forEach { rows += it.lastTs to signalThreadJson(it) }
        }
        // One list, newest first, the same order the phone shows.
        rows.sortedByDescending { it.first }.forEach { array.put(it.second) }
        return jsonResponse(Response.Status.OK, array)
    }

    private fun handleGetMessages(threadId: Long, session: IHTTPSession): Response {
        signalThreadFor(threadId)?.let { thread ->
            val requested = session.parameters["limit"]?.firstOrNull()?.toIntOrNull()
            val limit = (requested ?: MESSAGE_PAGE_SIZE).coerceIn(1, MESSAGE_MAX_LIMIT)
            val array = JSONArray()
            signalRepository.getMessagesSnapshot(thread.threadKey, limit)
                .forEach { array.put(signalMessageJson(it)) }
            return jsonResponse(Response.Status.OK, array)
        }

        // Only the tail of the thread by default: some conversations here run 600+
        // messages and the browser re-fetches this every few seconds. `limit` lets the
        // browser ask for more so older history is still reachable.
        //
        // Uses getMessagesSync (not the repo's limit overload) because that one can
        // return findAllAsync results, which need a Looper thread — and NanoHTTPD
        // serves each request on a plain worker thread.
        val requested = session.parameters["limit"]?.firstOrNull()?.toIntOrNull()
        val limit = (requested ?: MESSAGE_PAGE_SIZE).coerceIn(1, MESSAGE_MAX_LIMIT)

        // Who is in this thread, so a group message can say who sent it. A one-to-one
        // thread needs none of this: the name is at the top of the screen and repeating
        // it against every bubble is noise.
        val conversation = runCatching { conversationRepository.getConversation(threadId) }.getOrNull()
        val recipients = conversation?.recipients.orEmpty()
        val isGroup = recipients.size > 1
        val senders = recipients.map { it.address to it.getDisplayName() }

        val all = messageRepository.getMessagesSync(threadId)
        val total = all.size
        val array = JSONArray()
        all.takeLast(limit).forEach { message ->
            array.put(messageJson(message, if (isGroup) senders else emptyList()))
        }

        // Wrapped in an object (not a bare array) so the browser knows whether older
        // messages exist without having to guess from the count.
        return jsonResponse(Response.Status.OK, JSONObject().apply {
            put("total", total)
            put("hasMore", total > limit)
            put("messages", array)
        })
    }

    private fun handleSend(session: IHTTPSession, threadId: Long): Response {
        val submission = readSubmission(session)
            ?: return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "bad request body"))

        signalThreadFor(threadId)?.let { thread ->
            val body = submission.body.trim()
            if (body.isEmpty() || submission.attachments.isNotEmpty()) {
                // Attachments over the relay are an SMS path; Signal has no route for them
                // here yet, and silently dropping the picture would be worse than refusing.
                return jsonResponse(
                    Response.Status.BAD_REQUEST,
                    JSONObject().put("error", "Signal messages from the browser are text only for now")
                )
            }
            return try {
                signalRepository.send(thread.threadKey, body)
                notifyChanged()
                jsonResponse(Response.Status.OK, JSONObject().put("ok", true))
            } catch (t: Throwable) {
                // Sending has no offline queue on purpose: better to say it did not go
                // than to accept a message that never arrives.
                Timber.w(t, "Desktop Sync: Signal send failed")
                jsonResponse(
                    Response.Status.INTERNAL_ERROR,
                    JSONObject().put("error", t.message ?: "could not reach the Signal bridge")
                )
            }
        }

        rejectionResponse(submission)?.let { return it }

        // A picture on its own is a message. Only require text when nothing else is attached.
        if (submission.body.isBlank() && submission.attachments.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing body"))
        }

        val conversation = conversationRepository.getConversation(threadId)
            ?: return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "no such thread"))

        val addresses = conversation.recipients.map { it.address }
        val failed = sendAndWait(
            conversation.id, addresses, conversation.sendAsGroup,
            submission.body, submission.attachments
        )
        if (failed) return sendFailureResponse()
        return jsonResponse(Response.Status.OK, JSONObject().put("ok", true))
    }

    /** What a compose request carried, however it was encoded. */
    private class Submission(
        val body: String,
        val to: String,
        val attachments: List<Attachment>,
        /** Files that arrived but could not be used -- an image the phone cannot decode. */
        val rejected: Int = 0,
        /** The SIM the browser picked, or NO_SUB_ID to let the phone work it out. */
        val subId: Int = NO_SUB_ID
    )

    /**
     * Read a text field out of a multipart request.
     *
     * NanoHTTPD decodes a request body with the charset named in its Content-Type and falls back
     * to **US-ASCII** when there is none -- and a browser writes the multipart Content-Type
     * itself, boundary and all, so there is never a charset to find. Every byte above 127 becomes
     * U+FFFD before this code sees it, and no amount of re-encoding gets it back: an em dash
     * arrives as three replacement characters, and so does an emoji.
     *
     * So the browser sends each text field base64'd under a "B64" name. Base64 is pure ASCII and
     * comes through any charset untouched, and the UTF-8 is decoded here. The plain field is
     * still read as a fallback, for a client that doesn't know the convention.
     */
    private fun multipartText(session: IHTTPSession, field: String): String {
        session.parameters["${field}B64"]?.firstOrNull()?.let { encoded ->
            runCatching {
                return String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.onFailure { error -> Timber.w(error, "Desktop Sync: bad base64 in %s", field) }
        }
        return session.parameters[field]?.firstOrNull().orEmpty()
    }

    /**
     * Refuse the whole send if any file could not be used, rather than quietly sending the text
     * without the picture. Silently dropping an attachment is the worse failure: the message
     * looks sent, and nobody finds out the photo never went until the reply asks what photo.
     */
    private fun rejectionResponse(submission: Submission): Response? =
        submission.rejected
            .takeIf { rejected -> rejected > 0 }
            ?.let { rejected ->
                jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put(
                    "error",
                    // Deliberately not "as a picture": contacts and video go through here too,
                    // and a wrong reason is worse than a vague one.
                    if (rejected == 1) "that file could not be read"
                    else "$rejected of those files could not be read"
                ))
            }

    /**
     * Read a send from either encoding. Text-only sends still arrive as JSON, which is what the
     * browser has always posted; anything with a file attached arrives as multipart, because a
     * JSON body cannot carry bytes without base64 inflating them by a third.
     *
     * NanoHTTPD does the multipart parsing: parseBody() writes each file part to a temp file and
     * puts its path in the map under the field name, while ordinary fields land in the session
     * parameters. The temp files die with the request, so every one that matters is copied out
     * by stageUpload() before this returns.
     */
    private fun readSubmission(session: IHTTPSession): Submission? {
        val bodyMap = HashMap<String, String>()
        runCatching { session.parseBody(bodyMap) }.onFailure { error ->
            Timber.w(error, "Desktop Sync: could not parse a request body")
            return null
        }

        val contentType = session.headers["content-type"].orEmpty()
        if (!contentType.startsWith("multipart/form-data")) {
            val json = runCatching { JSONObject(bodyMap["postData"] ?: "{}") }.getOrNull() ?: return null
            return Submission(
                json.optString("body"), json.optString("to"), emptyList(),
                // A string either way, so the JSON and multipart bodies carry it identically.
                subId = json.optString("subId").toIntOrNull() ?: NO_SUB_ID
            )
        }

        val uploads = bodyMap
            .filterKeys { field -> field.startsWith(ATTACHMENT_FIELD) }
            .toSortedMap()
            .map { (field, path) ->
                // parameters holds the name the file was uploaded under, keyed by the same field.
                val uploadedName = session.parameters[field]?.firstOrNull()
                stageUpload(context, java.io.File(path), uploadedName)
            }

        return Submission(
            body = multipartText(session, "body"),
            to = multipartText(session, "to"),
            attachments = uploads.filterNotNull(),
            rejected = uploads.count { it == null },
            subId = multipartText(session, "subId").toIntOrNull() ?: NO_SUB_ID
        )
    }

    /**
     * Streams an MMS attachment (picture, etc.) straight out of the MMS content
     * provider, so pictures actually show up in the browser instead of appearing as
     * empty bubbles.
     */
    private fun handlePart(partId: Long, session: IHTTPSession): Response {
        val part = messageRepository.getPart(partId)
            ?: return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "no such part"))
        var mime = part.type.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        var uri = part.getUri()

        // A video the browser cannot decode is re-encoded once and served from the cache
        // afterwards. Nothing else is touched: see VideoForBrowser for which codecs count.
        if (mime.startsWith("video/")) {
            val size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull()
            VideoForBrowser.playableCopy(context, part.id, uri, size)?.let { playable ->
                uri = android.net.Uri.fromFile(playable)
                mime = "video/mp4"
            }
        }

        // How long the part is. A picture does not care, but a video does: a browser
        // will not play a stream whose length it does not know and cannot seek within,
        // which is what a chunked response is. Everything below exists to answer
        // "how big, and give me these bytes of it".
        val length = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0 && it != AssetFileDescriptor.UNKNOWN_LENGTH }

        fun open(): InputStream? = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()

        if (length == null) {
            // Length unknown: chunked is all that is left, and the browser will have to
            // take what it is given.
            val stream = open()
                ?: return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "part unreadable"))
            return newChunkedResponse(Response.Status.OK, mime, stream).apply {
                addHeader("Content-Disposition", "inline; filename=\"" + fileNameFor(part.id, mime) + "\"")
            }
        }

        val range = session.headers["range"]?.let { RANGE.find(it) }
        if (range == null) {
            val stream = open()
                ?: return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "part unreadable"))
            return newFixedLengthResponse(Response.Status.OK, mime, stream, length).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Disposition", "inline; filename=\"" + fileNameFor(part.id, mime) + "\"")
            }
        }

        val start = range.groupValues[1].toLongOrNull() ?: 0L
        val end = range.groupValues[2].toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
        if (start > end || start >= length) {
            return jsonResponse(Response.Status.RANGE_NOT_SATISFIABLE, JSONObject().put("error", "bad range"))
                .apply { addHeader("Content-Range", "bytes */$length") }
        }

        val stream = open()
            ?: return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "part unreadable"))
        // The provider's stream is not seekable, so the only way to the offset is to
        // read past it. Requests start at zero and walk forward, so this is cheap in
        // practice and correct in every case.
        var skipped = 0L
        while (skipped < start) {
            val n = stream.skip(start - skipped)
            if (n <= 0) break
            skipped += n
        }
        return newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT, mime, stream, end - start + 1
        ).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Range", "bytes $start-$end/$length")
            addHeader("Content-Disposition", "inline; filename=\"" + fileNameFor(part.id, mime) + "\"")
        }
    }

    /** A name to save it under, since the URL is a bare number. */
    private fun fileNameFor(partId: Long, mime: String): String {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return if (extension.isNullOrBlank()) "part-$partId" else "part-$partId.$extension"
    }

    /**
     * Reading a thread in the browser should clear it on the phone too. Goes through
     * the MarkRead interactor rather than the repository directly, so the phone's
     * notification is dismissed and the launcher badge updated as well — not just
     * the database flag.
     */
    private fun handleMarkRead(threadId: Long): Response {
        signalThreadFor(threadId)?.let { thread ->
            signalRepository.markRead(thread.threadKey, System.currentTimeMillis())
            notifyChanged()
            return jsonResponse(Response.Status.OK, JSONObject().put("ok", true))
        }
        markRead.execute(listOf(threadId))
        notifyChanged()
        return jsonResponse(Response.Status.OK, JSONObject().put("ok", true))
    }

    /**
     * The SIMs that can send, for the composer's picker.
     *
     * Empty with one SIM, and empty again without the phone permission -- either way there is
     * no choice to offer, and the browser draws no control. Only a phone with two live
     * subscriptions has a question to ask, which is the same rule the phone's own compose bar
     * follows: the toggle is there when there is something to toggle between.
     */
    private fun handleSims(): Response {
        val subs = runCatching { subscriptionManager.activeSubscriptionInfoList }
            .getOrDefault(emptyList())
        val array = JSONArray()
        if (subs.size > 1) {
            subs.forEach { sub ->
                array.put(JSONObject().apply {
                    put("subId", sub.subscriptionId)
                    put("slot", sub.simSlotIndex + 1)
                    // The carrier's name for it. The compat getter declares this non-null, so a
                    // phone that reports none throws on the way out rather than returning null;
                    // the slot number reads perfectly well alone, and the browser handles a blank.
                    put("name", runCatching { sub.displayName.toString().trim() }.getOrDefault(""))
                })
            }
        }
        return jsonResponse(Response.Status.OK, JSONObject().put("sims", array))
    }

    /**
     * Does a conversation already exist for this recipient? Lets the compose field
     * jump straight into an existing thread (with its history) instead of opening a
     * blank one when you pick a contact you've already been texting.
     */
    private fun handleThreadFor(session: IHTTPSession): Response {
        val address = session.parameters["address"]?.firstOrNull()?.trim().orEmpty()
        if (address.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing address"))
        }
        val conversation = runCatching {
            conversationRepository.getConversation(listOf(address))
        }.getOrNull()
        val result = JSONObject()
        if (conversation != null) {
            result.put("found", true)
            result.put("threadId", conversation.id)
            result.put("title", conversation.getTitle())
        } else {
            result.put("found", false)
        }
        return jsonResponse(Response.Status.OK, result)
    }

    /**
     * Contact lookup for the compose field's autocomplete. Matches on name or number,
     * digits-only for the number comparison so "5551234567" finds "(555) 123-4567".
     */
    private fun handleContacts(session: IHTTPSession): Response {
        val query = session.parameters["q"]?.firstOrNull()?.trim().orEmpty()
        val array = JSONArray()
        if (query.length < 2) return jsonResponse(Response.Status.OK, array)

        val needle = query.lowercase()
        val needleDigits = query.filter { it.isDigit() }

        val contacts = runCatching { contactRepository.getUnmanagedAllContacts() }.getOrNull().orEmpty()
        contacts.asSequence()
            .flatMap { contact ->
                contact.numbers.asSequence().map { number -> contact.name to number.address }
            }
            .filter { (name, address) ->
                name.lowercase().contains(needle) ||
                    (needleDigits.isNotEmpty() && address.filter { it.isDigit() }.contains(needleDigits))
            }
            .distinctBy { (name, address) -> name + '|' + address }
            .take(8)
            .forEach { (name, address) ->
                array.put(JSONObject().put("name", name).put("address", address))
            }

        return jsonResponse(Response.Status.OK, array)
    }

    /** Start a brand-new conversation with an arbitrary recipient (the "+" button). */
    private fun handleCompose(session: IHTTPSession): Response {
        val submission = readSubmission(session)
            ?: return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "bad request body"))

        rejectionResponse(submission)?.let { return it }

        val body = submission.body
        val rawTo = submission.to
        if (body.isBlank() && submission.attachments.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing body"))
        }

        // Accept a comma/semicolon separated list so a group message is possible too.
        val addresses = rawTo.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (addresses.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing recipient"))
        }

        // Resolve the conversation up front so its id can be handed back to the browser,
        // which then jumps straight into the thread. The interactor resolves the same
        // conversation from the same addresses.
        val sendAsGroup = addresses.size > 1
        val threadId = runCatching {
            conversationRepository.getOrCreateConversation(addresses)?.id
        }.getOrNull()

        val failed = sendAndWait(
            threadId ?: 0L, addresses, sendAsGroup, body, submission.attachments,
            chosenSubId = submission.subId
        )
        if (failed) return sendFailureResponse()

        return jsonResponse(Response.Status.OK, JSONObject().apply {
            put("ok", true)
            if (threadId != null && threadId != 0L) put("threadId", threadId)
        })
    }

    /**
     * Which SIM to send from.
     *
     * -1 means "unspecified", and SmsManagerFactory turns that into SmsManager.getDefault().
     * With one SIM that is right, and naming a subscription would only be a way to get it
     * wrong. With two it hands the send to whatever the system default resolves to, which on
     * a dual-SIM phone can be no usable subscription at all: the message is marked failed on
     * the phone while the browser, which asked for it, is told nothing. So once there is more
     * than one active subscription, say which one.
     *
     * An explicit [chosen] subscription wins: that is the browser's SIM picker, which stands in
     * for the toggle the phone's own compose bar has. Failing that the thread's most recent
     * message decides -- answer on the SIM the conversation is already happening on, which is
     * what the phone does. A new thread nobody chose for, or one whose last message came in on a
     * SIM that has since been removed, falls back to the system's default SMS subscription, and
     * then to the first active one.
     */
    private fun subIdFor(threadId: Long, chosen: Int = NO_SUB_ID): Int {
        val subs = runCatching { subscriptionManager.activeSubscriptionInfoList }
            .getOrDefault(emptyList())
        // Also the empty list you get without the phone permission: nothing to choose between.
        if (subs.size < 2) return NO_SUB_ID

        val ids = subs.map { it.subscriptionId }

        // Someone said which SIM. That settles it -- checked against the active list rather
        // than trusted, since the browser may be holding a list from before a SIM was pulled.
        if (chosen in ids) return chosen

        // Messages come back sorted by date ascending, so the last one is the newest.
        val threadSubId = runCatching {
            messageRepository.getMessagesSync(threadId).lastOrNull()?.subId
        }.getOrNull()
        if (threadSubId != null && threadSubId in ids) return threadSubId

        val systemDefault =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                SubscriptionManager.getDefaultSmsSubscriptionId()
            } else NO_SUB_ID
        if (systemDefault in ids) return systemDefault

        return ids.first()
    }

    /**
     * Send through the SendNewMessage interactor, NOT messageRepository.sendNewMessages()
     * directly. The message transmits either way, but only the interactor also runs
     * conversationRepo.updateConversations()/markUnarchived() — and without that, the
     * conversation LIST keeps showing the previous message and timestamp, on the phone as
     * well as in the browser, since both read the same Realm Conversation objects. Same
     * lesson as read-state going through MarkRead rather than the repository.
     */
    private fun sendAndWait(
        threadId: Long,
        addresses: List<String>,
        sendAsGroup: Boolean,
        body: String,
        attachments: List<Attachment> = emptyList(),
        chosenSubId: Int = NO_SUB_ID,
    ): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        sendNewMessage.execute(
            SendNewMessage.Params(
                subId = subIdFor(threadId, chosenSubId),
                threadId = threadId,
                addresses = addresses,
                body = body,
                sendAsGroup = sendAsGroup,
                attachments = attachments,
            )
        ) { done.countDown() }
        // Bounded wait: the interactor is asynchronous, and returning before it finishes
        // would have the browser reload a database that hasn't been written yet. Capped so
        // a stalled send can't hold the HTTP response open indefinitely.
        runCatching { done.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        notifyChanged()
        return sendAlreadyFailed(threadId)
    }

    /**
     * Did that send come straight back failed?
     *
     * Sending is asynchronous -- the phone hands the message to the radio and the outcome
     * arrives some time later -- so a message still in flight is not an error, and says
     * nothing here. A refusal the phone can make on the spot, though (no usable
     * subscription, no radio at all), has already landed by the time this runs, and the
     * person waiting on the other end of the browser should be told now rather than left
     * looking at a bubble that appears to have gone. Anything that fails later is carried
     * by the message's own status instead.
     */
    private fun sendAlreadyFailed(threadId: Long): Boolean = runCatching {
        messageRepository.getMessagesSync(threadId).lastOrNull()
            ?.takeIf { it.isMe() }
            ?.isFailedMessage() == true
    }.getOrDefault(false)

    /**
     * Not a 500: nothing here went wrong. The phone was asked to send and would not, and the
     * browser needs to say so and keep the message in the box so it can be tried again.
     */
    private fun sendFailureResponse(): Response = jsonResponse(
        Response.Status.SERVICE_UNAVAILABLE,
        JSONObject().put("error", "the phone could not send this")
    )

    private fun conversationJson(conversation: Conversation) = JSONObject().apply {
        put("id", conversation.id)
        put("title", conversation.getTitle())
        put("snippet", conversation.snippet ?: "")
        put("date", conversation.date)
        put("unread", conversation.unread)
        put("rail", "sms")
    }

    /**
     * [senders] is the thread's recipients, and is empty unless this is a group. When it
     * is not, a received message carries the name of whoever sent it: in a group every
     * bubble otherwise looks the same and the conversation reads as one voice.
     *
     * Matched with [PhoneNumberUtils.compare] rather than string equality, because the
     * address on a message and the address on a recipient are frequently the same number
     * written two ways — +1 and a bare ten digits, or spaced and not.
     */
    private fun messageJson(message: Message, senders: List<Pair<String, String>> = emptyList()) = JSONObject().apply {
        put("id", message.id)
        put("body", message.getText())
        put("date", message.date)
        put("isMe", message.isMe())
        put("read", message.read)
        // Which SIM carried it. The phone's own thread marks this where it changes, and
        // without it the browser was the one place two numbers looked like one: a reader
        // with a work SIM and a personal one could not tell which they had just answered
        // on. Sent on every message; whether it is worth drawing is the browser's call,
        // and on the single-SIM phone it never is.
        put("subId", message.subId)
        // How the send went. Without this the browser has no way to know a message
        // failed -- it drew the bubble the moment the phone accepted the message for
        // sending, and a bubble is what a sent message looks like too. Only outgoing
        // messages have a state worth reporting.
        if (message.isMe()) {
            put("status", when {
                message.isFailedMessage() -> "failed"
                message.isSending() -> "sending"
                else -> "sent"
            })
        }
        if (senders.isNotEmpty() && !message.isMe()) {
            val address = message.address.takeIf { it.isNotBlank() }
            val name = address?.let { from ->
                senders.firstOrNull { (recipient, _) -> PhoneNumberUtils.compare(recipient, from) }?.second
                    ?: from
            }
            if (name != null) put("from", name)
        }
        // MMS attachments: without these, a picture message renders as an empty
        // bubble in the browser. SMIL is layout metadata, and text/plain is already
        // folded into getText() above, so both are skipped.
        val attachments = JSONArray()
        message.parts
            .filterNot { it.type == "application/smil" || it.type == "text/plain" }
            .forEach { part ->
                attachments.put(JSONObject().apply {
                    put("id", part.id)
                    put("type", part.type)
                    put("label", part.getSummary() ?: part.type)
                    put("isImage", part.type.startsWith("image"))
                    put("isVideo", part.type.startsWith("video"))
                })
            }
        if (attachments.length() > 0) put("attachments", attachments)
    }

    private fun jsonResponse(status: Response.Status, body: Any): Response {
        val text = when (body) {
            is JSONArray -> body.toString()
            else -> body.toString()
        }
        return newFixedLengthResponse(status, "application/json", text)
    }
}
