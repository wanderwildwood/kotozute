package com.wanderwildwood.kotozute.signalstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * How long a link code stays usable.
 *
 * ⚠ These exist because the linking flow itself cannot be exercised from here: running it
 * would put a fifth device on a live account. What *can* be pinned is the bookkeeping, and the
 * bookkeeping is where the subtlety is.
 *
 * The fault being fixed: one socket, ninety seconds, and the ninety seconds start when the QR
 * appears -- before the person has picked up the other phone and found Linked Devices. Signal
 * does not extend that life; it replaces the socket every forty-five seconds, five times over.
 */
class LinkSocketRotationTest {

    @Test
    fun `the first two codes displace nothing`() {
        val live = mutableListOf<String>()
        assertNull(DeviceLinker.admit(live, "a"))
        assertNull(DeviceLinker.admit(live, "b"))
        assertEquals(listOf("a", "b"), live)
    }

    @Test
    fun `a third code closes the first, not the second`() {
        // The rule this test exists for. Closing "b" -- the one just displaced from the screen
        // -- would strand somebody who scanned it a second ago and is mid-exchange. Closing
        // "a" gives every code a full rotation of grace after it stops being shown.
        val live = mutableListOf<String>()
        DeviceLinker.admit(live, "a")
        DeviceLinker.admit(live, "b")
        assertEquals("a", DeviceLinker.admit(live, "c"))
        assertEquals(listOf("b", "c"), live)
    }

    @Test
    fun `only ever two are open at once`() {
        val live = mutableListOf<String>()
        val closed = mutableListOf<String>()
        repeat(10) { i -> DeviceLinker.admit(live, "s$i")?.let { closed += it } }
        assertEquals(2, live.size)
        assertEquals(listOf("s8", "s9"), live)
        // Everything else was closed, in order, exactly once.
        assertEquals((0..7).map { "s$it" }, closed)
    }

    @Test
    fun `a code is usable for two rotations, not one`() {
        // Stated as the time it buys, which is the thing that matters to somebody holding a
        // phone: a code shown at t=0 is displaced at t=45s and stays open until t=90s.
        val live = mutableListOf<String>()
        DeviceLinker.admit(live, "shown at 0s")
        DeviceLinker.admit(live, "shown at 45s")
        assert(live.contains("shown at 0s"))
        assertEquals("shown at 0s", DeviceLinker.admit(live, "shown at 90s"))
    }

    @Test
    fun `the rotation numbers are Signal's`() {
        // Half of ProvisioningSocket.LIFESPAN, which is ninety seconds, and its `count < 5`.
        assertEquals(TimeUnit.SECONDS.toMillis(45), DeviceLinker.LINK_ROTATE_INTERVAL_MS)
        assertEquals(5, DeviceLinker.MAX_LINK_ROTATIONS)
    }

    @Test
    fun `the attempt is not abandoned while a code is still scannable`() {
        // ⚠ The bug this replaced, as the timeline that exposed it. The timer keeps ticking
        // after the last socket opens, so at t=225s the rotation counter has reached five
        // while sockets E and F are alive -- and the socket that expires at that moment is D,
        // an old one. Asking "has the counter reached the maximum?" abandoned the whole
        // attempt there, one second after a fresh code went on screen.
        //
        // rotations=5 is not "no more coming": the last socket opens ON rotation 5.
        assertFalse(
            "t=225s: D expires, E and F are live",
            DeviceLinker.noCodeIsStillLive(rotations = 5, opened = 6, failed = 4)
        )
        // t=270s: no more rotations, but F is still up.
        assertFalse(
            "t=270s: E expires, F is still live",
            DeviceLinker.noCodeIsStillLive(rotations = 6, opened = 6, failed = 5)
        )
        // t=315s: F expires. Nothing left to scan, and only now is it a failure.
        assertTrue(
            "t=315s: the last code has expired",
            DeviceLinker.noCodeIsStillLive(rotations = 6, opened = 6, failed = 6)
        )
    }

    @Test
    fun `an early expiry never ends the attempt`() {
        // Every one of these is an old code expiring on schedule while newer ones are up.
        for (r in 1..5) {
            assertFalse(
                "rotation $r",
                DeviceLinker.noCodeIsStillLive(rotations = r, opened = r + 1, failed = r)
            )
        }
    }

    @Test
    fun `five rotations is four and a half minutes of linking time`() {
        // Against the ninety seconds one socket allows. This is the finding, as a number.
        val total = DeviceLinker.LINK_ROTATE_INTERVAL_MS * DeviceLinker.MAX_LINK_ROTATIONS
        assertEquals(TimeUnit.SECONDS.toMillis(225), total)
        assert(total > TimeUnit.SECONDS.toMillis(90))
    }
}
