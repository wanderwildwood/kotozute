package com.wanderwildwood.kotozute.repository

import com.wanderwildwood.kotozute.repository.BackupRepositoryImpl.BackupMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsRestoreTest {

    private fun sms(address: String, date: Long, type: Int, body: String) = BackupMessage(
            type = type, address = address, date = date, dateSent = date - 1_000, read = true,
            status = -1, body = body, protocol = 0, serviceCenter = null, locked = false, subId = 1
    )

    private val fromAda = sms("+15550100001", 1_760_000_000_000, 1, "Are we still on for Thursday?")
    private val toAda = sms("+15550100001", 1_760_000_060_000, 2, "Yes, six o'clock.")
    private val fromTomas = sms("+15550100002", 1_760_000_120_000, 1, "Running late, sorry")

    @Test
    fun messagesAlreadyOnThePhoneAreNotWrittenAgain() {
        val onPhone = setOf(SmsRestore.key(fromAda.address, fromAda.date, fromAda.type, fromAda.body))
        assertEquals(listOf(toAda, fromTomas), SmsRestore.toWrite(listOf(fromAda, toAda, fromTomas), onPhone))
    }

    @Test
    fun restoringTheSameFileTwiceWritesNothingTheSecondTime() {
        val backup = listOf(fromAda, toAda, fromTomas)
        val first = SmsRestore.toWrite(backup, emptySet())
        assertEquals(backup, first)
        val onPhone = first.map { SmsRestore.key(it.address, it.date, it.type, it.body) }.toSet()
        assertEquals(emptyList<BackupMessage>(), SmsRestore.toWrite(backup, onPhone))
    }

    @Test
    fun aNumberWrittenAnotherWayIsTheSameSender() {
        val onPhone = setOf(SmsRestore.key("(555) 010-0001", fromAda.date, 1, fromAda.body))
        assertEquals(emptyList<BackupMessage>(), SmsRestore.toWrite(listOf(fromAda), onPhone))
    }

    @Test
    fun sameTextAtAnotherTimeOrInTheOtherBoxIsADifferentMessage() {
        val again = fromAda.copy(date = fromAda.date + 1)
        val sentInstead = fromAda.copy(type = 2)
        val onPhone = setOf(SmsRestore.key(fromAda.address, fromAda.date, fromAda.type, fromAda.body))
        assertEquals(listOf(again, sentInstead), SmsRestore.toWrite(listOf(again, sentInstead), onPhone))
    }

    @Test
    fun aFileThatRepeatsAMessageWritesItOnce() {
        assertEquals(listOf(fromAda), SmsRestore.toWrite(listOf(fromAda, fromAda.copy(read = false)), emptySet()))
    }

    @Test
    fun emptyMessagesFromOldBackupsOfPictureMessagesAreLeftOut() {
        val picture = sms("+15550100002", 1_760_000_180_000, 1, "")
        assertEquals(listOf(fromTomas), SmsRestore.toWrite(listOf(picture, fromTomas), emptySet()))
    }

    @Test
    fun namedSendersAreComparedAsWritten() {
        assertEquals("bank", SmsRestore.address(" BANK "))
        assertEquals("5550100001", SmsRestore.address("+1 555-010-0001"))
        assertEquals("12345", SmsRestore.address("12345"))
    }
}
