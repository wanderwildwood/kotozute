# Signal integration — spike findings

Branch `kotozute_s`. Spike run 2026-08-31 against a real linked device.
Nothing in the app has been touched yet; this records what we are building against.

## Architecture chosen

kotozute does **not** implement the Signal protocol. `signal-cli` runs as a Signal
**linked device** (the same official multi-device mechanism Signal Desktop uses — scan a
QR from the primary phone, no second number, no unofficial client registering with
Signal's servers). kotozute talks to it over JSON-RPC.

Rejected: merging Molly/Signal-Android into the app. It is a few hundred thousand lines
plus libsignal, and libsignal is **AGPL-3.0** — linking it would move this repo off
GPL-3.0-only (GPLv3 §13 permits the combination; the result is AGPL).

The someday version that removes the always-on host requirement is making kotozute
*itself* the linked device — smaller than porting Signal-Android, since a linked device
needs no registration, PIN/KBS, or contact discovery. Still carries the AGPL problem, a
persistent-socket battery cost on e-ink, and the third-party-client question. Not v1.

## Deployment

| | |
|---|---|
| Host | a small always-on machine on the LAN |
| Binary | signal-cli 0.14.7, **JVM build** |
| Data dir | `/var/lib/signal-cli` (mode 700) |
| Service | `/etc/systemd/system/signal-cli.service`, enabled + active |
| Endpoint | JSON-RPC TCP **127.0.0.1:7583** (localhost only, deliberately) |
| Device name | `kotozute` |

### The native build does not work here
The GraalVM native image needs **x86-64-v3**. The Celeron N4020 is **x86-64-v2**
(`sse4_2`, no AVX/AVX2/BMI/FMA) and refuses to start: `CPU ISA level is lower than
required`. The native build's 94 MB / 0.09 s figures are real but only on v3 hardware.
On this host we run the JVM build.

### Measured cost (JVM build, N4020, 4 GB host)
- **RSS 207–226 MB** steady, ~4 % CPU idle
- Data dir 12 MB after link
- Host still reports ~1.85 GB available alongside Jellyfin/Navidrome/Audiobookshelf

`/mnt/media` is **exFAT** — unusable for the data dir (no POSIX mode for a 700 key store,
SQLite locking over fuse). Data stays on the 58 GB eMMC (35 GB free). Attachments
accumulate there, so retention needs a policy before daily use.

## Gating questions — answered

### History on link: NONE. This is the important one.
A freshly linked signal-cli receives **no prior message history**. Draining the queue
after link produced 6 envelopes, all stamped at link time, all sync/config
(`blockedGroupIds`, `blockedNumbers`, config type, contact/group sync). Zero
`dataMessage`.

Consequence: **Signal threads start empty and fill from the link date forward.** A merged
conversation will show years of SMS beside a Signal side that begins today. There is no
backfill path short of importing a Signal backup, which is a separate project.
The setup flow must say "from today forward" plainly.

### Contacts and groups: yes, these do sync
- 97 contacts (49 with a resolvable name), 0 unregistered, 0 blocked
- 3 groups, member of 2 (6 and 19 members), 1 with an invite link
- `hasAvatar` false across the board — avatars need a separate `getAvatar` fetch.
  Irrelevant here: avatars were removed from kotozute deliberately.

### Send: works, over both CLI and JSON-RPC
JSON-RPC `send` returned `{"type":"SUCCESS"}` with a message timestamp.

### Receive: works
A Note-to-Self sent from the primary phone arrived in ~24 s.

### Sent-by-you messages arrive differently — implementation note
A message *you* send from another device arrives as
`syncMessage.sentMessage`, **not** `dataMessage`. Messages from other people arrive as
`dataMessage`. kotozute must ingest both to build a complete thread, or every message
the user sends from their phone will be missing from the app's copy.

### Push vs poll: PUSH, confirmed
The daemon pushes `receive` notifications to any connected JSON-RPC client in real time.
Verified by sending from another linked device and watching a held socket: three
notifications arrived within ~250 ms, `params` carrying `account` and `envelope`:

| order | kind | what it is |
|---|---|---|
| 1 | `dataMessage` | the message |
| 2 | `syncMessage` | the sync from the sending device |
| 3 | `receiptMessage` | delivery receipt |

**kotozute holds one socket and gets live updates. No polling timer, no wakeup battery
cost.** This is the single best piece of news in the spike — it makes the phone side a
listener rather than a poller, which is the difference between a feature that is pleasant
on e-ink and one that is not.

Note that a single logical message can produce several notifications. The ingest path must
be idempotent and key on the message timestamp, or a thread will fill with duplicates.

## Two more facts found while building the bridge

### signal-cli is not a store, and `--receive-mode` decides whether that loses messages
With `--receive-mode on-start`, signal-cli acks messages to Signal's servers whether or not
anything is listening. A message that arrives with no JSON-RPC client attached is **gone** —
verified: sent one with nothing attached, connected afterwards, zero replayed, nothing in
`msg-cache`, and an explicit `receive` call is refused (*"cannot be used if messages are
already being received"*).

**`--receive-mode on-connection` fixes it.** Undelivered messages stay queued on Signal's
servers until a client attaches. Verified the same way: sent while detached, connected, and
it arrived. The service now runs in this mode. Any bridge downtime is a gap, not a loss.

### ACI vs PNI — using the wrong one splits every thread
`getUserStatus` on your own number returns `ff85…` (one uuid); the account's real identity is
`2696…` (a different one). The first is the **PNI** (phone-number identity), the second the **ACI**
(account identity), and sync messages are keyed on the ACI. Read the ACI from
`listIdentities`, matching the account number — `listAccounts` returns only the number.

Related: a `dataMessage` whose source is your own account is **Note to Self** (your own
direct and group sends come back as `syncMessage` instead), so it belongs on the outgoing
side of the thread.

## Security findings

### 1. signal-cli JSON-RPC has no authentication at all
Connecting to the port with **zero credentials** returned the full group list. Everything
is exposed, including destructive methods (`unregister`, `deleteLocalAccountData`,
`removeDevice`).

This is why the service is bound to `127.0.0.1`. **Binding it to `0.0.0.0` so the phone
can reach it would hand full read/send control of the Signal account to anything on the
LAN.** An authenticating layer is required before the phone can talk to it. Preferred
shape: a small bridge alongside signal-cli that speaks localhost JSON-RPC, exposes only the
handful of methods kotozute needs (list threads, get messages, send, mark read, fetch
attachment), and requires a token — mirroring Desktop Sync's existing token model. A bare
reverse proxy is not enough, because the destructive methods must not be reachable at all.

### 2. A second client on the same account is fine, but keep it separate
Another tool already linked to the same Signal account does not compete for messages --
each linked device gets its own copy. Keep any alerting integration on its own linked
device rather than sharing this one, so the two fail and get revoked independently.


## Linked devices
Signal allows several linked devices per account; this bridge takes one of them.

## What this means for the app

The Desktop Sync web API is already transport-agnostic — `/api/threads`,
`/api/threads/{id}/messages|send|read`, all keyed on thread id and read through
`messageRepository`. Signal threads that land in Realm as first-class conversations give
the browser its read side for free. `/api/sims` is the one route that changes: `subId`
is meaningless for Signal, so the SIM picker becomes a rail picker.

The real work is a second ingest path. `Message` (`domain/.../model/Message.kt`) is a
Realm mirror of the telephony provider — `contentId`, `boxId`, `subId`, `TYPE_SMS`/
`TYPE_MMS`, delivery PDUs. Signal needs a model that is not shaped like a telephony row,
thread merging by phone number, and a conversation list that shows which rail a thread
rides.

Graceful degradation is a v1 requirement, not polish: when the bridge is unreachable,
Signal threads stay visible and readable with an honest last-synced state and a disabled
composer. Receiving degrades softly (messages queue server-side and arrive on reconnect);
**sending fails hard**. The UI must not offer a compose box that cannot deliver.

## Importing history — deferred, and why the obvious route does not exist

Signal has two unrelated backup systems, and only one of them can be imported:

| | Secure Backups | On-device backups |
|---|---|---|
| key | **64 characters**, in groups of four | **30 digits** |
| stored | on Signal's servers, end-to-end encrypted | a local `.backup` file |
| platforms | Android and iOS | Android and Desktop only |
| restored by | Signal itself, during registration | the file, on reinstall or transfer |

A Secure Backups recovery key looks like the thing you want and is not: that backup never
exists as a file anyone holds, so there is nothing to decrypt and `signalbackup-tools` does
not apply. Only the **on-device** backup produces a readable file, and it is the 30-digit
passphrase that opens it.

So importing history needs on-device backups enabled on an Android Signal install, which
yields the file and the 30-digit passphrase. Deferred deliberately: the messages are already
on the phone the user carries, and the app says so rather than pretending otherwise.

**What is already in place for it.** The store is idempotent on `(author, timestamp)` and
makes no assumption about arrival order, and `SignalMessage.source` distinguishes `"live"`
from `"import"`. A backup arrives backwards in time; nothing has to be rewritten to accept
it. The thread preview is guarded on the timestamp for the same reason -- an old imported
message must not overwrite a newer preview.

## What became of the open items

All three were settled by the build, and this note is kept for the reasoning rather than as
a plan. Signal shipped publicly in 1.8.0.

- **The authenticating bridge** exists: `bridge/`, TLS with a pinned self-signed certificate,
  a bearer token, and a structural allowlist rather than a proxy, so the destructive
  signal-cli methods are not reachable at all.
- **Attachments** are fetched, cached and swept — `--attachment-days` and
  `--attachment-max-mb`, and a view-once attachment is never written down.
- **Rails, not merged threads.** The same person's SMS and Signal conversations stay separate,
  with a badge in each that crosses to the other. Merging would have meant one composer
  silently deciding which network a reply went out on, which is the one thing a messaging app
  must never guess.
