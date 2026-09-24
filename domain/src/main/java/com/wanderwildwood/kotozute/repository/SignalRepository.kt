package com.wanderwildwood.kotozute.repository

import com.wanderwildwood.kotozute.signal.BridgeMessage
import com.wanderwildwood.kotozute.model.SignalMessage
import com.wanderwildwood.kotozute.model.SignalThread
import io.reactivex.Observable
import io.realm.RealmResults

/**
 * Signal, reached by this phone as a device on the account.
 *
 * Receiving degrades softly: while Signal is unreachable, messages queue on its servers and
 * arrive when the connection comes back. Sending fails hard -- there is no offline queue,
 * because a message the user believes they sent and which never arrives is worse than a
 * composer that plainly refuses. [ConnectionState] is what the UI uses to say so.
 */
interface SignalRepository {

    data class ConnectionState(
        /** This phone is on the account: linked to it, or registered as it. */
        val configured: Boolean,
        /**
         * The same fact, under the name every screen already used for it. Kept while the
         * two could differ -- a phone could be linked and still reaching Signal through a
         * bridge -- and now they cannot.
         */
        val linkedDirectly: Boolean = false,
        /**
         * Envelopes that arrived and could not be decrypted, and are being kept in case a
         * fix can read them. Surfaced because a release build logs nothing and this is the
         * one early sign of a message shape the app cannot handle.
         */
        val undecryptable: Int = 0,
        /** Why they would not decrypt, distinct, newest first. Empty when there are none. */
        val undecryptableReasons: List<String> = emptyList(),
        /** Contacts known, how many carry a profile key, how many are named. Diagnostic. */
        val contactCounts: ContactCounts? = null,
        val enabled: Boolean,
        /** This phone's own connection to Signal is up. */
        val signalConnected: Boolean,
        /**
         * The socket is reaching for the server right now.
         *
         * Only meaningful while [signalConnected] is false, and it is the difference between
         * "not yet" and "not at all". Every launch starts disconnected; a screen that cannot
         * tell the two apart tells everyone their phone is broken once per launch.
         */
        val connecting: Boolean = false,
        val lastSyncedAt: Long,
        val error: String? = null,
        /**
         * Why the server refuses this device, or null if it does not.
         *
         * Separate from [error], which is whatever the last read threw and is usually a
         * transient network string nobody should be shown. This one is permanent until its
         * owner acts, and is the only thing worth interrupting them about: everything else
         * recovers on its own.
         */
        val rejected: String? = null
    ) {
        /** Only then may the composer offer to send. */
        val canSend: Boolean get() = enabled && signalConnected
    }

    /**
     * The contact store in numbers, for the status line. Names come from profiles, so these
     * are the only way to tell "nobody has shared a profile key" from "the fetch is broken".
     */
    data class ContactCounts(
        val known: Int,
        val withProfileKey: Int,
        val named: Int,
        val withUsername: Int,
        /** Neither a name, a number, nor a username: nothing to show but a fragment of an id. */
        val nameless: Int
    )

    fun isConfigured(): Boolean

    fun unpair()

    fun setEnabled(enabled: Boolean)

    /**
     * Links this phone to a Signal account as a secondary device.
     *
     * Blocks until the exchange finishes or the code expires, so the caller owns the thread.
     * [onUrl] is called once, as soon as there is something to show -- the wait after that is
     * a person picking up their other phone, so showing the code late wastes the window.
     *
     * @return what happened, or null if nothing came back before the code expired.
     */
    fun linkDevice(deviceName: String, onUrl: (String) -> Unit): Link?

    /** How linking ended. */
    sealed interface Link {
        data class Linked(val deviceId: Int) : Link
        data class Failed(val failure: LinkFailure) : Link
    }

    /** Why linking failed, as a kind the screen words in the reader's language. */
    sealed interface LinkFailure {
        /** Every code expired with nobody having scanned one. */
        data object NotScanned : LinkFailure

        /** Something came back, and it would not decrypt. */
        data object Undecryptable : LinkFailure

        /** The message named no provisioning code, so the server has no reason to trust it. */
        data object NoProvisioningCode : LinkFailure

        /** The server refused the device; [detail] is its answer, as it gave it. */
        data class Refused(val detail: String) : LinkFailure

        /** The exchange threw; [detail] is its own text, or its type where it had none. */
        data class Unexplained(val detail: String) : LinkFailure
    }

    /**
     * Where a registration has got to.
     *
     * The session id travels with it rather than living in the repository, so abandoning half
     * way leaves nothing behind and a rebuilt screen can carry on where it was.
     */
    sealed interface Registration {
        data class NeedsCaptcha(val sessionId: String) : Registration
        data class CodeSent(val sessionId: String) : Registration
        data class Registered(val e164: String) : Registration
        data class Failed(val failure: RegistrationFailure) : Registration
    }

    /**
     * Why a registration step failed, as a kind the screen words in the reader's language.
     *
     * A `detail` is what the server or the library said, passed on as it is: the step names
     * which request it was, and the detail is the only record of why.
     */
    sealed interface RegistrationFailure {
        /** Not a number this can register; see `E164Numbers`. */
        data object NotANumber : RegistrationFailure

        /** The session opened, and the server will not send a code yet. */
        data object NoCodeYet : RegistrationFailure

        data class CouldNotStart(val detail: String) : RegistrationFailure

        /** The captcha was taken, and the server still will not send a code. */
        data object CaptchaAcceptedNoCode : RegistrationFailure

        data class CaptchaRefused(val detail: String) : RegistrationFailure

        data class CodeNotSent(val detail: String) : RegistrationFailure

        /** The code was refused: with the server's answer, or null where it said "not verified". */
        data class CodeRefused(val detail: String?) : RegistrationFailure

        /** The account's root key could not be made, so nothing was sent. */
        data object NoKeyMaterial : RegistrationFailure

        /** The number has a registration lock with about [days] left to run. */
        data class Locked(val days: Long) : RegistrationFailure

        data class Refused(val detail: String) : RegistrationFailure

        /** A step came back as something this app does not know. */
        data object Unexpected : RegistrationFailure

        /** A step threw; [detail] is its own text, or its type where it had none. */
        data class Unexplained(val detail: String) : RegistrationFailure
    }

    /**
     * Starts registering [e164] as this phone's own Signal account.
     *
     * ⚠ This takes the number over: Signal allows one primary per number, so any Signal
     * already registered to it is deregistered, along with every device linked to it. The
     * caller must have said so plainly before this is called.
     */
    suspend fun registerBegin(e164: String): Registration

    /** Hands back a captcha solved from Signal's own page. */
    suspend fun registerCaptcha(sessionId: String, token: String): Registration

    /** Asks for the code again, by text or by voice call. */
    suspend fun registerResend(sessionId: String, voice: Boolean): Registration

    /** Submits the code and, if it is right, completes registration. */
    suspend fun registerVerify(sessionId: String, code: String, e164: String): Registration

    /**
     * Gives the newly registered account its own profile name.
     *
     * The last step of registering, and only of registering: a linked device inherits the
     * primary's profile and must not write over it. Until this runs the account has no
     * profile at all, and everyone it writes to sees a service id rather than a person.
     *
     * Separated from [registerVerify] rather than folded into it because it is the one step
     * that can be retried on its own -- the account already exists by then, so a failure here
     * is worth offering again rather than starting over.
     *
     * @return null on success, or why not, for the screen to word.
     */
    suspend fun registerSetProfileName(given: String, family: String): ProfileNameFailure?

    /** Why this account's name did not save. */
    sealed interface ProfileNameFailure {
        /** There is no account on this phone yet. */
        data object NoAccount : ProfileNameFailure

        /** The account has no service id yet. */
        data object NoServiceId : ProfileNameFailure

        /** The account holds no profile key. */
        data object NoProfileKey : ProfileNameFailure

        /** The account's profile key is there and will not load. */
        data object ProfileKeyUnreadable : ProfileNameFailure

        /** No given name, which Signal takes to mean no name at all. */
        data object NoGivenName : ProfileNameFailure

        /** The server would not take it; [detail] is its answer, as it gave it. */
        data class Refused(val detail: String) : ProfileNameFailure

        /** The write threw; [detail] is its own text, or its type where it had none. */
        data class Unexplained(val detail: String) : ProfileNameFailure
    }

    /**
     * Whether this phone is the account's **primary** device rather than a linked one.
     *
     * Local and cheap -- it reads the stored device id and asks the network nothing, unlike
     * [account], which throws when Signal cannot be reached. A settings screen has to be able
     * to draw itself with no connection.
     *
     * Only a primary may write the account's profile: a linked device inherits one the
     * primary already wrote and has no business replacing it.
     */
    fun isPrimaryDevice(): Boolean

    /**
     * Recomputes and republishes the connection state.
     *
     * Needed because the state starts as a literal "nothing is configured" and is otherwise
     * only republished by an event -- a link, a sync, a stream change. A device that is
     * itself linked to the account has no such event at startup, so without this every Signal
     * screen would keep offering to connect while messages arrived behind it.
     *
     * Does its work off the calling thread: answering it opens the keystore and an encrypted
     * database, which is not a main-thread question.
     */
    fun refresh()

    /**
     * Records that messages we sent reached, or were read by, the far end.
     *
     * Keyed by the send timestamp, because that is the only identifier a receipt carries --
     * a receipt says "the message you sent at T arrived", and says nothing else about it.
     *
     * @return how many rows changed.
     */
    fun applyReceipts(senderUuid: String, timestamps: List<Long>, read: Boolean): Int

    /**
     * Files messages that arrived some other way than the sync -- the device's own live
     * stream.
     *
     * The same storage path the sync uses, on purpose. Threads, previews, reactions and
     * expiry all key off the same rules, so a second writer with its own idea of them would
     * produce a second set of threads beside the real ones.
     *
     * @return how many were new.
     */
    fun ingest(messages: List<BridgeMessage>): Int

    /** Pulls everything after our cursor. Safe to call repeatedly; it is idempotent. */
    fun syncNow(): Int

    /**
     * Whether this phone is level with what the server was holding.
     *
     * False means the catch-up stopped short -- a dropped connection part-way through a
     * backlog, or a page that failed. Nothing is lost, because the cursor only advances over
     * what actually landed, but the phone is behind and should come back for the rest rather
     * than wait out the next round.
     *
     * ⚠ It is an answer about the phone, not a record of the last call. While the live stream
     * owns the socket there is no catch-up to run at all, and a flag that only a catch-up could
     * clear would stay false for ever after one failure -- asking for a retry on a phone that
     * was level. A connected stream is drawing level continuously and says so.
     *
     * Its own answer rather than a flag on [ConnectionState]: nine other things publish that
     * object, every one of them with this at its default, so a state change between the sync
     * and the reader would report the opposite of what happened.
     */
    fun lastSyncCaughtUp(): Boolean

    /** Holds the account's event stream open, reconnecting as needed. */
    fun startStream()
    fun stopStream()

    /**
     * [attachments] are RFC 2397 data URIs. Returns the Signal timestamp.
     *
     * [quoteTs] is the sent timestamp of a message in this thread being replied to, or 0.
     * Signal names a quoted message by that timestamp and its author; the author, text and
     * attachment are looked up here, so a caller only has to say which message.
     */
    fun send(
        threadKey: String,
        body: String,
        attachments: List<String> = emptyList(),
        quoteTs: Long = 0L
    ): Long

    fun markRead(threadKey: String, upToTs: Long)

    /** Blocking. Returns null if Signal cannot be reached or has no such attachment. */
    fun loadAttachment(id: String): ByteArray?

    /**
     * The Signal thread for a phone number, if this account has one. Matching is by
     * phone-number comparison rather than string equality -- the same person is
     * "+15551234567" to Signal and "(555) 123-4567" to the address book.
     */
    fun findThreadForNumber(number: String): SignalThread?

    /** The SMS conversation this Signal thread has been tied to by hand, or null. */
    fun linkedConversationId(threadKey: String): Long?

    /** The Signal thread tied by hand to this SMS conversation, or null. */
    fun linkedThreadKeyFor(conversationId: Long): String?

    /** Tie a Signal thread to an SMS conversation; null unties it. */
    fun linkConversation(threadKey: String, conversationId: Long?)


    /**
     * Display names for the people who sent messages in a thread, keyed by their Signal
     * uuid. Resolved in one pass rather than per drawn row, and only ever needed for a
     * group -- a one-to-one thread already says who it is at the top.
     */
    fun senderNamesFor(threadKey: String): Map<String, String>

    /** [archived] selects which shelf: the inbox, or the archive. */
    fun getThreads(archived: Boolean = false): RealmResults<SignalThread>

    /**
     * Detached copies, readable from any thread.
     *
     * The async results [getThreads] returns need a Looper, and the desktop relay serves
     * each request on a plain worker thread -- the same reason the SMS side has a Sync
     * variant.
     */
    fun getThreadsSnapshot(archived: Boolean = false): List<SignalThread>

    /**
     * What an import of a Signal export did, in terms a person can check against what they
     * expected to get back.
     */
    data class ImportStats(
        val messages: Int = 0,
        val alreadyPresent: Int = 0,
        val attachments: Int = 0,
        val attachmentsLost: Int = 0,
        val skippedEvents: Int = 0,
        val skippedDeleted: Int = 0,
        val skippedExpired: Int = 0,
        val skippedNoThread: Int = 0,
        val skippedNoAuthor: Int = 0,
        val skippedUnknownGroup: Int = 0
    )

    /** The chosen folder held no Signal export. */
    class NotAnExport : Exception()

    /** The folder holds a backup this app wrote, and the key given does not open it. */
    class WrongBackupKey : Exception()

    /** The folder holds a backup this app wrote and no key was given. */
    class BackupKeyNeeded : Exception()

    /** What an export wrote, and where. */
    data class ExportStats(
        val threads: Int = 0,
        val messages: Int = 0,
        val attachments: Int = 0,
        /** References with no file behind them: never downloaded, or gone since. */
        val missing: Int = 0,
        val folder: String = "",
        /**
         * Digits the reader has to write down, or empty when there are none.
         *
         * Empty for every copy written now: they are locked with a key derived from the
         * account, as Signal derives one (`AccountEntropyPool.deriveMessageBackupKey`), so a
         * copy opens on any device that can reach the account and nothing has to be kept on
         * paper. The field stays because copies written before this exist and are opened by
         * thirty digits, and because a screen that showed a key must be able to show none.
         */
        val key: String = ""
    )

    /**
     * Reads a Signal Desktop "export chat history" folder into this phone's Signal threads.
     *
     * Blocking, and slow: a history is thousands of messages and their pictures. [folder] is
     * what the document picker returned. [onProgress] reports the running count of messages
     * taken in, sparsely.
     *
     * Adds only. A message this phone already holds is left exactly as it is, whether it
     * arrived live or from an earlier run of this, so importing twice -- or importing a
     * window that overlaps what is already here -- changes nothing.
     */
    fun importHistory(folder: String, key: String = "", onProgress: (Int) -> Unit = {}): ImportStats

    /**
     * Asks Signal for the account's contact list. Blocking; returns what happened, for the
     * screen to say.
     */
    fun fetchContactsFromSignal(): ContactsReport

    /**
     * Asks Signal which numbers in this phone's address book are on it. Blocking; returns
     * what happened, for the screen to say.
     *
     * Different question from [fetchContactsFromSignal], which reads the people the account
     * already has a record for. This finds the ones it does not -- anybody in the address
     * book who has never written first, who until now could not be written to at all.
     *
     * ⚠ Sends the address book's numbers, hashed, to Signal's discovery enclave, and the
     * account is charged a quota for numbers it has not asked about before. Runs only when
     * somebody asks.
     */
    fun discoverContactsByNumber(): ContactsReport

    /**
     * What a contact fetch or a number lookup did, as counts and kinds rather than a sentence.
     *
     * ⚠ The counts are the point, not decoration: a fetch that understood a third of the
     * account used to report the same shape of success as one that understood all of it. So
     * everything a reader needs to notice that is carried here whole, and the screen decides
     * only how to say it.
     */
    sealed interface ContactsReport {
        /** This phone is not on an account yet. */
        data object NotLinked : ContactsReport

        /** The primary was asked for the account's keys, and the list follows them. */
        data object Requested : ContactsReport

        /** The address book holds no number to look up. */
        data object NoNumbers : ContactsReport

        /**
         * The account's records were read. [contacts] were kept out of [records]; [pniOnly]
         * of those are known by phone-number identity alone. The rest were skipped:
         * [unreadable] the service would not hand over, [unopened] would not open,
         * [notContacts] held something else, and [anonymous] named no address at all, of
         * which [anonymousWithNumber] still carried a number.
         */
        data class Read(
            val contacts: Int,
            val records: Int,
            val pniOnly: Int = 0,
            val unreadable: Int = 0,
            val unopened: Int = 0,
            val notContacts: Int = 0,
            val anonymous: Int = 0,
            val anonymousWithNumber: Int = 0
        ) : ContactsReport

        /** The records could not be read at all. */
        data class ReadRefused(val why: StorageRefusal) : ContactsReport

        /** [found] of [asked] numbers are on Signal; [withoutAci] by phone number only. */
        data class Discovered(val found: Int, val asked: Int, val withoutAci: Int) : ContactsReport

        /** Nothing was asked, because every number has been asked about before. */
        data object AllLookedUp : ContactsReport

        /**
         * The lookup did not happen. [minutes] is how long the service has said to wait,
         * for [LookupRefusal.BLOCKED] only.
         */
        data class LookupRefused(val why: LookupRefusal, val minutes: Long = 0) : ContactsReport
    }

    /** Why the account's records could not be read. */
    enum class StorageRefusal {
        /** The storage key has not arrived from the primary. */
        NO_KEY,

        /** The service would not issue an auth token. */
        NO_AUTH,

        /** A 404 on the manifest: an account nobody has written records for, not a fault. */
        NOTHING_STORED,

        /** The manifest could not be fetched. */
        MANIFEST_UNREADABLE,

        /** The manifest arrived and would not open -- most likely the wrong key. */
        WRONG_KEY
    }

    /** Why a number lookup did not happen. */
    enum class LookupRefusal {
        /** No number in the set was one the service would take. */
        NO_VALID_NUMBERS,

        /** The service said not to ask again yet, and that time has not passed. */
        BLOCKED,

        /** More new numbers than Signal's own ceiling for one ask. */
        TOO_MANY,

        /** The pairs the ask has to carry could not be read. */
        OWN_RECORDS_UNREADABLE,

        /** The account's lookup quota is spent for now. */
        EXHAUSTED,

        /** The token for earlier lookups and the record of them disagree. */
        TOKEN_OUT_OF_STEP,

        /** The service refused the list itself. */
        NUMBERS_REFUSED,

        /** Anything else that stopped the lookup part way. */
        UNFINISHED
    }

    /**
     * Tops up and rotates this account's own keys if either is owed.
     *
     * One batch of one-time prekeys is published when the device links, and the server hands
     * each out once. Without this they are never replaced, and once they run out every new
     * session opened with this phone is built without them -- silently, and for good.
     */
    fun maintainKeys()

    /**
     * Whether this phone is in the state the fetch exists for, and should say so once.
     *
     * True only where the account is linked, the contact list is empty, and the list has not
     * already been read. That is not a rare corner: it is how every linked device starts,
     * because the sync it asks for on connecting is one a modern Signal no longer answers.
     * Blocking -- it reads the protocol database.
     */
    fun shouldOfferContactFetch(): Boolean

    /**
     * This account's own number, as the phone knows it, or blank.
     *
     * Note to Self and the text conversation somebody has with their own number are the
     * same conversation with themselves, and nothing else links them: the Signal side knows
     * only a service id, and the text side only a number.
     */
    fun selfNumber(): String

    /**
     * Removes this phone's copy of one Signal conversation.
     *
     * ⚠ This is the only copy. Signal gives a linked device no history, so what is here was
     * either received while this phone was linked or imported into it, and no server and no
     * other device will send it again. Nothing about this reaches the Signal account: the
     * other person keeps their copy, and a new message starts the conversation again.
     *
     * @return how many messages went.
     */
    fun deleteThread(threadKey: String): Int

    /**
     * Something done to a person rather than to one of their two conversations.
     *
     * The inbox shows one row for somebody who is on both rails, so archiving, muting,
     * pinning, blocking or deleting from that row has to reach both halves or the row does
     * half of what it says -- archiving took the row away and let the text conversation
     * spring back as a row of its own, muting left half the messages chiming.
     *
     * One place, because three screens ask for it: the thread's details, the list's own
     * menu, and the browser. Three copies of the rule is three chances for them to disagree
     * about what one row means.
     */
    enum class PersonAction { ARCHIVE, UNARCHIVE, PIN, UNPIN, MUTE, UNMUTE, UNREAD, BLOCK, UNBLOCK, DELETE }

    /** @return true where it was done; false where nothing could be. */
    fun actOnPerson(threadKey: String, action: PersonAction): Boolean

    /**
     * Whether this device has been given the account's blocked list.
     *
     * Blocking is offered only when it has. Signal syncs the list whole, so a device without
     * one cannot change it without replacing it -- and a button that can only ever refuse is
     * worse than no button.
     */
    fun canBlock(): Boolean

    /** Whether this person is on the account's blocked list, as this device last heard it. */
    fun isBlocked(threadKey: String): Boolean

    /**
     * Whether [folder] holds a backup that needs **thirty digits from the person** to open.
     *
     * ⚠ Not "is it sealed". Every backup this app writes is sealed; the ones written since
     * copies were locked to the account open with a key the phone already has, and asking for
     * digits that were never shown is asking for something that does not exist. That is what
     * this used to do -- it answered "is there a header", which is true of both kinds -- and an
     * account-locked backup could not be restored through the UI at all.
     */
    fun needsBackupKeyFromPerson(folder: String): Boolean

    /**
     * Writes this phone's Signal messages into [folder] as an export [importHistory] reads
     * back exactly. Blocking, and slow for the same reason.
     *
     * Signal gives a linked device no history, so once these messages are here the phone is
     * the only place they exist. This is the way out.
     */
    fun exportHistory(folder: String, onProgress: (Int) -> Unit = {}): ExportStats

    /**
     * Someone this account can write to. [threadKey] is where the conversation with them
     * lives, whether or not it has anything in it yet; [name] is never blank, because a row
     * in a list has to say something.
     */
    data class Person(val threadKey: String, val name: String, val number: String)

    /**
     * Everyone Signal knows on this account, whether or not there is a conversation --
     * the recipient list for starting one. The conversation lists exclude the empty ones on
     * purpose; this is the one place they are wanted.
     */
    fun people(): List<Person>

    /**
     * Makes a group on the server and gives back the thread key it will be found under.
     *
     * [memberThreadKeys] are the keys [people] hands out; this account is added by the
     * server and must not be among them. Nothing is said in the group -- the members' own
     * clients learn of it from the first message, which carries the group's key and
     * revision the way every group message does.
     *
     * Throws [GroupNotMade] when it cannot be done, saying which way: an account with no
     * profile on file cannot make a group at all, and that is worth saying plainly rather
     * than failing quietly. The screen words it.
     */
    fun createGroup(title: String, memberThreadKeys: List<String>): String

    /**
     * Threads whose title or messages match [query]. Returns each thread once, with how
     * many of its messages matched and the newest matching body; a thread that matched only
     * by name reports zero.
     */
    fun searchThreads(query: String): List<SignalSearchHit>

    fun getMessagesSnapshot(threadKey: String, limit: Int): List<SignalMessage>

    /**
     * One message in a thread by its timestamp, detached, or null if it is not held.
     *
     * What a quoted reply needs: Signal names what it answers by timestamp, and the answer
     * is usually older than whatever page is on screen.
     */
    fun getMessageAt(threadKey: String, date: Long): SignalMessage?

    /**
     * How many unexpired messages this thread holds, so a caller showing only the tail can
     * say whether there is older history behind it.
     */
    fun countMessages(threadKey: String): Int

    /**
     * Archiving a Signal thread only hides it here. Signal has no such notion, so this
     * is not sent anywhere and other devices are unaffected.
     */
    fun setArchived(threadKey: String, archived: Boolean)

    /**
     * Who this phone is signed in as and which devices are on that account. Throws if Signal
     * cannot be reached, so a screen can say so rather than show a blank.
     */
    fun account(): SignalAccount

    /**
     * The safety number for a one-to-one thread and whether the key is still the accepted
     * one. Throws if Signal cannot be reached.
     */
    fun identity(threadKey: String): SignalIdentity

    /**
     * Accepts a contact's changed key, so messages can be sent to them again.
     *
     * Exists because refusing to send to a changed key is only half of what a Signal client
     * owes: the other half is telling someone it happened and letting them decide. Without
     * this the conversation is permanently unsendable, and a changed key is routine -- it is
     * what a reinstall or a new phone produces.
     *
     * @return false if nothing was accepted, so a caller cannot report success wrongly.
     */
    fun acceptIdentity(threadKey: String): Boolean

    /**
     * React to a message, or take a reaction back. [messageId] is this app's own id for the
     * message; the author and timestamp Signal needs are read off the stored row.
     */
    fun react(messageId: String, emoji: String, remove: Boolean)

    /**
     * Takes one of this account's own messages back, for everyone it was sent to.
     *
     * The other half of the withdrawal this app has always been able to receive. Only an
     * outgoing message, and only inside [WITHDRAW_WINDOW_MS] -- see [canWithdraw], which is
     * the same rule the menu is built from, so nothing is offered that would then be refused.
     */
    fun withdraw(messageId: String)

    companion object {
        /**
         * How long a sender has to take a message back.
         *
         * Signal's `normalDeleteMaxAgeInSeconds`, a day, from `RemoteConfig`. The receive
         * side allows a day more for delivery; the send side does not, because a withdrawal
         * sent later than this is one the recipient will refuse and nobody would be told.
         */
        const val WITHDRAW_WINDOW_MS = 24L * 60 * 60 * 1000

        /**
         * Whether this message can still be taken back, which is Signal's own
         * `MessageConstraintsUtil.isValidRemoteDeleteSend` reduced to what this app has:
         * it has to be ours, and it has to be inside the window.
         */
        fun canWithdraw(outgoing: Boolean, sentAt: Long, now: Long = System.currentTimeMillis()) =
            outgoing && now - sentAt < WITHDRAW_WINDOW_MS
    }

    /**
     * Block or unblock this thread's other party on the Signal account itself. Throws if
     * Signal cannot be reached, so the caller can say so rather than imply success.
     */
    fun setBlocked(threadKey: String, blocked: Boolean)

    fun setPinned(threadKey: String, pinned: Boolean)

    fun setMuted(threadKey: String, muted: Boolean)

    /**
     * Delete messages whose disappearing deadline has passed. Returns how many went. Reads
     * already hide them; this is what stops the phone being the copy that outlives the timer.
     */
    fun purgeExpired(): Int

    /**
     * Deletes attachment files that no message refers to any more, returning how many went.
     *
     * The backstop behind deleting a message's files by name. Runs rarely: it walks every
     * message row, and an orphan costs only disk until the next pass.
     */
    fun purgeAbandonedAttachments(): Int

    /**
     * Tries again for attachments that did not arrive the first time, returning how many did.
     *
     * Three immediate attempts cover a dropped socket; they do not cover a phone with no usable
     * connection for the length of one batch. Bounded to a day, as upstream's download job is.
     */
    fun retryPendingAttachments(): Int

    /** Put a thread back to unread, so it is picked up again later. */
    fun markUnread(threadKey: String)

    /** Whether this thread's notifications are silenced. Read on the notification path. */
    fun isMuted(threadKey: String): Boolean
    fun getMessages(threadKey: String): RealmResults<SignalMessage>

    fun connectionState(): Observable<ConnectionState>

    /**
     * Emits each newly stored *incoming* message, once. Notifications live in the
     * presentation layer, so the repository announces rather than notifies -- and because
     * it emits only on a genuinely new row, a message replayed on reconnect cannot ring
     * twice.
     */
    fun newIncoming(): Observable<SignalMessage>

    /**
     * Thread keys whose messages have just been removed -- withdrawn by their sender, expired,
     * or deleted on another device.
     *
     * The notification carries the message text. Removing the row leaves that text on the lock
     * screen until the conversation is next opened, so a withdrawal -- the one gesture whose
     * entire purpose is to unsay something -- left it legible exactly where it is most read.
     */
    fun messagesRemoved(): Observable<String>

    /**
     * Thread keys the account has finished reading somewhere else.
     *
     * A linked device is one of several, and the person reading on another one has already
     * seen the message. Marking it read here was only half of it: the notification stayed on
     * the lock screen, announcing something already dealt with, and the only way to clear it
     * was to open a conversation with nothing new in it. Signal dismisses on a read sync for
     * exactly this reason.
     */
    fun conversationsRead(): Observable<String>
}

/** One thread that matched a search, and what matched in it. */
data class SignalSearchHit(val thread: SignalThread, val messages: Int, val snippet: String)

/** One device on the Signal account. Id 1 is the primary; the rest are linked. */
data class SignalDevice(val id: Int, val name: String, val created: Long) {
    val isPrimary: Boolean get() = id == 1
}

/** The Signal account this phone is a device on. */
data class SignalAccount(
    val number: String,
    val selfUuid: String,
    val devices: List<SignalDevice>,
    /** Which of [devices] this phone is, or 0 when it could not be worked out. */
    val thisDeviceId: Int = 0
)

/** A contact's safety number, and whether their key is still the one that was accepted. */
data class SignalIdentity(val safetyNumber: String, val trustLevel: String) {
    val changed: Boolean get() = trustLevel == "UNTRUSTED"
    val verified: Boolean get() = trustLevel == "TRUSTED_VERIFIED"
}
