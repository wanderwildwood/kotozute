package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.ServiceId
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.whispersystems.signalservice.api.SignalSessionLock
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher
import org.whispersystems.signalservice.api.messages.EnvelopeResponse
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.internal.push.Envelope
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Takes messages off the socket and decrypts them.
 *
 * The receive half of what the bridge used to do. Structured around one rule, which is
 * signal-cli's and is easy to get backwards:
 *
 * > **Write the envelope down before acknowledging it.**
 *
 * The server deletes a message the moment it is acked and will not send it again. Ack first
 * and crash second and the message is not delayed, it is gone -- while the sender's client
 * shows it delivered. So an envelope is persisted, then acked, and only then decrypted and
 * filed. A crash after the write costs repeated work; it never costs a message.
 *
 * Decryption is deliberately *after* the ack rather than before. It is the step most likely to
 * fail -- an unknown session, a changed identity, a message from a device we have no session
 * with -- and holding the queue open behind a message that cannot be decrypted would stop
 * every message behind it.
 */
internal class SignalReceiver(
    private val db: ProtocolDatabase,
    private val accounts: SignalAccountStore,
    private val protocol: SignalDataStore,
    private val connection: SignalConnection,
    private val certificateValidator: CertificateValidator,
    /** Where a decrypted message goes. The same path the bridge sync files through. */
    private val file: (List<com.wanderwildwood.kotozute.signal.BridgeMessage>) -> Int,
    private val attachments: SignalAttachments,
    private val contacts: SignalContactStore,
    /**
     * Called after each batch, once anything new is on disk.
     *
     * A batch can bring a contacts sync, a profile key, or both, and either can make a name
     * fetchable that was not a moment ago. Running this per batch rather than only after a
     * sync is what keeps a conversation from staying nameless until something unrelated
     * happens to trigger a refresh.
     */
    private val afterBatch: () -> Unit,
    /** Records that messages we sent arrived, or were read, at the far end. */
    private val receipts: (String, List<Long>, Boolean) -> Unit
) {

    /**
     * The store's own lock, handed to libsignal as its session lock.
     *
     * The same lock, on purpose. libsignal takes this around a decryption and then calls back
     * into the stores, which take it again -- so a *different* lock here would mean two locks
     * acquired in a fixed order by one thread and the reverse order by another, which is the
     * deadlock the single-lock decision was made to avoid. Reentrant, so the callback is free.
     */
    private val sessionLock = SignalSessionLock {
        db.lock.lock()
        SignalSessionLock.Lock { db.lock.unlock() }
    }

    data class Received(
        val envelopes: Int,
        val decrypted: Int,
        val failed: Int,
        val queueEmptied: Boolean,
        val senders: Set<String>,
        /** Decrypted *and* something a thread can hold -- receipts and typing are neither. */
        val stored: Int
    )

    /**
     * Drains what the server has, up to [maxBatches] rounds.
     *
     * Returns when the server says the queue is empty, which it signals by the read returning
     * false rather than by any message. That signal is the only way to know a device has
     * caught up.
     */
    fun drain(maxBatches: Int = 20, timeout: Long = TimeUnit.SECONDS.toMillis(20)): Received =
        drainOnce(timeout, maxBatches)

    private fun drainOnce(timeout: Long, maxBatches: Int = 1): Received {
        var envelopes = 0
        var decrypted = 0
        var failed = 0
        var emptied = false
        val senders = mutableSetOf<String>()

        for (round in 1..maxBatches) {
            val queueNotEmpty = connection.authenticated.readMessageBatch(timeout, BATCH_SIZE) { batch ->
                batch.forEach { response ->
                    envelopes++
                    when (response) {
                        is EnvelopeResponse.Parsed -> store(response)
                        // Acked anyway. An envelope the client cannot even parse will never
                        // become parseable on a retry, and leaving it at the head of the queue
                        // blocks every message behind it forever.
                        is EnvelopeResponse.Unparseable ->
                            Timber.w("signal receive: unparseable envelope; acking to unblock the queue")
                    }
                    runCatching { connection.authenticated.sendAck(response) }
                        .onFailure { Timber.w(it, "signal receive: could not ack; it will be redelivered") }
                }
            }
            if (!queueNotEmpty) {
                emptied = true
                break
            }
        }

        // Only now, with everything acked and safely on disk.
        val messages = mutableListOf<com.wanderwildwood.kotozute.signal.BridgeMessage>()
        pending().forEach { (id, envelope, serverDeliveredTimestamp) ->
            // The server's own delivery receipt: type SERVER_DELIVERY_RECEIPT, whose content
            // is empty by definition. It is not a message and there is nothing in it to
            // decrypt -- it says "what you sent at this timestamp reached them".
            //
            // This was being treated as a message that would not decrypt, and kept. Two bugs
            // in one: a growing pile of envelopes reported to the user as unreadable messages,
            // and the delivery signal itself discarded -- the very thing deliveredAt exists to
            // record. It is what "1 message(s) could not be read (unknown)" turned out to be.
            if (envelope.type == Envelope.Type.SERVER_DELIVERY_RECEIPT) {
                val from = envelope.sourceServiceId.orEmpty()
                // clientTimestamp, not the server's: a receipt identifies the message by the
                // timestamp its *sender* stamped on it, which is the same value stored as the
                // message's own id and date. The server's timestamp would match nothing.
                val at = envelope.clientTimestamp ?: 0L
                if (from.isNotBlank() && at > 0) {
                    runCatching { receipts(from, listOf(at), false) }
                        .onFailure { Timber.w(it, "signal receive: could not record a delivery receipt") }
                }
                delete(id)
                return@forEach
            }

            when (val result = decrypt(envelope, serverDeliveredTimestamp)) {
                // Kept, not deleted. The envelope was acknowledged on the way past -- the
                // server has forgotten it and will never send it again -- so deleting a row
                // we failed to decrypt destroys the message permanently. A decryption that
                // fails today may succeed after a fix, and the ciphertext is the only copy
                // left anywhere. Swept by age below rather than kept for ever.
                null -> {
                    // Kept only when decryption actually threw. A null with nothing recorded
                    // means there was no content to decrypt, and keeping those is how an
                    // ordinary event becomes a permanent "message could not be read".
                    val why = lastFailure
                    if (why == null) {
                        delete(id)
                    } else {
                        failed++
                        recordFailure(id, why)
                    }
                }
                else -> {
                    decrypted++
                    senders += result.first
                    result.second?.let { messages += it }
                    delete(id)
                }
            }
        }
        // Filed in one transaction after the whole batch, not one at a time. The rail
        // announces what it stored, and a notification per message would be a notification
        // per message on a device catching up after a day offline.
        val stored = if (messages.isEmpty()) 0 else file(messages)
        sweepUndecryptable()

        // After filing, while the connection is still up: a name learned now is a name the
        // inbox shows on this pass rather than the next one.
        if (envelopes > 0) runCatching { afterBatch() }.onFailure { Timber.w(it, "signal: after-batch") }

        return Received(envelopes, decrypted, failed, emptied, senders, stored)
    }

    /**
     * Stays on the socket, handling messages as they arrive, until [keepGoing] says stop.
     *
     * The difference from [drain] is only that the connection is held open. `readMessageBatch`
     * blocks until a message arrives or the timeout elapses, so this loop *is* the persistent
     * connection -- there is no separate "listen" call to make.
     *
     * A long timeout rather than a short one on purpose. Each expiry is a wakeup that does
     * nothing, and on a phone that is battery spent to learn that nothing happened; the
     * keepalive in [SignalSocketHealthMonitor] is what actually notices a dead socket, and it
     * does that on its own schedule regardless of what this timeout is.
     *
     * @param onBatch called after each batch is stored, with what was filed.
     */
    fun listen(keepGoing: () -> Boolean, onBatch: (Received) -> Unit) {
        while (keepGoing()) {
            val result = try {
                drainOnce(READ_TIMEOUT_MS)
            } catch (e: java.util.concurrent.TimeoutException) {
                // Nothing arrived inside the read window, which is the ordinary state of a
                // quiet account and not a broken socket. The read is the only thing that
                // ended; the connection is still up and its keepalives are still going.
                //
                // Letting this out unwound the whole listen, and the loop above it treated
                // that as a dead connection and built a new authenticated websocket. On a
                // phone that is not being messaged every minute, that was a full reconnect
                // every sixty seconds -- 1,440 a day, each one a handshake on the radio, for
                // a socket that was never broken. Measured on the device: exactly 60.0s
                // apart, all day.
                //
                // Caught narrowly on purpose. A timeout means "carry on reading"; anything
                // else still unwinds and still reconnects, which is what should happen when
                // the socket really has gone.
                continue
            }
            // A batch that produced nothing is the common case -- the timeout expiring with an
            // empty queue -- and announcing it would wake everything downstream for no reason.
            if (result.envelopes > 0) onBatch(result)
        }
    }

    /**
     * `INSERT OR REPLACE` on the guid: an ack that is lost in flight means the server sends the
     * same message again, and that redelivery should replace the row rather than queue a
     * duplicate.
     */
    private fun store(response: EnvelopeResponse.Parsed) = withStoreLock(db) {
        db.writableDatabase.execSQL(
            """
            INSERT INTO envelope (server_guid, serialized, server_delivered_timestamp, stored_timestamp)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(server_guid) DO UPDATE SET serialized = excluded.serialized
            """.trimIndent(),
            arrayOf<Any?>(
                response.envelope.serverGuid,
                response.envelope.encode(),
                response.serverDeliveredTimestamp,
                System.currentTimeMillis()
            )
        )
    }

    private fun pending(): List<Triple<Long, Envelope, Long>> = withStoreLock(db) {
        db.readableDatabase.rawQuery(
            "SELECT _id, serialized, server_delivered_timestamp FROM envelope ORDER BY _id", null
        ).use { c ->
            generateSequence {
                if (c.moveToNext()) Triple(c.getLong(0), Envelope.ADAPTER.decode(c.getBlob(1)), c.getLong(2))
                else null
            }.toList()
        }
    }

    /**
     * Drops envelopes that have sat undecrypted for too long.
     *
     * They are kept in the first place because the ciphertext is the only copy left once the
     * server has been acknowledged, and a fix might yet read them. But a message that has been
     * unreadable for a fortnight is not going to become readable, and keeping every one for
     * ever turns a decryption bug into unbounded growth in a database holding key material.
     */
    private fun sweepUndecryptable() = withStoreLock(db) {
        val cutoff = System.currentTimeMillis() - UNDECRYPTABLE_RETENTION_MS
        db.writableDatabase.execSQL(
            "DELETE FROM envelope WHERE stored_timestamp < ?", arrayOf<Any?>(cutoff)
        )
        db.readableDatabase.rawQuery("SELECT count(*) FROM envelope", null).use { c ->
            val stuck = if (c.moveToFirst()) c.getInt(0) else 0
            if (stuck > 0) Timber.w("signal receive: %d envelope(s) still undecrypted", stuck)
        }
    }

    private fun recordFailure(id: Long, reason: String) = withStoreLock(db) {
        db.writableDatabase.execSQL(
            "UPDATE envelope SET failure = ? WHERE _id = ?", arrayOf<Any?>(reason, id)
        )
    }

    private fun delete(id: Long) = withStoreLock(db) {
        db.writableDatabase.execSQL("DELETE FROM envelope WHERE _id = ?", arrayOf<Any?>(id))
    }

    /**
     * @return the sender, or null if this envelope could not be decrypted.
     *
     * A failure here is logged and swallowed rather than thrown. One undecryptable message --
     * a device we have no session with, an identity that changed since -- must not stop the
     * ones behind it, and the envelope is already acked, so there is nothing to retry against
     * the server anyway.
     */
    /** Set by [decrypt] when it fails, so the caller can record it against the row. */
    private var lastFailure: String? = null

    private fun decrypt(
        envelope: Envelope,
        serverDeliveredTimestamp: Long
    ): Pair<String, com.wanderwildwood.kotozute.signal.BridgeMessage?>? {
        val credentials = accounts.credentials()
        val aci = ServiceId.ACI.parseOrNull(credentials.aci) ?: return null

        val cipher = SignalServiceCipher(
            SignalServiceAddress(aci, credentials.e164),
            credentials.deviceId,
            // The ACI store. A PNI-addressed envelope needs the PNI store instead, and using
            // the wrong one does not fail cleanly -- it fails as a decryption error, which
            // looks like a corrupt message.
            if (envelope.destinationServiceId?.startsWith("PNI:") == true) protocol.pni() else protocol.aci(),
            sessionLock,
            certificateValidator
        )
        return try {
            cipher.decrypt(envelope, serverDeliveredTimestamp)?.let { result ->
                // A receipt is about a message we already have, not a new one, so it is
                // handled here and never reaches the normalizer -- which would find nothing
                // in it and drop it silently.
                result.content.receiptMessage?.let { receipt ->
                    val timestamps = receipt.timestamp
                    if (timestamps.isNotEmpty()) {
                        receipts(
                            result.metadata.sourceServiceId.toString(),
                            timestamps,
                            receipt.type == org.whispersystems.signalservice.internal.push.ReceiptMessage.Type.READ
                        )
                    }
                }

                // Profile keys ride on ordinary messages, from the person whose profile they
                // open. This is the only route: the contacts sync does not carry them, and
                // without one a profile fetch returns ciphertext.
                rememberProfileKey(result.content, result.metadata)

                // A contacts sync is not a message and never becomes one -- it is the
                // primary answering a request, and the only way this device learns anybody's
                // name. Handled before normalizing, which would find nothing to store in it.
                result.content.syncMessage?.contacts?.let { handleContactsSync(it) }

                val normalized = ContentNormalizer.normalize(
                    result.content, result.metadata, credentials.aci, credentials.e164
                )
                // Downloaded now, while the CDN still has them. See SignalAttachments: a
                // pointer is only good for a window, so fetching lazily when a bubble is drawn
                // fails for exactly the attachments worth keeping.
                val message = normalized?.let { withAttachments(it, result.content) }
                Timber.i(
                    "signal receive: decrypted from %s -> %s",
                    result.metadata.sourceServiceId,
                    message?.let { "${it.threadKey} ts=${it.ts}" } ?: "nothing to store"
                )
                result.metadata.sourceServiceId.toString() to message
            }
        } catch (t: Throwable) {
            // Recorded against the row, not just logged: on a release build the log goes
            // nowhere, and "one message could not be read" without a reason is a report
            // nobody can act on.
            lastFailure = "${t::class.java.simpleName}: ${t.message?.take(120).orEmpty()}"
            Timber.w(t, "signal receive: could not decrypt an envelope; keeping it")
            null
        }
    }

    /**
     * Notes the sender's profile key when a message carries one.
     *
     * Signal shares these deliberately -- a person's key comes with their messages once they
     * have chosen to share their profile with you -- so this is not something that can be
     * asked for. It has to be taken when offered, which means every message, not just the
     * first: a rotated key arrives the same way and a stale one decrypts nothing.
     */
    private fun rememberProfileKey(
        content: org.whispersystems.signalservice.internal.push.Content,
        metadata: org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
    ) {
        // Either shape. An incoming message carries the sender's key; a sync of our own send
        // carries ours. In both cases it belongs to whoever the envelope says sent it, so one
        // attribution is right for both -- and taking only the first shape means never
        // learning our own profile at all.
        val key = (content.dataMessage?.profileKey ?: content.syncMessage?.sent?.message?.profileKey)
            ?.toByteArray()
            ?.takeIf { it.size == PROFILE_KEY_BYTES }
            ?: return
        val aci = metadata.sourceServiceId.toString().takeIf { it.isNotBlank() } ?: return
        Timber.i("signal profile: noted a profile key from %s", aci)
        contacts.store(listOf(SignalContactStore.Contact(aci = aci, e164 = null, name = null, profileKey = key)))
    }

    /**
     * Streams the contacts blob and stores what is in it.
     *
     * Streamed rather than kept: this is a snapshot that is parsed once and superseded by the
     * next sync, so writing it to disk would leave the whole address book sitting in a file
     * for no benefit.
     */
    private fun handleContactsSync(
        contactsMessage: org.whispersystems.signalservice.internal.push.SyncMessage.Contacts
    ) {
        val pointer = contactsMessage.blob ?: return
        val parsed = attachments.streamOnce(pointer) { input ->
            val stream = org.whispersystems.signalservice.api.messages.multidevice
                .DeviceContactsInputStream(input)
            val found = mutableListOf<SignalContactStore.Contact>()
            while (true) {
                val contact = try {
                    stream.read() ?: break
                } catch (e: java.io.IOException) {
                    // signal-cli skips these rather than abandoning the sync: one malformed
                    // entry should not cost every name after it in the stream.
                    if (e.message?.contains("Missing contact address") == true) continue else throw e
                }
                val aci = contact.aci.orElse(null)?.toString() ?: continue
                found += SignalContactStore.Contact(
                    aci = aci,
                    e164 = contact.e164.orElse(null),
                    name = contact.name.orElse(null)
                    // No profile key here. DeviceContact carries aci, e164, name, avatar and
                    // the expiration timer -- and nothing else. Profile keys travel on
                    // DataMessage instead, shared by the person themselves; see
                    // rememberProfileKey().
                )
            }
            found
        }
        parsed?.takeIf { it.isNotEmpty() }?.let { contacts.store(it) }
    }

    /**
     * Replaces the placeholder attachment metadata with what was actually fetched.
     *
     * A pointer that fails to download leaves the row saying an attachment exists but is not
     * here, rather than dropping it: a message that silently loses its picture reads as if the
     * sender never sent one.
     */
    private fun withAttachments(
        message: com.wanderwildwood.kotozute.signal.BridgeMessage,
        content: org.whispersystems.signalservice.internal.push.Content
    ): com.wanderwildwood.kotozute.signal.BridgeMessage {
        val dataMessage = content.syncMessage?.sent?.message ?: content.dataMessage ?: return message
        if (dataMessage.attachments.isEmpty() || message.viewOnce) return message

        val array = org.json.JSONArray()
        dataMessage.attachments.forEach { pointer ->
            val id = attachments.download(pointer)
            array.put(
                org.json.JSONObject()
                    .put("id", id.orEmpty())
                    .put("type", pointer.contentType.orEmpty())
                    .put("filename", pointer.fileName.orEmpty())
                    .put("size", pointer.size ?: 0)
                    .put("pending", id == null)
            )
        }
        return message.copy(attachmentsJson = array.toString())
    }

    companion object {
        private const val BATCH_SIZE = 10

        /** A profile key is exactly this; anything else is not one. */
        private const val PROFILE_KEY_BYTES = 32

        /** How long to keep an envelope that will not decrypt, in case a fix arrives. */
        private val UNDECRYPTABLE_RETENTION_MS = TimeUnit.DAYS.toMillis(14)

        /** Long, deliberately: every expiry is a wakeup that learned nothing. See [listen]. */
        private val READ_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(1)
    }
}
