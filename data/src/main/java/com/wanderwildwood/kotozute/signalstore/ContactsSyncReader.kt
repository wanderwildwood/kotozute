package com.wanderwildwood.kotozute.signalstore

import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactsInputStream
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
        val stream = DeviceContactsInputStream(input)
        val found = mutableListOf<SignalContactStore.Contact>()
        var avatars = 0
        var avatarBytes = 0L
        while (true) {
            val contact = try {
                stream.read() ?: break
            } catch (e: java.io.IOException) {
                // signal-cli skips these rather than abandoning the sync: one malformed
                // entry should not cost every name after it in the stream.
                if (e.message?.contains("Missing contact address") == true) continue else throw e
            }
            // The avatar sits in the stream between this contact and the next. This app keeps
            // none, and `DeviceContactsInputStream.read()` now steps over whatever of it is left
            // unread (upstream bc7a984923), so nothing here has to drain it. It is read anyway,
            // only to count it in the log line below.
            //
            // ⚠ Never close it. Until that upstream change the avatar was a `LimitedInputStream`
            // straight on the shared stream, so closing it -- a `.use { }` here once did --
            // closed the whole download after the first contact with a picture, and every name
            // after it was lost. Upstream now hands it out behind a `NonClosingInputStream`, so
            // a close is harmless, but there is no reason to rely on that.
            contact.avatar.orElse(null)?.inputStream?.let { avatar ->
                avatars++
                val scratch = ByteArray(8192)
                while (true) {
                    val read = avatar.read(scratch)
                    if (read < 0) break
                    avatarBytes += read
                }
            }

            val aci = contact.aci.orElse(null)?.toString() ?: continue
            found += SignalContactStore.Contact(
                serviceId = aci,
                e164 = contact.e164.orElse(null),
                name = contact.name.orElse(null)
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
        return found
    }
}
