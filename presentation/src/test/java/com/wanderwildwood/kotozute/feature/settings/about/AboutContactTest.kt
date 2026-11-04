package com.wanderwildwood.kotozute.feature.settings.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AboutContactTest {

    @Test fun theAddressIsTheOneEveryAppGives() = assertEquals("email@wanderthe.dev", AboutContact.EMAIL)

    @Test fun theSubjectIsEncodedWithSpacesAsPercent20() {
        assertEquals(
            "mailto:email@wanderthe.dev?subject=Messaging%201.35.15",
            AboutContact.mailUri(AboutContact.EMAIL, "Messaging 1.35.15"),
        )
    }

    /** Who we are, How to contact us and Your rights, in that order after Privacy (Mudita's headings). */
    @Test fun theAboutCarriesTheThreeSectionsAfterPrivacyAndTheContactBeforeTheSource() {
        val layout = File("src/main/res/layout/about_dialog.xml").readText()
        val order = listOf(
            "@string/about_privacy_close", "@string/privacy_who_title", "@string/privacy_contact_title",
            "@string/privacy_rights_title", "@string/about_terms_heading", "@+id/licences",
            "@+id/contact", "@+id/source",
        ).map { layout.indexOf(it) }
        assertTrue(order.toString(), order.all { it >= 0 } && order == order.sorted())
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"privacy_who_title\">Who we are</string>"))
        assertTrue(strings.contains("<string name=\"privacy_contact_title\">How to contact us</string>"))
        assertTrue(strings.contains("<string name=\"privacy_rights_title\">Your rights</string>"))
    }
}
