package com.wanderwildwood.kotozute.signalstore

import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactsReader
import timber.log.Timber
import java.io.InputStream

/**
 * Reads a contacts sync blob into the contacts this app keeps.
 *
 * Out of [SignalReceiver] so the walk over the stream can be tested on its own: the bug it
 * once had only showed on a real download, never on bytes in memory.
 */
internal object ContactsSyncReader {

    fun read(input: InputStream): List<SignalContactStore.Contact> {
        // Signal's own reader (`DeviceContactsReader`, which replaced DeviceContactsInputStream
        // upstream in 3676792cd8), walked the way `MultiDeviceContactSyncJob` walks it. A record
        // with no address is skipped and counted by the reader itself; a truncated or malformed
        // one fails the read.
        val reader = DeviceContactsReader(input)
        val found = mutableListOf<SignalContactStore.Contact>()
        var avatars = 0
        var avatarBytes = 0L
        for (contact in reader.contacts()) {
            // The avatar sits in the stream between this contact and the next. This app keeps
            // none, and the reader steps over whatever of it is left unread when the next
            // contact is asked for, so nothing here has to drain it. It is read anyway, only to
            // count it in the log line below.
            //
            // ⚠ Never close it. The reader hands it out behind a `NonClosingInputStream`, so a
            // close is harmless now -- a `.use { }` here once closed the whole download after
            // the first contact with a picture, and every name after it was lost -- but there
            // is no reason to rely on that.
            contact.avatar?.inputStream?.let { avatar ->
                avatars++
                val scratch = ByteArray(8192)
                while (true) {
                    val read = avatar.read(scratch)
                    if (read < 0) break
                    avatarBytes += read
                }
            }

            val aci = contact.aci?.toString() ?: continue
            found += SignalContactStore.Contact(
                serviceId = aci,
                e164 = contact.e164,
                name = contact.name
                // No profile key here. DeviceContact carries aci, e164, name, avatar and
                // the expiration timer -- and nothing else. Profile keys travel on
                // DataMessage instead, shared by the person themselves; see
                // rememberProfileKey().
            )
        }
        // Counted, because otherwise "this sync carried no avatars" and "the drain does
        // nothing" are the same silence -- and the first is what a primary that is
        // signal-cli rather than a Signal client produces.
        if (avatars > 0) {
            Timber.i("signal contacts: stepped over %d avatar(s), %d byte(s)", avatars, avatarBytes)
        }
        if (reader.skippedRecords > 0) {
            Timber.i("signal contacts: skipped %d record(s) with no address", reader.skippedRecords)
        }
        return found
    }
}
