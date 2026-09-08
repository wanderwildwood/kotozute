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
    private val file: (List<com.wanderwildwood.kotozute.signal.BridgeMessage>) -> Int
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
            when (val result = decrypt(envelope, serverDeliveredTimestamp)) {
                null -> failed++
                else -> {
                    decrypted++
                    senders += result.first
                    result.second?.let { messages += it }
                }
            }
            delete(id)
        }
        // Filed in one transaction after the whole batch, not one at a time. The rail
        // announces what it stored, and a notification per message would be a notification
        // per message on a device catching up after a day offline.
        val stored = if (messages.isEmpty()) 0 else file(messages)

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
            val result = drainOnce(READ_TIMEOUT_MS)
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
        Timber.i(
            "signal receive: envelope dest=%s type=%s using=%s",
            envelope.destinationServiceId, envelope.type,
            if (envelope.destinationServiceId?.startsWith("PNI:") == true) "pni" else "aci"
        )
        return try {
            cipher.decrypt(envelope, serverDeliveredTimestamp)?.let { result ->
                val message = ContentNormalizer.normalize(result.content, result.metadata, credentials.aci, credentials.e164)
                Timber.i(
                    "signal receive: decrypted from %s -> %s",
                    result.metadata.sourceServiceId,
                    message?.let { "${it.threadKey} ts=${it.ts}" } ?: "nothing to store"
                )
                result.metadata.sourceServiceId.toString() to message
            }
        } catch (t: Throwable) {
            Timber.w(t, "signal receive: could not decrypt an envelope; dropping it")
            null
        }
    }

    companion object {
        private const val BATCH_SIZE = 10

        /** Long, deliberately: every expiry is a wakeup that learned nothing. See [listen]. */
        private val READ_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(1)
    }
}
