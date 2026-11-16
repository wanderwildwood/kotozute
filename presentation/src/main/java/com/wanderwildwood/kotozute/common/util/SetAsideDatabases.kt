package com.wanderwildwood.kotozute.common.util

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * The message databases that were set aside because they would not open
 * ([RealmEncryption.setAsideUnreadableRealm]), and the one place they can be removed from.
 *
 * Setting a file aside instead of deleting it means it stays on the phone until somebody
 * decides otherwise. This is how they decide: Settings shows a row when there is anything
 * here, saying how many and how big, and removing asks twice.
 *
 * ⚠ **What may be deleted is decided by name, and the names are exact.** A set-aside database
 * is `default.realm.unreadable-<digits>` directly inside the app's files directory, and its
 * sealed key is the pair of preference entries `sealed_key.unreadable-<digits>` and
 * `iv.unreadable-<digits>`. Nothing else matches: not `default.realm` itself, not its lock,
 * note or management folder, not the copies the one-time encryption makes, and not the live
 * `sealed_key` and `iv`. The two predicates are plain functions of a name so that this can be
 * tested without a phone, and it is (SetAsideDatabasesTest).
 */
object SetAsideDatabases {

    /** Between the live name and the time it was set aside. Written by [RealmEncryption]. */
    const val MARK = ".unreadable-"

    private val FILE_NAME = Regex(
        Regex.escape(RealmEncryption.REALM_NAME) + Regex.escape(MARK) + "\\d+"
    )
    private val KEY_NAME = Regex(
        "(?:" + Regex.escape(RealmEncryption.PREF_SEALED_KEY) + "|" +
            Regex.escape(RealmEncryption.PREF_IV) + ")" + Regex.escape(MARK) + "\\d+"
    )

    /** Whether a file of this name, in the app's files directory, is a set-aside database. */
    fun isSetAsideFileName(name: String): Boolean = FILE_NAME.matches(name)

    /** Whether a preference entry of this name is the sealed key of a set-aside database. */
    fun isSetAsideKeyName(name: String): Boolean = KEY_NAME.matches(name)

    data class Found(val count: Int, val bytes: Long)

    private fun files(context: Context): List<File> =
        context.filesDir.listFiles { entry -> entry.isFile && isSetAsideFileName(entry.name) }
            ?.toList().orEmpty()

    /** How many there are and how much room they take. Zero files means no row is shown. */
    fun find(context: Context): Found =
        files(context).let { found -> Found(found.size, found.sumOf { it.length() }) }

    /**
     * Deletes the set-aside databases and their sealed keys, and nothing else.
     *
     * A key goes only once no file is left, so a file that would not delete keeps the key
     * that could one day open it.
     *
     * @return how many files were deleted.
     */
    fun remove(context: Context): Int {
        val deleted = files(context).count { file ->
            file.delete().also { gone ->
                if (gone) Timber.i("realm: removed a set-aside database (%s)", file.name)
                else Timber.w("realm: could not remove a set-aside database (%s)", file.name)
            }
        }
        if (files(context).isEmpty()) {
            val prefs = context.getSharedPreferences(RealmEncryption.PREFS, Context.MODE_PRIVATE)
            val stale = prefs.all.keys.filter(::isSetAsideKeyName)
            if (stale.isNotEmpty()) {
                prefs.edit().apply { stale.forEach { remove(it) } }.apply()
            }
        }
        return deleted
    }
}
