package com.wanderwildwood.kotozute.signalstore

import io.michaelrocks.libphonenumber.android.PhoneNumberUtil

/**
 * Whether a number is one worth trying to register.
 *
 * ⚠ The failure this prevents is not a rejected request. Registration sends a verification
 * code by SMS to whatever number is given, so a typo does not fail -- it succeeds, against
 * somebody else's phone. It also burns one of a small number of rate-limited attempts, and
 * nothing on this side can take it back.
 *
 * Ported from Signal's `E164Util.isValidNumberForRegistration`, which is four checks and not
 * one. This app had copied only the last of them -- the generic E.164 shape -- and the generic
 * shape is precisely the check that a plausible typo passes: a US number with nine digits is
 * still a plus and seven-to-fifteen digits.
 */
internal object E164Numbers {

    /**
     * @param util libphonenumber, which on Android has to be built with a [android.content.Context].
     * @param e164 the number as typed, already in E.164 form.
     */
    fun isValidForRegistration(util: PhoneNumberUtil, e164: String): Boolean {
        // Signal's last line, and this app's only one. Kept first here because it is the
        // cheapest and because libphonenumber will not parse something this rejects.
        if (!GENERIC.matches(e164)) return false

        // Signal's first check. The region comes from the number's own country code, since by
        // here it is E.164 and carries one; upstream takes it from the country picker instead,
        // which is the same fact arrived at differently.
        val parsed = runCatching { util.parse(e164, null) }.getOrNull() ?: return false
        if (!util.isPossibleNumber(parsed)) return false

        return matchesCountryRule(e164, util.getRegionCodeForNumber(parsed))
    }

    /**
     * Signal's generic line: a plus, a non-zero country code, seven to fifteen digits total.
     *
     * Split out from [isValidForRegistration] so it can be tested without a device.
     * libphonenumber on Android needs a `Context` to load its metadata, so the one check that
     * genuinely cannot run in a plain unit test is `isPossibleNumber` -- which is upstream's
     * own well-covered code, not ours.
     */
    fun matchesGenericShape(e164: String): Boolean = GENERIC.matches(e164)

    /**
     * Signal's two country-specific rules, by the region the number belongs to.
     *
     * @param region a two-letter region code, as libphonenumber reports it, or null.
     */
    fun matchesCountryRule(e164: String, region: String?): Boolean = when (region) {
        // Exactly ten digits after the country code. This is the one that catches a dropped or
        // doubled digit in a US number, which the generic shape alone lets past.
        "US" -> US.matches(e164)
        // Two digits of area code, an optional 9, then eight. Upstream leaves the 9 optional,
        // so this is a length and shape rule rather than a mobile-vs-landline one -- and it is
        // upstream's rule, kept as it is written there.
        "BR" -> BR.matches(e164)
        else -> true
    }

    private val GENERIC = Regex("""^\+[1-9]\d{6,14}$""")

    private val US = Regex("""^\+1[0-9]{10}$""")
    private val BR = Regex("""^\+55[0-9]{2}9?[0-9]{8}$""")
}
