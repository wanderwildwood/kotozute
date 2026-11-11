package com.wanderwildwood.kotozute.common.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePickerTest {

    @Test fun asksTheFirstTimeWhenThereIsAnotherFilesApp() {
        assertTrue(FilePicker.shouldAsk(chosen = "", asked = false, others = 1))
    }

    @Test fun neverAsksWithOnlyAndroidsOwnPicker() {
        assertFalse(FilePicker.shouldAsk(chosen = "", asked = false, others = 0))
    }

    @Test fun neverAsksTwice() {
        assertFalse(FilePicker.shouldAsk(chosen = "", asked = true, others = 2))
    }

    @Test fun neverAsksSomeoneWhoChoseBeforeTheQuestionExisted() {
        assertFalse(FilePicker.shouldAsk(chosen = "com.wanderwildwood.tana", asked = false, others = 1))
    }
}
