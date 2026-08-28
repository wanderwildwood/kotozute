package com.wanderwildwood.einkmessaging.mms

import com.google.android.mms.pdu_alt.PduHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The far end's reports are the one thing in this app that cannot be produced on demand: they
 * arrive only when somebody else's handset chooses to send one. So the folding rules are tested
 * here instead, where the rows can simply be written down.
 */
class MmsReportsTest {

    private fun delivery(messageId: String, status: Int = PduHeaders.STATUS_RETRIEVED) =
        MmsReport(
            messageId = messageId,
            messageType = PduHeaders.MESSAGE_TYPE_DELIVERY_IND,
            status = status
        )

    private fun read(messageId: String, readStatus: Int = PduHeaders.READ_STATUS_READ) =
        MmsReport(
            messageId = messageId,
            messageType = PduHeaders.MESSAGE_TYPE_READ_ORIG_IND,
            readStatus = readStatus
        )

    @Test
    fun aRetrievedDeliveryReportMeansDelivered() {
        val verdict = mmsReportVerdicts(listOf(delivery("a")))["a"]!!
        assertTrue(verdict.delivered)
        assertFalse(verdict.read)
    }

    @Test
    fun aReadReportMeansRead() {
        val verdict = mmsReportVerdicts(listOf(read("a")))["a"]!!
        assertTrue(verdict.read)
    }

    @Test
    fun anExpiredMessageIsNotDelivered() {
        // The report arrives either way; only its status says which happened.
        assertNull(mmsReportVerdicts(listOf(delivery("a", PduHeaders.STATUS_EXPIRED)))["a"])
    }

    @Test
    fun aRejectedMessageIsNotDelivered() {
        assertNull(mmsReportVerdicts(listOf(delivery("a", PduHeaders.STATUS_REJECTED)))["a"])
    }

    @Test
    fun deletedUnreadIsNotRead() {
        assertNull(mmsReportVerdicts(listOf(read("a", PduHeaders.READ_STATUS__DELETED_WITHOUT_BEING_READ)))["a"])
    }

    @Test
    fun aLaterDeliveryRowCannotUndoAnEarlierRead() {
        // Provider order is not arrival order, so this ordering is entirely possible.
        val verdict = mmsReportVerdicts(listOf(read("a"), delivery("a")))["a"]!!
        assertTrue(verdict.read)
        assertTrue(verdict.delivered)
    }

    @Test
    fun deliveryThenReadAccumulates() {
        val verdict = mmsReportVerdicts(listOf(delivery("a"), read("a")))["a"]!!
        assertTrue(verdict.delivered)
        assertTrue(verdict.read)
    }

    @Test
    fun reportsAreKeptApartByMessageId() {
        val verdicts = mmsReportVerdicts(listOf(read("a"), delivery("b")))
        assertTrue(verdicts["a"]!!.read)
        assertFalse(verdicts["b"]!!.read)
        assertTrue(verdicts["b"]!!.delivered)
    }

    @Test
    fun aReportNamingNothingIsDropped() {
        // A row with no Message-ID cannot be joined to anything, so it must not become a verdict.
        assertTrue(mmsReportVerdicts(listOf(read(""))).isEmpty())
    }

    @Test
    fun messagesWithNothingToSayAreLeftOut() {
        // Callers treat presence in the map as "there is something to write".
        assertEquals(0, mmsReportVerdicts(listOf(delivery("a", PduHeaders.STATUS_DEFERRED))).size)
    }

    @Test
    fun aReadReportIsReadFromTheReadStatusColumn() {
        // Regression: reading Mms.STATUS for a read report finds nothing there, and every read
        // receipt silently becomes a no-op. PduPersister writes READ_STATUS to its own column.
        val row = MmsReport(
            messageId = "a",
            messageType = PduHeaders.MESSAGE_TYPE_READ_ORIG_IND,
            status = 0,
            readStatus = PduHeaders.READ_STATUS_READ
        )
        assertTrue(mmsReportVerdicts(listOf(row))["a"]!!.read)
    }

    @Test
    fun aDeliveryReportIgnoresTheReadStatusColumn() {
        // The mirror image, and the reason the type is checked before the value: 0x80 means
        // READ_STATUS_READ on one type and STATUS_EXPIRED on the other.
        val row = MmsReport(
            messageId = "a",
            messageType = PduHeaders.MESSAGE_TYPE_DELIVERY_IND,
            status = PduHeaders.STATUS_EXPIRED,
            readStatus = PduHeaders.READ_STATUS_READ
        )
        assertNull(mmsReportVerdicts(listOf(row))["a"])
    }

    @Test
    fun noReportsMeansNoVerdicts() {
        assertTrue(mmsReportVerdicts(emptyList()).isEmpty())
    }
}
