package com.wanderwildwood.kotozute.repository

/**
 * Whether a newer build has been published, and installing it if so.
 *
 * Most people who have this app never got it from a release page: they followed a link on the
 * forum once, installed whatever was current that week, and have no way to hear that anything
 * came after. Obtainium solves this for the people who already use Obtainium. This is for
 * everyone else, and it is the same two questions -- is there a newer one, and will you take it.
 */
interface UpdateRepository {

    /**
     * Asks the project what the newest published version is.
     *
     * Never throws: a check that cannot be made is an answer, not a failure worth a stack trace.
     */
    suspend fun check(): UpdateCheck

    /**
     * Downloads [version] and hands it to the system installer.
     *
     * Returns once the installer has taken it, which is not the same as it being installed --
     * the system may still ask, and on success this process is replaced and nothing here runs
     * again. What it can report is everything that happens before that point.
     */
    suspend fun install(version: String): UpdateInstall

}

sealed interface UpdateCheck {

    /** Nothing newer has been published. */
    data class Current(val running: String) : UpdateCheck

    /** [version] is published, and it is newer than what is running. */
    data class Available(val version: String) : UpdateCheck

    /** The question could not be put -- no network, or a reply we could not read. */
    object Unreachable : UpdateCheck

    /**
     * This build did not come from a release tag, so there is nothing to compare it against.
     *
     * A debug build carries the placeholder version out of `presentation/build.gradle`, which
     * is older than every release ever made. Left to compare, it would offer to "update" a
     * developer's working build to the last tag, every time.
     */
    object NotAReleaseBuild : UpdateCheck

}

sealed interface UpdateInstall {

    /** The installer has it. Either it is being applied, or the system is asking first. */
    object HandedOver : UpdateInstall

    /** The download did not finish. */
    object Unreachable : UpdateInstall

    /**
     * What arrived was not what the checksum beside it said it would be, so it was discarded
     * unread rather than handed to an installer.
     */
    object WrongContents : UpdateInstall

    /**
     * Android will not let this app install packages. The permission is declared, but it is one
     * the person grants per app in system settings and can take back.
     */
    object NotPermitted : UpdateInstall

}
