package com.wanderwildwood.kotozute.glance

import android.content.Context
import android.preference.PreferenceManager
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.model.Conversation
import com.wanderwildwood.kotozute.model.Message
import com.wanderwildwood.kotozute.model.SignalThread
import io.realm.Realm

/**
 * How many messages are unread, for the lock screen: texts in the inbox (not archived, not
 * blocked) and Signal messages in threads that are not archived, counted as messages rather
 * than conversations so "3 messages" means three. Nothing when there are none.
 */
class UnreadOnLockScreen : GlanceProvider() {

    @Suppress("DEPRECATION")
    override fun enabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, true)

    override fun lines(context: Context): List<Line> {
        val count = Realm.getDefaultInstance().use { realm ->
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
            texts + signal
        }.toInt()
        if (count <= 0) return emptyList()
        return listOf(Line(context.resources.getQuantityString(R.plurals.lock_screen_unread_messages, count, count)))
    }

    companion object {
        /** The same key the settings switch writes (Preferences.lockScreen). */
        const val KEY = "lockScreen"
    }
}
