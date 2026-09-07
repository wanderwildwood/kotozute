package com.wanderwildwood.kotozute.feature.desktopsync

/**
 * Whether a caller presented the right Desktop Sync token.
 *
 * A free function rather than a method on the server so it can be tested without standing up
 * a web server, an HTTP session and a Realm. What it decides is who may read every message on
 * the phone, which is the last thing that should only be exercised by hand.
 *
 * Tokens issued since the move to Crockford's alphabet carry no lowercase, and those match
 * case-insensitively: the string is typed by a person, and a browser or a keyboard that
 * helpfully capitalises should not lock them out. Older mixed-case tokens still have to match
 * exactly, because for those the case is information.
 */
internal fun tokenMatches(supplied: String?, token: String): Boolean {
    if (supplied == null) return false
    if (constantTimeEquals(supplied, token)) return true
    if (token.any { it.isLowerCase() }) return false
    return constantTimeEquals(supplied.uppercase(), token.uppercase())
}

/**
 * Compares without stopping at the first character that differs.
 *
 * `==` returns as soon as it finds a mismatch, so how long the answer takes says how much of
 * the token the caller got right, and a patient caller on the same network can walk a secret
 * out one character at a time. Over a LAN, through NanoHTTPD, against a token of this length,
 * that attack is not remotely practical -- but the bridge is careful about exactly this on
 * its side of the same secret, and a check that is careful at one end and casual at the other
 * is worth neither reasoning about nor explaining.
 *
 * Length is not treated as a secret: the token's length is fixed and public.
 */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var difference = 0
    for (i in a.indices) difference = difference or (a[i].code xor b[i].code)
    return difference == 0
}
