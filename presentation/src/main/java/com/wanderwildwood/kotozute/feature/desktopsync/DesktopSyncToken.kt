package com.wanderwildwood.kotozute.feature.desktopsync

/**
 * Compares without stopping at the first character that differs.
 *
 * `==` returns as soon as it finds a mismatch, so how long the answer takes says how much of
 * the token the caller got right, and a patient caller on the same network can walk a secret
 * out one character at a time. Over a LAN, through NanoHTTPD, against a token of this length,
 * that attack is not remotely practical -- but a check that is careful in one place and
 * casual in another is worth neither reasoning about nor explaining.
 *
 * Length is not treated as a secret: the token's length is fixed and public.
 */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var difference = 0
    for (i in a.indices) difference = difference or (a[i].code xor b[i].code)
    return difference == 0
}
