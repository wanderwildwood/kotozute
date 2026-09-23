package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.signal.BridgeMessage
import org.signal.libsignal.protocol.message.DecryptionErrorMessage

/**
 * Everything the receive path has to tell the rest of the app.
 *
 * These were ten separate lambdas on [SignalReceiver]'s constructor, repeated again on both of
 * [SignalStore]'s entry points and once more where the repository called them. Six of the ten
 * arrived in a single evening of catching up with Signal, and each one cost four edits in four
 * files to add -- which is the kind of friction that makes the next piece of parity look more
 * expensive than it is, and the kind that gets a parameter passed in the wrong order eventually.
 *
 * One interface, every method defaulted to doing nothing. Adding the next thing Signal syncs is
 * now a method here and a handler there.
 *
 * Defaults are deliberate rather than lazy: a caller that does not care about read syncs should
 * not have to write `{}` to say so, and the self-check and the tests construct a receiver
 * without any of this.
 */
interface SignalEvents {

    /** Files decrypted messages. Returns how many were new, which the drain reports. */
    fun store(messages: List<BridgeMessage>): Int

    /** The account's key material has arrived, so its stored contact list can be read. */
    fun onKeysLearned() {}

    /**
     * Something new is on disk after a batch.
     *
     * A batch can bring a contacts sync, a profile key, or both, and either can make a name
     * fetchable that was not a moment ago.
     */
    fun afterBatch() {}

    /** Messages we sent arrived, or were read, at the far end. */
    fun receipts(sender: String, timestamps: List<Long>, read: Boolean) {}

    /**
     * Tell a sender their message arrived here.
     *
     * ⚠ **Returns whether it went**, for the same reason [resend] does: a receipt that is
     * *refused* is the ordinary failure and raises nothing, so a caller told only about thrown
     * exceptions believes almost every failure succeeded.
     */
    fun sendDeliveryReceipt(to: String, timestamps: List<Long>): Boolean = false

    /**
     * Try again for every sender still owed a receipt.
     *
     * @return how many people were told.
     */
    fun retryOwedReceipts(): Int = 0

    /**
     * The phone-number identity is running on keys the primary generated.
     *
     * Recorded rather than only attempted. Upstream sets `forcePniSignedPreKeyRotation` when
     * the number change arrives and clears it inside `PreKeysSyncJob`, so the rotation is owed
     * until it has actually been done; here the flag plays the part the job queue would.
     */
    fun pniRotationOwed(owed: Boolean) {}

    /** Ask a sender to send a message again, because it could not be read here. */
    fun sendRetryReceipt(to: String, error: DecryptionErrorMessage, groupId: ByteArray?) {}

    /**
     * Publish fresh pre keys before asking for a message again.
     *
     * ⚠ Only for a failure on a **prekey message**, and it is not optional there. The bundle
     * the sender used is the suspect: consumed twice, or its private half gone from this
     * device. Asking for a resend without replacing it means they fetch the same bundle and
     * send a message that fails in exactly the same way -- a retry receipt each time, for ever,
     * or a conversation that simply never opens. `MessageDecryptor` forces the rotation first
     * for this reason.
     */
    fun rotatePreKeys() {}

    /**
     * Says in the conversation that somebody's phone number is now different from the one it
     * held.
     *
     * Upstream notes this for its own reasons (`RecipientTable`'s `ChangeNumberInsert` ->
     * `insertNumberChangeMessages`). There is a second reason here: one person is one row
     * across two rails, so their number changing re-pairs the Signal half of that row with a
     * different text conversation, and nothing else would say so.
     */
    fun numberChanged(aci: String, from: String, to: String) {}

    /**
     * Says in the conversation that somebody's name is now different from the one it held.
     *
     * Not the first name ever learned -- only a name that replaced another. A contact's
     * displayed name changing under the reader is how one person gets mistaken for another,
     * and Signal treats it as worth a permanent row in the conversation rather than a silent
     * relabelling (`RetrieveProfileJob` -> `insertProfileNameChangeMessages`).
     */
    fun profileNameChanged(aci: String, from: String, to: String) {}

    /**
     * Says in the conversation that a message arrived and could not be read.
     *
     * Called once, an hour after this phone asked the sender to send it again and nothing came.
     * Until then there is every chance the resend arrives and nobody needs to know anything
     * happened; after it, the alternative is a conversation with a silent gap in it, which is
     * the worst way for a message to be lost -- the reader cannot even ask about something they
     * were never told existed.
     *
     * Upstream's `PendingRetryReceiptManager` does exactly this on exactly this timer, and
     * `insertBadDecryptMessage` is the row it writes.
     *
     * @param sender the account id the message was from.
     * @param sentTimestamp the timestamp the sender stamped on it -- half of its identity, so
     *   a resend that turns up later replaces this note rather than sitting beside it.
     * @param groupId the group it was sent to, or null for a one-to-one message.
     */
    fun undecryptableGaveUp(
        sender: String,
        sentTimestamp: Long,
        groupId: ByteArray?
    ) {}

    /**
     * Says in the conversation that a message arrived and cannot be shown, and why.
     *
     * Three causes, one row, because what the reader needs is the same in each: a marker where
     * a message was, rather than a gap. The [reason] decides only the sentence — and it is an
     * enum rather than a flag because two of the three tell the reader to do *opposite* things,
     * update this app or ask the sender to update theirs, and a boolean that gets those the
     * wrong way round would read perfectly.
     *
     * `DataMessage.requiredProtocolVersion` is the sender stating the minimum understanding a
     * client needs to render the message *correctly*. Below it, the parts this build recognises
     * still parse -- which is precisely the danger. `ProtocolVersion` is
     * `VIEW_ONCE = 2`, `VIEW_ONCE_VIDEO = 3`, `PAYMENTS = 7`, `POLLS = 8`: rendering a
     * view-once photo with an older understanding shows it as an ordinary one, and **the sender
     * believes it disappeared**. That is not a missing feature, it is a broken promise.
     *
     * So the message is not processed at all, and this is said instead. Upstream does the same:
     * `MessageDecryptor:205` returns `Result.UnsupportedDataMessage` rather than continuing, and
     * `MessageContentProcessor:435` inserts an error row and calls
     * `markAsUnsupportedProtocolVersion`.
     *
     * The other two causes are decryption failures rather than content this build refuses:
     * `ProtocolLegacyMessageException` is the *sender's* Signal being too old, and
     * `ProtocolInvalidVersionException` is a ciphertext version this build does not speak.
     * Upstream inserts a row for both — `MessageContentProcessor:421` and `:428`, each with its
     * own marker — and asks for no resend, because a resend would arrive in the same form.
     *
     * Filed under the message's own identity, like [undecryptableGaveUp], so a later build that
     * does understand it replaces the note rather than sitting beside it.
     *
     * @param sender the account id the message was from.
     * @param sentTimestamp the timestamp the sender stamped on it.
     * @param groupId the group it was sent to, or null for a one-to-one message.
     * @param reason which sentence the conversation should carry.
     */
    fun cannotShow(
        sender: String,
        sentTimestamp: Long,
        groupId: ByteArray?,
        reason: CannotShow
    ) {}

    /**
     * Send something again, because its recipient says they could not read it.
     *
     * The other half of a retry receipt, and the half this app could not do until it kept a
     * log of what it sent. Their client is showing nothing and waiting for exactly this.
     *
     * ⚠ **Returns whether it went.** It used to return nothing, so a send that was *refused*
     * -- no session, a server error, somebody who has left Signal -- was indistinguishable
     * from one that landed, and only a thrown exception counted as failure. That is the
     * common case, not the rare one.
     *
     * ⚠ **And whether it is worth trying again.** A refusal -- a rate limit, a proof request,
     * somebody who has left -- used to be kept owed and retried every quarter hour for a day,
     * which is ninety-six more knocks on a door that has already answered. Upstream retries
     * only what failed on the network (`ResendMessageJob.onShouldRetry`).
     */
    fun resend(to: String, sentTimestamp: Long): Resend = Resend.TRY_AGAIN

    /** How a resend went, and so what the caller owes the person who asked for it. */
    enum class Resend {
        /** It reached the server for them. Nothing is owed. */
        SENT,

        /** It never reached the server. Still owed; the next pass tries again. */
        TRY_AGAIN,

        /** The server answered no, or the message is no longer held. Asking again changes nothing. */
        GIVE_UP,
    }

    /**
     * Try again for everybody still owed a message they asked for.
     *
     * Called at the end of a batch, because a batch arriving is proof the socket is back. The
     * same work also runs from key maintenance on the periodic round, so a quiet phone does
     * not leave somebody waiting on traffic that is not coming.
     *
     * @return how many went.
     */
    fun retryOwedResends(): Int = 0

    /**
     * Messages the account has read on another device.
     *
     * @param readAt when the other device said so -- the read sync's own timestamp, not now.
     *   It dates a disappearing message's countdown; see the note where it is applied.
     */
    fun readElsewhere(read: List<Pair<String, Long>>, readAt: Long) {}

    /**
     * A message its sender has withdrawn, for everyone.
     *
     * [withdrawnAt] is when the withdrawal itself was sent, and it is what bounds the gesture:
     * see [SignalRepository]'s handling. Without it a sender can erase a message of any age.
     */
    fun withdrawn(author: String, sentAt: Long, withdrawnAt: Long) {}

    /** What the account has deleted elsewhere, for itself: messages, and whole conversations. */
    fun deletedElsewhere(messages: List<Pair<String, Long>>, threads: List<String>) {}

    /** The account's own settings, as its primary holds them. */
    fun configuration(readReceipts: Boolean?) {}

    /**
     * The account says its stored records have changed, and this device should re-read them.
     *
     * Signal's `FetchLatest`. It is the only push this device gets when somebody is added,
     * renamed or removed on another device -- storage records carry no notification of their
     * own, so without it the contact list is only ever as fresh as the last app launch or the
     * last time somebody went into Settings and asked. Somebody added on the primary at
     * breakfast was not writable to here until the app was restarted.
     */
    fun refreshStoredRecords() {}

    /**
     * A message arrived from a group at a revision this device has not caught up with.
     *
     * Every group message carries the group's master key and its revision number. The key was
     * being read and the revision thrown away, so a group renamed after this phone first saw
     * it kept the old name for ever -- the title was only ever filled in when it was blank.
     *
     * The revision is what makes checking affordable: without it the choice is between asking
     * the server about every group on every batch, and never noticing a change at all.
     */
    fun groupChanged(masterKey: ByteArray, revision: Int) {}

    /**
     * A conversation's disappearing-messages timer has been set.
     *
     * The timer belongs to the conversation, not to any one message. It was being detected and
     * thrown away, so this phone's own replies carried no timer -- which does not merely fail
     * to disappear, it tells the other person's client the conversation has been switched off.
     */
    fun timerChanged(threadKey: String, seconds: Long, version: Int) {}
}

/**
 * Why a message that arrived cannot be put in front of somebody.
 *
 * Deliberately not a boolean anywhere: [SENDER_TOO_OLD] and [NEEDS_NEWER_APP] ask the reader to
 * do opposite things, and the two are easy to state backwards.
 */
enum class CannotShow {
    /** Its sender marked it as needing a protocol version this build does not implement. */
    NEEDS_NEWER_APP,

    /** It was encrypted by a Signal too old for this build to decrypt -- their end, not ours. */
    SENDER_TOO_OLD,

    /** Its ciphertext is in a version this build does not speak. Neither end is named. */
    UNREADABLE_FORM
}
