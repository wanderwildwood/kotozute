package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import org.whispersystems.signalservice.api.SignalServiceDataStore
import timber.log.Timber
import org.whispersystems.signalservice.api.messages.multidevice.BlockedListMessage

/**
 * The protocol store, assembled.
 *
 * One place that knows how the database is keyed and how the stores are wired, so that
 * everything above holds a [SignalStore] rather than a `SQLiteOpenHelper` and a passphrase.
 *
 * Opened lazily. Constructing this must not touch the keystore or the disk, because it is
 * built during startup on the main thread and the first open creates the database.
 */
class SignalStore(private val context: Context) {

    private val database: ProtocolDatabase by lazy {
        ProtocolDatabase(context, ProtocolStoreKey.require(context))
    }

    /** The account: who this device is, and the counters that hand out key ids. */
    internal val account: SignalAccountStore by lazy { SignalAccountStore(database) }

    /**
     * The ACI/PNI pair the service layer is constructed with.
     *
     * Typed as the library's interface rather than the implementation: everything above this
     * module consumes it through the service layer, and nothing up there should be able to
     * reach a table.
     */
    val protocol: SignalServiceDataStore by lazy { SignalDataStore(database, account) }

    /**
     * Why the kept envelopes would not decrypt, most recent first.
     *
     * Distinct reasons only: ten copies of the same failure is one fact, and a screen that
     * repeats it ten times buries anything else.
     */
    fun undecryptableReasons(limit: Int = 3): List<String> = runCatching {
        withStoreLock(database) {
            database.readableDatabase.rawQuery(
                "SELECT DISTINCT failure FROM envelope WHERE failure IS NOT NULL ORDER BY _id DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
            }
        }
    }.getOrDefault(emptyList())

    /** Envelopes kept because they would not decrypt. See [SignalReceiver]. */
    fun undecryptableCount(): Int = withStoreLock(database) {
        database.readableDatabase.rawQuery("SELECT count(*) FROM envelope", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** This device's own ACI, or null when it is not linked. Cheap enough to ask per thread. */
    fun selfAciOrNull(): String? = runCatching { account.credentials().aci }.getOrNull()

    /** Which device on the account this phone is. */
    fun deviceId(): Int = runCatching { account.credentials().deviceId }.getOrDefault(0)

    /** This account's own number, for the messages an export attributes to it. */
    fun selfNumberOrNull(): String? = runCatching { account.credentials().e164 }.getOrNull()

    /** True once this device has a device id and a password -- that is, once it is linked. */
    fun isLinked(): Boolean = ProtocolStoreKey.exists(context) && account.credentials().complete

    /**
     * Whoever holds this may read the socket. Nobody else may.
     *
     * A flag was not enough. Two readers arose from the ordering between `startStream()` and a
     * `syncNow()` that arrived in the same millisecond, and every attempt to guard it with a
     * boolean lost the race somewhere else -- because a boolean tested and then acted on is
     * two operations. This is one.
     *
     * Two threads calling readMessageBatch on one connection race for each message and the
     * loser's read fails; the library's own source warns about it. The visible symptom was a
     * reconnect every sixty seconds with healthy keepalives either side, which reads as a
     * flaky network and is not one.
     */
    private val socketReader = java.util.concurrent.locks.ReentrantLock()

    /**
     * Where to report the server refusing this device.
     *
     * Settable rather than a constructor parameter because the connection is built lazily and
     * lives for the process, while the thing that wants to know is the repository, which is
     * constructed around this store. Assigning it later is enough: nothing can be refused
     * before something has connected.
     */
    @Volatile
    var onRejected: (String) -> Unit = {}

    /**
     * Told whether the server says the account's primary has gone idle.
     *
     * Set the same way and for the same reason as [onRejected]. Not a refusal: it is the
     * warning that arrives *before* one, because a linked device whose primary stays idle is
     * eventually unlinked and everything on it goes with it.
     */
    @Volatile
    var onPrimaryIdle: (Boolean) -> Unit = {}

    /**
     * The connection to Signal, and the APIs on it. Built from the network configuration
     * passed in rather than reached for, because that configuration still lives a module up;
     * see the note on [linker].
     *
     * **One** for the whole process, shared by everything that needs the socket -- not one
     * per operation, which is what this was. Signal allows a single authenticated websocket
     * per device, so opening a second one does not add a connection: it displaces the first.
     * The symptom was a send knocking the receive loop off the air, the listen loop failing
     * with `Connection closed!` at the exact moment a message was sent and reconnecting on
     * its backoff -- so messages arrived late rather than not at all, and the cause looked
     * like a flaky network.
     */
    internal val connection: SignalConnection by lazy {
        SignalConnection(
            context,
            account,
            SignalNetworkConfig.USER_AGENT,
            onRejected = { onRejected(it) },
            onPrimaryIdle = { onPrimaryIdle(it) }
        )
    }

    /**
     * Publishes a batch of one-time pre keys for both identities.
     *
     * Not part of linking, because the registration request carries only a signed and a
     * last-resort key. Until this has run the device is reachable but on the degraded path,
     * every new session reusing the last-resort key.
     */
    fun uploadPreKeys(): String = runPreKeys(maintenanceOnly = false)

    /**
     * Replaces the repeated-use keys because a message would not open against them -- if they
     * are actually wrong, or it has been long enough. See [PreKeyUploader.rotateIfKeysAreWrong];
     * the gate is what stops anyone who can send traffic from driving rotations at will.
     */
    fun rotatePreKeysIfWrong(lastForcedAt: Long, onRotated: (Long) -> Unit): String {
        connection.connect()
        return try {
            PreKeyUploader(
                account,
                connection,
                { SignalPreKeyStore(database, it) },
                { SignalSignedPreKeyStore(database, it) },
                { SignalKyberPreKeyStore(database, it) }
            ).rotateIfKeysAreWrong(lastForcedAt, onRotated)
        } finally {
            // The socket is shared and long-lived; see runPreKeys.
        }
    }

    /**
     * Tops up and rotates the account's keys if either is owed. See [PreKeyUploader.maintain].
     *
     * Cheap when nothing is needed -- two count requests and no upload -- so it can run on the
     * ordinary sync schedule rather than needing one of its own.
     */
    fun maintainPreKeys(): String = runPreKeys(maintenanceOnly = true)

    /**
     * Tells the server what this device can do, once per run of the app.
     *
     * Capabilities were declared at linking and never again. They are not a fixed fact about
     * the account: they are a claim by *this build*, and the server hands them to everybody
     * who sends to us -- a peer that reads them decides on their strength whether to use a
     * newer message shape at all. So a device that gains an ability in an update goes on being
     * treated as though it had not, indefinitely, and one that had to withdraw a claim goes on
     * being sent things it cannot read.
     *
     * A linked device may not set the account's attributes -- that is the primary's -- so this
     * is the narrow endpoint Signal's own linked devices use, and only capabilities are sent.
     * Phone-number discoverability is deliberately left alone: it is the account owner's
     * privacy setting, this device does not read it, and refreshing it from a default would be
     * changing something nobody asked to change.
     *
     * Once per process, as `RefreshAttributesJob` does, because nothing changes between two
     * calls in the same run.
     */
    fun refreshCapabilities(): String {
        if (capabilitiesRefreshed) return "already done this run"
        connection.connect()
        // Whether this device actually holds the account's storage key, rather than a
        // hardcoded claim. Upstream asks `hasPin()` for the same reason.
        val result = connection.account.setCapabilities(
            SignalCapabilities.forRefresh(storage = storageKeyKnown())
        )
        return when (result) {
            is org.signal.libsignal.net.RequestResult.Success -> {
                capabilitiesRefreshed = true
                "capabilities refreshed"
            }
            else -> throw IllegalStateException("the server would not take our capabilities: $result")
        }
    }

    @Volatile
    private var capabilitiesRefreshed = false

    private fun runPreKeys(maintenanceOnly: Boolean): String {
        connection.connect()
        return try {
            val uploader = PreKeyUploader(
                account,
                connection,
                { SignalPreKeyStore(database, it) },
                { SignalSignedPreKeyStore(database, it) },
                { SignalKyberPreKeyStore(database, it) }
            )
            // ⚠ Only for the deliberate, whole-batch upload. This used to run on every
            // maintenance pass as well -- and `SignalSyncWorker` calls that every fifteen
            // minutes -- so the phone asked the server for its prekey counts, for the ACI and
            // again for the PNI, before and after doing nothing at all. Roughly four hundred
            // requests a day on a device built to stay asleep, none of which changed a
            // decision: maintenance now reads counts only once it has decided to act.
            val before = if (maintenanceOnly) null else uploader.serverCounts()
            val result = if (maintenanceOnly) uploader.maintain() else uploader.uploadAll()
            // ⚠ A refusal is thrown, not returned as prose. Both callers wrapped this in
            // runCatching and logged whatever came back at INFO, so an upload the server
            // refused -- most likely right after linking, on the flaky connection a QR scan
            // tends to happen over -- was recorded as a success and never tried again. The
            // device then lived its whole life advertising one signed prekey and one
            // last-resort key, which is the degraded path this class exists to avoid.
            val outcome = when (result) {
                is PreKeyUploader.Result.Uploaded -> "uploaded"
                is PreKeyUploader.Result.NotNeeded -> "nothing owed"
                is PreKeyUploader.Result.Failed -> throw IllegalStateException(result.reason)
            }
            // Asked of the server after anything was actually sent. The upload's own 200 says
            // the request was accepted; this says the keys are there to be handed out. Nothing
            // is asked when nothing was owed, which is the overwhelmingly common case.
            when {
                before != null ->
                    "$outcome | server before: $before | after: ${uploader.serverCounts()}"
                result is PreKeyUploader.Result.Uploaded ->
                    "$outcome | server after: ${uploader.serverCounts()}"
                else -> outcome
            }
        } finally {
            // The socket is shared and long-lived, so it is not disconnected here; closing is
            // [disconnect]. Nor is the reader lock touched: this sends and requests, it never
            // calls readMessageBatch, so it was never a reader.
            //
            // It used to unlock anyway, which throws IllegalMonitorStateException from a
            // finally -- after the network work had already succeeded. The message went out
            // and then the exception ate the code that files it, so a sent message reached
            // everybody else and never appeared in the sender's own thread.
        }
    }

    /**
     * Connects, drains whatever the server is holding, and decrypts it.
     *
     * The receive half of what the bridge used to do, end to end.
     */
    fun receive(
        events: SignalEvents,
        onNamesLearned: () -> Unit = {}
    ): String {
        // If a listen loop already has the socket there is nothing to catch up on -- it is
        // reading continuously -- and joining in would only take messages away from it.
        if (!socketReader.tryLock()) return "already listening"
        connection.connect()
        return try {
            val result = SignalReceiver(
                database, account, SignalDataStore(database, account), connection,
                SignalNetworkConfig.certificateValidator(), attachmentsFor(connection),
                contacts, blocks, keys, StoreEvents(events, onNamesLearned)
            ).drain()
            "envelopes=${result.envelopes} decrypted=${result.decrypted} failed=${result.failed} " +
                "stored=${result.stored} queue-emptied=${result.queueEmptied} senders=${result.senders.size}"
        } finally {
            // Paired with the tryLock above. This one IS a reader -- it calls drain(), which
            // calls readMessageBatch -- so it holds the lock for the duration and must give
            // it back. The socket itself stays open: it is shared, and closing is
            // [disconnect].
            socketReader.unlock()
        }
    }

    /**
     * A new group's revision. `GroupsV2Operations.createNewGroup` builds one at zero, and the
     * update that announces it carries that number -- so a recipient's client knows it is
     * looking at the group's first state rather than something it has missed changes to.
     */
    private val NEW_GROUP_REVISION = 0

    /**
     * Makes a group and returns the thread it will appear under.
     *
     * See [SignalGroups.create]. Nothing is filed here: a conversation in this app exists
     * because a message is in it, so the group becomes visible when the first one is sent --
     * which is what the caller does next, and is also what tells the members it exists at all.
     */
    fun createGroup(title: String, memberAcis: List<String>): CreatedGroup {
        connection.connect()
        val masterKey = SignalGroups(connection, account, contacts).create(title, memberAcis)
        val bytes = masterKey.serialize()
        val groupId = ContentNormalizer.groupIdForCheck(bytes)
        if (groupId.isBlank()) {
            Timber.w("signal groups: made a group whose id would not derive")
            throw IllegalStateException("The group was made but this phone cannot address it.")
        }
        // Told to its members, which is upstream's last step in `GroupManagerV2.createGroup`
        // and not an optional one: a group nobody has been told about is a group only this
        // phone and the server know exists.
        //
        // Best effort, and deliberately so. The group is already made -- refusing to hand it
        // back because the notice did not go out would leave a real group on the account with
        // no way to reach it from here. A member who missed this learns of the group from the
        // first message sent in it, which carries the same key and revision.
        val told = runCatching {
            // The group's members as the server accepted them, not the list that was asked
            // for. A member whose profile credential could not be fetched is turned into an
            // *invitation* rather than a member, and upstream does not send them this: its
            // destinations are the group's members, and an invited person learns of the group
            // from the invitation in its state. Sending to them anyway is a message their
            // client discards for coming from outside a group they are not yet in.
            val members = SignalGroups(connection, account, contacts).fetch(bytes)
                ?.members
                .orEmpty()
                .mapNotNull { org.signal.core.models.ServiceId.parseOrNull(it) }
                .filter { it.toString() != account.credentials().aci }
            SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).sendGroupUpdate(bytes, members, NEW_GROUP_REVISION)
        }.onFailure { Timber.w(it, "signal groups: could not tell the members") }.getOrNull()
        if (told !is SignalSender.Result.Sent) {
            Timber.w("signal groups: the group was made but its members were not told")
        }

        return CreatedGroup(
            masterKey = bytes,
            threadKey = "group:$groupId",
            told = told is SignalSender.Result.Sent
        )
    }

    /**
     * A group that now exists, and where it will show up.
     *
     * [told] is whether its members have been told. False means the group is real and nobody
     * else knows yet; the first message sent in it carries the same group context and puts
     * that right.
     */
    data class CreatedGroup(
        val masterKey: ByteArray,
        val threadKey: String,
        val told: Boolean = false
    )

    /**
     * Sends to a group, fetching its membership first.
     *
     * @throws IllegalStateException naming the reason if it could not be sent.
     */
    fun sendToGroup(
        masterKey: ByteArray,
        body: String,
        expiresInSeconds: Int = 0,
        expireTimerVersion: Int = 0
    ): Long {
        connection.connect()
        // ⚠ Which kind of "no" matters. Being removed from a group is permanent and there is
        // nothing to try again; a server that would not answer is a minute's wait. Both used
        // to arrive as "could not read the group's members", which tells somebody to keep
        // retrying something that will never work.
        val group = when (val outcome = SignalGroups(connection, account, contacts).fetchOutcome(masterKey)) {
            is SignalGroups.Outcome.Got -> outcome.group
            SignalGroups.Outcome.NotAMember -> throw IllegalStateException(
                "You are not in this group any more, so nothing was sent."
            )
            SignalGroups.Outcome.Gone -> throw IllegalStateException(
                "This group has ended, so nothing was sent."
            )
            is SignalGroups.Outcome.Unknown -> throw IllegalStateException(
                "This phone could not reach the group just now, so nothing was sent. " +
                    "It will work when the connection is back."
            )
        }
        // An announcement group takes messages from its administrators only. Sending anyway
        // succeeds locally and is discarded by every recipient -- the message is lost behind a
        // tick, with nothing to tell the sender it did not arrive. Refused here instead, where
        // the refusal can be shown.
        if (group.announcementOnly && account.credentials().aci !in group.admins) {
            throw IllegalStateException(
                "Only this group's admins can post in it. Your message was not sent."
            )
        }
        val members = group.members
            .mapNotNull { org.signal.core.models.ServiceId.parseOrNull(it) }
            // Not ourselves: our own devices get the message as a sync, and encrypting to
            // our own address as though we were a peer is not the same thing.
            .filter { it.toString() != account.credentials().aci }
        return when (
            val r = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).sendToGroup(masterKey, members, body, expiresInSeconds, expireTimerVersion, group.revision)
        ) {
            is SignalSender.Result.Sent -> r.timestamp
            is SignalSender.Result.Failed -> throw IllegalStateException(r.reason)
        }
    }

    /**
     * Hangs an emoji on a message, or takes one back.
     *
     * [targetAuthor] is whoever wrote the message being reacted to -- this account when it is
     * one of ours, which is why it is asked for rather than assumed to be the other party.
     */
    fun sendReaction(
        recipient: String,
        emoji: String,
        remove: Boolean,
        targetAuthor: String,
        targetSentTimestamp: Long
    ): Long {
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient)
            ?: throw IllegalStateException("not a service id: $recipient")
        val author = org.signal.core.models.ServiceId.parseOrNull(targetAuthor)
            ?: throw IllegalStateException("not a service id: $targetAuthor")
        connection.connect()
        return when (
            val result = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).sendReaction(serviceId, emoji, remove, author, targetSentTimestamp)
        ) {
            is SignalSender.Result.Sent -> result.timestamp
            is SignalSender.Result.Failed -> throw IllegalStateException(result.reason)
        }
    }

    /**
     * Takes one of this account's own messages back, for everyone it was sent to.
     *
     * [targetSentTimestamp] is the timestamp the message went out with, which is how Signal
     * names a message everywhere. The author is always this account -- a withdrawal of
     * somebody else's message is not a thing anyone but a group admin can do, and this app
     * has no admin path.
     */
    fun sendRemoteDelete(recipient: String, targetSentTimestamp: Long): Long {
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient)
            ?: throw IllegalStateException("not a service id: $recipient")
        connection.connect()
        return when (
            val result = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).sendRemoteDelete(serviceId, targetSentTimestamp)
        ) {
            is SignalSender.Result.Sent -> result.timestamp
            is SignalSender.Result.Failed -> throw IllegalStateException(result.reason)
        }
    }

    /** The same, into a group, which means every member it can reach. */
    fun sendRemoteDeleteToGroup(masterKey: ByteArray, targetSentTimestamp: Long): Long {
        connection.connect()
        // ⚠ Which kind of "no" matters. Being removed from a group is permanent and there is
        // nothing to try again; a server that would not answer is a minute's wait. Both used
        // to arrive as "could not read the group's members", which tells somebody to keep
        // retrying something that will never work.
        val group = when (val outcome = SignalGroups(connection, account, contacts).fetchOutcome(masterKey)) {
            is SignalGroups.Outcome.Got -> outcome.group
            SignalGroups.Outcome.NotAMember -> throw IllegalStateException(
                "You are not in this group any more, so nothing was sent."
            )
            SignalGroups.Outcome.Gone -> throw IllegalStateException(
                "This group has ended, so nothing was sent."
            )
            is SignalGroups.Outcome.Unknown -> throw IllegalStateException(
                "This phone could not reach the group just now, so nothing was sent. " +
                    "It will work when the connection is back."
            )
        }
        val members = group.members
            .mapNotNull { org.signal.core.models.ServiceId.parseOrNull(it) }
            .filter { it.toString() != account.credentials().aci }
        return when (
            val result = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).sendRemoteDeleteToGroup(masterKey, members, targetSentTimestamp, group.revision)
        ) {
            is SignalSender.Result.Sent -> result.timestamp
            is SignalSender.Result.Failed -> throw IllegalStateException(result.reason)
        }
    }

    /** The same, into a group, which means every member it can reach. */
    fun sendReactionToGroup(
        masterKey: ByteArray,
        emoji: String,
        remove: Boolean,
        targetAuthor: String,
        targetSentTimestamp: Long
    ): Long {
        val author = org.signal.core.models.ServiceId.parseOrNull(targetAuthor)
            ?: throw IllegalStateException("not a service id: $targetAuthor")
        connection.connect()
        // ⚠ Which kind of "no" matters. Being removed from a group is permanent and there is
        // nothing to try again; a server that would not answer is a minute's wait. Both used
        // to arrive as "could not read the group's members", which tells somebody to keep
        // retrying something that will never work.
        val group = when (val outcome = SignalGroups(connection, account, contacts).fetchOutcome(masterKey)) {
            is SignalGroups.Outcome.Got -> outcome.group
            SignalGroups.Outcome.NotAMember -> throw IllegalStateException(
                "You are not in this group any more, so nothing was sent."
            )
            SignalGroups.Outcome.Gone -> throw IllegalStateException(
                "This group has ended, so nothing was sent."
            )
            is SignalGroups.Outcome.Unknown -> throw IllegalStateException(
                "This phone could not reach the group just now, so nothing was sent. " +
                    "It will work when the connection is back."
            )
        }
        val members = group.members
            .mapNotNull { org.signal.core.models.ServiceId.parseOrNull(it) }
            .filter { it.toString() != account.credentials().aci }
        return when (
            val result = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).sendReactionToGroup(masterKey, members, emoji, remove, author, targetSentTimestamp, group.revision)
        ) {
            is SignalSender.Result.Sent -> result.timestamp
            is SignalSender.Result.Failed -> throw IllegalStateException(result.reason)
        }
    }

    /**
     * Blocks or unblocks one person, by sending the account's list back with them added or
     * taken out.
     *
     * ⚠ Refuses unless the primary has already sent this device the list. Signal's blocked
     * sync replaces rather than adds, so blocking somebody while holding no list would
     * silently unblock everyone else on it -- a failure whose only symptom is a person
     * getting through months later. [requestBlockedList] asks; this waits to be told.
     */
    fun setBlocked(aci: String, blocked: Boolean): Boolean {
        if (!blocks.known()) return false
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(aci) ?: return false

        val updated = SignalBlockList.edit(
            blocks.individuals(), serviceId.toString(), blocked, System.currentTimeMillis()
        )

        connection.connect()
        val individuals = updated.mapNotNull { one ->
            val id = one.aci?.let { org.signal.core.models.ServiceId.parseOrNull(it) }
                as? org.signal.core.models.ServiceId.ACI
            if (id == null && one.e164 == null) null
            else BlockedListMessage.Individual(id, one.e164.orEmpty(), one.blockedAt)
        }
        val groupIds = blocks.groups().map { BlockedListMessage.Group(it, 0L) }

        return when (
            SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).sendBlockedList(individuals, groupIds)
        ) {
            is SignalSender.Result.Sent -> {
                // Only after the account has taken it. The list held here is a copy of the
                // account's, and a copy that ran ahead of it would be a lie the next sync
                // would silently correct.
                blocks.store(updated, blocks.groups())
                // A genuinely local decision, and the only one this app makes that a storage
                // record carries. Marked so a future sync knows this row differs from the
                // account's copy -- nothing pushes it yet, and the legacy blocked-list sync
                // above is what actually tells the account today.
                runCatching { contacts.rotateStorageId(serviceId.toString()) }
                    .onFailure { Timber.w(it, "signal blocked: could not mark the row for a push") }
                true
            }
            is SignalSender.Result.Failed -> false
        }
    }

    /**
     * Tells one person that messages up to [upToTs] have been read.
     *
     * The timestamps Signal names a message by are the ones it was sent with, so the rows
     * being marked supply them; a receipt for a timestamp nobody sent means nothing.
     */
    fun sendReadReceipt(recipient: String, timestamps: List<Long>): Boolean {
        if (timestamps.isEmpty()) return true
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient) ?: return false
        connection.connect()
        return SignalSender(
            SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
            SignalDataStore(database, account), connection, contacts
        ).sendReadReceipt(serviceId, timestamps) is SignalSender.Result.Sent
    }

    /**
     * Forgets a message this device sent, so a retry receipt can never resend it.
     *
     * For a withdrawal: the whole promise of taking a message back is that it does not come
     * back. See [SignalMessageLog.forgetSent].
     */
    fun forgetSentMessage(sentTimestamp: Long): Int = SignalMessageLog(database).forgetSent(sentTimestamp)

    /**
     * Empties the resend log. See [SignalMessageLog.forgetEverything] -- for the case where
     * every message goes at once and there are no timestamps left to name.
     */
    fun forgetEverySentMessage(): Int = SignalMessageLog(database).forgetEverything()

    /**
     * Tells this account's own devices what was read here. Not a receipt; see the sender.
     *
     * @param read whoever wrote each message, and the timestamp they sent it with.
     */
    fun sendReadSync(read: List<Pair<String, Long>>): Boolean {
        val named = read.mapNotNull { (author, at) ->
            org.signal.core.models.ServiceId.ACI.parseOrNull(author)?.let { it to at }
        }
        if (named.isEmpty()) return true
        connection.connect()
        return SignalSender(
            SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
            SignalDataStore(database, account), connection, contacts
        ).sendReadSync(named) is SignalSender.Result.Sent
    }

    /**
     * Tells one person that what they sent arrived on this phone.
     *
     * Called from the receive path for every message filed, not from the UI: nothing about it
     * is the reader's decision. See [SignalSender.sendDeliveryReceipt].
     */
    fun sendDeliveryReceipt(recipient: String, timestamps: List<Long>): Boolean {
        if (timestamps.isEmpty()) return true
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient) ?: return false
        return SignalSender(
            SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
            SignalDataStore(database, account), connection, contacts
        ).sendDeliveryReceipt(serviceId, timestamps) is SignalSender.Result.Sent
    }

    /**
     * Asks a sender to send a message again, after this phone could not read it.
     *
     * See [SignalSender.sendRetryReceipt]. Best effort: the message is already unread, and
     * failing to ask leaves it exactly as it was.
     */
    fun sendRetryReceipt(
        recipient: String,
        error: org.signal.libsignal.protocol.message.DecryptionErrorMessage,
        groupId: ByteArray?
    ): Boolean {
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient) ?: return false
        return SignalSender(
            SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
            SignalDataStore(database, account), connection, contacts
        ).sendRetryReceipt(serviceId, error, groupId) is SignalSender.Result.Sent
    }

    /**
     * Sends something again for a recipient who could not read it. See [SignalSender.resend].
     */
    fun resend(recipient: String, sentTimestamp: Long): Boolean {
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient) ?: return false
        return SignalSender(
            SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
            SignalDataStore(database, account), connection, contacts
        ).resend(serviceId, sentTimestamp) is SignalSender.Result.Sent
    }

    /**
     * Sends again to anybody still owed a message they asked for and did not get.
     *
     * The standing half of a retry receipt. Upstream needs nothing like this because every
     * send is a job and a failed job is retried for a day (`ResendMessageJob`); there is no
     * job queue here, so the attempt that failed has to be picked up by something that runs
     * again on its own.
     *
     * ⚠ **Not hung off the receive batch alone.** The obvious place was the end of a drain,
     * and the obvious place is wrong on the account this is for: the listen loop's read times
     * out after a minute and unwinds before the end-of-batch work, so on a phone nobody is
     * messaging the retry would wait for traffic that is not coming. It is called from key
     * maintenance instead, which the periodic worker runs every fifteen minutes whether or not
     * anything has arrived -- and from the end of a batch as well, because a batch arriving is
     * proof the socket is back.
     *
     * Giving up is its own step and says so: a request nobody could be reached about in a day
     * is one to stop waking the radio for, which is that job's own lifespan.
     *
     * @return how many went.
     */
    fun retryOwedResends(): Int {
        val log = SignalMessageLog(database)
        val owed = runCatching { log.owedResends() }
            .onFailure { Timber.w(it, "signal retry: could not read who is owed a resend") }
            .getOrDefault(emptyList())
        var sent = 0
        if (owed.isNotEmpty()) {
            connection.connect()
            owed.forEach { entry ->
                val went = runCatching { resend(entry.recipient, entry.sentTimestamp) }
                    .onFailure { Timber.w(it, "signal retry: still could not send it again") }
                    .getOrDefault(false)
                if (went) {
                    sent++
                    runCatching { log.clearResendOwed(entry.recipient, entry.sentTimestamp) }
                        .onFailure { Timber.w(it, "signal retry: could not clear a resend that went") }
                }
            }
            Timber.i("signal retry: %d of %d owed resend(s) went this time", sent, owed.size)
        }
        runCatching { log.abandonExpiredResends() }
            .onFailure { Timber.w(it, "signal retry: could not give up on the old ones") }
        return sent
    }

    /**
     * Tells every sender still owed a receipt that their message arrived.
     *
     * The mirror of [retryOwedResends] on the receiving side, and hung off the same two
     * moments for the same reasons: the end of a batch, because a batch arriving is proof the
     * socket is back, and the periodic round, because a phone nobody is messaging never has a
     * batch.
     *
     * @return how many people were told.
     */
    fun retryOwedReceipts(): Int {
        val store = SignalReceiptStore(database)
        var told = 0

        // ⚠ Nobody the service has already said is gone. Upstream draws this line inside each
        // job -- `onShouldRetry` is true for a `PushNetworkException` and **false** for a
        // `ServerRejectedException` -- so a refusal ends the work and a dropped connection does
        // not. Somebody who has left Signal is a refusal that will be repeated every time, and
        // a day of unlimited attempts at them is ninety-six wakeups for an answer already
        // known. Dropped rather than left to expire, so it stops now.
        val gone = runCatching { store.forget { contacts.isUnregistered(it) } }
            .onFailure { Timber.w(it, "signal receipt: could not drop what is owed to people who have left") }
            .getOrDefault(0)
        if (gone > 0) {
            Timber.i("signal receipt: dropped %d owed to somebody no longer on Signal", gone)
        }

        // Delivery: one message per person, carrying their own timestamps.
        val deliveries = runCatching { store.owed(SignalReceiptStore.Kind.DELIVERY) }
            .onFailure { Timber.w(it, "signal receipt: could not read who is owed one") }
            .getOrDefault(emptyMap())
        if (deliveries.isNotEmpty()) {
            connection.connect()
            deliveries.forEach { (recipient, timestamps) ->
                val went = runCatching { sendDeliveryReceipt(recipient, timestamps) }
                    .onFailure { Timber.w(it, "signal receipt: still could not tell them") }
                    .getOrDefault(false)
                if (went) {
                    told++
                    runCatching {
                        store.clear(recipient, timestamps, SignalReceiptStore.Kind.DELIVERY)
                    }.onFailure { Timber.w(it, "signal receipt: could not clear one that went") }
                }
            }
            Timber.i("signal receipt: told %d of %d sender(s) this time", told, deliveries.size)
        }

        // Read sync: **one** message to this account's own devices, carrying every pair at
        // once -- it is not addressed to the people the pairs name. So it goes or it does not,
        // and the whole lot is cleared together.
        val reads = runCatching { store.owed(SignalReceiptStore.Kind.READ_SYNC) }
            .onFailure { Timber.w(it, "signal read sync: could not read what is owed") }
            .getOrDefault(emptyMap())
        if (reads.isNotEmpty()) {
            val pairs = reads.flatMap { (author, timestamps) -> timestamps.map { author to it } }
            val went = runCatching { sendReadSync(pairs) }
                .onFailure { Timber.w(it, "signal read sync: still could not tell our own devices") }
                .getOrDefault(false)
            if (went) {
                told++
                reads.forEach { (author, timestamps) ->
                    runCatching {
                        store.clear(author, timestamps, SignalReceiptStore.Kind.READ_SYNC)
                    }.onFailure { Timber.w(it, "signal read sync: could not clear one that went") }
                }
                Timber.i("signal read sync: told our own devices about %d message(s)", pairs.size)
            }
        }

        // Read receipts: per person, like delivery, and the only one behind a setting. The
        // setting is read here rather than where the receipt was owed, so a backlog cannot go
        // out behind somebody who has turned receipts off since.
        if (readReceiptsEnabled()) {
            val reads = runCatching { store.owed(SignalReceiptStore.Kind.READ_RECEIPT) }
                .onFailure { Timber.w(it, "signal receipt: could not read which read receipts are owed") }
                .getOrDefault(emptyMap())
            if (reads.isNotEmpty()) {
                connection.connect()
                reads.forEach { (recipient, timestamps) ->
                    val went = runCatching { sendReadReceipt(recipient, timestamps) }
                        .onFailure { Timber.w(it, "signal receipt: still could not send a read receipt") }
                        .getOrDefault(false)
                    if (went) {
                        told++
                        runCatching {
                            store.clear(recipient, timestamps, SignalReceiptStore.Kind.READ_RECEIPT)
                        }.onFailure { Timber.w(it, "signal receipt: could not clear a read receipt that went") }
                    }
                }
            }
        } else {
            // Not held for later. Upstream's job returns without sending and is then finished
            // and gone; keeping them would be keeping a promise the account has withdrawn.
            runCatching { store.drop(SignalReceiptStore.Kind.READ_RECEIPT) }
                .onSuccess { gone ->
                    if (gone > 0) {
                        Timber.i("signal receipt: dropped %d read receipt(s); they are switched off", gone)
                    }
                }
                .onFailure { Timber.w(it, "signal receipt: could not drop the read receipts owed") }
        }

        runCatching { store.abandonExpired() }
            .onFailure { Timber.w(it, "signal receipt: could not give up on the old ones") }
        return told
    }

    /** Notes that the person who wrote a message has not been told it was read here. */
    internal fun oweReadReceipt(recipient: String, timestamps: List<Long>) {
        runCatching {
            SignalReceiptStore(database)
                .owe(recipient, timestamps, SignalReceiptStore.Kind.READ_RECEIPT)
        }.onFailure { Timber.w(it, "signal receipt: could not note that a read receipt is owed") }
    }

    /** Notes that our own devices have not been told a conversation was read here. */
    internal fun oweReadSync(read: List<Pair<String, Long>>) {
        read.filterNot { it.first.isBlank() }
            .groupBy({ it.first }, { it.second })
            .forEach { (author, timestamps) ->
                runCatching {
                    SignalReceiptStore(database)
                        .owe(author, timestamps, SignalReceiptStore.Kind.READ_SYNC)
                }.onFailure { Timber.w(it, "signal read sync: could not note that one is owed") }
            }
    }

    /**
     * Says so if any number is held by more than one recipient row.
     *
     * The equivalent of upstream's `DuplicateE164MigrationJob`, minus the repair: see
     * [SignalContactStore.duplicateNumbers] for why looking is the whole of it here.
     *
     * @return how many numbers are duplicated; zero on a healthy store.
     */
    fun reportDuplicateNumbers(): Int {
        val dupes = runCatching { contacts.duplicateNumbers() }
            .onFailure { Timber.w(it, "signal contacts: could not check for duplicate numbers") }
            .getOrDefault(emptyList())
        if (dupes.isNotEmpty()) {
            // Counts only. The numbers themselves are the thing this app exists to keep, and a
            // log is the one place they would leak out of it.
            Timber.w(
                "signal contacts: %d number(s) are held by more than one row (%s rows in total)",
                dupes.size,
                dupes.sumOf { it.second }
            )
        }
        return dupes.size
    }

    /**
     * Says so if any stored number is not shaped like a number.
     *
     * `BadE164MigrationJob`'s rule, without its repair. See
     * [SignalContactStore.malformedNumbers].
     */
    fun reportMalformedNumbers(): Int {
        val bad = runCatching { contacts.malformedNumbers() }
            .onFailure { Timber.w(it, "signal contacts: could not check the numbers' shape") }
            .getOrDefault(0)
        if (bad > 0) {
            Timber.w("signal contacts: %d row(s) hold a number that is not shaped like one", bad)
        }
        return bad
    }

    /**
     * Runs a key transparency check if one is due. See [SignalKeyTransparency].
     *
     * Here rather than in the repository because this is where the account, the contacts and the
     * connection already are; the repository owns the two flags that outlive the process.
     */
    internal suspend fun checkKeyTransparency(
        now: Long,
        nextDueAt: Long,
        alreadyFailing: Boolean,
        setNextDueAt: (Long) -> Unit
    ): SignalKeyTransparency.Outcome =
        SignalKeyTransparency(account, contacts, connection)
            .checkIfDue(now, nextDueAt, alreadyFailing, setNextDueAt)

    /** Whether this device has been told the blocked list yet. */
    fun blockedListKnown(): Boolean = runCatching { blocks.known() }.getOrDefault(false)

    /**
     * Whether this person is blocked, by any of the names the account might have used.
     *
     * The number is looked up rather than required from the caller: a block can be held
     * against a phone number alone, and passing null here asked only half the question. Every
     * screen that greys out a blocked conversation went through this.
     *
     * ⚠ **Fails open, deliberately, and says so.** A read this cannot answer reports "not
     * blocked", so a message from somebody blocked would be filed and announced. Failing the
     * other way is worse rather than safer: every conversation would grey out and refuse to
     * send on one bad read, which is the app breaking rather than the app being careful. What
     * is not acceptable is doing it quietly.
     */
    fun isBlocked(aci: String): Boolean = runCatching {
        blocks.isBlocked(aci, contacts.numberFor(aci))
    }.onFailure {
        Timber.w(it, "signal blocked: could not read the list; treating this person as not blocked")
    }.getOrDefault(false)

    /**
     * Where muted and archived go once the account's records have been read.
     *
     * Settable rather than a constructor parameter for the same reason [onRejected] is: the
     * conversations live in Realm, a layer above this one, and this class is built first.
     */
    @Volatile
    internal var onConversationState: (List<SignalStorageService.ConversationState>) -> Unit = {}

    /**
     * Whether this account sends read receipts, asked fresh each time.
     *
     * A lambda rather than a value, and settable for the same reason [onRejected] is: the
     * preference lives a layer up and this class is built first. Asked *at the moment of
     * sending*, because a read receipt owed from yesterday must not go out behind somebody who
     * has turned receipts off since -- `SendReadReceiptJob.onRun` re-reads the setting and
     * returns without sending, rather than trusting the check made when the job was queued.
     *
     * Defaults to off. A receipt not sent is a smaller mistake than one sent against the
     * account's wishes, and this is only ever asked about work that is already owed.
     */
    @Volatile
    internal var readReceiptsEnabled: () -> Boolean = { false }

    /**
     * Where "the phone-number identity still has the primary's keys" is written down.
     *
     * Settable for the reason [onRejected] is: the preference lives a layer up. Upstream keeps
     * the same fact as `forcePniSignedPreKeyRotation`.
     */
    @Volatile
    internal var onPniRotationOwed: (Boolean) -> Unit = {}

    /**
     * Rotates the phone-number identity's keys when they are still the ones the primary made.
     *
     * The other half of [onPniRotationOwed], and the part upstream gives to `PreKeysSyncJob`:
     * it reads the flag as `pniRotationOverride` and clears it once it has rotated. Called from
     * the periodic round, which is the only thing here that comes back on its own.
     *
     * @return true if the rotation happened.
     */
    fun rotatePniIfOwed(): Boolean {
        connection.connect()
        val result = runCatching {
            PreKeyUploader(
                account,
                connection,
                { SignalPreKeyStore(database, it) },
                { SignalSignedPreKeyStore(database, it) },
                { SignalKyberPreKeyStore(database, it) }
            ).rotateNow(org.whispersystems.signalservice.api.push.ServiceIdType.PNI)
        }.onFailure { Timber.w(it, "signal keys: rotating the phone-number identity threw") }
            .getOrNull()
        return if (result is PreKeyUploader.Result.Uploaded) {
            Timber.i("signal keys: the phone-number identity is on its own keys again")
            true
        } else {
            Timber.w("signal keys: still on the primary's phone-number keys (%s)", result ?: "threw")
            false
        }
    }

    /**
     * Reads the account's contact list out of the storage service, where modern Signal keeps
     * it. Returns what it did, in a sentence, for a status line and a log.
     */
    fun readStorage(): String {
        // Who this account is, so a record describing it can be refused rather than filed as
        // one of its own contacts. Read once per storage read, not per record.
        val credentials = runCatching { account.credentials() }.getOrNull()
        val result = SignalStorageService(
            connection, keys, contacts,
            self = SignalStorageService.Self(
                aci = credentials?.aci,
                pni = credentials?.pni,
                e164 = credentials?.e164
            ),
            identities = { who, key, state ->
                // The account's own record of somebody's key, taken as this device's starting
                // point rather than trusting whatever the server offers first.
                runCatching {
                    SignalDataStore(database, account).aciStore().adoptIdentity(
                        who,
                        key,
                        when (state) {
                            SignalStorageService.IdentityState.Verified ->
                                SignalIdentityKeyStore.AdoptedState.Verified
                            SignalStorageService.IdentityState.Unverified ->
                                SignalIdentityKeyStore.AdoptedState.Unverified
                            SignalStorageService.IdentityState.Default ->
                                SignalIdentityKeyStore.AdoptedState.Default
                        }
                    )
                }.onFailure { Timber.w(it, "signal storage: could not adopt an identity") }
            },
            onProfileKey = { key ->
                // Only when it has actually changed, so an unchanged account does not rewrite
                // the row on every read.
                val held = runCatching { account.profileKey() }.getOrNull()
                if (held != null && held.contentEquals(key)) {
                    // Said, quietly, so that "we compared them and they agree" can be told
                    // apart from "the record carried no key and nothing was compared". A
                    // check whose silence means two different things is not a check.
                    Timber.i("signal storage: the account's profile key is the one this device holds")
                } else {
                    runCatching { account.saveProfileKey(key) }
                        .onSuccess {
                            Timber.w(
                                "signal storage: the account's profile key has changed; " +
                                    "this device was sending the old one"
                            )
                        }
                        .onFailure { Timber.w(it, "signal storage: could not keep the new profile key") }
                }
            },
            conversationState = { states -> onConversationState(states) },
            blocked = { people, groups ->
                // Replaces the held list rather than adding to it: a storage read is the
                // account's current answer, and somebody unblocked upstream has to become
                // unblocked here too.
                //
                // ⚠ Which means a read can undo a block made here, and that is said out loud
                // rather than left to be noticed. Signal never faces this: its sync reads and
                // writes in one pass, so a local block goes up as a remote insert while the
                // record it replaced is deleted from the manifest, and the stale record never
                // gets a second chance to be applied. A client that reads and never writes
                // **must** let the account win -- anything else is inventing a resolution the
                // protocol does not have -- so this reports the disagreement instead of
                // resolving it.
                //
                // Not symmetrical, and that is why it is worth a warning rather than a debug
                // line: a block kept too long is an inconvenience somebody can undo, while a
                // block dropped too early delivers messages from somebody they blocked *and*
                // answers with a delivery receipt, which tells that person the phone is on and
                // reading them.
                //
                // The window is narrow by construction: [setBlocked] only writes locally once
                // the account has accepted the legacy blocked-list sync, so this device is
                // never ahead of the primary's *knowledge* -- only, possibly, of the primary's
                // storage record. Closing it properly needs the write path; see
                // docs/DECISION-storage-write.md.
                val blockStore = SignalBlockStore(database)
                val heldBefore = runCatching { blockStore.individuals().mapNotNull { it.aci }.toSet() }
                    .getOrDefault(emptySet())
                val arriving = people.mapNotNull { it.aci }.toSet()
                val dropped = heldBefore - arriving
                if (dropped.isNotEmpty()) {
                    Timber.w(
                        "signal blocked: the account's records do not list %d person(s) this " +
                            "phone had blocked; following the account and unblocking them",
                        dropped.size
                    )
                }

                blockStore.store(people, groups)
                Timber.i("signal blocked: the account's records name %d blocked", people.size)
            }
        ).read()
        if (result.reason != null) return result.reason
        val line = "${result.contacts} contact(s) from ${result.records} record(s)"
        // A record this could not use is said out loud. The whole of this bug was a fetch
        // that dropped two records in three and reported only the one it kept, so "71 from
        // 201" read as a complete answer rather than as the alarm it was.
        // Kept, but worth saying: a person known only by their phone-number identity can be
        // written to, and their reply still arrives under their account id, so the two halves
        // only become one conversation once the account tells this phone they are the same.
        val byPni = result.pniOnly.takeIf { it > 0 }?.let { " · $it by phone number" }.orEmpty()
        val dropped = listOfNotNull(
            // Said first, because it means the rest of the numbers are not the whole story.
            result.unreadable.takeIf { it > 0 }?.let { "$it the service would not hand over" },
            result.unopened.takeIf { it > 0 }?.let { "$it would not open" },
            result.notContacts.takeIf { it > 0 }?.let { "$it not a contact" },
            result.anonymous.takeIf { it > 0 }?.let { anon ->
                val num = result.anonymousWithNumber.takeIf { it > 0 }?.let { ", $it with a number" }
                "$anon with no address at all${num.orEmpty()}"
            }
        )
        return if (dropped.isEmpty()) "$line$byPni"
        else "$line$byPni · skipped ${dropped.joinToString(", ")}"
    }

    /**
     * Asks Signal which of [numbers] are on it, and keeps the ones that are.
     *
     * Separate from [readStorage], which reads people the account already has a record for.
     * This is the only way somebody in the phone's own address book who has never written
     * first can become writable-to.
     *
     * Says what it did in a sentence, the same as the rest of this class.
     */
    fun discover(numbers: Set<String>): String {
        val result = SignalDiscovery(connection, contacts, discovery).read(numbers)
        if (result.reason != null) return result.reason
        if (result.asked == 0) return "Every number has been looked up already"
        val parts = listOfNotNull(
            "${result.found} on Signal, from ${result.asked} number(s)",
            // Nearly all of them, for a linked device: CDSI hands back an account id only
            // where the asker already holds a matching ACI/UAK pair. Said out loud because
            // "found by phone number" is a different, weaker thing to know about somebody.
            result.withoutAci.takeIf { it > 0 }?.let { "$it known by phone number only" }
        )
        return parts.joinToString(" · ")
    }

    /** Whether the storage service key is here yet. */
    fun storageKeyKnown(): Boolean = runCatching { keys.known() }.getOrDefault(false)

    /**
     * The key a written-out copy is locked with, or null before the primary has sent its keys.
     *
     * Derived from the account entropy pool, as Signal derives one -- so it is the same on
     * every device on the account, and a copy written here opens anywhere that can reach it.
     */
    fun messageBackupKey(): ByteArray? =
        runCatching { keys.messageBackupKey(selfAciOrNull().orEmpty()) }.getOrNull()

    /**
     * Asks the primary for the account's keys. Asked once per connection and only while the
     * answer is still missing: it is the account's own key material, and there is no reason
     * to have it sent again once it is here.
     */
    fun requestKeys(): String {
        connection.connect()
        return when (
            val r = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).requestKeys()
        ) {
            is SignalSender.Result.Sent -> "requested"
            is SignalSender.Result.Failed -> r.reason
        }
    }

    /** Asks the primary for the blocked list. The answer arrives later, through the socket. */
    fun requestBlockedList(): String {
        connection.connect()
        return when (
            val r = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).requestBlockedList()
        ) {
            is SignalSender.Result.Sent -> "requested"
            is SignalSender.Result.Failed -> r.reason
        }
    }

    /**
     * Sends one message, on this device's own authority. The primary is not in the path.
     *
     * @return the send timestamp, which is also the message's identity.
     * @throws IllegalStateException with the reason if the send did not happen.
     *
     * A value rather than a sentence. This used to return a human-readable string that the
     * caller pulled the timestamp back out of with a regular expression -- so a change to the
     * wording would have silently stopped messages being filed, with no compiler complaint
     * and no failure at the point of the change.
     */
    fun send(
        recipient: String,
        body: String,
        attachments: List<String> = emptyList(),
        expiresInSeconds: Int = 0,
        expireTimerVersion: Int = 0
    ): Long {
        val serviceId = org.signal.core.models.ServiceId.parseOrNull(recipient)
            ?: throw IllegalStateException("not a service id: $recipient")
        connection.connect()
        return try {
            when (val result = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database, SignalDataStore(database, account), connection, contacts
            ).send(serviceId, body, attachments, expiresInSeconds, expireTimerVersion)) {
                is SignalSender.Result.Sent -> result.timestamp
                // Typed, so the screen can offer "Send anyway" rather than reprint the
                // reason. See [SafetyNumberChanged].
                // ⚠ The "they have left Signal" mark is **not** done here. It lives in
                // [SignalSender.failed], which every send path goes through -- one-to-one and
                // group alike. Doing it here covered one of them, and a group is exactly where
                // somebody who has left is most likely to be found: nobody writes to them
                // one-to-one any more, which is why they went unnoticed.
                is SignalSender.Result.Failed -> if (result.safetyNumberChanged) {
                    throw com.wanderwildwood.kotozute.repository.SafetyNumberChanged(
                        threadKey = "direct:$recipient",
                        name = runCatching { contacts.nameFor(recipient) }.getOrNull()
                            ?.takeIf { it.isNotBlank() }
                    )
                } else {
                    throw IllegalStateException(result.reason)
                }
            }
        } finally {
            // The socket is shared and long-lived, so it is not disconnected here; closing is
            // [disconnect]. Nor is the reader lock touched: this sends and requests, it never
            // calls readMessageBatch, so it was never a reader.
            //
            // It used to unlock anyway, which throws IllegalMonitorStateException from a
            // finally -- after the network work had already succeeded. The message went out
            // and then the exception ate the code that files it, so a sent message reached
            // everybody else and never appeared in the sender's own thread.
        }
    }

    /**
     * Holds the connection open and files messages as they arrive.
     *
     * Blocks for as long as [keepGoing] says to, so the caller owns the thread.
     *
     * Does **not** close the connection on the way out. The socket is shared and outlives any
     * one pass of this loop: the loop exits on every reconnect, and closing here meant each
     * retry tore down a socket the next retry immediately rebuilt. Closing is [disconnect],
     * called by whoever knows nobody wants it any more.
     */
    fun listen(
        keepGoing: () -> Boolean,
        events: SignalEvents,
        onNamesLearned: () -> Unit = {},
        onBatch: (String) -> Unit
    ) {
        socketReader.lock()
        try {
        connection.connect()
        SignalReceiver(
            database, account, SignalDataStore(database, account), connection,
            SignalNetworkConfig.certificateValidator(), attachmentsFor(connection),
            contacts, blocks, keys, StoreEvents(events, onNamesLearned)
        ).listen(keepGoing) { r ->
            onBatch("envelopes=${r.envelopes} decrypted=${r.decrypted} failed=${r.failed} stored=${r.stored}")
        }
        } finally {
            socketReader.unlock()
        }
    }

    /**
     * Where to send word that somebody's number changed, once there is anybody listening.
     *
     * A field rather than a constructor argument because [contacts] outlives any one listen:
     * the store is built once and the events wrapper is built per connection, so the store
     * cannot hold a reference to it. Null while nothing is listening, which is the right answer
     * -- a number learned during a one-off storage read with no conversation on screen has
     * nobody to tell.
     */
    @Volatile
    private var onNumberChanged: ((aci: String, from: String, to: String) -> Unit)? = null

    /** Names for the people on the other end, from the primary's contacts sync. */
    internal val contacts: SignalContactStore by lazy {
        SignalContactStore(database) { aci, from, to ->
            // Blocked people are skipped for the same reason as a name change: a blocked
            // person should not be able to put a line into a conversation, even a line about
            // themselves. Read through `blocks`, not `contacts`, so this never re-enters the
            // store that is mid-write.
            if (!runCatching { blocks.isBlocked(aci, from) }.getOrDefault(false)) {
                onNumberChanged?.invoke(aci, from, to)
            }
        }
    }

    /** The account's blocked list, as the primary last sent it. */
    internal val blocks: SignalBlockStore by lazy { SignalBlockStore(database) }

    /** The account's own keys; see [SignalKeyStore] for why they are kept where they are. */
    internal val keys: SignalKeyStore by lazy { SignalKeyStore(database) }

    /** What contact discovery has already asked about; see [SignalDiscoveryStore]. */
    internal val discovery: SignalDiscoveryStore by lazy { SignalDiscoveryStore(database) }

    /**
     * A group's name and members, fetched from the server using the master key a message
     * carried. Null when it cannot be had -- a group whose details are unavailable should
     * still receive messages.
     */
    internal fun groupFor(masterKey: ByteArray): SignalGroups.Group? =
        runCatching { SignalGroups(connection, account, contacts).fetch(masterKey) }.getOrNull()

    /** A peer's safety number and trust level, or null if they are unknown to the store. */
    internal fun identityFor(aci: String): SignalIdentityKeyStore.Identity? = runCatching {
        val self = org.signal.core.models.ServiceId.parseOrNull(account.credentials().aci)
            ?: return@runCatching null
        SignalIdentityKeyStore(database, ProtocolDatabase.ACCOUNT_ID_TYPE_ACI)
            .identityFor(aci, self)
    }.getOrNull()

    /**
     * Trusts the key already on file for somebody, and throws away the sessions built on the
     * old one.
     *
     * ⚠ The second half was missing, and Signal's own comment says why it matters: when
     * `saveIdentity` reports NO_CHANGE it archives the sessions anyway, because they "appear
     * to be out of sync". NO_CHANGE is **always** our case -- this device already holds the new
     * key, stored untrusted, and accepting only promotes it -- so the sessions here are always
     * the ones built against the key that was replaced.
     *
     * Left in place, accepting made sends allowed again and left them going out over a ratchet
     * the other end had already moved off: refused, or arriving undecryptable, with the app
     * showing the safety number as settled.
     *
     * Every device of theirs, not just the primary: upstream archives the sibling sessions too
     * (`archiveSiblingSessions`), and a person's other devices were negotiated against the same
     * identity. [SignalAccountDataStore.archiveSession] clears the sender-key sharing with each,
     * which is upstream's third step.
     */
    fun acceptIdentity(aci: String): Boolean = runCatching {
        val accepted = SignalIdentityKeyStore(database, ProtocolDatabase.ACCOUNT_ID_TYPE_ACI)
            .acceptIdentity(aci)
        if (accepted) {
            val store = SignalDataStore(database, account).aciStore()
            // ⚠ The whole point of archiving here is the *siblings*. If they cannot be
            // enumerated, only the primary's session is retired, and accepting reports
            // success while sending resumes over a ratchet the other end has moved off --
            // which is exactly the fault this archiving was added to fix. It cannot be
            // repaired from here, so it is said out loud rather than defaulted away.
            val siblings = runCatching { store.getSubDeviceSessions(aci) }
                .onFailure {
                    Timber.w(
                        it,
                        "signal identity: could not list the other devices' sessions; " +
                            "accepting may leave sessions built on the key that was replaced"
                    )
                }
                .getOrDefault(emptyList())
            val devices = listOf(SignalSessionStore.PRIMARY_DEVICE_ID) + siblings
            devices.distinct().forEach { deviceId ->
                runCatching {
                    store.archiveSession(
                        org.signal.libsignal.protocol.SignalProtocolAddress(aci, deviceId)
                    )
                }.onFailure { Timber.w(it, "signal identity: could not archive a session after accepting") }
            }
            Timber.i(
                "signal identity: accepted a changed key and archived %d session(s) built on the old one",
                devices.distinct().size
            )
        }
        accepted
    }.getOrDefault(false)

    /** "known/with-key/named", for the Connection screen. Names come from profiles. */
    fun contactSummary(): String = runCatching {
        val c = contacts.counts()
        buildString {
            append("${c.known} contact(s), ${c.withProfileKey} with a profile key, ${c.named} named")
            // Only when there are any. A count of nought is a line of noise in a status
            // string somebody reads on a small screen.
            if (c.withUsername > 0) append(", ${c.withUsername} by username")
            if (c.nameless > 0) append(", ${c.nameless} with nothing to show but an id")
        }
    }.getOrDefault("")

    /**
     * Marks one person's row as differing from the account's copy. See
     * [SignalContactStore.rotateStorageId]; nothing pushes it yet.
     */
    fun rotateStorageId(serviceId: String) = contacts.rotateStorageId(serviceId)

    fun rotateStorageIdForGroup(groupId: String) = contacts.rotateStorageIdForGroup(groupId)

    /** Rows the account has not been told about. Read only; nothing here writes them up. */
    internal fun needingStoragePush(): List<SignalContactStore.Pending> = contacts.needingStoragePush()

    /** The name known for a service id, or null. */
    fun contactName(aci: String): String? = runCatching { contacts.nameFor(aci) }.getOrNull()

    /** The number known for one service id, or null. */
    fun contactNumber(aci: String): String? = runCatching { contacts.numberFor(aci) }.getOrNull()

    /** The service id known for a number, or null. */
    fun contactAciForNumber(e164: String): String? =
        runCatching { contacts.aciForNumber(e164) }.getOrNull()

    /** The account id a phone-number identity belongs to, where the account has said so. */
    fun contactAciForPni(pni: String): String? =
        runCatching { contacts.aciForPni(pni) }.getOrNull()

    /** Every name known, for renaming threads in one pass after a sync. */
    fun contactNames(): Map<String, String> = runCatching { contacts.all() }.getOrDefault(emptyMap())

    /**
     * Everyone the primary has told us about: service id, number and name, named or not.
     * This is the whole of a linked device's directory -- there is no lookup of its own.
     */
    internal fun contactDirectory(): List<SignalContactStore.Contact> =
        runCatching { contacts.everyone() }.getOrDefault(emptyList())

    /** Asks the primary for the account's settings; the answer arrives through the socket. */
    fun requestConfiguration(): String {
        connection.connect()
        return when (
            val r = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).requestConfiguration()
        ) {
            is SignalSender.Result.Sent -> "requested"
            is SignalSender.Result.Failed -> r.reason
        }
    }

    /** Asks the primary for its contacts. The answer arrives later, through the socket. */
    fun requestContacts(): String {
        connection.connect()
        return try {
            when (val r = SignalSender(
                SignalNetworkConfig.production(), SignalNetworkConfig.USER_AGENT, account, database,
                SignalDataStore(database, account), connection, contacts
            ).requestContactsSync()) {
                is SignalSender.Result.Sent -> "requested"
                is SignalSender.Result.Failed -> r.reason
            }
        } finally {
            // The socket is shared and long-lived, so it is not disconnected here; closing is
            // [disconnect]. Nor is the reader lock touched: this sends and requests, it never
            // calls readMessageBatch, so it was never a reader.
            //
            // It used to unlock anyway, which throws IllegalMonitorStateException from a
            // finally -- after the network work had already succeeded. The message went out
            // and then the exception ate the code that files it, so a sent message reached
            // everybody else and never appeared in the sender's own thread.
        }
    }

    /**
     * Bound to a connection because downloading needs one, and a connection is per-operation
     * here rather than a long-lived singleton.
     */
    private fun attachmentsFor(connection: SignalConnection) =
        SignalAttachments(context) { connection.messageReceiver }

    /**
     * Fetches an attachment from a pointer kept on a message row, for a retry.
     *
     * See the note in `SignalReceiver.withAttachments` on why the pointer is kept at all:
     * three immediate attempts do not cover a phone with no usable connection for the length
     * of one batch, and without this the message says "not downloaded" until the CDN copy
     * expires.
     *
     * @return the id to record, or null if it still could not be had.
     */
    fun downloadAttachment(pointerBytes: ByteArray): String? {
        connection.connect()
        val pointer = runCatching {
            org.whispersystems.signalservice.internal.push.AttachmentPointer.ADAPTER
                .decode(pointerBytes)
        }.getOrElse {
            // A pointer this build cannot parse will not become parseable, so this is not a
            // failure to retry -- it is one to stop retrying.
            Timber.w(it, "signal attachment: a kept pointer could not be read back")
            return null
        }
        return attachmentsFor(connection).download(pointer)
    }

    /**
     * Removes the bytes behind attachments whose message is gone.
     *
     * Needs no connection: it only deletes files. See [SignalAttachments.forget] for why this
     * had to exist -- without it a disappearing message lost its words and kept its picture.
     */
    fun forgetAttachments(ids: List<String>): Int =
        SignalAttachments(context) { throw IllegalStateException("no network needed to forget") }
            .let { store -> ids.count { id -> store.forget(id) } }

    /**
     * Deletes every attachment file no message names any more.
     *
     * The backstop behind [forgetAttachments], which deletes by name and so depends on the
     * name having survived. See [SignalAttachments.forgetAbandoned] -- and note the warning
     * there: [known] must be **every** id, because this deletes what is not in it.
     */
    fun forgetAbandonedAttachments(known: Set<String>): Int =
        SignalAttachments(context) { throw IllegalStateException("no network needed to forget") }
            .forgetAbandoned(known)

    /**
     * Files an attachment that came from an import rather than from the network, returning
     * the id to record on the message, or null if it could not be kept.
     *
     * The id becomes the filename, so it is built from the export's own name with anything
     * that is not a plain filename character removed -- the same shape the download path
     * produces, because the reader ends up in one attachment store either way.
     */
    fun keepImportedAttachment(name: String, open: () -> java.io.InputStream): String? {
        val safe = name.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: return null
        // A name that was already a plain one is kept exactly. It matters for a copy whose
        // file is named by the attachment id the messages already hold: renaming it would
        // leave every one of those rows pointing at a file that is now on the phone under a
        // name nothing asks for. A name that had to be changed
        // gets the prefix, so it cannot collide with an id that means something.
        val id = if (safe == name) name else "import-$safe"
        return id.takeIf {
            SignalAttachments(context) { error("no download needed to keep") }.keep(it, open)
        }
    }

    /** The bytes of a downloaded attachment, or null if it was never fetched. */
    fun readAttachment(id: String): ByteArray? =
        SignalAttachments(context) { error("no download needed to read") }.read(id)

    /**
     * Closes the shared socket.
     *
     * Only the thing that knows nobody wants it any more should call this -- which is
     * stopStream(), not the listen loop. The loop exits on every reconnect, and disconnecting
     * there meant each retry tore down a socket another retry was about to rebuild, so the
     * connection spent its life closing and reopening and messages arrived late.
     */
    fun disconnect() = runCatching { connection.disconnect() }.getOrNull()

    /**
     * Registering this phone as an account of its own, rather than joining one.
     *
     * Built the same way as [linker] and from the same stores, because the two flows end in
     * the same place: an identity, a set of pre keys, and credentials this device can use.
     * They differ only in how the server is persuaded to issue them.
     */
    fun registrar(): SignalRegistrar = SignalRegistrar(
        SignalNetworkConfig.production(),
        SignalNetworkConfig.USER_AGENT,
        account,
        { SignalSignedPreKeyStore(database, it) },
        { SignalKyberPreKeyStore(database, it) },
        io.michaelrocks.libphonenumber.android.PhoneNumberUtil.createInstance(context)
    )

    /**
     * The configuration is passed in rather than reached for. `SignalNetworkConfig` still
     * lives in the presentation module -- only because the smoke test that first needed it
     * did -- and this module cannot depend on that one. It belongs down here eventually,
     * along with the trust store it reads off the classpath; that is a move on its own, not
     * something to fold into a linking change.
     */
    fun linker(onReadReceipts: (Boolean) -> Unit = {}): DeviceLinker = DeviceLinker(
        SignalNetworkConfig.production(),
        SignalNetworkConfig.USER_AGENT,
        account,
        { SignalSignedPreKeyStore(database, it) },
        { SignalKyberPreKeyStore(database, it) },
        onAccountKeys = { pool ->
            // The same derivation the KEYS sync response goes through -- one path, so the two
            // cannot disagree about what is kept.
            if (keys.store(pool)) {
                Timber.i("signal link: the account's storage key came with the link")
            } else {
                Timber.w("signal link: the pool in the provisioning message would not derive")
            }
        },
        onReadReceipts = onReadReceipts
    )

    /**
     * What this store adds to whatever the caller asked for.
     *
     * Three things happen on the way through, and none of them are the caller's business: the
     * account's stored contact list is read the moment its key arrives, names are fetched
     * after a batch that made any fetchable, and receipts go back out over this device's own
     * connection. Everything else is passed straight along.
     *
     * Delegation rather than a pile of lambdas, so the next thing Signal syncs is one method
     * here instead of an argument threaded through four files. See [SignalEvents].
     */
    private inner class StoreEvents(
        private val outer: SignalEvents,
        private val onNamesLearned: () -> Unit
    ) : SignalEvents by outer {

        init {
            // Pointed at whoever is listening, for as long as they are. [contacts] is built
            // once and outlives every connection, so this is the only moment the two can be
            // introduced.
            onNumberChanged = { aci, from, to -> outer.numberChanged(aci, from, to) }
        }

        override fun onKeysLearned() {
            // The key has just arrived: read the account's contact list with it, and let the
            // caller rename its threads if anybody was learned.
            val read = runCatching { readStorage() }
                .onFailure { Timber.w(it, "signal storage: could not read") }
                .getOrNull()
            Timber.i("signal storage: %s", read ?: "not read")
            onNamesLearned()
            outer.onKeysLearned()
        }

        override fun afterBatch() {
            // Fetch whatever names became fetchable, then let the caller rename its threads --
            // only if something was actually learned, so a quiet batch does not walk the whole
            // thread list for nothing.
            val profiles = SignalProfiles(connection, contacts) { aci, from, to ->
                // ⚠ Only for somebody this account can still hear from. Upstream skips a
                // blocked recipient (`RetrieveProfileJob`'s `!recipient.isBlocked`), and the
                // reason is the same one blocking exists for: a blocked person should not be
                // able to put a line into a conversation, even a line about themselves.
                if (!runCatching { blocks.isBlocked(aci, contacts.numberFor(aci)) }.getOrDefault(false)) {
                    outer.profileNameChanged(aci, from, to)
                }
            }
            if (profiles.refreshMissingNames() > 0) onNamesLearned()
            outer.afterBatch()
        }

        override fun sendDeliveryReceipt(to: String, timestamps: List<Long>): Boolean =
            this@SignalStore.sendDeliveryReceipt(to, timestamps)

        override fun retryOwedReceipts(): Int = this@SignalStore.retryOwedReceipts()

        override fun pniRotationOwed(owed: Boolean) = onPniRotationOwed(owed)

        override fun sendRetryReceipt(
            to: String,
            error: org.signal.libsignal.protocol.message.DecryptionErrorMessage,
            groupId: ByteArray?
        ) {
            this@SignalStore.sendRetryReceipt(to, error, groupId)
        }

        override fun resend(to: String, sentTimestamp: Long): Boolean =
            this@SignalStore.resend(to, sentTimestamp)

        override fun retryOwedResends(): Int = this@SignalStore.retryOwedResends()
    }

}
