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
 *
 * ⚠ Nor the phone's own message store (`mms`, `sms`, `mms-sms` and the rest of the telephony
 * provider). This app may read it as the SMS app; another app may not, and handing the compose
 * screen an address in it -- a picture from another conversation -- would have it read and
 * attached with this app's rights, one press of Send from going to whoever asked. No other app
 * has a reason to share a part of the message store, so it is refused whatever comes with it:
 * on Android 12 a grant flag on the intent proves nothing here, because the system does not
 * check the sender's own access when the receiver already holds the permission.
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
        return a.isNotEmpty() && a != own && !a.startsWith("$own.") && !a.contains("@") &&
            a !in MESSAGE_STORE
    }

    /** The telephony provider's authorities: the phone's texts, picture messages and SIM. */
    private val MESSAGE_STORE = setOf("sms", "mms", "mms-sms", "telephony", "icc")
}
