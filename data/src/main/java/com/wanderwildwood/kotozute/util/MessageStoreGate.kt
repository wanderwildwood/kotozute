package com.wanderwildwood.kotozute.util

/**
 * Set once, at startup, when the app has refused to open its message database: the phone's
 * keystore would not give up the key, and the app does not keep messages without one.
 *
 * While it is set the app shows one screen that says so, and the database in use is an empty
 * one held in memory, so nothing that still runs (a broadcast receiver, a worker) crashes and
 * nothing it does reaches the message file.
 *
 * It lives in this module because the one thing that must not happen in that state is here:
 * Signal delivers a message to a device once. A message taken off the socket and written to a
 * database that is gone when the process ends would be lost for good, so
 * [com.wanderwildwood.kotozute.signalstore.SignalConnection.connect] does not connect while
 * this is set. Texts are not at risk in the same way: an arriving text is written to the
 * phone's own message store first.
 */
object MessageStoreGate {
    @Volatile
    var blocked: Boolean = false
}
