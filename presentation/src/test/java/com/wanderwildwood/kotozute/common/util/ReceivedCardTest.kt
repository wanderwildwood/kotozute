package com.wanderwildwood.kotozute.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceivedCardTest {

    private val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ada Example\r\nN:Example;Ada;;;\r\nTEL:555-0100\r\nEND:VCARD\r\n"

    @Test fun plainCardPasses() {
        assertEquals(card.trim(), String(ReceivedCard.clean(card.toByteArray())!!))
    }

    @Test fun utf8MarkIsDropped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + card.toByteArray()
        assertEquals(card.trim(), String(ReceivedCard.clean(bytes)!!))
    }

    @Test fun utf16CardsAreReadAndWrittenAsUtf8() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + card.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + card.toByteArray(Charsets.UTF_16BE)
        assertEquals(card.trim(), String(ReceivedCard.clean(le)!!))
        assertEquals(card.trim(), String(ReceivedCard.clean(be)!!))
    }

    @Test fun severalCardsStayTogether() {
        val two = card + card.replace("Ada", "Bo")
        assertEquals(2, String(ReceivedCard.clean(two.toByteArray())!!).split("BEGIN:VCARD").size - 1)
    }

    @Test fun emptyAndNonCardsAreRefused() {
        assertNull(ReceivedCard.clean(null))
        assertNull(ReceivedCard.clean(ByteArray(0)))
        assertNull(ReceivedCard.clean("hello".toByteArray()))
    }
}
