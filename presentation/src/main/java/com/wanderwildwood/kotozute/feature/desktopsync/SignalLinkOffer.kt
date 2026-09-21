package com.wanderwildwood.kotozute.feature.desktopsync

/**
 * The Signal link code, for as long as it is worth anything, so a computer can show it.
 *
 * ⚠ This exists because of an optical fact rather than a software one: linking is done by
 * one Signal scanning another's QR with a camera, and a phone cannot photograph its own
 * panel. Somebody already running Signal on their Kompakt therefore had no way in at all --
 * the only other route on offer registers the number afresh, which deregisters Signal
 * everywhere else and, on that phone, restarts thirty days of RCS removal. Signal's own
 * `sgnl://linkdevice` handler is no help: it drops the link and opens a screen that only
 * scans.
 *
 * What this phone does have is a page it already serves to the reader's own computer. Put
 * the code on that page, and the phone's own Signal can read it off the monitor: no second
 * phone, no SIM moved, nothing about the account changed.
 *
 * Held in memory and nowhere else, and only while the link screen is open. The code is a
 * live offer to join the account -- whoever redeems it first becomes a device on it -- so
 * it is never written down, never logged, and stops existing the moment that screen closes.
 * It is served behind the same token as the whole message history, which is a stricter
 * secret than this one and is already trusted with far more.
 */
object SignalLinkOffer {

    @Volatile
    private var code: String? = null

    /** What the link screen is currently showing, or null when nothing is being linked. */
    val current: String? get() = code

    /** Offer the code to the computer, or, with null, stop offering it. */
    fun offer(url: String?) {
        code = url
    }
}
