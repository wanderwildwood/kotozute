package com.wanderwildwood.kotozute.feature.signal

import android.content.Context
import androidx.annotation.StringRes
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.repository.GroupNotMade
import com.wanderwildwood.kotozute.repository.SendFailure
import com.wanderwildwood.kotozute.repository.SendRefused
import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.repository.SignalRepository.ContactsReport
import com.wanderwildwood.kotozute.repository.SignalRepository.LookupRefusal
import com.wanderwildwood.kotozute.repository.SignalRepository.StorageRefusal

/**
 * Something to say, as resources and the pieces that fill them, not yet read in any language.
 *
 * A value rather than a string so the choice of words can be tested without Android: a test
 * reads the English out of `values/strings.xml` and checks the sentence a person would see,
 * which is the only check that catches a wrong resource or an argument in the wrong place.
 */
sealed interface Words {
    /** A string resource. An argument that is itself [Words] is read first. */
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : Words

    /** Text that came from outside the app -- the server, the library -- shown as it came. */
    data class Raw(val text: String) : Words

    /** Several pieces, one after another, with [separator] between each. */
    data class Joined(val parts: List<Words>, val separator: String) : Words

    /** The words, read through [string], which is `getString` on a device. */
    fun resolve(string: (Int, Array<Any>) -> String): String = when (this) {
        is Res -> string(id, args.map { if (it is Words) it.resolve(string) else it }.toTypedArray())
        is Raw -> text
        is Joined -> parts.joinToString(separator) { it.resolve(string) }
    }
}

/** [words] in this context's language. */
fun Context.say(words: Words): String = words.resolve { id, args ->
    // Without arguments, not with an empty array: a string with no placeholder is not run
    // through the formatter at all, which is how every plain `getString` in the app reads.
    if (args.isEmpty()) getString(id) else getString(id, *args)
}

/**
 * A failure from the Signal rail in this context's language: in words where the rail said
 * which failure it was, and otherwise the exception's own text, as every screen showed it before.
 */
fun Context.sayFailure(failure: Throwable): String? =
    SignalWording.failure(failure)?.let { say(it) } ?: failure.message

/**
 * The words for what the Signal rail decided.
 *
 * ⚠ **The rail decides and this words.** The data layer used to write these sentences where it
 * found the failure, which is a layer with no `Context`, so they could only ever be English --
 * the one part of the app a community translation could not reach. It now hands back a kind,
 * and this is the one place each kind becomes words. The English is unchanged, byte for byte:
 * the test beside this reads it back out of `strings.xml` and compares it with what the rail
 * used to say.
 *
 * Every number goes into the resource already written out, the way the rail wrote it, so a
 * locale's own digits do not change what English says today.
 */
object SignalWording {

    /** Words for a failure that is one of the rail's own kinds, or null for anything else. */
    fun failure(failure: Throwable): Words? = when (failure) {
        is SendRefused -> send(failure.failure)
        is GroupNotMade -> group(failure.why)
        else -> null
    }

    /** Why a send did not happen. */
    fun send(failure: SendFailure): Words = when (failure) {
        is SendFailure.ProofRequired ->
            Words.Res(R.string.signal_send_proof_required, listOf(afterWards(failure.retryAfterSeconds)))
        is SendFailure.RateLimited ->
            Words.Res(R.string.signal_send_rate_limited, listOf(afterWards(failure.retryAfterSeconds)))
        SendFailure.Unlinked -> Words.Res(R.string.signal_send_unlinked)
        SendFailure.VersionRefused -> Words.Res(R.string.signal_send_version_refused)
        SendFailure.ServerRejected -> Words.Res(R.string.signal_send_server_rejected)
        SendFailure.TheyLeft -> Words.Res(R.string.signal_send_they_left)
        is SendFailure.Unexplained -> Words.Raw(failure.detail)
        is SendFailure.SafetyNumberChanged ->
            Words.Res(R.string.signal_send_safety_number_changed, listOf(who(failure.name)))
        is SendFailure.NotOnSignal ->
            Words.Res(R.string.signal_send_not_on_signal, listOf(who(failure.name)))
        SendFailure.Unreachable -> Words.Res(R.string.signal_send_unreachable)
        is SendFailure.KeyUnusable ->
            Words.Res(R.string.signal_send_key_unusable, listOf(who(failure.name)))
        is SendFailure.TooFast -> failure.waitMillis
            ?.let { Words.Res(R.string.signal_send_too_fast_wait, listOf(waitFor(it))) }
            ?: Words.Res(R.string.signal_send_too_fast)
        SendFailure.ProofNeeded -> Words.Res(R.string.signal_send_proof_needed)
        SendFailure.ServerSilent -> Words.Res(R.string.signal_send_server_silent)
        SendFailure.NoLongerHeld -> Words.Res(R.string.signal_send_no_longer_held)
        SendFailure.NoReachableMembers -> Words.Res(R.string.signal_send_no_reachable_members)
        is SendFailure.NobodyReached ->
            Words.Res(R.string.signal_send_nobody_reached, listOf(failure.count.toString()))
        SendFailure.PrimaryRefusedRequest -> Words.Res(R.string.signal_send_primary_refused)
        SendFailure.TooLong -> Words.Res(R.string.signal_send_too_long)
        is SendFailure.AttachmentUnprepared ->
            Words.Res(R.string.signal_send_attachment_unprepared, listOf(failure.detail))
        SendFailure.KeysStale -> Words.Res(R.string.signal_send_keys_stale)
        SendFailure.NoCertificate -> Words.Res(R.string.signal_send_no_certificate)
        SendFailure.NotInGroup -> Words.Res(R.string.signal_send_not_in_group)
        SendFailure.GroupEnded -> Words.Res(R.string.signal_send_group_ended)
        SendFailure.GroupUnreachable -> Words.Res(R.string.signal_send_group_unreachable)
        SendFailure.AdminsOnly -> Words.Res(R.string.signal_send_admins_only)
        SendFailure.NumberOnly -> Words.Res(R.string.signal_send_number_only)
        SendFailure.AttachmentsToGroup -> Words.Res(R.string.signal_send_attachments_to_group)
        SendFailure.NoGroupKey -> Words.Res(R.string.signal_send_no_group_key)
    }

    /** Somebody's name, or "they" where this phone holds only an id. */
    private fun who(name: String?): Any = name ?: Words.Res(R.string.signal_send_them)

    /**
     * A wait in the words somebody would use for it, rather than milliseconds.
     *
     * Rounded **up**, always: telling somebody to wait two minutes when it is really two
     * minutes and fifty seconds earns a second failure, and the second one reads as the app
     * being wrong rather than the server being busy.
     */
    fun waitFor(millis: Long): Words {
        val seconds = millis / 1000
        return when {
            seconds < 90 -> Words.Res(R.string.signal_send_wait_seconds, listOf("$seconds"))
            seconds < 5400 -> Words.Res(R.string.signal_send_wait_minutes, listOf("${(seconds + 59) / 60}"))
            else -> Words.Res(R.string.signal_send_wait_hours, listOf("${(seconds + 3599) / 3600}"))
        }
    }

    /**
     * " Try again in about ten minutes." -- or nothing, when the server did not say.
     *
     * Rounded, and never to the second: a countdown accurate to the second invites somebody to
     * sit and watch it, and the server's number is a floor rather than a promise.
     */
    fun afterWards(seconds: Long): Words = when {
        seconds <= 0 -> Words.Raw("")
        seconds < 90 -> Words.Res(R.string.signal_send_retry_minute)
        seconds < 3600 -> Words.Res(R.string.signal_send_retry_minutes, listOf("${(seconds + 59) / 60}"))
        seconds < 7200 -> Words.Res(R.string.signal_send_retry_hour)
        else -> Words.Res(R.string.signal_send_retry_hours, listOf("${(seconds + 3599) / 3600}"))
    }

    /** Why a new group was not made. */
    fun group(why: GroupNotMade.Why): Words = Words.Res(
        when (why) {
            GroupNotMade.Why.NOT_LINKED -> R.string.signal_group_not_linked
            GroupNotMade.Why.NO_PROFILE -> R.string.signal_group_no_profile
            GroupNotMade.Why.NOBODY_ELSE -> R.string.signal_group_needs_somebody
            GroupNotMade.Why.NOT_PUT_TOGETHER -> R.string.signal_group_not_put_together
            GroupNotMade.Why.NOT_AUTHORIZED -> R.string.signal_group_not_authorized
            GroupNotMade.Why.SERVER_REFUSED -> R.string.signal_group_server_refused
            GroupNotMade.Why.UNADDRESSABLE -> R.string.signal_group_unaddressable
        }
    )

    /** Why a registration step failed. */
    fun registration(failure: SignalRepository.RegistrationFailure): Words = when (failure) {
        SignalRepository.RegistrationFailure.NotANumber -> Words.Res(R.string.signal_register_not_a_number)
        SignalRepository.RegistrationFailure.NoCodeYet -> Words.Res(R.string.signal_register_no_code_yet)
        is SignalRepository.RegistrationFailure.CouldNotStart ->
            Words.Res(R.string.signal_register_could_not_start, listOf(failure.detail))
        SignalRepository.RegistrationFailure.CaptchaAcceptedNoCode ->
            Words.Res(R.string.signal_register_captcha_no_code)
        is SignalRepository.RegistrationFailure.CaptchaRefused ->
            Words.Res(R.string.signal_register_captcha_refused, listOf(failure.detail))
        is SignalRepository.RegistrationFailure.CodeNotSent ->
            Words.Res(R.string.signal_register_code_not_sent, listOf(failure.detail))
        is SignalRepository.RegistrationFailure.CodeRefused -> failure.detail
            ?.let { Words.Res(R.string.signal_register_code_refused_because, listOf(it)) }
            ?: Words.Res(R.string.signal_register_code_refused)
        SignalRepository.RegistrationFailure.NoKeyMaterial ->
            Words.Res(R.string.signal_register_no_key_material)
        is SignalRepository.RegistrationFailure.Locked ->
            Words.Res(R.string.signal_register_locked, listOf("${failure.days}"))
        is SignalRepository.RegistrationFailure.Refused ->
            Words.Res(R.string.signal_register_refused, listOf(failure.detail))
        SignalRepository.RegistrationFailure.Unexpected -> Words.Res(R.string.signal_register_unexpected)
        is SignalRepository.RegistrationFailure.Unexplained -> Words.Raw(failure.detail)
    }

    /** Why linking failed. A refusal is the registration refusal: linking registers a device. */
    fun link(failure: SignalRepository.LinkFailure): Words = when (failure) {
        SignalRepository.LinkFailure.NotScanned -> Words.Res(R.string.signal_link_not_scanned)
        SignalRepository.LinkFailure.Undecryptable -> Words.Res(R.string.signal_link_undecryptable)
        SignalRepository.LinkFailure.NoProvisioningCode -> Words.Res(R.string.signal_link_no_code)
        is SignalRepository.LinkFailure.Refused ->
            Words.Res(R.string.signal_register_refused, listOf(failure.detail))
        is SignalRepository.LinkFailure.Unexplained -> Words.Raw(failure.detail)
    }

    /** Why this account's name did not save. */
    fun profileName(failure: SignalRepository.ProfileNameFailure): Words = when (failure) {
        SignalRepository.ProfileNameFailure.NoAccount -> Words.Res(R.string.signal_profile_no_account)
        SignalRepository.ProfileNameFailure.NoServiceId -> Words.Res(R.string.signal_profile_no_service_id)
        SignalRepository.ProfileNameFailure.NoProfileKey -> Words.Res(R.string.signal_profile_no_key)
        SignalRepository.ProfileNameFailure.ProfileKeyUnreadable ->
            Words.Res(R.string.signal_profile_key_unreadable)
        SignalRepository.ProfileNameFailure.NoGivenName -> Words.Res(R.string.signal_profile_no_given_name)
        is SignalRepository.ProfileNameFailure.Refused ->
            Words.Res(R.string.signal_profile_refused, listOf(failure.detail))
        is SignalRepository.ProfileNameFailure.Unexplained -> Words.Raw(failure.detail)
    }

    /**
     * What a contact fetch or a number lookup did.
     *
     * Every count is said. A record this could not use is said out loud, first the ones the
     * service would not hand over, because they mean the rest of the numbers are not the whole
     * story. A person known only by their phone-number identity is said too: they can be written
     * to, but the two halves of them only become one conversation once the account says so.
     */
    fun contacts(report: ContactsReport): Words = when (report) {
        ContactsReport.NotLinked -> Words.Res(R.string.settings_signal_contacts_not_linked)
        ContactsReport.Requested -> Words.Res(R.string.settings_signal_contacts_requested)
        ContactsReport.NoNumbers -> Words.Res(R.string.settings_signal_contacts_no_numbers)
        is ContactsReport.Read -> {
            val dropped = listOfNotNull(
                report.unreadable.counted(R.string.settings_signal_contacts_unreadable),
                report.unopened.counted(R.string.settings_signal_contacts_unopened),
                report.notContacts.counted(R.string.settings_signal_contacts_not_contacts),
                report.anonymous.takeIf { it > 0 }?.let { anonymous ->
                    if (report.anonymousWithNumber > 0) {
                        Words.Res(
                            R.string.settings_signal_contacts_anonymous_numbered,
                            listOf("$anonymous", "${report.anonymousWithNumber}")
                        )
                    } else {
                        Words.Res(R.string.settings_signal_contacts_anonymous, listOf("$anonymous"))
                    }
                }
            )
            Words.Joined(
                listOfNotNull(
                    Words.Res(
                        R.string.settings_signal_contacts_read,
                        listOf("${report.contacts}", "${report.records}")
                    ),
                    report.pniOnly.counted(R.string.settings_signal_contacts_by_pni),
                    dropped.takeIf { it.isNotEmpty() }?.let {
                        Words.Res(R.string.settings_signal_contacts_skipped, listOf(Words.Joined(it, ", ")))
                    }
                ),
                " · "
            )
        }
        is ContactsReport.ReadRefused -> Words.Res(
            when (report.why) {
                StorageRefusal.NO_KEY -> R.string.settings_signal_contacts_no_key
                StorageRefusal.NO_AUTH -> R.string.settings_signal_contacts_no_auth
                StorageRefusal.NOTHING_STORED -> R.string.settings_signal_contacts_nothing_stored
                StorageRefusal.MANIFEST_UNREADABLE -> R.string.settings_signal_contacts_manifest_unreadable
                StorageRefusal.WRONG_KEY -> R.string.settings_signal_contacts_wrong_key
            }
        )
        is ContactsReport.Discovered -> Words.Joined(
            listOfNotNull(
                Words.Res(
                    R.string.settings_signal_lookup_found,
                    listOf("${report.found}", "${report.asked}")
                ),
                report.withoutAci.counted(R.string.settings_signal_lookup_without_aci)
            ),
            " · "
        )
        ContactsReport.AllLookedUp -> Words.Res(R.string.settings_signal_lookup_all_done)
        is ContactsReport.LookupRefused -> when (report.why) {
            LookupRefusal.BLOCKED ->
                Words.Res(R.string.settings_signal_lookup_blocked, listOf("${report.minutes}"))
            LookupRefusal.NO_VALID_NUMBERS -> Words.Res(R.string.settings_signal_lookup_no_numbers)
            LookupRefusal.TOO_MANY -> Words.Res(R.string.settings_signal_lookup_too_many)
            LookupRefusal.OWN_RECORDS_UNREADABLE -> Words.Res(R.string.settings_signal_lookup_own_records)
            LookupRefusal.EXHAUSTED -> Words.Res(R.string.settings_signal_lookup_exhausted)
            LookupRefusal.TOKEN_OUT_OF_STEP -> Words.Res(R.string.settings_signal_lookup_token)
            LookupRefusal.NUMBERS_REFUSED -> Words.Res(R.string.settings_signal_lookup_refused)
            LookupRefusal.UNFINISHED -> Words.Res(R.string.settings_signal_lookup_unfinished)
        }
    }

    /**
     * The contact store in numbers, for the Connection line. The last two only when there are
     * any: a count of nought is a line of noise on a small screen.
     */
    fun contactCounts(counts: SignalRepository.ContactCounts): Words = Words.Joined(
        listOfNotNull(
            Words.Res(
                R.string.settings_signal_status_contacts,
                listOf("${counts.known}", "${counts.withProfileKey}", "${counts.named}")
            ),
            counts.withUsername.counted(R.string.settings_signal_status_contacts_username),
            counts.nameless.counted(R.string.settings_signal_status_contacts_nameless)
        ),
        ", "
    )

    /** The count in [id], or nothing when there are none. */
    private fun Int.counted(@StringRes id: Int): Words? =
        takeIf { it > 0 }?.let { Words.Res(id, listOf("$it")) }
}
