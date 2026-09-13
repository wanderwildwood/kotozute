# Working on kotozute

## ⛔ The Signal rail defers to Signal's repo. This is not a preference.

A full checkout of Signal Android lives at **`/opt/projects/signal-android-upstream`**. It is
the authority for every question about the Signal side of this app.

**The rule:**

> Do not write a solution to a problem Signal has already solved. Read the repo first, take
> its answer, and only invent where the code must be unique to *this* app's integration —
> our Realm store, our two-rail UI, our e-ink constraints. Uncertainty is not a licence to
> reason it out; it is the signal to go and read.

This is a hard rule, decided by David and restated many times. The repo is
**AGPL-3.0-or-later precisely so Signal's code can be taken** — never ask whether it is
allowed, and never treat "we'd have to look it up" as a reason to guess.

### Why it is written this way

Every wrong value on this rail arrived with a confident comment explaining it. A sample of
what deferring to the repo actually found, each one shipped and each one wrong:

- Prekeys rotated every **14 days** because a comment called it "Signal's own ceiling". It is
  the ceiling — `MAXIMUM_ALLOWED_SIGNED_PREKEY_AGE`, the age at which Signal *refuses to send*
  until the key is replaced. The cadence is `REFRESH_INTERVAL`, two days. We sat permanently
  in the state Signal treats as a fault.
- A backup was locked with **thirty digits shown once and stored nowhere**, so losing the slip
  of paper lost the archive. Signal derives a backup key from the account
  (`AccountEntropyPool.deriveMessageBackupKey`); nothing is written down and any device on the
  account can open any copy.
- A remote delete was applied **and also stored as a message**, leaving an empty row. Signal's
  `DataMessageProcessor` is a `when`: `hasRemoteDelete` never reaches `handleTextMessage`.
- Blocking looked correct against the legacy `SyncMessage.Blocked` path while a modern account
  keeps blocking **per-record in the storage service**. Blocked people kept arriving.
- A service id was read from the legacy `String` field when a modern client fills only the
  `*Binary` twin, so a sync named nobody and silently did nothing. Seventeen such pairs exist.

### How to apply it

1. **Before writing Signal-side code, grep the checkout.** `grep -rn` over
   `/opt/projects/signal-android-upstream/app/src/main/java/org/thoughtcrime/securesms/` and
   `lib/`.
2. **Quote the source** in the comment or commit message — the class and method, so the next
   reader sees the authority rather than the reasoning.
3. **Name a divergence as a divergence.** Where this app must differ, say so at the point it
   differs, and say *why the difference is forced* (see `SignalBackupCrypto.deriveFromAccount`
   for the shape: Signal's key, our container, the reason the extra step exists).
4. **A constant copied from Signal carries its Signal name** in the doc comment, so the next
   person can check it. Two constants that differ by a factor of seven look identical at a
   glance.

### Where the seams legitimately are

Ours, with no upstream to defer to: the Realm message store and its schema, the two-rail
inbox and the badge between them, Desktop Sync, the e-ink UI throughout, and the export
*container* (Signal's key, our chunked AES-GCM). Everything touching the protocol, the
storage service, prekeys, sessions, identities, receipts, sync messages, groups, profiles and
contact discovery is Signal's question and takes Signal's answer.

## ⚑ The current quest: the Signal parity audit

Ninety-eight confirmed places where this app invented an answer Signal already had, found by
reading our Signal rail against the upstream checkout file by file and checking every claim
twice.

⚠ **The queue lives outside this repo, at `~/kotozute-private/SIGNAL-PARITY-AUDIT.md`.**
This repository is public. A list of where a live messaging app is weakest, with file and line
numbers, is a roadmap for somebody else even though the code itself is open — so the findings
stay off GitHub until they are fixed. Do not add that file, or a summary of its unfixed
entries, to this repository. Fixes land here one at a time on their own merits, and a commit
message says what it fixed without cataloguing what is still open.

When David says **"continue the parity audit"**, or just **"keep going"** while this is the
open quest: open that file, read Status, and start the next open batch. One batch is one file.
Finish it, update Status in the same commit to the private file, and start the next without
stopping to ask.

**The rule that keeps it on course: do not open new work.** No features, no refactors beyond
what a finding needs, no new audits, no chasing an interesting thing found along the way.
Something new gets *appended to the queue as a new entry* and the batch carries on.

Re-check each citation before changing code: if the upstream file does not say what an entry
claims, the **entry** is wrong and our code may be fine. Mark it `refuted` with the reason.
Never bend working code to match a bad claim.

## The rest

- **UI follows `/opt/projects/STYLE.md`**, the cross-app house authority. It is the same kind
  of rule: it has already answered, so read it rather than deciding afresh. In particular a
  destructive action **arms in place** ("<action> — <warning>; tap again", disarming after
  four seconds) and never opens a confirmation dialog, and destructive entries go last.
- **Builds go on the Legion**, `~/legion-build.sh kotozute`, never on this machine.
- **Never credit AI anywhere public.** No `Co-Authored-By`, no Claude or Anthropic in a commit
  message, and the author field stays `wander wildwood <wanderwildwood@users.noreply.github.com>`.
- **Shared checkout:** another session may be committing here. Stage by path, never
  `git add -A` blindly, and check `git show --stat` before pushing.
- **Never move a pushed tag.** Cut a new one.
