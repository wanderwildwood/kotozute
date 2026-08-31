/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.wanderwildwood.kotozute.compat

import android.content.Context
import android.provider.Telephony
import android.text.TextUtils
import android.util.Patterns
import java.util.regex.Pattern
import timber.log.Timber

class TelephonyCompat {
    companion object {
        val THREADS_CONTENT_URI = Telephony.Threads.CONTENT_URI!!

        private val NAME_ADDRESS_EMAIL_PATTERN = Pattern.compile("\\s*(\"[^\"]*\"|[^<>\"]+)\\s*<([^<>]+)>\\s*")

        fun getOrCreateThreadId(context: Context, recipient: String): Long {
            return getOrCreateThreadId(context, listOf(recipient))
        }

        /**
         * 0 when the provider won't answer. Reading it needs READ_SMS, which arrives with
         * the default-SMS role and not otherwise, so on a phone where the role was declined
         * this throws -- and it used to throw all the way out through an RxJava chain with
         * no error handler, killing the app on the first tap of a conversation. Callers
         * already treat 0 as "no thread", which is the truth here.
         */
        fun getOrCreateThreadId(context: Context, recipients: Collection<String>): Long {
            return try {
                Telephony.Threads.getOrCreateThreadId(context, recipients.toSet())
            } catch (e: SecurityException) {
                Timber.w(e, "No permission to read the SMS provider for a thread id")
                0L
            }
        }

        private fun extractAddrSpec(address: String): String {
            val match = NAME_ADDRESS_EMAIL_PATTERN.matcher(address)
            return if (match.matches()) {
                match.group(2)
            } else address
        }

        fun isEmailAddress(address: String): Boolean {
            if (TextUtils.isEmpty(address)) {
                return false
            }
            val s = extractAddrSpec(address)
            val match = Patterns.EMAIL_ADDRESS.matcher(s)
            return match.matches()
        }
    }

}