package com.wanderwildwood.kotozute.glance

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.model.Conversation
import com.wanderwildwood.kotozute.model.Message
import com.wanderwildwood.kotozute.model.SignalThread
import io.realm.Realm

/**
 * How many conversations are waiting to be read, for the lock screen, texts and Signal each on
 * their own: text conversations in the inbox (not archived, not blocked) with an unread message,
 * and Signal conversations that are not archived with one. A conversation counts once however
 * many messages it is holding, so eight messages in one group are "1 Signal chat", not eight.
 *
 * A muted conversation is left out, as it is from notifications: the lock screen is somewhere a
 * message announces itself, and muting was the choice that it should not. For Signal that is
 * [SignalThread.muted], the flag notifications read. A text conversation is muted when its own
 * notifications have been switched off ([MUTED_TEXT_PREFIX]); switching them all off app-wide
 * is not a choice about any one conversation and leaves the count alone, as it leaves
 * "Keep muted chats archived" alone (`Preferences.keepsArchived`).
 *
 * Glance puts the two on one line, "2 text chats · 1 Signal chat"; either is left out when it is
 * nothing, and so is the whole line when both are.
 */
class UnreadOnLockScreen : GlanceProvider() {

    @Suppress("DEPRECATION")
    override fun enabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, true)

    @Suppress("DEPRECATION")
    override fun lines(context: Context): List<Line> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val (texts, signal) = Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            val threads = realm.where(Conversation::class.java)
                .equalTo("archived", false)
                .equalTo("blocked", false)
                .findAll()
                .map { it.id }
                .toTypedArray()
            val unreadTexts: Map<Long, Int> = if (threads.isEmpty()) emptyMap() else realm.where(Message::class.java)
                .`in`("threadId", threads)
                .equalTo("read", false)
                .findAll()
                .groupingBy { it.threadId }
                .eachCount()
            val texts = chatsWaiting(unreadTexts) { textMuted(prefs, it) }

            val signalThreads = realm.where(SignalThread::class.java)
                .equalTo("archived", false)
                .greaterThan("unread", 0)
                .findAll()
            val muted = signalThreads.filter { it.muted }.map { it.threadKey }.toSet()
            val signal = chatsWaiting(signalThreads.associate { it.threadKey to it.unread }) { it in muted }
            texts to signal
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

        /**
         * A text conversation's own notification switch, `Preferences.notifications(threadId)`,
         * is stored under this and the thread id.
         */
        const val MUTED_TEXT_PREFIX = "notifications_"

        /**
         * How many conversations have something unread and are not muted, from each
         * conversation's unread count. One conversation is one, however many it holds.
         */
        fun <K> chatsWaiting(unreadByChat: Map<K, Int>, muted: (K) -> Boolean): Int =
            unreadByChat.count { (chat, unread) -> unread > 0 && !muted(chat) }

        /** Whether this text conversation's own notifications have been switched off. */
        private fun textMuted(prefs: SharedPreferences, threadId: Long): Boolean {
            val key = MUTED_TEXT_PREFIX + threadId
            return prefs.contains(key) && !prefs.getBoolean(key, true)
        }
    }
}
