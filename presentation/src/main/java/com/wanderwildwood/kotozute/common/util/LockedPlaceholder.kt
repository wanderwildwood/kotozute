package com.wanderwildwood.kotozute.common.util

import timber.log.Timber
import java.io.File

/**
 * The empty database Realm is handed while the message database is locked
 * (`QKApplication.refuseMessageStore`), and the removal of what it leaves behind.
 *
 * It lives in memory, but Realm still keeps a file of that name and its scratch files beside
 * the real database, and the process that made them is ended on purpose ("Try again", "Start
 * with an empty list"), so nothing closes them. They are removed on the next start that opens
 * the real database, when nothing can be using them.
 *
 * ⚠ **Removed by exact name.** The four names below, directly inside the app's files
 * directory, and nothing else: not `default.realm` or anything of its, and not a database
 * that was set aside ([SetAsideDatabases]). Tested without a phone (LockedPlaceholderTest).
 */
object LockedPlaceholder {

    const val NAME = "locked-placeholder.realm"

    /** The database itself, Realm's lock and note pipe, and its management folder. */
    val FILE_NAMES = listOf(NAME, "$NAME.lock", "$NAME.note", "$NAME.management")

    /** @return how many of the four were there and are gone. */
    fun remove(filesDir: File): Int = FILE_NAMES.count { name ->
        val entry = File(filesDir, name)
        if (!entry.exists()) return@count false
        (if (entry.isDirectory) entry.deleteRecursively() else entry.delete()).also { gone ->
            if (!gone) Timber.w("realm: could not remove the locked screen's placeholder (%s)", name)
        }
    }
}
