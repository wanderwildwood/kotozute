/*
 * Copyright (C) 2026 wander wildwood
 *
 * This file is part of Messaging.
 *
 * Messaging is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Messaging is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Messaging.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.wanderwildwood.kotozute.mms

import com.google.android.mms.pdu_alt.PduHeaders

/**
 * One report row read off the MMS provider: an M-Delivery.ind or an M-Read-Orig.ind that the
 * far end sent back. [messageId] is the MMS Message-ID header naming the message it answers.
 *
 * The two kinds put their answer in *different columns*, which is easy to get wrong and silent
 * when you do: PduPersister maps the PDU's STATUS header to Mms.STATUS and its READ_STATUS
 * header to Mms.READ_STATUS, so a read report leaves Mms.STATUS unset and a delivery report
 * leaves Mms.READ_STATUS unset. Both are carried here so the rule about which one to believe
 * stays in one place, next to a test for it.
 */
data class MmsReport(
    val messageId: String,
    val messageType: Int,
    val status: Int = 0,
    val readStatus: Int = 0
)

/** What the reports add up to for a single sent message. */
data class MmsReportVerdict(
    val delivered: Boolean = false,
    val read: Boolean = false
) {
    val saysAnything: Boolean get() = delivered || read
}

/**
 * Fold report rows into one verdict per acknowledged message.
 *
 * Kept as a pure function so the awkward parts can be tested without a phone, a content resolver
 * or a cooperating handset on the other end -- none of which are available to this code in
 * practice, since a read receipt only ever arrives from someone else's device.
 *
 * The awkward parts, each of which has a test:
 * - A handset may answer twice, delivery first and read second, and the rows come back in
 *   provider order, not in the order the answers arrived. Verdicts therefore only ever
 *   accumulate; a later row can add "delivered" but can never take "read" away.
 * - A delivery report is not automatically good news. An expired or rejected message still
 *   produces an M-Delivery.ind, carrying the status that says so -- treating the row's mere
 *   existence as delivery would report failures as successes.
 * - An M-Read-Orig.ind can equally say the message was DELETED unread, which is an answer but
 *   not a read.
 * - The two report types answer in different columns, and reading the wrong one fails silently:
 *   every read report would look like a status of zero and no message would ever show as read.
 *   Note also that READ_STATUS_READ and STATUS_EXPIRED are both 0x80, so the type has to be
 *   checked before the value means anything at all.
 */
internal fun mmsReportVerdicts(reports: List<MmsReport>): Map<String, MmsReportVerdict> {
    val verdicts = mutableMapOf<String, MmsReportVerdict>()

    reports.forEach { report ->
        if (report.messageId.isEmpty())
            return@forEach

        val delivered = report.messageType == PduHeaders.MESSAGE_TYPE_DELIVERY_IND &&
                report.status == PduHeaders.STATUS_RETRIEVED

        val read = report.messageType == PduHeaders.MESSAGE_TYPE_READ_ORIG_IND &&
                report.readStatus == PduHeaders.READ_STATUS_READ

        val running = verdicts[report.messageId] ?: MmsReportVerdict()
        verdicts[report.messageId] = MmsReportVerdict(
            delivered = running.delivered || delivered,
            read = running.read || read
        )
    }

    return verdicts.filterValues { verdict -> verdict.saysAnything }
}
