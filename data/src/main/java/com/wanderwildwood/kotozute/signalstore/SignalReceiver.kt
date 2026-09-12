package com.wanderwildwood.kotozute.signalstore

import org.signal.core.models.ServiceId
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.protocol.SignalProtocolAddress
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
    private val attachments: SignalAttachments,
    private val contacts: SignalContactStore,
    /** The account's blocked list, kept whole; see [SignalBlockStore]. */
    private val blocks: SignalBlockStore,
    /** Where the storage service key ends up; see [SignalKeyStore]. */
    private val keys: SignalKeyStore,
    /**
     * Everything this path has to tell the rest of the app; see [SignalEvents].
     *
     * One object rather than the ten lambdas this used to take, because six of them arrived in
     * one evening and each cost an edit in four files.
     */
    private val events: SignalEvents
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
                val from = senderOf(envelope).orEmpty()
                // clientTimestamp, not the server's: a receipt identifies the message by the
                // timestamp its *sender* stamped on it, which is the same value stored as the
                // message's own id and date. The server's timestamp would match nothing.
                val at = envelope.clientTimestamp ?: 0L
                if (from.isNotBlank() && at > 0) {
                    runCatching { events.receipts(from, listOf(at), false) }
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
        val stored = if (messages.isEmpty()) 0 else events.store(messages)
        sweepUndecryptable()

        // Only now, and only for what is actually on disk. A delivery receipt is a claim that
        // this phone has the message; sending it before filing would make that claim on
        // behalf of a message that could still be lost.
        //
        // Sent for everything received, without asking. Unlike a read receipt this is not a
        // setting and reveals nothing about the reader -- it is how a sender's message stops
        // saying nothing at all, and a phone that never sends one leaves everyone writing to
        // it unsure whether it is even on.
        if (stored > 0) {
            messages.asSequence()
                .filterNot { it.outgoing }
                .filter { it.senderUuid.isNotBlank() && it.ts > 0 }
                .groupBy({ it.senderUuid }, { it.ts })
                .forEach { (sender, timestamps) ->
                    runCatching { events.sendDeliveryReceipt(sender, timestamps.distinct()) }
                        .onFailure { Timber.w(it, "signal receive: could not send a delivery receipt") }
                }
        }

        // After filing, while the connection is still up: a name learned now is a name the
        // inbox shows on this pass rather than the next one.
        if (envelopes > 0) runCatching { events.afterBatch() }.onFailure { Timber.w(it, "signal: after-batch") }

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
    /** Whether this is the server sending something again, rather than a message going wrong. */
    private fun isDuplicate(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.take(CAUSE_DEPTH).any { cause ->
            cause is org.signal.libsignal.protocol.DuplicateMessageException ||
                cause::class.java.simpleName.contains("DuplicateMessage")
        }

    private fun sweepUndecryptable() = withStoreLock(db) {
        val cutoff = System.currentTimeMillis() - UNDECRYPTABLE_RETENTION_MS
        db.writableDatabase.execSQL(
            "DELETE FROM envelope WHERE stored_timestamp < ?", arrayOf<Any?>(cutoff)
        )
        // Anything an older build filed as unreadable that was only a redelivery. Left alone
        // it sits in the connection line for the whole retention window, reporting a problem
        // that was never one.
        db.writableDatabase.execSQL(
            "DELETE FROM envelope WHERE failure LIKE '%DuplicateMessage%'"
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
            if (addressedToPni(envelope)) protocol.pni() else protocol.aci(),
            sessionLock,
            certificateValidator
        )
        return try {
            cipher.decrypt(envelope, serverDeliveredTimestamp)?.let { result ->
                // First, before anything else in this batch is decrypted. A group send
                // encrypts once to a key the sender distributes separately, and the message
                // that carries the key can arrive in the same batch as messages that need
                // it. Handled later -- or not at all, which is what happened here -- every
                // group message from a sender using sender keys fails to decrypt, and the
                // failure looks like a broken message rather than a missing key.
                result.content.senderKeyDistributionMessage?.let { distribution ->
                    acceptSenderKey(
                        result.metadata.sourceServiceId.toString(),
                        result.metadata.sourceDeviceId,
                        distribution.toByteArray()
                    )
                }

                // A receipt is about a message we already have, not a new one, so it is
                // handled here and never reaches the normalizer -- which would find nothing
                // in it and drop it silently.
                result.content.receiptMessage?.let { receipt ->
                    val timestamps = receipt.timestamp
                    if (timestamps.isNotEmpty()) {
                        events.receipts(
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

                // A transcript of our own send names the recipient twice: by service id and,
                // usually, by number. That pairing arrives nowhere else on a linked device --
                // no contact discovery is done here and an incoming envelope carries no
                // number -- and it is what lets a Signal conversation be matched to a person
                // in the phone's own address book instead of showing a raw service id.
                rememberDestination(result.content)

                // Proof, from the person themselves, that a phone-number identity and an
                // account id are one person. Discovery hands this phone a PNI and nothing
                // else, so without this the pairing only ever arrives second-hand, in one of
                // the account's own storage records.
                result.content.pniSignatureMessage?.let { signature ->
                    rememberVerifiedPni(signature, result.metadata)
                }

                // A contacts sync is not a message and never becomes one -- it is the
                // primary answering a request, and the only way this device learns anybody's
                // name. Handled before normalizing, which would find nothing to store in it.
                result.content.syncMessage?.contacts?.let { handleContactsSync(it) }

                // A message withdrawn for everyone. Before the normalizer, which would find
                // nothing in it and drop it: a delete carries no body, so treated as a
                // message it is simply not one.
                //
                // The author is the envelope's sender for somebody else's delete, and this
                // account for a sync of our own. Either way it is the author of the message
                // being withdrawn, which is what the row is keyed by -- so nobody can reach
                // anybody else's messages with this.
                (result.content.dataMessage?.delete
                    ?: result.content.syncMessage?.sent?.message?.delete)
                    ?.targetSentTimestamp
                    ?.let { at ->
                        val author = if (result.content.syncMessage?.sent?.message?.delete != null) {
                            credentials.aci.orEmpty()
                        } else {
                            result.metadata.sourceServiceId.toString()
                        }
                        if (author.isNotBlank()) {
                            runCatching { events.withdrawn(author, at) }
                                .onFailure { Timber.w(it, "signal delete: could not withdraw") }
                        }
                    }

                // What the account has read on another device. Applied here, never answered:
                // the device that did the reading has already told the sender, and saying so
                // again would tell them twice.
                result.content.syncMessage?.read
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { read ->
                        val pairs = read.mapNotNull { one ->
                            // Both fields, as everywhere: the string is the old one and a
                            // modern primary fills only the binary twin. Reading one would
                            // make every read sync name nobody and quietly do nothing.
                            val sender = ServiceId.parseOrNull(one.senderAci, one.senderAciBinary)
                                ?.toString() ?: return@mapNotNull null
                            val at = one.timestamp ?: return@mapNotNull null
                            sender to at
                        }
                        if (pairs.isNotEmpty()) {
                            runCatching { events.readElsewhere(pairs) }
                                .onFailure { Timber.w(it, "signal read sync: could not apply") }
                        }
                    }

                // What the account has deleted on another device, for itself.
                result.content.syncMessage?.deleteForMe?.let { deletes ->
                    val messages = deletes.messageDeletes
                        .flatMap { it.messages }
                        .mapNotNull { message ->
                            val author = ServiceId.parseOrNull(
                                message.authorServiceId, message.authorServiceIdBinary
                            )?.toString() ?: return@mapNotNull null
                            val at = message.sentTimestamp ?: return@mapNotNull null
                            author to at
                        }

                    // Direct conversations only. A group's thread key here is derived from
                    // the group's master key, and the id this names is not that -- guessing
                    // at the derivation would either match nothing or, far worse, match the
                    // wrong conversation and empty it. Group deletes are left alone until
                    // the two can be shown to agree.
                    val threads = deletes.conversationDeletes.mapNotNull { conversation ->
                        conversation.conversation?.let { id ->
                            if (id.threadGroupId != null && id.threadGroupId!!.size > 0) return@mapNotNull null
                            ServiceId.parseOrNull(id.threadServiceId, id.threadServiceIdBinary)
                                ?.toString()?.let { "direct:$it" }
                        }
                    }

                    if (messages.isNotEmpty() || threads.isNotEmpty()) {
                        runCatching { events.deletedElsewhere(messages, threads) }
                            .onFailure { Timber.w(it, "signal delete sync: could not apply") }
                    }
                }

                // Somebody telling us they could not read something we sent.
                //
                // The message itself cannot be sent again from here -- that needs a record of
                // the ciphertext this device sent, which it does not keep -- but the session
                // can be cleared so the *next* thing sent to them is built fresh instead of
                // failing the same way for ever.
                result.content.decryptionErrorMessage?.let { bytes ->
                    repairSessionFor(
                        result.metadata.sourceServiceId.toString(),
                        result.metadata.sourceDeviceId,
                        bytes.toByteArray()
                    )
                }

                // What the account has verified about somebody's safety number, decided on
                // another device. A verification is a thing a person does once, carefully, in
                // the room; it belongs to the account rather than the device it happened on.
                result.content.syncMessage?.verified?.let { verified ->
                    val who = ServiceId.parseOrNull(
                        verified.destinationAci, verified.destinationAciBinary
                    )?.toString()
                    val key = verified.identityKey?.toByteArray()
                    if (who != null && key != null && key.isNotEmpty()) {
                        runCatching {
                            protocol.aciStore().setVerified(
                                who,
                                org.signal.libsignal.protocol.IdentityKey(key),
                                verified.state ==
                                    org.whispersystems.signalservice.internal.push.Verified.State.VERIFIED
                            )
                        }.onFailure { Timber.w(it, "signal identity: could not record a verification") }
                    }
                }

                // The account's settings. Sent when they change and on request, so this is
                // how a device that was asleep catches up with a choice made elsewhere.
                result.content.syncMessage?.configuration?.let { settings ->
                    runCatching { events.configuration(settings.readReceipts) }
                        .onFailure { Timber.w(it, "signal configuration: could not apply") }
                }

                // The account's blocked list, which arrives whole and replaces what is held.
                // Stored rather than acted on: this device does not hide anything on the
                // strength of it, it keeps it so that blocking somebody from here can send
                // the list back with one more name on it instead of a list of one.
                result.content.syncMessage?.blocked?.let { handleBlockedSync(it) }

                // The account's own key material, which arrives only because this device
                // asked. Derived from and dropped; see SignalKeyStore. The contact list is
                // read as soon as it lands rather than at the next connection: this is the
                // moment the phone becomes able to read it at all.
                result.content.syncMessage?.keys?.let { sent ->
                    // Said either way, because the two failures look identical from outside
                    // and need different answers: a primary that never replies is one thing,
                    // a primary that replies with key material in a shape this build cannot
                    // read is another -- an older Signal sends a master key where a newer one
                    // sends the pool, and only the pool is in this protocol version.
                    val pool = sent.accountEntropyPool
                    when {
                        pool.isNullOrBlank() ->
                            Timber.w("signal keys: the primary answered with no account entropy pool")
                        keys.store(pool) -> events.onKeysLearned()
                        else -> Timber.w("signal keys: the pool the primary sent would not derive")
                    }
                }

                val normalized = ContentNormalizer.normalize(
                    result.content, result.metadata, credentials.aci, credentials.e164
                )
                // Downloaded now, while the CDN still has them. See SignalAttachments: a
                // pointer is only good for a window, so fetching lazily when a bubble is drawn
                // fails for exactly the attachments worth keeping.
                val message = normalized?.let { withAttachments(it, result.content) }
                // Without the sender and without the thread key. Both identify a person, the
                // thread key because it is derived from exactly that -- and this is a release
                // build with a logging tree planted, so anything here is written down. Who is
                // talking to this phone is the metadata the rest of this protects; it is not
                // worth a better debug line. That it decrypted, and whether there was anything
                // to keep, is what this line was actually for.
                Timber.i(
                    "signal receive: decrypted -> %s",
                    if (message != null) "stored" else "nothing to store"
                )
                result.metadata.sourceServiceId.toString() to message
            }
        } catch (t: Throwable) {
            // A duplicate is not a failure. The server redelivers an envelope whose ack was
            // lost, and libsignal refuses to run the ratchet backwards -- which is the
            // protocol working. The message it names is already in the thread, so reporting
            // it as unreadable tells the user something is wrong with a conversation that is
            // perfectly intact, and there is nothing they could do about it if it were.
            if (isDuplicate(t)) {
                Timber.i("signal receive: the server sent a message again; already have it")
                return null
            }
            // Recorded against the row, not just logged: on a release build the log goes
            // nowhere, and "one message could not be read" without a reason is a report
            // nobody can act on.
            lastFailure = "${t::class.java.simpleName}: ${t.message?.take(120).orEmpty()}"
            Timber.w(t, "signal receive: could not decrypt an envelope; keeping it")
            askForItAgain(envelope, t)
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
    /**
     * Keeps the key a sender uses for their group messages.
     *
     * A group send is encrypted once, to a key the sender hands out beforehand in an ordinary
     * one-to-one message. Store it and their group messages open; ignore it and they never do.
     * The store was already wired into the protocol store -- nothing ever put anything in it.
     *
     * Adapted from Signal Android's `MessageDecryptor.handleSenderKeyDistributionMessage`,
     * including where it sits: before the rest of the batch, not after.
     */
    private fun acceptSenderKey(sender: String, deviceId: Int, distribution: ByteArray) {
        if (sender.isBlank()) return
        runCatching {
            val message = org.signal.libsignal.protocol.message.SenderKeyDistributionMessage(distribution)
            org.whispersystems.signalservice.api.crypto.SignalGroupSessionBuilder(
                sessionLock,
                org.signal.libsignal.protocol.groups.GroupSessionBuilder(protocol.aci())
            ).process(SignalProtocolAddress(sender, deviceId), message)
            Timber.i("signal group key: kept a sender key for distribution %s", message.distributionId)
        }.onFailure {
            // Not fatal to the envelope that carried it: that message is still a message, and
            // it decrypted. Only this sender's group messages are affected.
            Timber.w(it, "signal group key: a sender key would not be kept")
        }
    }

    /**
     * Clears a session somebody says is broken, but only the one they mean.
     *
     * The receipt carries the ratchet key of the session that failed. Archiving without
     * checking it would throw away a session that has since been rebuilt and is working --
     * turning somebody else's stale complaint into a fresh break here, which is the opposite
     * of a repair. Signal makes the same check for the same reason.
     *
     * A receipt naming one of this account's other devices is not ours to act on.
     *
     * Adapted from Signal Android's `MessageContentProcessor.handleIndividualRetryReceipt`,
     * minus the half that resends: that needs a log of sent ciphertext this app does not keep.
     */
    private fun repairSessionFor(sender: String, deviceId: Int, serialized: ByteArray) {
        if (sender.isBlank()) return
        runCatching {
            val error = org.signal.libsignal.protocol.message.DecryptionErrorMessage(serialized)
            if (error.deviceId != accounts.credentials().deviceId) {
                Timber.i("signal retry: a retry receipt for another of this account's devices")
                return
            }
            val ratchetKey = error.ratchetKey.orElse(null) ?: run {
                // Without it there is no way to tell which session they mean, and archiving
                // on a guess is how a working conversation gets broken.
                Timber.w("signal retry: a retry receipt with no ratchet key; leaving the session alone")
                return
            }
            val address = SignalProtocolAddress(sender, deviceId)
            val store = protocol.aci()
            val session = store.loadSession(address)
            if (session != null && session.currentRatchetKeyMatches(ratchetKey)) {
                store.archiveSession(address)
                Timber.i("signal retry: archived the session they could not read, so the next send is fresh")
            } else {
                Timber.i("signal retry: the session has already moved on; leaving it alone")
            }
        }.onFailure { Timber.w(it, "signal retry: could not act on a retry receipt") }
    }

    /**
     * Asks the sender to send it again, after a decrypt this phone could not do.
     *
     * The envelope is kept either way -- a later fix might read it -- but keeping it is not a
     * recovery. This is: the receipt names the exact message and shows the session is broken,
     * and the sender's client resends over a fresh one.
     *
     * Only for a real protocol failure. A network error or a bug here is not something the far
     * end can fix by sending again, and asking would be noise in somebody else's app.
     *
     * Shape adapted from Signal Android's `MessageDecryptor.buildSendRetryReceiptJob`,
     * including which bytes to quote back: a sealed-sender envelope has the original inside
     * the exception rather than in the envelope, and quoting the wrong one produces a receipt
     * the sender cannot match to anything.
     */
    private fun askForItAgain(envelope: Envelope, failure: Throwable) {
        val protocolFailure = generateSequence(failure) { it.cause }
            .take(CAUSE_DEPTH)
            .filterIsInstance<org.signal.libsignal.metadata.ProtocolException>()
            .firstOrNull() ?: return

        val sender = protocolFailure.sender?.takeIf { it.isNotBlank() }
            ?: senderOf(envelope)
            ?: return
        val timestamp = envelope.clientTimestamp ?: return

        val sealed = protocolFailure.unidentifiedSenderMessageContent
        val original: ByteArray
        val type: Int
        if (sealed.isPresent) {
            original = sealed.get().content
            type = sealed.get().type
        } else {
            original = envelope.content?.toByteArray() ?: return
            type = ciphertextTypeOf(envelope.type)
        }

        val error = runCatching {
            org.signal.libsignal.protocol.message.DecryptionErrorMessage.forOriginalMessage(
                original, type, timestamp, protocolFailure.senderDevice
            )
        }.getOrElse {
            Timber.w(it, "signal retry: could not describe the message that would not open")
            return
        }
        runCatching { events.sendRetryReceipt(sender, error, protocolFailure.groupId.orElse(null)) }
            .onFailure { Timber.w(it, "signal retry: could not ask for the message again") }
    }

    /**
     * Associates an account id with a phone-number identity, but only on proof.
     *
     * The sender signs their PNI identity key with their ACI identity key; verifying it says
     * the two keys belong to the same person, which is the one claim worth acting on. An
     * unverified claim would let anybody assert somebody else's PNI and take over the
     * conversation held under it -- so a signature that does not check out is dropped, and
     * loudly enough to find in a log.
     *
     * Adapted from Signal Android's `MessageDecryptor.handlePniSignatureMessage`, which is
     * where the shape of this -- which identity store to ask, and what to do when the PNI
     * identity is only known for device 1 -- comes from.
     */
    private fun rememberVerifiedPni(
        message: org.whispersystems.signalservice.internal.push.PniSignatureMessage,
        metadata: org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
    ) {
        val pniBytes = message.pni?.toByteArray() ?: return
        val signature = message.signature?.toByteArray() ?: return
        val pni = org.signal.core.models.ServiceId.PNI.parseOrNull(pniBytes) ?: return
        val aci = metadata.sourceServiceId.toString().takeIf { it.isNotBlank() } ?: return

        // Already known, from here or from the account's own records. Verifying again costs
        // two store reads and a curve operation to reach the same conclusion.
        if (runCatching { contacts.aciForPni(pni.toString()) }.getOrNull() == aci) return

        val store = protocol.aci()
        val deviceId = metadata.sourceDeviceId
        val aciIdentity = runCatching {
            store.getIdentity(SignalProtocolAddress(aci, deviceId))
        }.getOrNull() ?: run {
            Timber.w("signal pni: no identity for the sender, so nothing to check a signature against")
            return
        }
        // The PNI identity may only be on file for the primary device: a session with one of
        // somebody's other devices does not imply one with their phone-number identity on
        // that same device.
        val pniIdentity = runCatching {
            store.getIdentity(SignalProtocolAddress(pni.toString(), deviceId))
                ?: store.getIdentity(SignalProtocolAddress(pni.toString(), DEFAULT_DEVICE_ID))
        }.getOrNull() ?: run {
            Timber.w("signal pni: no identity on file for the phone-number identity being claimed")
            return
        }

        val proven = runCatching { pniIdentity.verifyAlternateIdentity(aciIdentity, signature) }
            .getOrDefault(false)
        if (!proven) {
            // Not an error to recover from -- it is somebody claiming an identity that is not
            // theirs, or a corrupted message. Either way the pairing is not taken.
            Timber.w("signal pni: a phone-number identity was claimed with a signature that does not check out")
            return
        }
        runCatching { contacts.pair(pni.toString(), aci) }
            .onFailure { Timber.w(it, "signal pni: a verified pairing would not keep") }
        Timber.i("signal pni: a phone-number identity was proved to belong to a known account")
    }

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
        // Deliberately without the account id. This is a Signal client in a release build that
        // plants a logging tree, so anything written here is written down: who is talking to
        // this phone is exactly the metadata the rest of the app goes to some length not to
        // hand anyone, and it should not be in a log to buy a slightly better debug line.
        Timber.i("signal profile: noted a profile key")
        contacts.store(listOf(SignalContactStore.Contact(serviceId = aci, profileKey = key)))
    }

    /**
     * Notes who one of our own sends went to, when the transcript says it in full.
     *
     * Only the pairing, never a name: the number is a fact the account already has, while a
     * name for it is the reader's own business and comes from their address book.
     */
    private fun rememberDestination(
        content: org.whispersystems.signalservice.internal.push.Content
    ) {
        val sent = content.syncMessage?.sent ?: return
        val aci = ContentNormalizer.destinationServiceIdOf(sent).takeIf { it.isNotBlank() } ?: return
        val e164 = sent.destinationE164?.takeIf { it.isNotBlank() } ?: return
        contacts.store(listOf(SignalContactStore.Contact(serviceId = aci, e164 = e164)))
    }

    /**
     * Streams the contacts blob and stores what is in it.
     *
     * Streamed rather than kept: this is a snapshot that is parsed once and superseded by the
     * next sync, so writing it to disk would leave the whole address book sitting in a file
     * for no benefit.
     */
    private fun handleBlockedSync(
        blocked: org.whispersystems.signalservice.internal.push.SyncMessage.Blocked
    ) {
        // Both shapes: the newer typed lists, and the older bare strings that a primary on an
        // older build still sends. Taking only one of them would silently drop half a list.
        val individuals = buildList {
            blocked.blockedAcis.forEach { one ->
                // The binary form, which is the raw sixteen bytes rather than the hyphenated
                // string the rest of this app keys on.
                val aci = one.aciBinary?.toByteArray()
                    ?.let { org.signal.core.models.ServiceId.parseOrNull(it) }
                if (aci != null) add(SignalBlockStore.Blocked(aci.toString(), null, one.timestamp ?: 0L))
            }
            blocked.blockedE164s.forEach { one ->
                add(SignalBlockStore.Blocked(null, one.e164, one.timestamp ?: 0L))
            }
            blocked.acis.filter { aci -> none { it.aci == aci } }
                .forEach { add(SignalBlockStore.Blocked(it, null, 0L)) }
            blocked.numbers.filter { number -> none { it.e164 == number } }
                .forEach { add(SignalBlockStore.Blocked(null, it, 0L)) }
        }
        val groups = blocked.blockedGroups.mapNotNull { it.groupId?.toByteArray() } +
            blocked.groupIds.map { it.toByteArray() }
        blocks.store(individuals, groups.distinctBy { it.toList() })
    }

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
                    serviceId = aci,
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
        // An edit carries its own attachments, and they are the ones to keep -- the row is
        // about to be rewritten with the edited body, so leaving the original's pointers
        // behind would pair new text with an old picture.
        val dataMessage = content.syncMessage?.sent?.message
            ?: content.dataMessage
            ?: content.syncMessage?.sent?.editMessage?.dataMessage
            ?: content.editMessage?.dataMessage
            ?: return message
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

        /** Signal's primary device. A PNI identity is usually only on file for this one. */
        private const val DEFAULT_DEVICE_ID = 1

        /**
         * What kind of ciphertext an envelope carried, in libsignal's numbering.
         *
         * The two vocabularies do not line up by value, and a retry receipt quoting the wrong
         * one names a message the sender cannot find. Taken from Signal Android's own mapping
         * rather than inferred from the enum order.
         */
        internal fun ciphertextTypeOf(type: Envelope.Type?): Int = when (type) {
            Envelope.Type.DOUBLE_RATCHET ->
                org.signal.libsignal.protocol.message.CiphertextMessage.WHISPER_TYPE
            Envelope.Type.PREKEY_MESSAGE ->
                org.signal.libsignal.protocol.message.CiphertextMessage.PREKEY_TYPE
            Envelope.Type.UNIDENTIFIED_SENDER ->
                org.signal.libsignal.protocol.message.CiphertextMessage.SENDERKEY_TYPE
            Envelope.Type.PLAINTEXT_CONTENT ->
                org.signal.libsignal.protocol.message.CiphertextMessage.PLAINTEXT_CONTENT_TYPE
            else ->
                org.signal.libsignal.protocol.message.CiphertextMessage.WHISPER_TYPE
        }

        /**
         * Who an envelope came from, from whichever field the server filled.
         *
         * `sourceServiceId` is the old string and `sourceServiceIdBinary` the raw bytes, and
         * a modern server sends only the second. Read as a string alone this came back blank,
         * and a blank sender meant the delivery receipt above was dropped -- which is the very
         * failure the comment there says this code exists to end. See
         * [[SignalStorageService.aciOf]] for the same trap in the contact list.
         */
        internal fun senderOf(envelope: Envelope): String? =
            ServiceId.parseOrNull(envelope.sourceServiceId, envelope.sourceServiceIdBinary)
                ?.toString()

        /**
         * Whether an envelope was addressed to this account's PNI rather than its ACI.
         *
         * Decided on the parsed id rather than on `startsWith("PNI:")`, because the string it
         * was testing is empty on a modern server -- so every PNI-addressed envelope was
         * decrypted against the ACI store. As the comment at the call site says, that does not
         * fail cleanly: it fails as a decryption error and reads as a corrupt message.
         */
        internal fun addressedToPni(envelope: Envelope): Boolean =
            ServiceId.parseOrNull(
                envelope.destinationServiceId, envelope.destinationServiceIdBinary
            ) is ServiceId.PNI

        /** A profile key is exactly this; anything else is not one. */
        private const val PROFILE_KEY_BYTES = 32

        /** How long to keep an envelope that will not decrypt, in case a fix arrives. */
        private val UNDECRYPTABLE_RETENTION_MS = TimeUnit.DAYS.toMillis(14)

        /** How far down a wrapped exception to look. Deep enough for the wrapping libsignal does. */
        private const val CAUSE_DEPTH = 5

        /** Long, deliberately: every expiry is a wakeup that learned nothing. See [listen]. */
        private val READ_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(1)
    }
}
