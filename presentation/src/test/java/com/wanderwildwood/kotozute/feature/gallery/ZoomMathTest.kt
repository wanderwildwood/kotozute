package com.wanderwildwood.kotozute.feature.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomMathTest {

    @Test fun largePictureGoesToOnePixelPerPixel() {
        // 4000x3000 fitted into 480x752 is 0.12 of its size, so 1:1 is 8.33 times the fitted size.
        assertEquals(8.333f, doubleTapTarget(1f, 480f, 752f, 4000, 3000), 0.01f)
    }

    @Test fun enlargedGoesBackToFitted() {
        assertEquals(1f, doubleTapTarget(8.3f, 480f, 752f, 4000, 3000), 0f)
        assertEquals(1f, doubleTapTarget(1.5f, 480f, 752f, 4000, 3000), 0f)
    }

    @Test fun smallPictureGoesToTwice() {
        assertEquals(2f, doubleTapTarget(1f, 480f, 752f, 200, 300), 0f)
    }

    @Test fun hardlyBiggerPictureGoesToTwice() {
        assertEquals(2f, doubleTapTarget(1f, 480f, 752f, 500, 780), 0f)
    }
}
