package com.wanderwildwood.kotozute.signalstore

import okhttp3.Dns
import okhttp3.Interceptor
import org.signal.network.config.SignalCdnUrl
import org.signal.network.config.SignalCdsiUrl
import org.signal.network.config.SignalProxy
import org.signal.network.config.SignalServiceConfiguration
import org.signal.network.config.SignalServiceUrl
import org.signal.network.config.SignalStorageUrl
import org.signal.network.config.SignalSvr2Url
import org.signal.network.config.TrustStore
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.io.InputStream
import java.util.Base64
import java.util.Optional

/**
 * Where to point at Signal.
 *
 * The service layer can open a provisioning socket in two calls, but it will not tell you
 * which servers to open it against: no artifact ships production configuration. Every client
 * carries its own. This one is a port of signal-cli's `LiveConfig.java`, which is GPL-3.0, as
 * is this app -- so it is a licence-compatible copy rather than a reimplementation, and it is
 * marked as one.
 *
 * Ported from signal-cli (AsamK), lib/.../manager/config/LiveConfig.java, GPL-3.0.
 * The trust store beside this file is signal-cli's `whisper.store`, unmodified: Signal pins
 * its own certificate authority, so the system trust store is not enough. It is BKS format,
 * which Android reads; a JKS one would not have loaded at all.
 *
 * These values go stale. Signal rotates enclaves and occasionally moves hosts, and a client
 * carrying old ones simply stops working. Check them against signal-cli whenever it is
 * upgraded rather than assuming they are constants.
 */
object SignalNetworkConfig {

    private const val URL = "https://chat.signal.org"
    private const val CDN_URL = "https://cdn.signal.org"
    private const val CDN2_URL = "https://cdn2.signal.org"
    private const val CDN3_URL = "https://cdn3.signal.org"
    private const val STORAGE_URL = "https://storage.signal.org"
    private const val CDSI_URL = "https://cdsi.signal.org"
    private const val SVR2_URL = "https://svr2.signal.org"

    private val zkGroupServerPublicParams: ByteArray = Base64.getDecoder().decode(
        "AMhf5ywVwITZMsff/eCyudZx9JDmkkkbV6PInzG4p8x3VqVJSFiMvnvlEKWuRob/1eaIetR31IYe" +
            "Abm0NdOuHH8Qi+Rexi1wLlpzIo1gstHWBfZzy1+qHRV5A4TqPp15YzBPm0WSggW6PbSn+F4lf57V" +
            "CnHF7p8SvzAA2ZZJPYJURt8X7bbg+H3i+PEjH9DXItNEqs2sNcug37xZQDLm7X36nOoGPs54XsEG" +
            "zPdEV+itQNGUFEjY6X9Uv+Acuks7NpyGvCoKxGwgKgE5XyJ+nNKlyHHOLb6N1NuHyBrZrgtY/JYJ" +
            "HRooo5CEqYKBqdFnmbTVGEkCvJKxLnjwKWf+fEPoWeQFj5ObDjcKMZf2Jm2Ae69x+ikU5gBXsRmo" +
            "F94GXTLfN0/vLt98KDPnxwAQL9j5V1jGOY8jQl6MLxEs56cwXN0dqCnImzVH3TZT1cJ8SW1BRX6q" +
            "IVxEzjsSGx3yxF3suAilPMqGRp4ffyopjMD1JXiKR2RwLKzizUe5e8XyGOy9fplzhw3jVzTRyUZT" +
            "RSZKkMLWcQ/gv0E4aONNqs4P+NameAZYOD12qRkxosQQP5uux6B2nRyZ7sAV54DgFyLiRcq1FvwK" +
            "w2EPQdk4HDoePrO/RNUbyNddnM/mMgj4FW65xCoT1LmjrIjsv/Ggdlx46ueczhMgtBunx1/w8k8V" +
            "+l8LVZ8gAT6wkU5J+DPQalQguMg12Jzug3q4TbdHiGCmD9EunCwOmsLuLJkz6EcSYXtrlDEnAM+h" +
            "icw7iergYLLlMXpfTdGxJCWJmP4zqUFeTTmsmhsjGBt7NiEB/9pFFEB3pSbf4iiUukw63Eo8Aqnf" +
            "4iwob6X1QviCWuc8t0LUlT9vALgh/f2DPVOOmR0RW6bgRvc7DSF20V/omg+YBw=="
    )
    /**
     * ⚠ Signal's **production** value, which is the version-0x01 keyset.
     *
     * This and the backup params below were copied from signal-cli's LiveConfig and are the
     * older 0x00 revision. Nothing in this app reads them yet, so the mismatch is inert -- but
     * they sit in a file whose own comment tells the next reader to trust it, and the first
     * use of group-send credentials or of archive would surface it as an opaque zkgroup
     * InvalidInputException rather than as "these constants are out of date".
     *
     * The zkgroup params above and the sender-certificate trust roots were already upstream's
     * exact production values; these two were the pair that had drifted.
     */
    private val genericServerPublicParams: ByteArray = Base64.getDecoder().decode(
        "AeCO67P9mIv1yUHkdeZ9JF789GDbox61GvTqq3S4kYc1ADUWxWHQygU390tv1oRWt9WjkdZlU7mK" +
            "kifF59ftjE+2ZlMmxns6I+ySiLpR8FEmfu+TGpVp3zYTjNV93obJJTyBCCsSHVETCyQRbKdCyb5T" +
            "Ma6LGrvcZaX0Q/VAavhuNA/m4kSiRMgSnYrUjGhVekdDnF+7xioo4wvFnxjIDh7uJQrYOWD6MloN" +
            "GX7St5gbysTuQQ7i/HI38b9V8x8mKazuDSXxB//BKGZx/XHkK8cHX+QK1MPxYUVM1/CBI5oW"
    )
    /** Signal's production value, the version-0x01 keyset. See [genericServerPublicParams]. */
    private val backupServerPublicParams: ByteArray = Base64.getDecoder().decode(
        "AZwNSU55fsFCbgaxGRD11wO1juAs8Yr5GF8FPlGzzvdJJIKH5/4CC7ZJSOe3yL2vturVaRU2Cx0n" +
            "751Vt8wkj1Y4pyiScu0/S10n647ipo+iq97JZQv+UOlwH8ThyNlGT5DfxXCwTqivxHuXvZpuezPg" +
            "Hk5Gxl5aC6xuNxOnwmFlmu4CeSgdhW8+Pp0vAJOQ1MsU2D0+/kzI+tU94nB3tybY/Ao1AcGW2q41" +
            "uKQbnOJUWwmQaFT6s+xTISgzsg7CPox6oORGX8rnyk/9lic3DbGsUHctIVpMAl/ogJBb4aYC"
    )

    /**
     * The roots that sign sender certificates, for sealed sender.
     *
     * **Two, not one, and both are current.** Signal rotated the root and kept the old one
     * valid, so a certificate may be signed by either. Carrying only the newer would reject
     * every message from a sender whose certificate predates the rotation -- as an
     * `InvalidMetadataMessageException`, which reads like a corrupt message rather than a
     * missing key.
     *
     * These are the trust anchors for *who sent a message* when the envelope deliberately does
     * not say. Without them there is no sealed sender at all, only the identified path.
     */
    private val unidentifiedSenderTrustRoots: List<ECPublicKey> = listOf(
        "BXu6QIKVz5MA8gstzfOgRQGqyLqOwNKHL6INkv3IHWMF",
        "BUkY0I+9+oPgDCn4+Ac6Iu813yvqkDr/ga8DzLxFxuk6"
    ).map { ECPublicKey(Base64.getDecoder().decode(it)) }

    fun certificateValidator(): CertificateValidator = CertificateValidator(unidentifiedSenderTrustRoots)

    /**
     * Signal pins its own CA, so the platform trust store is not sufficient. Loaded off the
     * classpath the way signal-cli does it, which works on Android because AGP packages
     * `src/main/resources` into the APK -- and which means this needs no Context, so the
     * configuration stays a plain object.
     */
    private val trustStore = object : TrustStore {
        override fun getKeyStoreInputStream(): InputStream =
            requireNotNull(SignalNetworkConfig::class.java.getResourceAsStream("/com/wanderwildwood/kotozute/signalnet/whisper.store")) {
                "whisper.store is missing from the APK"
            }

        override fun getKeyStorePassword(): String = "whisper"
    }

    /**
     * Signal identifies clients by this and nothing else.
     *
     * It has to be an interceptor: nothing in this stack takes a user-agent parameter. The
     * provisioning socket builds its own OkHttp client from the configuration and installs
     * only the interceptors found here, so a user agent held anywhere else -- a constant next
     * to the call site, say -- is simply never sent. That was the shape of the bug this
     * replaces: the string existed and reached nothing.
     */
    private val userAgentInterceptor = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
    }

    const val USER_AGENT = "kotozute/1.11.2"

    /** Signal's production servers. */
    fun production(): SignalServiceConfiguration = SignalServiceConfiguration(
        arrayOf(SignalServiceUrl(URL, trustStore)),
        mapOf(
            0 to arrayOf(SignalCdnUrl(CDN_URL, trustStore)),
            2 to arrayOf(SignalCdnUrl(CDN2_URL, trustStore)),
            3 to arrayOf(SignalCdnUrl(CDN3_URL, trustStore))
        ),
        arrayOf(SignalStorageUrl(STORAGE_URL, trustStore)),
        arrayOf(SignalCdsiUrl(CDSI_URL, trustStore)),
        arrayOf(SignalSvr2Url(SVR2_URL, trustStore, null, null)),
        listOf(userAgentInterceptor),
        Optional.empty<Dns>(),
        Optional.empty<SignalProxy>(),
        Optional.empty<org.signal.network.config.HttpProxy>(),
        zkGroupServerPublicParams,
        genericServerPublicParams,
        backupServerPublicParams,
        false
    )

    // --- staging -------------------------------------------------------------------------
    //
    // Signal runs a second, separate world at these hosts, with its own accounts and its own
    // key material. It exists here for one reason: **registration cannot be tested against
    // production without taking a real number over.** Registering is not reversible by tapping
    // back -- one primary per number, every linked device dropped -- so the flow went unexercised
    // rather than risk a number that matters, and an unexercised registration path is exactly
    // the thing that fails in front of the first person who ever needs it.
    //
    // ⚠ **A staging account is not a Signal account.** Nobody on real Signal can reach it and
    // it cannot reach them. That is the point: a number here can be registered, re-registered
    // and thrown away, and none of it touches the account the phone actually uses.
    //
    // ⚠ Every constant below differs from production and they are **not interchangeable**.
    // The zkgroup params in particular are a different keyset; mixing one environment's params
    // with another's hosts surfaces as an opaque zkgroup `InvalidInputException` rather than as
    // "wrong environment". They are copied from upstream's `app/build.gradle.kts` staging
    // flavour and go stale the same way the production ones do.

    private const val STAGING_URL = "https://chat.staging.signal.org"
    private const val STAGING_CDN_URL = "https://cdn-staging.signal.org"
    private const val STAGING_CDN2_URL = "https://cdn2-staging.signal.org"
    private const val STAGING_CDN3_URL = "https://cdn3-staging.signal.org"
    private const val STAGING_STORAGE_URL = "https://storage-staging.signal.org"
    private const val STAGING_CDSI_URL = "https://cdsi.staging.signal.org"
    private const val STAGING_SVR2_URL = "https://svr2.staging.signal.org"

    private val stagingZkGroupServerPublicParams: ByteArray = Base64.getDecoder().decode(
        "ABSY21VckQcbSXVNCGRYJcfWHiAMZmpTtTELcDmxgdFbtp/bWsSxZdMKzfCp8rvIs8ocCU3B37fT" +
            "3r4Mi5qAemeGeR2X+/YmOGR5ofui7tD5mDQfstAI9i+4WpMtIe8KC3wU5w3Inq3uNWVmoGtpKnds" +
            "NfwJrCg0Hd9zmObhypUnSkfYn2ooMOOnBpfdanRtrvetZUayDMSC5iSRcXKpdlukrpzzsCIvEwjw" +
            "QlJYVPOQPj4V0F4UXXBdHSLK05uoPBCQG8G9rYIGedYsClJXnbrgGYG3eMTG5hnx4X4ntARBgELu" +
            "MWWUEEfSK0mjXg+/2lPmWcTZWR9nkqgQQP0tbzuiPm74H2wMO4u1Wafe+UwyIlIT9L7KLS19Aw8r" +
            "4sPrXZSSsOZ6s7M1+rTJN0bI5CKY2PX29y5Ok3jSWufIKcgKOnWoP67d5b2du2ZVJjpjfibNIHbT" +
            "/cegy/sBLoFwtHogVYUewANUAXIaMPyCLRArsKhfJ5wBtTminG/PAvuBdJ70Z/bXVPf8TVsR292z" +
            "Q65xwvWTejROW6AZX6aqucUjlENAErBme1YHmOSpU6tr6doJ66dPzVAWIanmO/5mgjNEDeK7DDqQ" +
            "dB1xd03HT2Qs2TxY3kCK8aAb/0iM0HQiXjxZ9HIgYhbtvGEnDKW5ILSUydqH/KBhW4Pb0jZWnqN/" +
            "YgbWDKeJxnDbYcUob5ZY5Lt5ZCMKuaGUvCJRrCtuugSMaqjowCGRempsDdJEt+cMaalhZ6gczklJ" +
            "B/IbdwENW9KeVFPoFNFzhxWUIS5ML9riVYhAtE6JE5jX0xiHNVIIPthb458cfA8daR0nYfYAUKog" +
            "QArm0iBezOO+mPk5vCNWI+wwkyFCqNDXz/qxl1gAntuCJtSfq9OC3NkdhQlgYQ=="
    )

    private val stagingGenericServerPublicParams: ByteArray = Base64.getDecoder().decode(
        "AYhaw+NbxtNLo/RlGFEsHd904hW38LpPJ59jYJlNmT4wwtyOq4xzCs/MyXsfRbIsAYhQjDnpE0rh" +
            "FtWkMcn/kV740SISwFfpPHunrtZ9h0YWz5QNNbI5I3DRGUjhKXgMU7J7s7qOr0fdms+g0e+L9FMS" +
            "jJLobDkOngp/m0B5TsxTyqLscJ5VyU69Cj8txImTfHMCKrYphYfRHO78RwPoz2g2tGUAzEbKHm12" +
            "OgDna2qutkE5TvYqwZczvgZyLVHdHXpvdyOlEdv4afVyWkI7u/S0XYDonIJoHlxqJoTSepZR"
    )

    private val stagingBackupServerPublicParams: ByteArray = Base64.getDecoder().decode(
        "AXYrGb9IfugAAJiPKp+mdXUx+OL9zBolPYHYQz6GI1gWjpEu5me3zVNSvmYY4zWboZHif+HG1sDH" +
            "SuvwFd0QszS6h3nZ6vRdM/IYGK+cLynw3ucWo7idf3zjOG3b6JnGT/z7XYCr6HuOGkWH4DQWCH98" +
            "hxVZMGOgmT8DCQoqebQb3oK1yrwEglRWmtI01KhRg9RGUKoQiwuej1JZEY8uaG4Uz9n1cVODJ1iu" +
            "ByhNqGHo+KfI4iWhjtx2AnhYqHViQ3CMd4ASGBJtic9UTFVk/4vegVIy0wfYsAmViftzK6t4"
    )

    /**
     * Staging's sender-certificate roots. Two, as production has two, and neither is shared
     * with it -- a production root cannot validate a staging certificate.
     */
    private val stagingUnidentifiedSenderTrustRoots: List<ECPublicKey> = listOf(
        "BbqY1DzohE4NUZoVF+L18oUPrK3kILllLEJh2UnPSsEx",
        "BYhU6tPjqP46KGZEzRs1OL4U39V5dlPJ/X09ha4rErkm"
    ).map { ECPublicKey(Base64.getDecoder().decode(it)) }

    fun stagingCertificateValidator(): CertificateValidator =
        CertificateValidator(stagingUnidentifiedSenderTrustRoots)

    /**
     * Signal's staging servers, for exercising registration without a real number.
     *
     * ⚠ The trust store is the **same** [trustStore] production uses, and that is what
     * upstream does: `NetworkDependenciesModule:256` and `SignalServiceNetworkAccess:171`
     * both build one `SignalServiceTrustStore` with no reference to the build flavour, so the
     * pinned CA covers both environments. The copy here came from signal-cli rather than from
     * upstream, though, so if staging is the one environment that fails its TLS handshake,
     * this is the first thing to suspect -- and upstream's
     * `app/src/main/res/raw/whisper.store` is the copy known to serve both.
     */
    fun staging(): SignalServiceConfiguration = SignalServiceConfiguration(
        arrayOf(SignalServiceUrl(STAGING_URL, trustStore)),
        mapOf(
            0 to arrayOf(SignalCdnUrl(STAGING_CDN_URL, trustStore)),
            2 to arrayOf(SignalCdnUrl(STAGING_CDN2_URL, trustStore)),
            3 to arrayOf(SignalCdnUrl(STAGING_CDN3_URL, trustStore))
        ),
        arrayOf(SignalStorageUrl(STAGING_STORAGE_URL, trustStore)),
        arrayOf(SignalCdsiUrl(STAGING_CDSI_URL, trustStore)),
        arrayOf(SignalSvr2Url(STAGING_SVR2_URL, trustStore, null, null)),
        listOf(userAgentInterceptor),
        Optional.empty<Dns>(),
        Optional.empty<SignalProxy>(),
        Optional.empty<org.signal.network.config.HttpProxy>(),
        stagingZkGroupServerPublicParams,
        stagingGenericServerPublicParams,
        stagingBackupServerPublicParams,
        false
    )

    /**
     * The captcha page for [environment].
     *
     * ⚠ Separate pages, not one page that works for both. A production captcha token is not
     * accepted by staging and the reverse is also true; the server answers with a rejected
     * captcha, which reads as "solve it again" and never succeeds however many times it is
     * tried. Upstream keeps the two as separate `SIGNAL_CAPTCHA_URL` build fields for this
     * reason.
     */
    fun captchaUrl(environment: Environment): String = when (environment) {
        Environment.PRODUCTION -> "https://signalcaptchas.org/registration/generate.html"
        Environment.STAGING -> "https://signalcaptchas.org/staging/registration/generate.html"
    }

    /**
     * Which Signal to talk to.
     *
     * ⛔ **Staging is a debug affordance and must never be reachable in a release build.** An
     * account registered against staging looks registered -- the screens say so, the socket
     * connects -- while being unable to exchange a message with a single real person. That is
     * a worse failure than not registering at all, because nothing about it announces itself.
     * Whatever offers the choice gates it on `BuildConfig.DEBUG`; [environment] refuses the
     * change anyway, so the gate is the second lock rather than the only one.
     */
    enum class Environment { PRODUCTION, STAGING }

    /**
     * The environment everything in this app talks to. **One answer, asked in one place.**
     *
     * There are around two dozen call sites that need a configuration, and this is the only
     * thing that decides for all of them. That is deliberate and it is the whole design: an
     * environment threaded through two dozen constructors is two dozen chances for one of them
     * to keep production while the rest moved, and the failure that produces -- a registration
     * against one world and a message socket against another -- does not look like a
     * misconfiguration from the outside. It looks like Signal being broken.
     *
     * ⛔ **Refused outright in a release build.** Not gated at the caller, refused here: a
     * caller that forgets the gate is the case this is for.
     *
     * ⚠ Set once, before anything opens a connection. Nothing re-reads it, and the stores that
     * hold a configuration build theirs lazily and keep it -- so moving it under a running app
     * changes some things and not others, which is the incoherent state this exists to prevent.
     * Changing it means restarting the process.
     */
    @Volatile
    var environment: Environment = Environment.PRODUCTION
        set(value) {
            if (value == Environment.STAGING && !isDebugBuild()) {
                // Loud, and then ignored. A release build asking for staging is a bug in the
                // caller, and silently honouring it would be the worst of the outcomes here.
                android.util.Log.w("SignalNetworkConfig", "refusing staging in a release build")
                return
            }
            field = value
        }

    /**
     * Whether this is a debug build, without this module depending on the app's BuildConfig.
     *
     * `data` has its own BuildConfig and its `DEBUG` follows the build type, so this is the
     * same answer the app's would give, read from the module that is actually asking.
     */
    private fun isDebugBuild(): Boolean = com.wanderwildwood.kotozute.data.BuildConfig.DEBUG

    /** The configuration for whatever [environment] says. Every call site uses this. */
    fun configuration(): SignalServiceConfiguration =
        if (environment == Environment.STAGING) staging() else production()

    /** The sender-certificate roots for whatever [environment] says. */
    fun currentCertificateValidator(): CertificateValidator =
        if (environment == Environment.STAGING) stagingCertificateValidator() else certificateValidator()

    /** The captcha page for whatever [environment] says. */
    fun currentCaptchaUrl(): String = captchaUrl(environment)
}
