package com.wanderwildwood.kotozute.feature.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wanderwildwood.kotozute.util.Preferences
import dagger.android.AndroidInjection
import javax.inject.Inject

/** The notification's "It's switched on". */
class DuraSpeedAllowedReceiver : BroadcastReceiver() {

    @Inject lateinit var prefs: Preferences

    override fun onReceive(context: Context, intent: Intent) {
        AndroidInjection.inject(this, context)
        DuraSpeed.markAllowed(context, prefs)
    }
}
