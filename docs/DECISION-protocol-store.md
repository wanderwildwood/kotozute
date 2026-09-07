# Decision: the Signal protocol state goes in its own encrypted SQLite database

Branch `signal-on-the-phone`. This is the decision the rest of the port stands on, so it is
written down with its reasoning rather than left implicit in the first commit that assumes it.

## The decision

**A second database.** Signal's protocol state — identities, sessions, one-time pre keys,
signed pre keys, Kyber pre keys, sender keys, peer identities, and the account record — lives
in its own **SQLCipher-encrypted SQLite** database, reached through hand-written SQL. Realm
keeps what it already keeps: conversations, messages, the things the screens read.

Not Room, not SQLDelight, not Realm. Raw `SupportSQLiteOpenHelper` over SQLCipher.

## Why not Realm, which is already here

Four reasons, and the first is on its own sufficient.

**1. libsignal calls back into the store synchronously, from native code.** The store methods
are `@CalledFromNative`: the Rust core re-enters your Java in the middle of
`SessionCipher.decrypt`. If that thread already holds a Realm write transaction, the nested
`beginTransaction()` throws — from inside a JNI callback, where it surfaces as an opaque
native failure rather than a Kotlin stack trace. There is no arrangement of Realm transactions
that makes this safe, because the call order is not ours to choose.

**2. Realm instances are thread-confined; libsignal is not.** It will call the store from the
websocket read thread, from the sender's executor, and from the UI thread. Surviving that means
opening and closing a thread-local Realm inside every store method — on tables written once per
message. That is the wrong cost profile on a four-core A53.

**3. Realm hands back a frozen snapshot off a Looper thread.** This is already written down
here from 2026-08-05, and it is worse in this context than the crash in (1): a stale
`SessionRecord` read on a background thread and written back is **silent ratchet corruption**.
Messages stop decrypting later, for reasons that no longer point at the cause.

**4. Atomicity crosses the boundary.** The pre-key counters live on the account record and the
keys live in tables; the identity key pairs live on the account record and the sessions live in
tables. Those pairs must move together or the store is inconsistent after a process death mid
write. That forces the account record into the same database as the tables — so it cannot be a
Realm object or a preference, whatever engine the tables use.

signal-cli gets away with a JSON file beside SQLite because it holds a process-wide file lock
and saves after every mutation. Android has process death instead, so it needs real
transactions.

## Why raw SQL rather than Room

signal-cli's schema can be copied nearly verbatim, and its store implementations are 100–350
lines of straightforward SQL each. Room would want entities and DAOs wrapped around a schema we
do not control and did not design, and it would add another annotation processor to a build
that already pays for kapt three times over. The stores are narrow key/value CRUD; an ORM buys
nothing here and costs build time and indirection.

## Why encrypted, and the honest limit of it

Today the private keys live on an always-on computer. Moving them onto a phone is a real
reduction in safety, and plaintext would make it a larger one — so the protocol database is
encrypted with the same pattern the message database already uses since v1.11.2: a random key
sealed by an AES-GCM key in the Android keystore.

**Be honest about what that buys.** The keystore key cannot be bound to user authentication,
because the app must open this database from a boot broadcast and a background socket with
nobody present. An unauthenticated keystore key protects against a lifted file and a casual
extraction; it does not protect against code running as the app on an unlocked device. It is
better than plaintext and it is not a passphrase.

## Locking

One global reentrant lock for the whole protocol store, following signal-cli's
`ReentrantSignalSessionLock`. **Not** a lock per store. `SignalProtocolStore.archiveSession()`
reaches into the sender-key store while inside a libsignal callback that is already inside the
session lock; independent per-store locks acquired in different orders deadlock, and it will
deadlock on the receive thread, in the field, not in a test.

## What this commits us to

- A schema migration path of our own, separate from Realm's, and a second thing that can
  corrupt.
- Two encryption keys and two recovery stories. The Realm one already discards and refills from
  the bridge when its key is lost; **this one cannot do that.** Losing the protocol key means
  losing the device's identity, and the answer is to link again as a new device.
- Storage that must be backed up and restored as a unit with the account record, or not at all.

That last point is the one to design for early rather than discover: a half-restored protocol
store is worse than an empty one, because it looks like it works.
