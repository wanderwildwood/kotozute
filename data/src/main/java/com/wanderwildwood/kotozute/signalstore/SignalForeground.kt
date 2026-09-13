package com.wanderwildwood.kotozute.signalstore

/**
 * Whether anything of this app is on screen.
 *
 * One boolean, because one decision needs it: how often to send a keepalive. Signal chooses
 * its cadence per iteration from `AppForegroundObserver.isForegrounded()` -- thirty seconds
 * while somebody is looking, sixty when nobody is -- and a keepalive is a radio wake, so on a
 * phone whose whole point is to sit still that is half the wakes for most of the day.
 *
 * ⚠ Not `ProcessLifecycleOwner`, which would mean a lifecycle dependency in this module for a
 * single flag. The presentation layer already watches activity lifecycle and sets this; the
 * default is **false**, so a caller that never sets it gets the conservative cadence rather
 * than the busy one.
 *
 * Public only because the application class that sets it lives in another module; nothing
 * else has any business writing it.
 */
object SignalForeground {

    @Volatile
    private var visible = false

    fun onScreen(): Boolean = visible

    fun set(value: Boolean) {
        visible = value
    }
}
