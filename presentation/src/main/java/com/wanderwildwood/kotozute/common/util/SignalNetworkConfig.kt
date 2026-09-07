package com.wanderwildwood.kotozute.common.util

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
 * EXPERIMENT (signal-on-the-phone branch). Where to point at Signal.
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
    private val genericServerPublicParams: ByteArray = Base64.getDecoder().decode(
        "AByD873dTilmOSG0TjKrvpeaKEsUmIO8Vx9BeMmftwUs9v7ikPwM8P3OHyT0+X3EUMZrSe9VUp26" +
            "Wai51Q9I8mdk0hX/yo7CeFGJyzoOqn8e/i4Ygbn5HoAyXJx5eXfIbqpc0bIxzju4H/HOQeOpt6h7" +
            "42qii5u/cbwOhFZCsMIbElZTaeU+BWMBQiZHIGHT5IE0qCordQKZ5iPZom0HeFa8Yq0ShuEyAl0W" +
            "INBiY6xE3H/9WnvzXBbMuuk//eRxXgzO8ieCeK8FwQNxbfXqZm6Ro1cMhCOF3u7xoX83QhpN"
    )
    private val backupServerPublicParams: ByteArray = Base64.getDecoder().decode(
        "AJwNSU55fsFCbgaxGRD11wO1juAs8Yr5GF8FPlGzzvdJJIKH5/4CC7ZJSOe3yL2vturVaRU2Cx0n" +
            "751Vt8wkj1bozK3CBV1UokxV09GWf+hdVImLGjXGYLLhnI1J2TWEe7iWHyb553EEnRb5oxr9n3lU" +
            "bNAJuRmFM7hrr0Al0F0wrDD4S8lo2mGaXe0MJCOM166F8oYRQqpFeEHfiLnxA1O8ZLh7vMdv4g9j" +
            "I5phpRBTsJ5IjiJrWeP0zdIGHEssUeprDZ9OUJ14m0v61eYJMKsf59Bn+mAT2a7YfB+Don9O"
    )

    /**
     * Signal pins its own CA, so the platform trust store is not sufficient. Loaded off the
     * classpath the way signal-cli does it, which works on Android because AGP packages
     * `src/main/resources` into the APK -- and which means this needs no Context, so the
     * configuration stays a plain object.
     */
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
}
