package com.wanderwildwood.kotozute.common

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * The other apps of this set that an app hands things to by name, and the certificate each one's
 * releases are signed with. The same file in every app that hands something over (shared-houseapps).
 *
 * A hand-off goes to an app only when the app holding its name is that app, signed with its
 * release certificate and nothing else: on a phone without it any app could take the name. When
 * it is not, the app acts as if it were not installed. A debuggable build also takes one signed
 * with a debug key, so two debug builds can be tried together.
 */
object HouseApps {

    const val MEMO = "com.wanderwildwood.oboegaki"
    const val DATEBOOK = "com.wanderwildwood.koyomi"
    const val PASSWORDS = "com.wanderwildwood.aikotoba"
    const val WALLET = "com.wanderwildwood.satsuire"
    const val PICTURES = "com.wanderwildwood.garo"
    const val CONNECTIONS = "com.wanderwildwood.enishi"

    /** SHA-256 of the certificate each app's releases are signed with, lower-case hex (read from its release APK). */
    val RELEASE_CERTS_SHA256 = mapOf(
        MEMO to "20e289f7dcdd22e11dc4dfdb46ecd1b883615f3350904c7575afc1849632514e",
        DATEBOOK to "96f0e571a4e14ce861d40e4f6c536adac2026fa271b8a4ec932eb922351957bd",
        PASSWORDS to "9b9fbd10920e2c8a94609f3aa9a1bb11213e36d5d21b53c973de11033fba25e1",
        WALLET to "e8dd0e8acf311e519c336dc5898acb0eba0eb627b51306cf3cdb594c3ffdd637",
        PICTURES to "fb01c054c1e349eb5df5937c7e6c4968824dd719ada7566342dfcb1a562d96c5",
        CONNECTIONS to "def146af0a169328cee14b3cb40db62f8d352fadf1e20371dbcf68018c8a5c5b",
    )

    /** The debug certificates of the build box and the desk machine; heard only by a debuggable build. */
    val DEBUG_CERTS_SHA256 = setOf(
        "1e7e9cc0ea37a725d0eaf622187792c425dc0351a3e228ef462f8eaacbb9db06",
        "9ae26e87553000817b4185b15d9047845e87871d8f5204dac77b82183fee248b",
    )

    /** Whether the app holding [pkg]'s name is that app; see the class comment. */
    @JvmStatic
    fun isReal(context: Context, pkg: String): Boolean {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return trusted(pkg, signers(context.packageManager, pkg), debuggable)
    }

    /** Whether certificates [signers] (SHA-256, lower-case hex) are [pkg]'s alone. */
    fun trusted(pkg: String, signers: List<String>, debuggable: Boolean): Boolean {
        val release = RELEASE_CERTS_SHA256[pkg] ?: return false
        val certs = if (debuggable) DEBUG_CERTS_SHA256 + release else setOf(release)
        return signers.isNotEmpty() && signers.all { it in certs }
    }

    /**
     * [request] addressed to [pkg] when the real one is on the phone and answers it; null
     * otherwise, so the caller goes on as if it were not installed.
     */
    fun aimed(context: Context, request: Intent, pkg: String): Intent? {
        val addressed = Intent(request).setPackage(pkg)
        val answers = runCatching {
            context.packageManager.queryIntentActivities(addressed, 0).isNotEmpty()
        }.getOrDefault(false)
        return addressed.takeIf { answers && isReal(context, pkg) }
    }

    /** The SHA-256 of each certificate [pkg] is signed with, lower-case hex; none when it cannot be read. */
    @Suppress("DEPRECATION")
    private fun signers(pm: PackageManager, pkg: String): List<String> = runCatching {
        val raw = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        raw.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }.getOrDefault(emptyList())
}
