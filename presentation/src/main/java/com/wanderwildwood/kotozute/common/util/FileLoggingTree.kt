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
package com.wanderwildwood.kotozute.common.util

import android.content.Context
import android.util.Log
import com.wanderwildwood.kotozute.util.Preferences
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Based off Vipin Kumar's FileLoggingTree: https://medium.com/@vicky7230/file-logging-with-timber-4e63a1b86a66
 */
@Singleton
class FileLoggingTree @Inject constructor(
    private val prefs: Preferences,
    private val context: Context
) : Timber.DebugTree() {
    companion object {
        val TAG: String? = FileLoggingTree::class.simpleName
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!prefs.logging.get()) return

        Schedulers.io().scheduleDirect {
            synchronized(this) {    // one thread can access file at a time
                val logItem =
                    "${    // date/time
                        SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss:SSS",
                            Locale.getDefault()
                        ).format(System.currentTimeMillis())
                    } ${    // priority
                        when (priority) {
                            Log.VERBOSE -> "V"
                            Log.DEBUG -> "D"
                            Log.INFO -> "I"
                            Log.WARN -> "W"
                            Log.ERROR -> "E"
                            else -> "?"
                        }
                    }/${    // tag
                        tag
                    }: ${    // message
                        message
                    }${    // stack trace
                        Log.getStackTraceString(t)
                    }\n"

                // The log stays in the app's own storage, not in the public Downloads folder:
                // it holds message text, so nothing else on the phone may read it.
                val filename = "Quik-log-${
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(System.currentTimeMillis())
                }.log"
                try {
                    val dir = context.getExternalFilesDir(null) ?: context.filesDir
                    java.io.File(dir, filename).appendBytes(logItem.toByteArray())
                } catch (e: java.io.IOException) {
                    Log.e(TAG, "Error while logging into file", e)
                }
            }
        }
    }
}