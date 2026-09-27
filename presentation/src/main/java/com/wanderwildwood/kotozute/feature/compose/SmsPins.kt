package com.wanderwildwood.kotozute.feature.compose

import com.wanderwildwood.kotozute.model.Message
import com.wanderwildwood.kotozute.util.Preferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pinned messages on the text side, as Signal pins them: at most three a conversation, the
 * oldest letting go, a banner naming the newest. Texts have no account to tell, so the pins
 * are kept on this phone -- and beside the messages rather than on them, because the message
 * rows are rebuilt from Android's own store whenever the app syncs, and a field on them would
 * go with it. A message is named by its table and Android's id for it, which a resync keeps.
 */
object SmsPins {

    private const val MAX = 3

    fun keyOf(message: Message): String = "${message.type}:${message.contentId}"

    private fun all(prefs: Preferences): JSONObject =
        runCatching { JSONObject(prefs.smsPinnedMessages.get()) }.getOrElse { JSONObject() }

    /** The pinned keys in [threadId], newest pin first. */
    fun pinned(prefs: Preferences, threadId: Long): List<String> {
        val list = all(prefs).optJSONArray(threadId.toString()) ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }
            .sortedByDescending { it.optLong("at") }
            .map { it.getString("key") }
    }

    fun isPinned(prefs: Preferences, threadId: Long, key: String) = key in pinned(prefs, threadId)

    fun pin(prefs: Preferences, threadId: Long, key: String) {
        val kept = pinned(prefs, threadId).filter { it != key }.take(MAX - 1)
        write(prefs, threadId, listOf(key) + kept)
    }

    fun unpin(prefs: Preferences, threadId: Long, key: String) =
        write(prefs, threadId, pinned(prefs, threadId).filter { it != key })

    /** [keys] newest first; stored with times that keep that order. */
    private fun write(prefs: Preferences, threadId: Long, keys: List<String>) {
        val root = all(prefs)
        val now = System.currentTimeMillis()
        if (keys.isEmpty()) root.remove(threadId.toString())
        else root.put(threadId.toString(), JSONArray(keys.mapIndexed { i, k ->
            JSONObject().put("key", k).put("at", now - i)
        }))
        prefs.smsPinnedMessages.set(root.toString())
    }
}
