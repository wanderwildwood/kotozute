package com.wanderwildwood.kotozute.signal

/**
 * Everything needed to reach a kotozute-bridge, as carried by its pairing payload:
 *
 *     kotozute-bridge://<host>:<port>/?token=<token>&fp=<sha256 hex, no colons>
 *
 * The fingerprint matters as much as the token. The bridge serves a self-signed
 * certificate -- there is no CA to check it against -- so the app pins this exact
 * certificate. Without the pin, TLS here would prove nothing.
 */
data class BridgeConfig(
    val host: String,
    val port: Int,
    val token: String,
    val fingerprint: String
) {
    val baseUrl: String get() = "https://$host:$port"

    fun isValid(): Boolean =
        host.isNotBlank() && port in 1..65535 && token.length >= 16 && fingerprint.length == 64

    companion object {
        private val PATTERN = Regex(
            """^kotozute-bridge://([^:/\s]+):(\d+)/?\?(.*)$""", RegexOption.IGNORE_CASE
        )

        /** Parses a pairing payload, or returns null if it is not one. */
        fun parse(raw: String): BridgeConfig? {
            val m = PATTERN.find(raw.trim()) ?: return null
            val (host, portStr, query) = m.destructured
            val params = query.split('&')
                .mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
                }
                .toMap()
            val cfg = BridgeConfig(
                host = host,
                port = portStr.toIntOrNull() ?: return null,
                token = params["token"].orEmpty(),
                fingerprint = params["fp"].orEmpty().replace(":", "").uppercase()
            )
            return if (cfg.isValid()) cfg else null
        }
    }
}
