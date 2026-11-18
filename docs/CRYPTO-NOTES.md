# Crypto notes

For answering a scanner or a reviewer who reads `AES/CBC/PKCS5Padding` in the APK.

## What it is

`signal-service/libsignal-service/.../AttachmentCipherOutputStream.kt` and
`AttachmentCipherInputStream.kt` use `AES/CBC/PKCS5Padding`. That is Signal's attachment
format, not a choice made in this app. Each attachment (picture, video, voice note, file) is
encrypted before upload with a fresh random 64-byte key: 32 bytes for AES-256 in CBC mode with a
random IV, 32 bytes for an HMAC-SHA256 over the IV and the ciphertext. The file on Signal's
servers is `IV || ciphertext || MAC`. A receiver checks the MAC (in constant time) before it
decrypts anything, so the construction is encrypt-then-MAC and authenticated; the padding is
never judged before the MAC has passed, which is what padding-oracle attacks need.

## Why it is there

Every Signal client reads and writes attachments in this one format. Messaging is a linked
device of the person's own Signal account, so it has to produce and accept exactly what Signal
Android, iOS and Desktop do. Changing the mode (to GCM, say) would make its attachments
unreadable to every other Signal client and theirs unreadable here. The code is taken from
Signal's own `libsignal-service`, not rewritten (see SIGNAL-ON-THE-PHONE.md and
ALIGNMENT-with-molly.md).

## What it is not

- Not custom cryptography: the primitives are the platform's `javax.crypto` AES and HMAC.
- Not used for SMS/MMS (those are plain, by their nature) and not used for anything the app
  stores itself. What it stores is encrypted separately: the two databases with keys sealed
  by the phone's keystore, and Signal attachments as described under "AES/CTR" below.
- The message layer itself (Double Ratchet, sealed sender) is libsignal's, a separate library.

## If a reviewer asks

State the above in two lines: protocol-mandated attachment format of the Signal service,
authenticated by HMAC-SHA256, taken unchanged from Signal's open-source client library. Offer
the file paths. If a real weakness in the format were published, Signal would change the
format for all clients, and this app would take their update.

## AES/CTR/NoPadding, for attachments stored on the phone

A second thing a scanner will read. `AttachmentCipher.kt` (data module) encrypts each Signal
attachment the app keeps on the phone with `AES/CTR/NoPadding`. This too is Signal's format,
the one Signal Android stores its own attachments in (`ModernEncryptingPartOutputStream` and
`ModernDecryptingPartInputStream`), carried over unchanged: 32 random bytes at the head of the
file, a key for that file alone that is HMAC-SHA256 of the app's attachment secret over those
bytes, and AES-256 in counter mode starting from zero. Because every file has its own key, the
fixed starting counter never repeats a keystream. Counter mode is what lets a reader start
from the middle of a file.

The attachment secret is 32 random bytes, sealed with AES-GCM by a key in the Android
keystore (`AttachmentSecret.kt`, after Signal's `AttachmentSecretProvider`).

The attachments of a message that has not finished sending (the outbox) are stored in the same
format under the same secret. A voice message or view-once video is decrypted into memory to
be played, not to a file. The one unencrypted copy the app makes is the one staged in its
private cache when an attachment is opened in another app or a picture is copied, which that
app has to be able to read; it is removed once it is two hours old.

The stored file carries no authentication tag, as in Signal Android; upstream's own comment is
that the format "intentionally no longer includes authentication". It protects a file read
off the storage from being understood. It does not detect a file that was altered on the
phone. The attachment was authenticated once already, by the HMAC described above, when it
was downloaded.
