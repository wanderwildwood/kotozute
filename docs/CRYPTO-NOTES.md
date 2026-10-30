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
  stores itself; local storage relies on Android's own protections.
- The message layer itself (Double Ratchet, sealed sender) is libsignal's, a separate library.

## If a reviewer asks

State the above in two lines: protocol-mandated attachment format of the Signal service,
authenticated by HMAC-SHA256, taken unchanged from Signal's open-source client library. Offer
the file paths. If a real weakness in the format were published, Signal would change the
format for all clients, and this app would take their update.
