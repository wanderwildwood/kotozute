package com.wanderwildwood.kotozute.feature.signal

import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * A notification channel that asks for the phone's notification light.
 *
 * Android blinks the light only for a channel made with lights on, and a channel's settings are
 * frozen once it exists: turning lights on in code for a channel already on somebody's phone does
 * nothing. So a channel that was made without the light moves to a new id. What the person chose
 * for the old one (its importance, off included, its sound, vibration, lock screen and badge)
 * comes along, and the old one goes.
 *
 * No color is asked for: the system picks, and where there is a setting for a color per app
 * (LineageOS, so KompaktOS) that setting decides. A phone that drives no light is not changed by
 * any of this. Edit this file in /opt/projects/shared-notifylight, then copy.sh; never an app's
 * copy.
 */
object LightChannel {

    /**
     * Makes [fresh] with the light on, taking over from the channel [old] if that one is still
     * there. Safe to call every time a notification is about to be posted.
     */
    fun ensure(manager: NotificationManager, old: String?, fresh: NotificationChannel) {
        val was = old?.let { manager.getNotificationChannel(it) }
        if (was != null && manager.getNotificationChannel(fresh.id) == null) {
            fresh.importance = was.importance
            fresh.setSound(was.sound, was.audioAttributes)
            fresh.enableVibration(was.shouldVibrate())
            // Setting a pattern turns vibration on, so only where it is on.
            if (was.shouldVibrate()) was.vibrationPattern?.let { fresh.vibrationPattern = it }
            fresh.lockscreenVisibility = was.lockscreenVisibility
            fresh.setShowBadge(was.canShowBadge())
        }
        fresh.enableLights(true)
        manager.createNotificationChannel(fresh)
        if (was != null) manager.deleteNotificationChannel(was.id)
    }
}
