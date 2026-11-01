package com.wanderwildwood.kotozute.feature.signal

/**
 * Where phone-number registration had got to, so leaving for the text message that holds the
 * code does not send the person back to the start (forum t/15210, post 152).
 *
 * Kept in a small private store rather than only in the activity: the activity is rebuilt on
 * return when Android needed the memory, and the whole process can go too. Only the code step
 * is kept. A PIN step cannot be resumed after a restart, because the credentials it checks the
 * PIN against live in memory for that attempt alone. The code typed is never kept.
 */
class RegisterProgress(private val store: Store, private val clock: () -> Long = System::currentTimeMillis) {

    interface Store {
        fun read(): Map<String, String>
        fun write(values: Map<String, String>)
    }

    data class Saved(val sessionId: String, val number: String, val askedForACall: Boolean)

    /** Call after the server has sent (or been asked to send) a code. */
    fun codeSent(sessionId: String, number: String, askedForACall: Boolean) {
        store.write(
            mapOf(
                SESSION to sessionId, NUMBER to number,
                CALL to askedForACall.toString(), AT to clock().toString()
            )
        )
    }

    /** What to put back, or null (and the file emptied) if it is missing, stale or unreadable. */
    fun resume(): Saved? {
        val v = store.read()
        val id = v[SESSION]
        val at = v[AT]?.toLongOrNull()
        if (id.isNullOrBlank() || at == null || clock() - at !in 0..WINDOW_MS) {
            if (v.isNotEmpty()) forget()
            return null
        }
        return Saved(id, v[NUMBER].orEmpty(), v[CALL] == "true")
    }

    fun forget() = store.write(emptyMap())

    companion object {
        const val WINDOW_MS = 10 * 60 * 1000L
        private const val SESSION = "session"
        private const val NUMBER = "number"
        private const val CALL = "call"
        private const val AT = "at"
    }
}
