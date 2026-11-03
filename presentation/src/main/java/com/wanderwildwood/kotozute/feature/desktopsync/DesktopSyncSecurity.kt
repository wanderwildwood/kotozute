package com.wanderwildwood.kotozute.feature.desktopsync

import java.security.MessageDigest
import java.security.SecureRandom

/*
 * The rules that decide who may reach Desktop Sync and how a browser proves itself, kept apart
 * from the server so each one can be tested on a desk. They follow Memo's server, which was the
 * stricter of the two:
 *
 * - every browser holds its own token, and the phone keeps only a SHA-256 of it;
 * - a token travels in an Authorization header, or in a cookie the browser cannot read, and
 *   never in a URL, so it is not in history, a bookmark, a manifest or a launcher file;
 * - with "VPN only" off, only private addresses are let in, never the open internet;
 * - every answer says: no caching, no sniffing, no referrer, no framing, and the page carries a
 *   strict Content-Security-Policy.
 */

// ------------------------------------------------------------------------------- who may connect

/**
 * Whether a peer is on a private network: this phone, 10/8, 172.16/12, 192.168/16, link-local
 * (169.254/16, fe80::/10), unique-local (fc00::/7) or the 100.64/10 range Tailscale uses. With
 * "VPN only" off these are still the only peers let in, so a global IPv6 address or a carrier's
 * public address never reaches the server. Anything that is not a literal address is no.
 */
internal fun isPrivatePeer(ip: String?): Boolean {
    val addr = ip?.substringBefore('%')?.lowercase()?.trim() ?: return false
    // A literal only: a name made of hex letters and dots must never reach a DNS lookup.
    val v4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(addr)
    val v6 = ':' in addr && Regex("^[0-9a-f.:]+$").matches(addr)
    if (!v4 && !v6) return false
    // Four numbers are read here rather than by InetAddress, which takes "999.1.1.1" for a
    // host name and asks DNS about it. Anything with a colon is only ever read as IPv6.
    val bytes = if (v4) {
        val parts = addr.split('.').map { it.toInt() }
        if (parts.any { it > 255 }) return false
        ByteArray(4) { parts[it].toByte() }
    } else {
        runCatching { java.net.InetAddress.getByName(addr).address }.getOrNull() ?: return false
    }
    if (bytes.size == 4) {
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }
    if (bytes.size == 16) {
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        val loopback = bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1
        // An IPv4 address written as IPv6 (::ffff:a.b.c.d) is judged as the IPv4 it is.
        val mapped = bytes.take(10).all { it.toInt() == 0 } &&
            (bytes[10].toInt() and 0xFF) == 0xFF && (bytes[11].toInt() and 0xFF) == 0xFF
        if (mapped) {
            val v4text = (12..15).joinToString(".") { (bytes[it].toInt() and 0xFF).toString() }
            return isPrivatePeer(v4text)
        }
        return loopback || (a and 0xFE) == 0xFC || (a == 0xFE && (b and 0xC0) == 0x80)
    }
    return false
}

/**
 * The gate in front of everything, the token included. With "VPN only" on: this phone,
 * Tailscale, or a trusted VPN's network. With it off: still only private addresses.
 */
internal fun peerAllowed(ip: String?, vpnOnly: Boolean, vpnNets: List<DesktopSyncService.Companion.Subnet>): Boolean =
    if (vpnOnly) DesktopSyncService.isAllowedPeer(ip, vpnNets) else isPrivatePeer(ip)

// ------------------------------------------------------------------------- how a browser proves itself

/** The cookie's name carries the port, so a debug build beside a release build keeps its own. */
internal fun sessionCookieName(port: Int) = "kotozute_$port"

/** A month, as long as an unused pairing lives. */
internal const val SESSION_COOKIE_SECONDS = 30L * 24 * 60 * 60

/**
 * The Set-Cookie value that lets the page's pictures, voice notes, videos, downloads and its
 * WebSocket prove themselves, none of which can send a header. HttpOnly, so the page's own
 * script never sees it; SameSite=Strict, so no other site's page can send it; Secure over HTTPS.
 */
internal fun sessionCookie(port: Int, token: String, secure: Boolean): String =
    sessionCookieName(port) + "=" + token + "; Path=/; Max-Age=" + SESSION_COOKIE_SECONDS +
        "; HttpOnly; SameSite=Strict" + (if (secure) "; Secure" else "")

/** The cookie that clears it again, sent when a browser's token is refused. */
internal fun clearedSessionCookie(port: Int, secure: Boolean): String =
    sessionCookieName(port) + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" + (if (secure) "; Secure" else "")

/** One cookie's value out of a Cookie header, or null. */
internal fun cookieValue(header: String?, name: String): String? {
    if (header.isNullOrEmpty()) return null
    return header.split(';').asSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotEmpty() }
}

/** The token out of an `Authorization: Bearer` header, or null. */
internal fun bearerToken(header: String?): String? {
    val prefix = "Bearer "
    if (header == null || !header.startsWith(prefix)) return null
    return header.substring(prefix.length).takeIf { it.isNotEmpty() }
}

/**
 * The token a request presents, from the only two places one is taken: the Authorization
 * header (what the page's own fetches send) or, for a GET only, the session cookie (what a
 * picture, a voice note, a download or the WebSocket sends, none of which can carry a header).
 *
 * Never from the query string: a token in a URL ends up in history, in bookmarks and in logs.
 * Never from the cookie on a POST: a change is only ever made by the page's own script, which
 * sends the header, so another page on the same address cannot ride on the cookie.
 *
 * [headers] are lower-case names, as NanoHTTPD gives them. [query] is handed in only so the
 * rule that it is never read can be tested: a `?token=` in it changes nothing.
 */
@Suppress("UNUSED_PARAMETER")
internal fun presentedToken(
    method: String,
    headers: Map<String, String>,
    query: Map<String, List<String>>,
    port: Int,
): String? {
    bearerToken(headers["authorization"])?.let { return it }
    if (!method.equals("GET", ignoreCase = true)) return null
    return cookieValue(headers["cookie"], sessionCookieName(port))
}

/**
 * Whether a WebSocket upgrade came from this server's own page. A browser always sends Origin
 * on one, and a page elsewhere cannot forge it, so a page on another address that knows the
 * phone's cannot open a socket that rides on the cookie.
 */
internal fun sameOrigin(origin: String?, host: String?): Boolean {
    if (origin.isNullOrEmpty() || host.isNullOrEmpty()) return false
    val withoutScheme = when {
        origin.startsWith("http://") -> origin.removePrefix("http://")
        origin.startsWith("https://") -> origin.removePrefix("https://")
        else -> return false
    }
    return withoutScheme.equals(host, ignoreCase = true)
}

// ----------------------------------------------------------------------------------- headers

/** Every answer: no caching, nothing sniffed, no referrer, no framing. No CORS header is ever added. */
internal val SECURITY_HEADERS: Map<String, String> = linkedMapOf(
    "Cache-Control" to "no-store",
    "X-Content-Type-Options" to "nosniff",
    "Referrer-Policy" to "no-referrer",
    "X-Frame-Options" to "DENY",
    "Cross-Origin-Resource-Policy" to "same-origin",
)

/** The header the page sets on every change it asks for. A page elsewhere cannot add it without a preflight that is never answered. */
internal const val PAGE_HEADER = "x-kotozute"

/**
 * The dashboard's Content-Security-Policy: its own script file and the one style block that
 * carries this answer's [nonce]; pictures, sound and video from the phone or from a file being
 * attached; connections back to the phone only, the WebSocket included. Nothing inline runs.
 * [host] is the Host header, only if it looks like a host, for the WebSocket's address.
 */
internal fun pageCsp(nonce: String, host: String?): String {
    val safeHost = host?.takeIf { Regex("^[A-Za-z0-9.\\-:\\[\\]]{1,100}$").matches(it) }
    val ws = if (safeHost != null) " ws://$safeHost wss://$safeHost" else ""
    return "default-src 'none'; script-src 'self'; style-src 'nonce-$nonce'; " +
        "img-src 'self' blob: data:; media-src 'self' blob:; font-src 'self'; " +
        "connect-src 'self'$ws; manifest-src 'self'; base-uri 'none'; form-action 'none'; " +
        "frame-ancestors 'none'"
}

/** For the small Signal link page: nothing but its one picture and its one style block. */
internal fun linkPageCsp(nonce: String): String =
    "default-src 'none'; img-src data:; style-src 'nonce-$nonce'; base-uri 'none'; " +
        "form-action 'none'; frame-ancestors 'none'"

// ------------------------------------------------------------------------------- paired browsers

/**
 * The browsers that have paired with Desktop Sync. Each holds its own token, made when its
 * six-digit code was accepted. The phone keeps only a SHA-256 of it, a label, the day it was
 * made and the day it was last used; the token itself is given once, to the browser that
 * asked, and never written down on the phone or logged.
 *
 * Earlier versions gave every browser the same token. That token, if there was one, is carried
 * over once as a single entry that can do one thing only: trade itself, from a browser that
 * still holds it, for that browser's own token ([exchangeLegacy]). It shows in the list, can be
 * forgotten like any other, and goes after 30 days from the update whatever happens.
 */
class PairedBrowsers internal constructor(
    private val now: () -> Long,
    private val random: SecureRandom = SecureRandom(),
    initial: String = "",
    private val save: (String) -> Unit = {},
) {

    companion object {
        const val MAX_BROWSERS = 8
        const val EXPIRES_MS = 30L * 24 * 60 * 60 * 1000
        const val TOKEN_BYTES = 32
        const val LABEL_MAX = 40

        /** The id of the entry carried over from the one shared token. */
        const val LEGACY_ID = "legacy"

        /** A "last used" time is written to storage at most this often. */
        private const val SAVE_EVERY_MS = 60L * 60 * 1000

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    /** What the settings screen shows. The hash never leaves this class. */
    data class Browser(val id: String, val label: String, val created: Long, val lastUsed: Long) {
        val legacy get() = id == LEGACY_ID
    }

    private class Entry(val id: String, val hash: String, val label: String, val created: Long, var lastUsed: Long, var savedAt: Long)

    private val entries = ArrayList<Entry>()

    /** Told which browsers were just removed (or expired), so their open connections can be closed. */
    @Volatile var onRemoved: ((Set<String>) -> Unit)? = null

    /** Told whenever the list changes, so a screen showing it can redraw. */
    @Volatile var onChanged: (() -> Unit)? = null

    init {
        entries.addAll(parse(initial))
        if (expireLocked().isNotEmpty()) persistLocked()
    }

    /**
     * Carries an earlier version's shared token over as the one legacy entry, if there is a
     * token and no legacy entry yet. Its 30 days start now and are not renewed by use.
     */
    @Synchronized
    fun importLegacy(sharedToken: String, label: String) {
        if (sharedToken.isEmpty() || entries.any { it.id == LEGACY_ID }) return
        val t = now()
        entries.add(Entry(LEGACY_ID, hash(sharedToken), cleanLabel(label), t, t, t))
        persistLocked()
    }

    /** Makes a new token for a browser that has just proved itself, and returns it. */
    fun issue(label: String): String {
        val token: String
        val removed = HashSet<String>()
        synchronized(this) {
            removed += expireLocked()
            token = ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            val t = now()
            // Room for the new one: the browser unused for longest goes.
            while (entries.size >= MAX_BROWSERS) {
                val oldest = entries.minByOrNull { it.lastUsed } ?: break
                entries.remove(oldest)
                removed += oldest.id
            }
            entries.add(Entry(newId(), hash(token), cleanLabel(label), t, t, t))
            persistLocked()
        }
        if (removed.isNotEmpty()) onRemoved?.invoke(removed)
        onChanged?.invoke()
        return token
    }

    /**
     * The id of the browser this token belongs to, or null. The legacy shared token is not a
     * browser and is never accepted here. Looks at every entry, whatever it finds, so the time
     * taken says nothing about how much was right.
     */
    fun check(token: String?): String? = find(token, legacy = false, touch = true)

    /**
     * A browser still holding the old shared token trades it for a token of its own, labelled
     * [label]. Null if [token] is not the shared token (or it has been forgotten or has run out).
     * The shared token keeps working for the other browsers that hold it until it runs out.
     */
    fun exchangeLegacy(token: String?, label: String): String? {
        find(token, legacy = true, touch = false) ?: return null
        return issue(label)
    }

    /** True if there was such a browser. Its token stops working at once. */
    fun remove(id: String): Boolean {
        val had: Boolean
        synchronized(this) {
            had = entries.removeAll { it.id == id }
            if (had) persistLocked()
        }
        if (had) {
            onRemoved?.invoke(setOf(id))
            onChanged?.invoke()
        }
        return had
    }

    fun removeAll() {
        val ids: Set<String>
        synchronized(this) {
            ids = entries.map { it.id }.toSet()
            entries.clear()
            persistLocked()
        }
        if (ids.isNotEmpty()) {
            onRemoved?.invoke(ids)
            onChanged?.invoke()
        }
    }

    /** The paired browsers, most recently used first; anything gone 30 days unused is dropped first. */
    fun list(): List<Browser> {
        val removed: Set<String>
        val out: List<Browser>
        synchronized(this) {
            removed = expireLocked()
            if (removed.isNotEmpty()) persistLocked()
            out = entries.sortedByDescending { it.lastUsed }.map { Browser(it.id, it.label, it.created, it.lastUsed) }
        }
        if (removed.isNotEmpty()) onRemoved?.invoke(removed)
        return out
    }

    // ------------------------------------------------------------------ inside

    private fun find(token: String?, legacy: Boolean, touch: Boolean): String? {
        if (token.isNullOrEmpty() || token.length > 200) return null
        val given = hash(token)
        // Older shared tokens were matched without regard to case, because they were typed.
        val givenUpper = if (legacy) hash(token.uppercase()) else given
        var removed: Set<String> = emptySet()
        var found: String? = null
        synchronized(this) {
            removed = expireLocked()
            var hit: Entry? = null
            for (e in entries) {
                if ((e.id == LEGACY_ID) != legacy) continue
                if (constantTimeEquals(e.hash, given) or constantTimeEquals(e.hash, givenUpper)) hit = e
            }
            if (hit != null) {
                found = hit.id
                val t = now()
                if (touch) {
                    hit.lastUsed = t
                    if (t - hit.savedAt >= SAVE_EVERY_MS) {
                        hit.savedAt = t
                        persistLocked()
                    }
                }
            }
            if (removed.isNotEmpty()) persistLocked()
        }
        if (removed.isNotEmpty()) onRemoved?.invoke(removed)
        return found
    }

    private fun expireLocked(): Set<String> {
        val t = now()
        val old = entries.filter { t - it.lastUsed > EXPIRES_MS || it.lastUsed - t > EXPIRES_MS }
        if (old.isEmpty()) return emptySet()
        entries.removeAll(old.toSet())
        return old.map { it.id }.toSet()
    }

    private fun persistLocked() {
        save(entries.joinToString("\n") { "${it.id}\t${it.hash}\t${it.created}\t${it.lastUsed}\t${it.label}" })
    }

    private fun parse(text: String): List<Entry> = text.lineSequence().mapNotNull { line ->
        val p = line.split('\t', limit = 5)
        if (p.size < 5) return@mapNotNull null
        val created = p[2].toLongOrNull() ?: return@mapNotNull null
        val used = p[3].toLongOrNull() ?: return@mapNotNull null
        if (p[1].length != 64) return@mapNotNull null
        Entry(p[0], p[1], cleanLabel(p[4]), created, used, used)
    }.take(MAX_BROWSERS + 1).toList()

    private fun newId(): String {
        while (true) {
            val id = ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            if (id != LEGACY_ID && entries.none { it.id == id }) return id
        }
    }
}

/** A label that is safe to store and show: no control characters, no tabs, at most 40 characters. */
internal fun cleanLabel(raw: String?): String {
    val s = (raw ?: "").filter { !it.isISOControl() }.trim().take(PairedBrowsers.LABEL_MAX).trim()
    return s.ifEmpty { "A browser" }
}

/** "Firefox on Linux" from a User-Agent, which is all a browser tells; unknown parts are left out. */
internal fun browserLabel(userAgent: String?): String {
    val ua = userAgent ?: return "A browser"
    val browser = when {
        "Edg/" in ua || "EdgA/" in ua -> "Edge"
        "OPR/" in ua -> "Opera"
        "Firefox/" in ua -> "Firefox"
        "Chrome/" in ua || "CriOS/" in ua -> "Chrome"
        "Safari/" in ua -> "Safari"
        else -> null
    }
    val system = when {
        "Android" in ua -> "Android"
        "iPhone" in ua || "iPad" in ua -> "iOS"
        "Windows" in ua -> "Windows"
        "Mac OS X" in ua || "Macintosh" in ua -> "Mac"
        "CrOS" in ua -> "ChromeOS"
        "Linux" in ua || "X11" in ua -> "Linux"
        else -> null
    }
    return when {
        browser != null && system != null -> "$browser on $system"
        browser != null -> browser
        system != null -> "A browser on $system"
        else -> "A browser"
    }
}
