package com.wanderwildwood.kotozute.signalstore

import android.content.Context
import android.net.Uri
import org.signal.core.models.AccountEntropyPool
import org.signal.core.models.ServiceId
import org.signal.core.models.backup.MediaRootBackupKey
import org.signal.core.util.Base64
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.NetworkResult
import timber.log.Timber

/**
 * Adding another device (Signal Desktop, say) to this account, from the account's primary.
 *
 * Upstream's `LinkDeviceRepository.addDevice`, without the optional history copy: the new
 * device starts empty, exactly as this app does when it is the one being linked.
 *
 * Only a primary may do it. A linked device has no pool to hand on and no say over who else
 * joins, which is why the caller checks [SignalRepositoryImpl.isPrimaryDevice] first and this
 * refuses again on its own: the keys it needs are simply not there on a secondary.
 */
internal class DesktopLinker(
    private val context: Context,
    private val connection: SignalConnection,
    private val accounts: SignalAccountStore,
    private val keys: SignalKeyStore
) {

    sealed interface Outcome {
        object Linked : Outcome
        /** Not a Signal link code, or one this app cannot read. */
        object BadCode : Outcome
        object LimitReached : Outcome
        /** This phone is not the account's primary, or does not hold what it would hand over. */
        object NotPrimary : Outcome
        data class Failed(val why: String) : Outcome
    }

    fun isValid(link: String): Boolean = runCatching { parse(link) != null }.getOrDefault(false)

    private fun parse(link: String): Pair<String, ECPublicKey>? {
        val uri = Uri.parse(link.trim())
        if (!uri.isHierarchical || uri.scheme != "sgnl" || uri.host != "linkdevice") return null
        val id = uri.getQueryParameter("uuid")?.takeIf { it.isNotBlank() } ?: return null
        val encoded = uri.getQueryParameter("pub_key")?.takeIf { it.isNotBlank() } ?: return null
        val key = runCatching { ECPublicKey(Base64.decode(encoded)) }.getOrNull() ?: return null
        return id to key
    }

    fun add(link: String): Outcome {
        val (deviceId, devicePublicKey) = parse(link) ?: return Outcome.BadCode
        val creds = accounts.credentials()
        if (creds.deviceId != SignalRegistrar.PRIMARY_DEVICE_ID) return Outcome.NotPrimary
        val aci = ServiceId.ACI.parseOrNull(creds.aci) ?: return Outcome.NotPrimary
        val pni = ServiceId.PNI.parseOrNull(creds.pni)
        val aciIdentity = accounts.identityKeyPair(ProtocolDatabase.ACCOUNT_ID_TYPE_ACI) ?: return Outcome.NotPrimary
        val pniIdentity = accounts.identityKeyPair(ProtocolDatabase.ACCOUNT_ID_TYPE_PNI)
        val profileKey = accounts.profileKey()?.let { runCatching { ProfileKey(it) }.getOrNull() }
            ?: return Outcome.NotPrimary
        val pool = keys.entropyPool()?.let { AccountEntropyPool.parseOrNull(it) } ?: return Outcome.NotPrimary

        connection.connect()
        val code = when (val r = connection.linkDevice.getDeviceVerificationCode()) {
            is NetworkResult.Success -> r.result
            is NetworkResult.StatusCodeError ->
                return if (r.code == 411) Outcome.LimitReached else Outcome.Failed("code ${r.code}")
            is NetworkResult.NetworkError -> return Outcome.Failed("network")
            is NetworkResult.ApplicationError -> return Outcome.Failed("application")
        }

        val result = connection.linkDevice.linkDevice(
            e164 = creds.e164,
            aci = aci,
            pni = pni,
            deviceIdentifier = deviceId,
            deviceKey = devicePublicKey,
            aciIdentityKeyPair = aciIdentity,
            pniIdentityKeyPair = pniIdentity,
            profileKey = profileKey,
            accountEntropyPool = pool,
            masterKey = pool.deriveMasterKey(),
            mediaRootBackupKey = mediaRootKey(),
            code = code.verificationCode,
            ephemeralMessageBackupKey = null,
            authCredentialSalt = accounts.authCredentialSalt()
        )
        return when (result) {
            is NetworkResult.Success -> Outcome.Linked
            is NetworkResult.StatusCodeError -> when (result.code) {
                411 -> Outcome.LimitReached
                else -> Outcome.Failed("code ${result.code}")
            }
            is NetworkResult.NetworkError -> Outcome.Failed("network")
            is NetworkResult.ApplicationError -> Outcome.Failed("application")
        }.also { Timber.i("signal link: adding a device ended as %s", it::class.simpleName) }
    }

    /** Unlinks another device. False when it could not be done, so the list is left as it was. */
    fun remove(deviceId: Int): Boolean {
        if (deviceId == SignalRegistrar.PRIMARY_DEVICE_ID) return false
        connection.connect()
        val r = kotlinx.coroutines.runBlocking { connection.linkDevice.removeDevice(deviceId) }
        return r is org.signal.libsignal.net.RequestResult.Success
    }

    /**
     * One random key for the life of the account, made the first time it is wanted: upstream
     * does the same (`BackupValues.mediaRootBackupKey`), and keeps it in its encrypted
     * key-value store. Here it is in the protocol store, beside the entropy pool.
     *
     * An older build kept it in a plain preferences file. That is moved in first, here as
     * well as at start-up, so a key already handed to a linked device is never passed over
     * for a new one.
     *
     * Throws if the protocol store cannot be read or will not keep a new key; linking then
     * fails and says so, which is better than linking with a key this phone will not have
     * tomorrow.
     */
    private fun mediaRootKey(): MediaRootBackupKey {
        moveMediaRootKey(context, keys)
        keys.mediaRootKey()?.let { return MediaRootBackupKey(it) }
        val fresh = MediaRootBackupKey.generate()
        keys.storeMediaRootKey(fresh.value)
        check(keys.mediaRootKey()?.contentEquals(fresh.value) == true) {
            "the media root key was not kept"
        }
        return fresh
    }

    companion object {

        private const val OLD_PREFS = "signal_link_extras"
        private const val OLD_PREF_KEY = "media_root"

        /**
         * Moves the key out of the plain preferences file of older builds. See
         * [MediaRootKeyMove] for the order; this only supplies the two real stores.
         */
        fun moveMediaRootKey(context: Context, keys: SignalKeyStore): MediaRootKeyMove.Result {
            val prefs = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
            return MediaRootKeyMove.run(
                oldPresent = { prefs.contains(OLD_PREF_KEY) },
                readOld = {
                    prefs.getString(OLD_PREF_KEY, null)
                        ?.let { stored -> runCatching { Base64.decode(stored) }.getOrNull() }
                },
                readStored = { keys.mediaRootKey() },
                store = { keys.storeMediaRootKey(it) },
                removeOld = {
                    // commit, not apply: "gone" has to mean gone from the disk.
                    val cleared = prefs.edit().remove(OLD_PREF_KEY).commit()
                    // The file held nothing else, so it goes too rather than staying as an
                    // empty file with a telling name.
                    if (cleared) context.deleteSharedPreferences(OLD_PREFS)
                    cleared
                }
            ).also { result ->
                if (result != MediaRootKeyMove.Result.NOTHING_TO_MOVE) {
                    Timber.i("signal link: moving the media root key ended as %s", result)
                }
            }
        }
    }
}
