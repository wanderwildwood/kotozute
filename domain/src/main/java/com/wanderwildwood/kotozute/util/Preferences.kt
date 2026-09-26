/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.wanderwildwood.kotozute.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.f2prateek.rx.preferences2.Preference
import com.f2prateek.rx.preferences2.RxSharedPreferences
import com.wanderwildwood.kotozute.common.util.extensions.versionCode
import io.reactivex.Observable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Preferences @Inject constructor(
    context: Context,
    private val rxPrefs: RxSharedPreferences,
    private val sharedPrefs: SharedPreferences
) {

    companion object {
        const val NIGHT_MODE_SYSTEM = 0
        const val NIGHT_MODE_OFF = 1
        const val NIGHT_MODE_ON = 2
        const val NIGHT_MODE_AUTO = 3

        const val TEXT_SIZE_SMALL = 0
        const val TEXT_SIZE_NORMAL = 1
        const val TEXT_SIZE_LARGE = 2
        const val TEXT_SIZE_LARGER = 3
        const val TEXT_SIZE_SUPER = 4

        const val NOTIFICATION_PREVIEWS_ALL = 0
        const val NOTIFICATION_PREVIEWS_NAME = 1
        const val NOTIFICATION_PREVIEWS_NONE = 2

        const val NOTIFICATION_ACTION_NONE = 0
        const val NOTIFICATION_ACTION_ARCHIVE = 1
        const val NOTIFICATION_ACTION_DELETE = 2
        const val NOTIFICATION_ACTION_BLOCK = 3
        const val NOTIFICATION_ACTION_CALL = 4
        const val NOTIFICATION_ACTION_READ = 5
        const val NOTIFICATION_ACTION_REPLY = 6
        const val NOTIFICATION_ACTION_SPEAK = 7

        const val SEND_DELAY_NONE = 0
        const val SEND_DELAY_SHORT = 1
        const val SEND_DELAY_MEDIUM = 2
        const val SEND_DELAY_LONG = 3

        const val SWIPE_ACTION_NONE = 0
        const val SWIPE_ACTION_ARCHIVE = 1
        const val SWIPE_ACTION_DELETE = 2
        const val SWIPE_ACTION_BLOCK = 3
        const val SWIPE_ACTION_CALL = 4
        const val SWIPE_ACTION_READ = 5
        const val SWIPE_ACTION_UNREAD = 6
        const val SWIPE_ACTION_SPEAK = 7

        const val BLOCKING_MANAGER_QKSMS = 0
        const val BLOCKING_MANAGER_CC = 1
        const val BLOCKING_MANAGER_SIA = 2
        const val BLOCKING_MANAGER_CB = 3

        const val MESSAGE_LINK_HANDLING_BLOCK = 0
        const val MESSAGE_LINK_HANDLING_ALLOW = 1
        const val MESSAGE_LINK_HANDLING_ASK = 2

        const val CONVERSATION_FILTER_ALL = 0
        const val CONVERSATION_FILTER_GROUPS = 1
        const val CONVERSATION_FILTER_UNKNOWN = 2
    }

    // Internal
    val didSetReferrer = rxPrefs.getBoolean("didSetReferrer", false)
    val night = rxPrefs.getBoolean("night", false)
    val canUseSubId = rxPrefs.getBoolean("canUseSubId", true)
    val version = rxPrefs.getInteger("version", context.versionCode)
    val changelogVersion = rxPrefs.getInteger("changelogVersion", context.versionCode)
    val hasAskedForNotificationPermission = rxPrefs.getBoolean("hasAskedForNotificationPermission", false)
    val backupDirectory = rxPrefs.getObject("backupDirectory", Uri.EMPTY, UriPreferenceConverter())
    @Deprecated("This should only be accessed when migrating to @blockingManager")
    val sia = rxPrefs.getBoolean("sia", false)

    // User configurable
    val sendAsGroup = rxPrefs.getBoolean("sendAsGroup", true)
    val nightMode = rxPrefs.getInteger("nightMode", when (Build.VERSION.SDK_INT >= 29) {
        true -> NIGHT_MODE_SYSTEM
        false -> NIGHT_MODE_OFF
    })
    /**
     * The newest version the background check has already told someone about.
     *
     * Stops the same release being announced every six hours for as long as it goes uninstalled.
     * A version rather than a flag, so the next release after an ignored one still gets said.
     */
    val updateNotified = rxPrefs.getString("updateNotified", "")

    val nightStart = rxPrefs.getString("nightStart", "18:00")
    val nightEnd = rxPrefs.getString("nightEnd", "6:00")
    val black = rxPrefs.getBoolean("black", true)
    val autoColor = rxPrefs.getBoolean("autoColor", true)
    val systemFont = rxPrefs.getBoolean("systemFont", true)
    val desktopSyncEnabled = rxPrefs.getBoolean("desktopSyncEnabled", false)
    val desktopSyncToken = rxPrefs.getString("desktopSyncToken", "")
    // Was "Tailscale only"; the key keeps its old name so nobody's setting resets.
    val desktopSyncVpnOnly = rxPrefs.getBoolean("desktopSyncTailscaleOnly", true)

    // Signal. Off unless this phone is actually on the account: the toggle is the result of
    // a working setup, not a switch that can be flipped into a broken state.
    val signalEnabled = rxPrefs.getBoolean("signalEnabled", false)

    /**
     * Whether to talk to Signal's **staging** servers instead of the real ones.
     *
     * ⛔ **A debug affordance.** Staging is a separate world with its own accounts: an account
     * registered there cannot exchange a message with a single real person, while looking
     * entirely registered from the inside. `SignalNetworkConfig` refuses to honour this in a
     * release build, so the flag can be set and simply will not take effect there.
     *
     * ⚠ It has to persist, which is why it is a preference rather than a field set on the
     * register screen. The environment decides which servers *everything* uses, so a phone
     * that registered against staging and came back up pointing at production would hold
     * staging credentials against real hosts -- which fails as authentication errors rather
     * than as anything that names the cause.
     */
    val signalStaging = rxPrefs.getBoolean("signalStaging", false)

    /** When Signal was last reachable, for the honest "last synced" line. */
    val signalLastSync = rxPrefs.getLong("signalLastSync", 0L)

    /**
     * When this phone last asked the primary to send its contacts.
     *
     * ⚠ **The ask is not free, and it is not free on this phone.** A linked device's contacts
     * request makes the primary run a full contacts sync *immediately*, bypassing the cooldown
     * it applies to its own: `SyncMessageProcessor` answers `Request.Type.CONTACTS` with
     * `MultiDeviceContactUpdateJob(true)` -- the `true` is `forceSync`, and without it that job
     * refuses to run twice inside `FULL_SYNC_TIME`, six hours. So asking on every process start
     * means building and uploading the whole contact list from somebody's other phone every
     * time Android restarts this one, which on a phone built to sleep is often.
     */
    val signalLastContactRequest = rxPrefs.getLong("signalLastContactRequest", 0L)

    /**
     * When the repeated-use Signal keys were last replaced because something would not decrypt.
     *
     * Signal keeps the same value (`lastForcedPreKeyRefresh`) for the same reason: a forced
     * rotation is only allowed once an hour when the keys turn out to be fine, so a run of
     * undecryptable envelopes cannot become a run of rotations.
     */
    val signalLastForcedKeyRotation = rxPrefs.getLong("signalLastForcedKeyRotation", 0L)

    /**
     * When key transparency should next verify this account's own identifiers.
     *
     * Signal's `SignalStore.misc.nextKeyTransparencyTime`, on the same cadence: **seven days**
     * plus a random 0-8 hours, so a household's devices do not all ask at the same instant.
     *
     * ⚠ Written *before* the check runs, exactly as `CheckKeyTransparencyJob.doRun` does. A
     * check that set its next time only on success would retry on every pass after a crash,
     * which turns a failed verification into a hot loop against the server.
     */
    val signalNextKeyTransparencyCheck = rxPrefs.getLong("signalNextKeyTransparencyCheck", 0L)

    /**
     * A key transparency check has failed, and whether the person has been told.
     *
     * Two values rather than one because Signal treats the first failure as probably-stale
     * local data rather than a lying server: it refreshes what the account says, re-sends what
     * this device says, and checks again a day later. Only a **second** failure is worth
     * putting in front of somebody (`CheckKeyTransparencyJob.doRun`).
     */
    val signalKeyTransparencyFailed = rxPrefs.getBoolean("signalKeyTransparencyFailed", false)
    val signalKeyTransparencyFailureSeen = rxPrefs.getBoolean("signalKeyTransparencyFailureSeen", false)

    /**
     * The phone-number identity is still using keys another device made for it.
     *
     * Set when a number change arrives, because the keys it carries were generated by the
     * primary and sent through a sync -- Signal's own comment is *"Rotate the primary-generated
     * keys as soon as possible so we don't rely on them long-term."* Cleared only once a
     * rotation has actually happened.
     *
     * A stored flag rather than a thing attempted once, because attempting it once is what this
     * was: a rotation that did not go was reported as done and nothing came back for it. The
     * periodic pass cannot notice on its own -- its clock is the stored key's age, and the key
     * the primary just sent is brand new. Upstream keeps the same flag
     * (`forcePniSignedPreKeyRotation`, set in `SyncMessageProcessor` and cleared in
     * `PreKeysSyncJob`) for the same reason.
     */
    val signalPniRotationOwed = rxPrefs.getBoolean("signalPniRotationOwed", false)

    /** Whether the one-time battery-optimisation ask has been made; see `BackgroundRunning`. */
    val signalAskedBackground = rxPrefs.getBoolean("signalAskedBackground", false)

    /**
     * The storage record ids whose muted and archived this phone has already applied.
     *
     * Signal applies a storage record only when its id is new to it
     * (`StorageSyncJob` reads just `idDifference.remoteOnlyIds`), and an id changes whenever
     * the record's content does. Re-applying every record on every read instead is what made
     * an unarchive on the phone come undone at the next launch: the account still said
     * archived, unchanged, and it was said again. Lost or empty, every record is applied once,
     * which is how it behaved before this existed.
     */
    val signalAppliedStateRecords = rxPrefs.getStringSet("signalAppliedStateRecords", emptySet())

    /**
     * Whether this device still owes the server its first full set of pre keys.
     *
     * The registration and linking requests carry only a **signed** key and a **last-resort**
     * Kyber key. The one-time keys go up separately, once, right afterwards -- and if that one
     * attempt fails there is nothing behind it. The device keeps working, so nothing looks
     * wrong; every new session opened with it simply falls back to the last-resort key and
     * loses the forward secrecy the one-time keys exist to provide.
     *
     * ⚠ **The periodic pass cannot notice**, for exactly the reason [signalPniRotationOwed]
     * cannot: `PreKeyUploader.maintain` is gated on the *age* of the stored signed key, and on
     * a device that has just registered that key is minutes old. So maintenance answers
     * "not due" and uploads nothing for the length of the refresh interval -- days -- while
     * the account has no one-time keys published at all.
     *
     * ⚠ Set **before** the attempt and cleared only on success, so a process that dies
     * mid-upload still owes it. The opposite order would mark the work done by having started.
     */
    val signalPreKeysOwed = rxPrefs.getBoolean("signalPreKeysOwed", false)

    /**
     * Whether Desktop Sync serves over TLS with a certificate this phone made for itself.
     *
     * ⚠ **Off by default, and that is not timidity.** Turning it on changes the scheme, so a
     * browser already paired needs the link again, and every visit begins with a full-page
     * certificate warning that has to be clicked through -- the browser correctly reporting
     * that nothing vouches for this phone but itself.
     *
     * What it buys is the one thing plain HTTP cannot: a **secure context**, which is what a
     * browser requires before it will give a page the microphone. Recording a voice note from
     * the desktop is impossible without it, whatever the page does.
     */
    val desktopSyncTls = rxPrefs.getBoolean("desktopSyncTls", false)

    /**
     * Whether the server says this account's primary device has gone idle.
     *
     * The warning that comes before a linked device is unlinked for it. Signal keeps the same
     * fact as `hasInactivePrimaryDeviceAlert` and shows it only on a linked device.
     */
    val signalPrimaryIdle = rxPrefs.getBoolean("signalPrimaryIdle", false)
    /**
     * Why the server last refused this device, or blank if it has not.
     *
     * Persisted rather than held in memory because the condition is permanent until someone
     * acts on it: once the account owner removes this linked device the credentials never
     * come back, and a reason that lived only in a state object would vanish on the next
     * restart -- leaving a phone that silently receives nothing and says nothing about it.
     */
    val signalRejected = rxPrefs.getString("signalRejected", "")
    /**
     * Whether Signal's own status check last said Signal is down. Signal keeps the same fact
     * as `TextSecurePreferences.getServiceOutage`, set by `ServiceOutageDetectionJob`.
     */
    val signalServiceOutage = rxPrefs.getBoolean("signalServiceOutage", false)
    /** When that check last got an answer. Signal asks at most once a minute. */
    val signalOutageCheckedAt = rxPrefs.getLong("signalOutageCheckedAt", 0L)
    /**
     * Whether reading a Signal message tells the sender. Off by default: Signal has its
     * own read-receipt setting which cannot be read from here, and sending them when the
     * user has chosen not to would share something they declined to share.
     */
    val signalReadReceipts = rxPrefs.getBoolean("signalReadReceipts", false)

    /**
     * Whether Signal threads sit in the one conversation list alongside SMS. On by default:
     * a conversation is a conversation, and which rail it arrived on is the badge's job to
     * say. Off keeps two lists, crossed by the badge in each list's toolbar, for anyone who
     * would rather not have them mixed.
     */
    val signalWeave = rxPrefs.getBoolean("signalWeave", true)

    /**
     * With the lists kept apart, whether the app opens on the Signal one. Only read while
     * [signalWeave] is off: woven, there is one list and nothing to choose between.
     */
    val signalOpensFirst = rxPrefs.getBoolean("signalOpensFirst", false)

    /** The Signal list's own tab, 0 all or 1 groups. Kept apart from the SMS list's. */
    val signalConversationFilter = rxPrefs.getInteger("signalConversationFilter", 0)

    /**
     * Whether the offer to fetch the account's contact list has been made.
     *
     * Asked once and never again, whatever the answer. A linked device's first impression is
     * a list of service ids where names should be, because a modern Signal keeps its contacts
     * somewhere this phone will not read unasked -- reading it fetches the account's own key
     * material, which is not something to do on a guess. Offering it once is the difference
     * between a choice and a setting nobody finds; offering it twice is nagging.
     */
    val signalContactsOffered = rxPrefs.getBoolean("signalContactsOffered", false)

    /**
     * Whether to hold the Signal connection open in the background.
     *
     * Off, the stream lives only as long as the app's process does -- which is however long
     * Android feels like, so a message arrives when something next wakes the app rather than
     * when it was sent. A periodic sync bounds that; it does not remove it.
     *
     * On, a foreground service owns the connection and messages arrive as they are sent. The
     * price is a permanent notification, which on this phone is a real cost and the reason
     * this is a choice rather than a default.
     */
    val signalKeepConnected = rxPrefs.getBoolean("signalKeepConnected", false)

    /**
     * Signal threads tied to an SMS conversation by hand, as JSON: {"direct:<aci>": <id>}.
     *
     * The badge finds the other rail by phone number and only by phone number, because
     * two rails on two different numbers may be two conversations on purpose. That leaves
     * the case where Signal shares no number at all -- a contact with phone-number privacy
     * on gives an ACI and nothing else -- and then there is no way across at all, in
     * either direction, however obvious the pairing is to the person reading them.
     *
     * This is that person saying so. Nothing here is inferred.
     *
     * A preference rather than a Realm object: it is a handful of small pairs, and a
     * schema migration is a far larger risk than that is worth.
     */
    val signalThreadLinks = rxPrefs.getString("signalThreadLinks", "{}")
    val textSize = rxPrefs.getInteger("textSize", TEXT_SIZE_LARGE)
    val blockingManager = rxPrefs.getInteger("blockingManager", BLOCKING_MANAGER_QKSMS)
    val drop = rxPrefs.getBoolean("drop", false)
    val blockNonContacts = rxPrefs.getBoolean("blockNonContacts", false)
    val silentNotContact = rxPrefs.getBoolean("silentNotContact", false)
    val notifAction1 = rxPrefs.getInteger("notifAction1", NOTIFICATION_ACTION_READ)
    val notifAction2 = rxPrefs.getInteger("notifAction2", NOTIFICATION_ACTION_REPLY)
    val notifAction3 = rxPrefs.getInteger("notifAction3", NOTIFICATION_ACTION_NONE)
    val qkreply = rxPrefs.getBoolean("qkreply", Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
    val qkreplyTapDismiss = rxPrefs.getBoolean("qkreplyTapDismiss", true)
    val sendDelay = rxPrefs.getInteger("sendDelay", SEND_DELAY_NONE)
    val swipeRight = rxPrefs.getInteger("swipeRight", SWIPE_ACTION_ARCHIVE)
    val swipeLeft = rxPrefs.getInteger("swipeLeft", SWIPE_ACTION_ARCHIVE)
    val autoEmoji = rxPrefs.getBoolean("autoEmoji", true)
    val delivery = rxPrefs.getBoolean("delivery", false)
    val readReceipts = rxPrefs.getBoolean("readReceipts", false)
    val signature = rxPrefs.getString("signature", "")
    val unicode = rxPrefs.getBoolean("unicode", false)
    val mobileOnly = rxPrefs.getBoolean("mobileOnly", false)
    val autoDelete = rxPrefs.getInteger("autoDelete", 0)
    val longAsMms = rxPrefs.getBoolean("longAsMms", false)
    val mmsSize = rxPrefs.getInteger("mmsSize", -1)
    val messageLinkHandling = rxPrefs.getInteger("messageLinkHandling", MESSAGE_LINK_HANDLING_ASK)
    val disableScreenshots = rxPrefs.getBoolean("disableScreenshots", false)
    val logging = rxPrefs.getBoolean("logging", false)
    val unreadAtTop = rxPrefs.getBoolean("unreadAtTop", false)
    /** Whether the unread count is handed to Glance for the lock screen. */
    val lockScreen = rxPrefs.getBoolean("lockScreen", true)
    val conversationFilter = rxPrefs.getInteger("conversationFilter", CONVERSATION_FILTER_ALL)

    init {
        // Migrate from old night mode preference to new one, now that we support android Q night mode
        val nightModeSummary = rxPrefs.getInteger("nightModeSummary")
        if (nightModeSummary.isSet) {
            nightMode.set(when (nightModeSummary.get()) {
                0 -> NIGHT_MODE_OFF
                1 -> NIGHT_MODE_ON
                2 -> NIGHT_MODE_AUTO
                else -> NIGHT_MODE_OFF
            })
            nightModeSummary.delete()
        }
    }

    /**
     * Returns a stream of preference keys for changing preferences
     */
    val keyChanges: Observable<String> = Observable.create<String> { emitter ->
        // Making this a lambda would cause it to be GCd
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null)
                emitter.onNext(key)
        }

        emitter.setCancellable {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }

        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
    }.share()

    fun theme(
        recipientId: Long = 0,
        default: Int = rxPrefs.getInteger("theme", 0xFF000000.toInt()).get()
    ): Preference<Int> {
        return when (recipientId) {
            0L -> rxPrefs.getInteger("theme", 0xFF000000.toInt())
            else -> rxPrefs.getInteger("theme_$recipientId", default)
        }
    }

    fun notifications(threadId: Long = 0): Preference<Boolean> {
        val default = rxPrefs.getBoolean("notifications", true)

        return when (threadId) {
            0L -> default
            else -> rxPrefs.getBoolean("notifications_$threadId", default.get())
        }
    }

    fun notificationPreviews(threadId: Long = 0): Preference<Int> {
        val default = rxPrefs.getInteger("notification_previews", 0)

        return when (threadId) {
            0L -> default
            else -> rxPrefs.getInteger("notification_previews_$threadId", default.get())
        }
    }

    fun wakeScreen(threadId: Long = 0): Preference<Boolean> {
        val default = rxPrefs.getBoolean("wake", false)

        return when (threadId) {
            0L -> default
            else -> rxPrefs.getBoolean("wake_$threadId", default.get())
        }
    }

    fun vibration(threadId: Long = 0): Preference<Boolean> {
        val default = rxPrefs.getBoolean("vibration", true)

        return when (threadId) {
            0L -> default
            else -> rxPrefs.getBoolean("vibration$threadId", default.get())
        }
    }

    fun ringtone(threadId: Long = 0): Preference<String> {
        val default = rxPrefs.getString("ringtone", Settings.System.DEFAULT_NOTIFICATION_URI.toString())

        return when (threadId) {
            0L -> default
            else -> rxPrefs.getString("ringtone_$threadId", default.get())
        }
    }
}
