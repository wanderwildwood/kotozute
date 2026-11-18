package com.wanderwildwood.kotozute.common.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.result.contract.ActivityResultContract
import com.wanderwildwood.kotozute.common.HouseApps

/**
 * Contacts (enishi), the address book made to sit beside this app, answers every contacts
 * request Messaging makes whenever it is on the phone.
 *
 * Without this a phone with two contacts apps — Mudita's and this one — asks "Complete
 * action using" for each kind of request, one at a time: show a person, add a number,
 * pick someone for a card. Naming the app skips all of that. Where it is not installed the
 * request goes out unnamed, exactly as before, and whatever contacts app the phone has
 * answers it.
 */
object ContactsApp {
    const val PACKAGE = HouseApps.CONNECTIONS

    /**
     * The same request, addressed to Contacts if the real one is here to take it: signed with its
     * release certificate ([HouseApps]). An app that only took its name is treated as not installed.
     */
    fun prefer(context: Context, intent: Intent): Intent = HouseApps.aimed(context, intent, PACKAGE) ?: intent

    /**
     * `ActivityResultContracts.PickContact`, addressed the same way. What comes back is the
     * same either way: the person's contact address, read with this app's own permission.
     */
    class PickContact : ActivityResultContract<Void?, Uri?>() {
        override fun createIntent(context: Context, input: Void?): Intent =
            prefer(context, Intent(Intent.ACTION_PICK).setType(ContactsContract.Contacts.CONTENT_TYPE))

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            intent.takeIf { resultCode == Activity.RESULT_OK }?.data
    }
}
