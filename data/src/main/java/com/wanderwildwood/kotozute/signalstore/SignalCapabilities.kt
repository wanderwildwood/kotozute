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
 * All six, because a linked device is expected to speak all of them and the server refuses the
 * link outright with `MissingCapability` otherwise. Where the app does not yet handle one, that
 * is a gap to close rather than a flag to unset.
 */
internal object SignalCapabilities {

    private const val STORAGE = true
    private const val VERSIONED_EXPIRATION_TIMER = true
    private const val ATTACHMENT_BACKFILL = true
    private const val SPQR = true
    private const val USERNAME_CHANGE_SYNC_MESSAGE = true
    private const val OPTIONAL_PHONE_NUMBER = true

    /** For the registration call that links this device. */
    fun forLinking(): org.signal.network.api.RegistrationApiV2.AccountAttributes.Capabilities =
        org.signal.network.api.RegistrationApiV2.AccountAttributes.Capabilities(
            STORAGE,
            VERSIONED_EXPIRATION_TIMER,
            ATTACHMENT_BACKFILL,
            SPQR,
            USERNAME_CHANGE_SYNC_MESSAGE,
            OPTIONAL_PHONE_NUMBER
        )

    /** For the running device saying the same thing again. */
    fun forRefresh(): org.whispersystems.signalservice.api.account.AccountAttributes.Capabilities =
        org.whispersystems.signalservice.api.account.AccountAttributes.Capabilities(
            STORAGE,
            VERSIONED_EXPIRATION_TIMER,
            ATTACHMENT_BACKFILL,
            SPQR,
            USERNAME_CHANGE_SYNC_MESSAGE,
            OPTIONAL_PHONE_NUMBER
        )
}
