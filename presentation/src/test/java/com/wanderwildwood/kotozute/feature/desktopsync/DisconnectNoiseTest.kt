package com.wanderwildwood.kotozute.feature.desktopsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Which WebSocket failures are worth a stack trace.
 *
 * The push channel is a browser tab. Tabs close, laptops sleep, wifi changes hands — each ends
 * the socket from the far side, and each was logged as a warning with fourteen lines of stack.
 * A log that reports the expected outcome that loudly stops being read, and the next real fault
 * goes past with it.
 */
class DisconnectNoiseTest {

    @Test
    fun `the far end going away is quiet`() {
        // The exact one seen on the handset: a drop at 15:15:46 with the client back 11s later.
        assertTrue(isOrdinaryDisconnect(SocketException("Software caused connection abort")))
        assertTrue(isOrdinaryDisconnect(SocketException("Connection reset by peer")))
        assertTrue(isOrdinaryDisconnect(SocketException("Broken pipe")))
        assertTrue(isOrdinaryDisconnect(SocketException("Socket closed")))
        assertTrue(isOrdinaryDisconnect(IOException("Stream closed")))
    }

    @Test
    fun `and so are the two that say it in their type`() {
        assertTrue(isOrdinaryDisconnect(EOFException()))
        assertTrue(isOrdinaryDisconnect(SocketTimeoutException("Read timed out")))
    }

    @Test
    fun `a real fault is still loud`() {
        // ⛔ The control, and the point of the whole change. Quietening a disconnect is only
        // worth doing if it does not also quieten the thing the log exists for.
        assertFalse(isOrdinaryDisconnect(IOException("No space left on device")))
        assertFalse(isOrdinaryDisconnect(IOException("Permission denied")))
        assertFalse(isOrdinaryDisconnect(SocketException("Network is unreachable")))
        assertFalse(isOrdinaryDisconnect(IOException("Invalid WebSocket frame")))
    }

    @Test
    fun `an exception with no message at all is loud`() {
        // Nothing said it was a disconnect, so it is not assumed to be one. Failing toward
        // noise is the right direction for a log.
        assertFalse(isOrdinaryDisconnect(IOException()))
        assertFalse(isOrdinaryDisconnect(SocketException()))
    }

    @Test
    fun `the match does not care about case`() {
        assertTrue(isOrdinaryDisconnect(SocketException("SOFTWARE CAUSED CONNECTION ABORT")))
    }
}
