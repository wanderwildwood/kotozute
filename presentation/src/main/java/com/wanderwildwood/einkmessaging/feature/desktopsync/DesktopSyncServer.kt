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
package com.wanderwildwood.einkmessaging.feature.desktopsync

import android.content.Context
import com.wanderwildwood.einkmessaging.model.Attachment
import com.wanderwildwood.einkmessaging.model.Conversation
import com.wanderwildwood.einkmessaging.model.Message
import com.wanderwildwood.einkmessaging.interactor.MarkRead
import com.wanderwildwood.einkmessaging.interactor.SendNewMessage
import com.wanderwildwood.einkmessaging.repository.ContactRepository
import com.wanderwildwood.einkmessaging.repository.ConversationRepository
import com.wanderwildwood.einkmessaging.repository.MessageRepository
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
        /** How many of a thread's most recent messages to send to the browser. */
        const val MESSAGE_PAGE_SIZE = 300

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
            val authed = handshakeRequest.parameters["token"]?.firstOrNull() == token
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
        }

        val authed = session.headers["authorization"] == "Bearer $token" ||
                session.parameters["token"]?.firstOrNull() == token
        if (!authed) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().put("error", "bad token"))
        }

        // Realm only auto-refreshes on threads with a Looper. NanoHTTPD serves each
        // connection on a plain worker thread, and a browser reusing one keep-alive
        // connection lands on that same thread every time — so without an explicit
        // refresh it keeps serving whatever snapshot the thread first opened, forever.
        // (curl looked fine only because each new connection got a fresh thread.)
        runCatching { Realm.getDefaultInstance().use { it.refresh() } }

        val threadMessagesMatch = Regex("^/api/threads/(\\d+)/messages$").find(uri)
        val threadSendMatch = Regex("^/api/threads/(\\d+)/send$").find(uri)
        val threadReadMatch = Regex("^/api/threads/(\\d+)/read$").find(uri)
        val partMatch = Regex("^/api/parts/(\\d+)$").find(uri)

        return when {
            uri == "/api/threads" && session.method == Method.GET -> handleGetThreads()
            threadMessagesMatch != null && session.method == Method.GET ->
                handleGetMessages(threadMessagesMatch.groupValues[1].toLong(), session)
            threadSendMatch != null && session.method == Method.POST ->
                handleSend(session, threadSendMatch.groupValues[1].toLong())
            uri == "/api/compose" && session.method == Method.POST -> handleCompose(session)
            uri == "/api/contacts" && session.method == Method.GET -> handleContacts(session)
            uri == "/api/thread-for" && session.method == Method.GET -> handleThreadFor(session)
            partMatch != null && session.method == Method.GET ->
                handlePart(partMatch.groupValues[1].toLong())
            threadReadMatch != null && session.method == Method.POST ->
                handleMarkRead(threadReadMatch.groupValues[1].toLong())
            else -> jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "not found"))
        }
    }

    private fun serveAsset(name: String, mimeType: String): Response {
        val stream = runCatching { context.assets.open("desktopsync/$name") }.getOrNull()
            ?: return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "asset missing"))
        return newChunkedResponse(Response.Status.OK, mimeType, stream)
    }

    private fun handleGetThreads(): Response {
        val conversations = conversationRepository.getConversationsSnapshot(unreadAtTop = true)
        val array = JSONArray()
        conversations.filterNot { it.archived || it.blocked }.forEach { conversation ->
            array.put(conversationJson(conversation))
        }
        return jsonResponse(Response.Status.OK, array)
    }

    private fun handleGetMessages(threadId: Long, session: IHTTPSession): Response {
        // Only the tail of the thread by default: some conversations here run 600+
        // messages and the browser re-fetches this every few seconds. `limit` lets the
        // browser ask for more so older history is still reachable.
        //
        // Uses getMessagesSync (not the repo's limit overload) because that one can
        // return findAllAsync results, which need a Looper thread — and NanoHTTPD
        // serves each request on a plain worker thread.
        val requested = session.parameters["limit"]?.firstOrNull()?.toIntOrNull()
        val limit = (requested ?: MESSAGE_PAGE_SIZE).coerceIn(1, MESSAGE_MAX_LIMIT)

        val all = messageRepository.getMessagesSync(threadId)
        val total = all.size
        val array = JSONArray()
        all.takeLast(limit).forEach { message -> array.put(messageJson(message)) }

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

        rejectionResponse(submission)?.let { return it }

        // A picture on its own is a message. Only require text when nothing else is attached.
        if (submission.body.isBlank() && submission.attachments.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing body"))
        }

        val conversation = conversationRepository.getConversation(threadId)
            ?: return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "no such thread"))

        val addresses = conversation.recipients.map { it.address }
        sendAndWait(
            conversation.id, addresses, conversation.sendAsGroup,
            submission.body, submission.attachments
        )
        return jsonResponse(Response.Status.OK, JSONObject().put("ok", true))
    }

    /** What a compose request carried, however it was encoded. */
    private class Submission(
        val body: String,
        val to: String,
        val attachments: List<Attachment>,
        /** Files that arrived but could not be used -- an image the phone cannot decode. */
        val rejected: Int = 0
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
            return Submission(json.optString("body"), json.optString("to"), emptyList())
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
            rejected = uploads.count { it == null }
        )
    }

    /**
     * Streams an MMS attachment (picture, etc.) straight out of the MMS content
     * provider, so pictures actually show up in the browser instead of appearing as
     * empty bubbles.
     */
    private fun handlePart(partId: Long): Response {
        val part = messageRepository.getPart(partId)
            ?: return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "no such part"))
        val mime = part.type.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val stream = runCatching { context.contentResolver.openInputStream(part.getUri()) }.getOrNull()
            ?: return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "part unreadable"))
        return newChunkedResponse(Response.Status.OK, mime, stream)
    }

    /**
     * Reading a thread in the browser should clear it on the phone too. Goes through
     * the MarkRead interactor rather than the repository directly, so the phone's
     * notification is dismissed and the launcher badge updated as well — not just
     * the database flag.
     */
    private fun handleMarkRead(threadId: Long): Response {
        markRead.execute(listOf(threadId))
        notifyChanged()
        return jsonResponse(Response.Status.OK, JSONObject().put("ok", true))
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

        sendAndWait(threadId ?: 0L, addresses, sendAsGroup, body, submission.attachments)

        return jsonResponse(Response.Status.OK, JSONObject().apply {
            put("ok", true)
            if (threadId != null && threadId != 0L) put("threadId", threadId)
        })
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
    ) {
        val done = java.util.concurrent.CountDownLatch(1)
        sendNewMessage.execute(
            SendNewMessage.Params(
                subId = -1,
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
    }

    private fun conversationJson(conversation: Conversation) = JSONObject().apply {
        put("id", conversation.id)
        put("title", conversation.getTitle())
        put("snippet", conversation.snippet ?: "")
        put("date", conversation.date)
        put("unread", conversation.unread)
    }

    private fun messageJson(message: Message) = JSONObject().apply {
        put("id", message.id)
        put("body", message.getText())
        put("date", message.date)
        put("isMe", message.isMe())
        put("read", message.read)
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
