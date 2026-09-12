# Writing to the storage service — what it needs, and why it is not built

*2026-09-12. Written after reading Signal's write path end to end, so the next attempt starts
from what it actually costs rather than discovering it halfway in.*

This app **reads** the account's storage records and does not write them. Reading was worth
doing on its own — it is where blocking, muting and archiving actually live, and reading it
closed three real faults. Writing is the other half, and it is a much larger piece of work than
the read was.

## What not writing costs today

Nothing the phone decides reaches the account. Concretely:

- Blocking somebody **from the phone** goes out as a legacy `SyncMessage.Blocked` list, not as a
  storage record. Signal's own clients stopped treating that as the source of truth.
- A name, a mute, an archive or a timer set here is local only. Another device will never see
  it, and the next storage read will quietly overwrite it with the account's answer.

That last part is worth being clear about: the read path we now have is **authoritative and
one-directional**. It is the correct behaviour while there is no write path, and it becomes the
wrong behaviour the moment there is one, because a local change would be reverted before it
could be pushed.

## What the write path actually involves

Read from `StorageSyncJob`, `StorageSyncHelper`, `StorageSyncValidations` and
`StorageSyncLoopDetector`. In rough order of how much is missing here:

1. **Local change tracking, which this app has none of.** Signal gives every recipient a
   `STORAGE_SERVICE_ID` and rotates it on every local change — `rotateStorageId` is called from
   **31 places** in `RecipientTable` alone. That rotation *is* the dirty flag: the sync computes
   what to push by finding records whose storage id no longer matches the manifest. Without an
   equivalent, there is nothing to diff and nothing to write.
2. **Manifest versioning.** A write is a new manifest at version+1 carrying every record id,
   with inserts and deletes alongside. It is not a per-record update.
3. **Conflict handling.** The server rejects a write against a stale manifest. Signal refetches,
   re-resolves, and retries — `ConflictError` → `RetryLaterException`.
4. **Validation before writing.** `StorageSyncValidations.validate` exists because a malformed
   manifest damages the account's records for every device, not just this one.
5. **Loop detection.** `StorageSyncLoopDetector` exists because two devices can undo each
   other's writes indefinitely. Signal treats a detected loop as reportable at HIGH priority.
   A fork that writes without it can burn an account's storage service in a tight loop.

## Why it is not built yet

Points 1 and 5 are the reasons. Point 1 means this is not "add a write call" — it is a local
data model change touching every place the app mutates a conversation. Point 5 means getting it
wrong is not a local bug: two devices fighting over the manifest is an account-level failure,
and this app would be the badly-behaved one.

⚠ It also writes to a **live account**. On 2026-09-12 an unrelated careless call to signal-cli
took the household's alerting down for twenty-five minutes. The write path deserves a session
that starts with it, not one that reaches it at hour ten.

## If it is picked up

Build in this order, and stop at each step:

1. Storage ids on the `recipient` table, rotated wherever the app mutates a contact. No network.
2. A diff that says what *would* be written, logged and not sent. Run it for a while and read it.
3. The write itself, behind validation, with conflict retry.
4. Loop detection last, before it is ever enabled by default.

Steps 1 and 2 are safe to do at any time and are most of the work. Step 3 is the one that needs
care and a live account to test against.

## Step 1 is done (2026-09-12, schema v17)

`recipient.storage_id`, plus `rotateStorageId` and `needingStoragePush` on the contact store.
Sixteen random bytes, base64 with padding, exactly as `StorageSyncHelper.KEY_GENERATOR` makes
them. Nothing writes to the storage service; this only records.

⚠ **It is called from exactly one place, and that is the finding.** Signal rotates from 31 call
sites; this app has **one** — a local block. Everything else that touches a recipient row here
is *applying what the account just said*, and rotating there would make the device permanently
believe it had something to send: two devices each undoing the other, which is what
`StorageSyncLoopDetector` exists to catch.

That is worth knowing before step 3 is costed. The phone barely makes local decisions that a
storage record carries, so the write path buys less than it first appears:

| what a record holds | where this app keeps it | local changes today |
|---|---|---|
| `blocked` | `blocked` table | yes — one path, the only rotation site |
| `muted`, `archived` | **Realm `SignalThread`**, not the recipient row | yes, but in another store |
| name, profile key, ids | `recipient` | no — all learned, never chosen here |

So before step 3 is worth building, mute and archive need a dirty marker of their own, because
they live in Realm and cannot be found by looking at `recipient.storage_id`. That is a second
model decision, not a detail — and it is the honest reason the write path is not simply "add the
network call".
