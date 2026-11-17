package com.wanderwildwood.kotozute.common.util

import java.util.UUID

/**
 * Which attachment addresses the compose screen takes from an intent.
 *
 * The compose screen is open to other apps (Share, "send to"), and it reads what it is handed with
 * this app's own rights. So an address another app hands over is taken only when it is a
 * content:// address of some other app: never a file:// path (which could name this app's own
 * private files, the message store or its key) and never one of this app's own content providers.
 * This app's own screens (forward, a scheduled message) mark their intents with [token], a value
 * that lives only in this process, and are taken as they are.
 */
object SharedAttachments {

    const val EXTRA_OWN = "com.wanderwildwood.kotozute.OWN_ATTACHMENTS"

    /** Made fresh at each start; another app cannot know it. */
    val token: String = UUID.randomUUID().toString()

    /** Whether an address handed over by another app may be attached. */
    fun mayTake(scheme: String?, authority: String?, ownPackage: String): Boolean {
        if (!scheme.equals("content", ignoreCase = true)) return false
        val a = authority?.lowercase() ?: return false
        val own = ownPackage.lowercase()
        return a.isNotEmpty() && a != own && !a.startsWith("$own.") && !a.contains("@")
    }
}
