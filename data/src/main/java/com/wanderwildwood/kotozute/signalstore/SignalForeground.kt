package com.wanderwildwood.kotozute.signalstore

/**
 * Whether anything of this app is on screen.
 *
 * This app's `AppForegroundObserver.isForegrounded()`: one boolean, for the decisions upstream
 * makes on the same fact.
 *
 * ⚠ Not `ProcessLifecycleOwner`, which would mean a lifecycle dependency in this module for a
 * single flag. The presentation layer already watches activity lifecycle and sets this; the
 * default is **false**, so a process with no screen open is treated as in the background --
 * the conservative answer for every reader.
 *
 * Public only because the application class that sets it lives in another module; nothing
 * else has any business writing it.
 *
 * Its first reader was the keepalive sender in [SignalSocketHealthMonitor], which went when
 * Signal removed manual keepalives (upstream 37f8b149dd). Its reader now is the limit on
 * storage reads nobody asked for, which upstream also decides on
 * `AppForegroundObserver.isForegrounded()`: see
 * [com.wanderwildwood.kotozute.repository.BackgroundStorageSync].
 */
object SignalForeground {

    @Volatile
    private var visible = false

    fun onScreen(): Boolean = visible

    fun set(value: Boolean) {
        visible = value
    }
}
