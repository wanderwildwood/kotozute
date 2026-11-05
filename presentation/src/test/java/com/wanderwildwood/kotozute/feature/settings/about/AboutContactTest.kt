package com.wanderwildwood.kotozute.feature.settings.about

import org.junit.Assert.assertEquals
import org.junit.Test

class AboutContactTest {

    @Test fun theAddressIsTheOneEveryAppGives() = assertEquals("email@wanderthe.dev", AboutContact.EMAIL)

    @Test fun theSubjectIsEncodedWithSpacesAsPercent20() {
        assertEquals(
            "mailto:email@wanderthe.dev?subject=Messaging%201.35.15",
            AboutContact.mailUri(AboutContact.EMAIL, "Messaging 1.35.15"),
        )
    }
}
