package com.wanderwildwood.kotozute.signalstore

/**
 * What this build promises the server it can do.
 *
 * Declared in two places that must not drift: once when the device links, and again on every
 * run of the app, because a capability is a claim by this *build* rather than a fact about the
 * account. They were separate literals, which is the arrangement where a capability gained in
 * one place and not the other is not a compile error and not a visible fault -- just peers
 * quietly not using a message shape this device can read.
 *
 * The two APIs want the same six booleans in different types, so this holds the booleans.
 *
 *   storage                   -- the encrypted storage service (contacts, groups)
 *   versionedExpirationTimer  -- versioned disappearing-message timers
 *   attachmentBackfill        -- answering backfill requests for attachments
 *   spqr                      -- the sparse post-quantum ratchet
 *   usernameChangeSyncMessage -- username-change sync messages
 *   optionalPhoneNumber       -- working without a visible phone number
 *
 * ⚠ They were all six hardcoded true, on the stated grounds that the server refuses the link
 * with `MissingCapability` otherwise. That is not so, and upstream is the proof: its linked
 * registration passes `AppCapabilities.getCapabilities(false)` and its `optionalPhoneNumber`
 * is false at every ordinary call site. A capability is a promise about what this build can
 * be sent, and promising one it cannot keep does not close the gap -- it just moves the
 * failure to the peer who believed it.
 *
 * Four are genuinely true of this build and stay true. The other two do not:
 *
 * - **optionalPhoneNumber** is false, as it is everywhere in Signal except an account that is
 *   actually numberless. This build is not one: [DeviceLinker] stores the provisioning
 *   message's number as the account's number and dereferences the phone-number fields
 *   outright, so an account without one does not get through linking at all.
 * - **storage** is not a constant in Signal either way. `AppCapabilities` takes it as a
 *   parameter -- "another way of asking if the user has set a Signal PIN" -- and the linked
 *   registration passes false because at that moment the device has not been told anything
 *   about the account yet, while the periodic refresh passes what it has since learned. So it
 *   is answered here the same way: false while linking, and afterwards whether this device
 *   actually holds the account's storage key.
 */

internal object SignalCapabilities {

    private const val VERSIONED_EXPIRATION_TIMER = true
    private const val ATTACHMENT_BACKFILL = true
    private const val SPQR = true
    private const val USERNAME_CHANGE_SYNC_MESSAGE = true

    /**
     * Working without a visible phone number. **False**, and unconditionally so.
     *
     * Signal's `AppCapabilities` writes `optionalPhoneNumber = false` as a literal and only an
     * account that is genuinely numberless ever flips it. This build cannot be one.
     */
    internal const val OPTIONAL_PHONE_NUMBER = false

    /**
     * For the registration call that links this device.
     *
     * `storage` is false here for the reason upstream's is: nothing has been learned about the
     * account yet, so there is nothing to base the claim on. The refresh that follows corrects
     * it.
     */
    fun forLinking(): org.signal.network.api.RegistrationApiV2.AccountAttributes.Capabilities =
        org.signal.network.api.RegistrationApiV2.AccountAttributes.Capabilities(
            false,
            VERSIONED_EXPIRATION_TIMER,
            ATTACHMENT_BACKFILL,
            SPQR,
            USERNAME_CHANGE_SYNC_MESSAGE,
            OPTIONAL_PHONE_NUMBER
        )

    /**
     * For the registration call that registers this phone as an account of its own.
     *
     * ⚠ `storage` is **true** here, where [forLinking] has it false, and the difference is not
     * a stylistic one. Upstream draws exactly this line: its primary registration passes
     * `AppCapabilities.getCapabilities(true)` and its linked registration passes `false`
     * (`RegistrationRepository:448` against `:533`).
     *
     * The reason the linked case says false is that a device joining an account has been told
     * nothing about it yet and has no storage key to claim. A device *registering* an account
     * is the opposite case: it generates the account entropy pool itself, in the same call,
     * so the storage key is known before the claim goes up rather than after. Saying false
     * here would be a device disclaiming a service it is about to be the only user of.
     */
    fun forRegistering(): org.signal.network.api.RegistrationApiV2.AccountAttributes.Capabilities =
        org.signal.network.api.RegistrationApiV2.AccountAttributes.Capabilities(
            true,
            VERSIONED_EXPIRATION_TIMER,
            ATTACHMENT_BACKFILL,
            SPQR,
            USERNAME_CHANGE_SYNC_MESSAGE,
            OPTIONAL_PHONE_NUMBER
        )

    /**
     * For the running device saying the same thing again.
     *
     * @param storage whether this device holds the account's storage key, which is the nearest
     *   true thing to upstream's "has the user set a PIN" -- both are asking whether the
     *   encrypted storage service is in use for this account.
     */
    fun forRefresh(storage: Boolean): org.whispersystems.signalservice.api.account.AccountAttributes.Capabilities =
        org.whispersystems.signalservice.api.account.AccountAttributes.Capabilities(
            storage,
            VERSIONED_EXPIRATION_TIMER,
            ATTACHMENT_BACKFILL,
            SPQR,
            USERNAME_CHANGE_SYNC_MESSAGE,
            OPTIONAL_PHONE_NUMBER
        )
}
