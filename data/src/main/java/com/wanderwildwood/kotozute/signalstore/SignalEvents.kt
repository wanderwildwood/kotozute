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

    /** Tell a sender their message arrived here. */
    fun sendDeliveryReceipt(to: String, timestamps: List<Long>) {}

    /** Ask a sender to send a message again, because it could not be read here. */
    fun sendRetryReceipt(to: String, error: DecryptionErrorMessage, groupId: ByteArray?) {}

    /**
     * Send something again, because its recipient says they could not read it.
     *
     * The other half of a retry receipt, and the half this app could not do until it kept a
     * log of what it sent. Their client is showing nothing and waiting for exactly this.
     */
    fun resend(to: String, sentTimestamp: Long) {}

    /** Messages the account has read on another device. */
    fun readElsewhere(read: List<Pair<String, Long>>) {}

    /** A message its sender has withdrawn, for everyone. */
    fun withdrawn(author: String, sentAt: Long) {}

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
