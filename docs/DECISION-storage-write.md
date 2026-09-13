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

**Resolved the same day, by reading rather than deciding.** There is no second marker, because
Signal does not have one. `ThreadTable.setArchived` resolves its threads back to their
recipients and calls `markNeedsSync`, which is `rotateStorageId` and a change notification and
nothing else. **One dirty flag, on the recipient row, whatever table holds the value.**

So mute and archive now mark the recipient row too, and the table above collapses to: every
local change this app makes rotates one id in one place. The write path really is "compute the
diff and send it" once steps 2-4 exist.

⚠ Still open, and genuinely: **group threads**. A group's state lives on a group record, not a
contact record, and this app has no recipient row for a group — so `markNeedsSync` skips them.
Muting or archiving a group is not yet recorded as needing a push. That needs the group half of
the write path, which does not exist.

## Step 2 is done (2026-09-12, schema v19)

`logStoragePushDiff()` runs straight after every storage read and writes to the log what a
push *would* carry. Nothing is sent. It names nobody: kind, and which fields would go.

Groups can finally be marked. v19 gives `recipient` a `group_id` column, because that is where
Signal keeps them — `RecipientTable.getOrInsertFromGroupId` inserts a row with `group_id` set,
no service id and no number, and gives it a storage id there and then. One table, one dirty
flag, whatever kind of conversation it is. `markNeedsSync` no longer skips `group:` threads.
`counts()` gained `WHERE group_id IS NULL`, Signal's own `FILTER_GROUPS`, so a marked group is
not counted as a contact with nothing to show but an id.

⚠ **`ALTER TABLE ... ADD COLUMN ... UNIQUE` is not legal SQLite**, and this database holds the
identity keys, the sessions and the device's own password — an unhandled migration throws and
there is no drop-and-recreate available. The column is added plain and a unique index follows,
which is what Signal does (`V323_AddStickerPackStorageSync` adds its own `storage_service_id`
as a bare `TEXT DEFAULT NULL`). Both phones migrated to v19 and read their storage after.

### What it found in the first ten minutes

Muting a conversation on the phone, then restarting:

```
signal storage: 1 record(s) would be written -- 1 contact, 0 group
signal storage:   contact record -- named=true profileKey=true muted=true archived=false blocked=false
signal storage: 1 conversation(s) muted or archived to match the account
```

and on the very next read:

```
signal storage:   contact record -- named=true profileKey=true muted=false archived=false blocked=false
```

**The mark survives; the value it would push does not.** The read applied the account's
`muted=false` over the local `muted=true` and left the row marked, so a write path today would
send the account back exactly what it already said, for ever.

This is the warning at the top of this document, observed rather than predicted, and it is why
step 3 cannot simply be "add the write call".

### What Signal does about it, and why a read-only client cannot

`ContactRecordProcessor.merge` does **not** protect the local value. It takes `archived`,
`mutedUntilTimestamp`, `blocked` and `markedUnread` from the remote record unconditionally;
only a few fields (profile key, username, note) fall back to local when remote is empty. Remote
wins on conversation state, in Signal too.

What saves the local change there is that a sync is **one pass that reads and writes together**
(`StorageSyncJob.performSync`): the locally-rotated id is in `localOnlyIds` and goes up as a
remote insert, while the stale id it replaced is in `remoteOnlyIds` and is *deleted* from the
manifest in the same write. The stale record never gets a second chance to be applied.

So this is not a bug to fix in the read path. A client that reads and never writes **must**
revert local changes — anything else would be inventing a resolution Signal does not have. It
is the strongest argument yet that steps 3 and 4 are one piece of work with the read, not a
bolt-on: until the write exists, every mark this step logs is a local decision already lost.
