package com.wanderwildwood.kotozute.common.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What another app may hand the compose screen to attach: only other apps' content:// addresses. */
class SharedAttachmentsTest {

    private val own = "com.wanderwildwood.kotozute"

    @Test
    fun anotherAppsContentAddressIsTaken() {
        assertTrue(SharedAttachments.mayTake("content", "com.android.providers.media.documents", own))
        assertTrue(SharedAttachments.mayTake("content", "com.wanderwildwood.tana.files", own))
    }

    @Test
    fun aFilePathIsNeverTaken() {
        assertFalse(SharedAttachments.mayTake("file", null, own))
        assertFalse(SharedAttachments.mayTake("FILE", "", own))
    }

    @Test
    fun thisAppsOwnProvidersAreNeverTakenFromAnotherApp() {
        assertFalse(SharedAttachments.mayTake("content", "com.wanderwildwood.kotozute.mmspart", own))
        assertFalse(SharedAttachments.mayTake("content", "com.wanderwildwood.kotozute.MmsFileProvider", own))
        assertFalse(SharedAttachments.mayTake("content", "com.wanderwildwood.kotozute", own))
        assertFalse(SharedAttachments.mayTake("content", "com.wanderwildwood.kotozute.debug.mmspart", "$own.debug"))
    }

    @Test
    fun thePhonesMessageStoreIsNeverTakenFromAnotherApp() {
        assertFalse(SharedAttachments.mayTake("content", "mms", own))
        assertFalse(SharedAttachments.mayTake("content", "MMS", own))
        assertFalse(SharedAttachments.mayTake("content", "sms", own))
        assertFalse(SharedAttachments.mayTake("content", "mms-sms", own))
    }

    @Test
    fun oddAddressesAreRefused() {
        assertFalse(SharedAttachments.mayTake(null, "com.example", own))
        assertFalse(SharedAttachments.mayTake("content", null, own))
        assertFalse(SharedAttachments.mayTake("content", "user@com.wanderwildwood.kotozute.mmspart", own))
        assertFalse(SharedAttachments.mayTake("http", "example.org", own))
    }
}
