package com.wanderwildwood.kotozute

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pairing QR has to survive the round trip, at the size a real payload makes.
 *
 * A 140-character link is not a small QR, and the whole point of scanning it is that nobody
 * has to read it. If the decoder cannot get it back out at a plausible camera resolution,
 * the feature is worse than pasting -- so this asserts the shape rather than trusting it.
 */
class PairingQrTest {

    private val payload =
        "kotozute-bridge://192.168.1.50:8422/?token=XGru129jhVnOU_cXEYclcJl_mnHJLLUELkm_06MqM_Y" +
            "&fp=" + "A0A5EAD44D577E07798A5BFCE6B09725A790E48B441B18834FE1A6997826986E"

    private fun roundTrip(size: Int): String {
        val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) 0x000000 else 0xFFFFFF
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun `a full pairing link survives being drawn and read back`() {
        assertEquals(payload, roundTrip(400))
    }

    // The Kompakt's screen is 480px wide; a camera frame is larger, but this is the floor.
    @Test
    fun `it still decodes at the smallest size the phone would ever see`() {
        assertEquals(payload, roundTrip(240))
    }
}
