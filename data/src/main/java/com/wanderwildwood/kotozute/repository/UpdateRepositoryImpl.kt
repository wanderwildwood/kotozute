package com.wanderwildwood.kotozute.repository

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateRepositoryImpl @Inject constructor(
    private val context: Context
) : UpdateRepository {

    companion object {
        private const val REPO = "wanderwildwood/kotozute"
        private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
        private const val ASSET = "kotozute.apk"

        /**
         * Where a given release's APK and its checksum live.
         *
         * Pinned to the tag rather than taken from `releases/latest/download`. This app has
         * published six releases in a day before now, and the fixed-name link follows whatever
         * is newest at the moment it is fetched -- so between the check that said "1.19.66" and
         * the download a minute later, the bytes can be a different build than the one the
         * person was shown and agreed to.
         */
        private fun apkUrl(version: String) =
            "https://github.com/$REPO/releases/download/v$version/$ASSET"

        private fun checksumUrl(version: String) = "${apkUrl(version)}.sha256"

        private val RELEASE_VERSION = Regex("""\d+\.\d+\.\d+""")

        /**
         * Orders two `major.minor.patch` versions, oldest first.
         *
         * Compares the three numbers rather than the packed versionCode the release workflow
         * derives (`major * 10000 + minor * 100 + patch`). That packing has no room for a patch
         * above 99 -- 1.19.100 and 1.20.0 pack to the same 12000 -- and at this app's release
         * rate that is a fortnight away, not a theoretical worry.
         */
        internal fun compare(a: String, b: String): Int {
            val left = a.split('.').map { it.toIntOrNull() ?: 0 }
            val right = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(left.size, right.size)) {
                val difference = (left.getOrNull(i) ?: 0) - (right.getOrNull(i) ?: 0)
                if (difference != 0) return difference
            }
            return 0
        }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Not infinite. A read timeout that never fires leaves the row saying "Checking"
            // for the rest of the session with nothing to cancel it.
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /** What is actually installed, which is the honest thing to compare against. */
    private val running: String
        get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    override suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        val running = running
        if (!RELEASE_VERSION.matches(running)) {
            Timber.d("Running $running, which is not a release version; not checking")
            return@withContext UpdateCheck.NotAReleaseBuild
        }

        val published = runCatching {
            client.newCall(Request.Builder().url(LATEST).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Update check got HTTP ${response.code}")
                    return@use null
                }
                JSONObject(response.body?.string().orEmpty())
                    .optString("tag_name")
                    .removePrefix("v")
                    .takeIf { RELEASE_VERSION.matches(it) }
            }
        }.getOrElse { error ->
            Timber.w(error, "Update check could not be made")
            null
        } ?: return@withContext UpdateCheck.Unreachable

        if (compare(published, running) > 0) {
            UpdateCheck.Available(published)
        } else {
            UpdateCheck.Current(running)
        }
    }

    override suspend fun install(version: String): UpdateInstall = withContext(Dispatchers.IO) {
        // Asked before anything is downloaded. The session would throw on commit anyway, but
        // only after pulling six megabytes over what may be a phone's own data to find out.
        if (!context.packageManager.canRequestPackageInstalls()) {
            return@withContext UpdateInstall.NotPermitted
        }

        val expected = fetchChecksum(version) ?: return@withContext UpdateInstall.Unreachable

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            // Refuses the session outright if what we downloaded declares some other package.
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Best effort. The system only takes this from whoever installed the app in the
                // first place, which for most people here is Obtainium rather than us, so
                // expect to be asked anyway -- STATUS_PENDING_USER_ACTION covers that.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            val digest = MessageDigest.getInstance("SHA-256")

            installer.openSession(sessionId).use { session ->
                val request = Request.Builder().url(apkUrl(version)).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("Update download got HTTP ${response.code}")
                        installer.abandonSession(sessionId)
                        return@withContext UpdateInstall.Unreachable
                    }
                    val body = response.body ?: run {
                        installer.abandonSession(sessionId)
                        return@withContext UpdateInstall.Unreachable
                    }
                    session.openWrite(context.packageName, 0, -1).use { sink ->
                        body.byteStream().use { source ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = source.read(buffer)
                                if (read == -1) break
                                digest.update(buffer, 0, read)
                                sink.write(buffer, 0, read)
                            }
                        }
                        session.fsync(sink)
                    }
                }

                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    // The bytes are already in the session at this point, which is harmless:
                    // an abandoned session installs nothing. Writing and then abandoning costs
                    // one pass over the download; staging it to a file to digest first would
                    // cost a second copy on a phone that has not got the room to spare.
                    Timber.w("Downloaded APK digest $actual, expected $expected; discarding")
                    installer.abandonSession(sessionId)
                    return@withContext UpdateInstall.WrongContents
                }

                session.commit(installerCallback(sessionId).intentSender)
            }
            UpdateInstall.HandedOver
        } catch (error: SecurityException) {
            Timber.w(error, "Not allowed to install packages")
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            UpdateInstall.NotPermitted
        } catch (error: Exception) {
            Timber.w(error, "Update install failed")
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            UpdateInstall.Unreachable
        }
    }

    private fun fetchChecksum(version: String): String? = runCatching {
        client.newCall(Request.Builder().url(checksumUrl(version)).build()).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("Checksum fetch got HTTP ${response.code}")
                return@use null
            }
            // `sha256sum` writes "<hex>  <filename>".
            response.body?.string()?.trim()?.substringBefore(' ')?.takeIf { it.length == 64 }
        }
    }.getOrElse { error ->
        Timber.w(error, "Checksum could not be fetched")
        null
    }

    private fun installerCallback(sessionId: Int) = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(context, UpdateInstallReceiver::class.java),
        // Mutable, because the system fills in the status it is reporting back. Below API 31
        // that is the default and there is no flag to say it with.
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    )

}
