package com.wanderwildwood.kotozute.glance

import android.content.Context
import android.preference.PreferenceManager
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.model.Conversation
import com.wanderwildwood.kotozute.model.Message
import com.wanderwildwood.kotozute.model.SignalThread
import io.realm.Realm

/**
 * How many messages are unread, for the lock screen, texts and Signal each on their own: texts
 * in the inbox (not archived, not blocked) and Signal messages in threads that are not
 * archived, counted as messages rather than conversations so "3 texts" means three. Glance puts
 * the two on one line, "3 texts · 2 Signal"; either is left out when it is nothing, and so is
 * the whole line when both are.
 */
class UnreadOnLockScreen : GlanceProvider() {

    @Suppress("DEPRECATION")
    override fun enabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, true)

    override fun lines(context: Context): List<Line> {
        val (texts, signal) = Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            val threads = realm.where(Conversation::class.java)
                .equalTo("archived", false)
                .equalTo("blocked", false)
                .findAll()
                .map { it.id }
                .toTypedArray()
            val texts = if (threads.isEmpty()) 0L else realm.where(Message::class.java)
                .`in`("threadId", threads)
                .equalTo("read", false)
                .count()
            val signal = realm.where(SignalThread::class.java)
                .equalTo("archived", false)
                .sum("unread")
                .toLong()
            texts.toInt() to signal.toInt()
        }
        val res = context.resources
        return listOfNotNull(
            texts.takeIf { it > 0 }?.let { Line(res.getQuantityString(R.plurals.lock_screen_unread_texts, it, it)) },
            signal.takeIf { it > 0 }?.let { Line(res.getQuantityString(R.plurals.lock_screen_unread_signal, it, it)) }
        )
    }

    companion object {
        /** The same key the settings switch writes (Preferences.lockScreen). */
        const val KEY = "lockScreen"
    }
}
