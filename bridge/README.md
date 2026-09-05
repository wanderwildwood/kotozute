# kotozute-bridge

Sits between `signal-cli` and the Messaging app. It exists for three reasons:

1. **signal-cli's JSON-RPC has no authentication** and exposes destructive methods
   (`unregister`, `deleteLocalAccountData`, `removeDevice`). It must never be reachable by
   the phone. The bridge talks to it over loopback and exposes only its own small API —
   the allowlist is structural, not a filter: there is no passthrough.
2. **signal-cli is not a store.** Messages are delivered to a connected client and then
   gone. The bridge persists them to SQLite so the phone can be offline, page back through
   history, and catch up with a cursor.
3. **The wire shape is awkward.** Messages you send from another device arrive as
   `syncMessage.sentMessage`, everyone else's as `dataMessage`, and one logical message can
   produce several notifications. The bridge normalizes all of it into one message shape so
   the app cannot get it wrong.

Auth is a bearer token generated on first run. Transport is TLS with a self-signed
certificate; the pairing payload carries its SHA-256 fingerprint for the app to pin.

Run it on the same host as signal-cli, which must be started with
`--receive-mode on-connection` — with `on-start`, signal-cli acks messages to Signal's
servers whether or not anything is listening, and anything arriving while the bridge is
down is lost. On-connection leaves them queued server-side instead.

## What the host has to be

**x86-64 Linux, with systemd.** signal-cli is Java and its tarball installs anywhere, but the
Signal protocol underneath is a Rust library loaded through JNI, and the only Linux build
inside `libsignal-client` is x86-64 — there is no arm64 one. A Raspberry Pi or an ARM NAS will
install this cleanly and then fail on the first message, so `install.sh` checks `uname -m`
before it downloads anything and stops there instead. Building
[libsignal](https://github.com/signalapp/libsignal) for your architecture is a real option;
it is also then yours to rebuild on every upgrade.

**It does not have to be on all the time.** With `--receive-mode on-connection` (which
`install.sh` sets) messages stay queued on Signal's servers until the bridge next connects, so
a desktop that is on in the evenings loses nothing — only the time in between, and the ability
to send while it is off.

## Setting it up

```
sudo ./install.sh
```

It installs signal-cli, builds and installs the bridge, writes both systemd units, starts
them, and prints the line you paste into the phone.

Re-running it is how you upgrade: it reinstalls the bridge, **rewrites both unit files** from
that run's environment, and restarts both services. Any hand-editing of those units is lost,
so diff them first if you have customised them. signal-cli and your account are untouched. If it cannot work out this machine's address on your network, pass one:
`sudo ADVERTISE=192.168.1.50 ./install.sh`.

It stops short of two things, both on purpose.

**The account.** Linking and registering are not interchangeable and the script will not
choose for you. *Linking* makes this computer another device on an account your phone
already holds; nothing about your existing Signal changes. *Registering* makes this computer
the account: Signal's own app stops working for that number, and since Signal's Android app
cannot be a secondary device, there is no phone app afterwards. The script prints the
command for each and waits.

**Pairing the phone.** The payload is about 140 characters, two thirds of it a certificate
fingerprint. Do not type it. Open Desktop Sync in a browser — Messaging → Settings →
Desktop Sync → Show link — and paste it into the box at the top of the conversation list,
which appears only while Signal is unset. Then Messaging → Settings → Signal → on.

## Bringing old messages across

Signal threads start empty and fill from the moment you pair: Signal's servers do not hold
history, and a newly linked device is not sent any. Export it from Signal Desktop
(Settings → Chats → Export chat history) and read it in:

```
kotozute-bridge --account +15551234567 --import /path/to/signal-export-...
```

Running it twice imports nothing twice — messages are keyed the same way live ones are.

`--export <dir>` writes the store back out in the same shape, which is how you back it up or
move it to another machine.

Our own exports round-trip whole: reply-quote links, group titles and attachments all come
back, because we write a filename beside every attachment and match on it. Signal's own
exports do not carry one, so there an attachment is identified by byte length alone, and two
files of identical length cannot be told apart -- both are dropped and counted in the stats
line as unmatched. Check that line either way: a backup that quietly lost files is worse
than one that says so.

## Wiping the bridge

`--wipe` deletes every message, thread, contact, stored identity and attachment file. It is
the other half of the phone's "Delete Signal data", which clears only the phone -- the
bridge keeps its own copy until this is run. It asks for the account number before doing
anything; `--yes` skips that, for scripts.

```
kotozute-bridge --account +15551234567 --wipe
```

Deliberately not something the phone can ask for over the pairing: a token that can erase
the whole store is a different kind of token.

## Attachment retention

Attachments pile up in `/var/lib/signal-cli/attachments` and nothing else prunes them --
signal-cli downloads and forgets. Two flags will prune them, and **both are off by
default**:

| flag | what it does |
|---|---|
| `--attachment-days N` | delete attachments older than N days (0, the default, never deletes) |
| `--attachment-max-mb N` | delete oldest-first until the directory is under N MB (0, the default, no cap) |

Off by default because of what those files are. If this machine registered the number
rather than linking to a phone, that directory holds **the only copy** of every photo
anyone has ever sent it. Signal's servers do not keep them and no other device has them.
Turn these on only where something else holds a copy, and note the sweep runs immediately
at startup and every six hours after -- not only on old files you had already written off.
When either flag is set the bridge says so in its log at every start.

## Doing it by hand

If you do not have systemd, or would rather see the parts:

```
signal-cli --config /var/lib/signal-cli daemon \
  --tcp 127.0.0.1:7583 --receive-mode on-connection --no-receive-stdout

kotozute-bridge --account +15551234567 \
  --signal-cli 127.0.0.1:7583 \
  --data /var/lib/kotozute-bridge \
  --signal-cli-data /var/lib/signal-cli \
  --listen 0.0.0.0 --port 8422 --advertise 192.168.1.50
```

`--pairing` prints the pairing line and exits. `--receive-mode on-connection` is not
optional: see above.

## Where things live

| | |
|---|---|
| `/var/lib/signal-cli` | the Signal account. If this machine registered the number rather than linking to a phone, **this directory is the account** — back it up, encrypted. Its `attachments/` subdirectory holds received media, and is the one place the bridge writes into and can be told to delete from; see [Attachment retention](#attachment-retention). |
| `/var/lib/kotozute-bridge` | messages, and `token`, which is the only credential the phone uses. Treat it as the account. |
| `127.0.0.1:7583` | signal-cli's JSON-RPC. No authentication; never expose it. |
| `:8422` | the bridge. TLS, pinned certificate, bearer token. Meant to be reachable. |

## Attachment retention, which deletes files

The bridge prunes signal-cli's own `attachments/` directory: on startup and every six hours
it removes anything older than `--attachment-days` (default **90**), then keeps removing the
oldest until the directory is under `--attachment-max-mb` (default **2048**).

That is a directory the bridge writes to *and deletes from*, and on a setup where this
machine is the account's primary device those files are the only copy of received media in
existence. Pass `--attachment-days 0` to keep everything, and size your backups accordingly.

## Other flags

| | |
|---|---|
| `--pairing` | print the line the phone needs, and exit |
| `--import <dir>` | read a Signal Desktop export, or one of ours, into the store |
| `--export <dir>` | write the store out in that same shape |
| `--wipe` | **destroy** every message, thread, contact and stored identity, then exit. No confirmation. This is the other half of the phone's "Delete Signal data", which clears only the phone. |
| `--attachment-days`, `--attachment-max-mb` | see retention above |

## Licence

GPL-3.0-only, the same as the app it serves — see [LICENSE](../LICENSE) at the root of this
repository. The bridge is its own Go module and can be built alone, which is why the terms
are restated here rather than left to be inferred from a directory it happens to sit in.

Its dependencies are permissive and compatible: modernc.org/sqlite, go-humanize, google/uuid,
go-isatty, go-strftime, bigfft, golang.org/x/sys and modernc.org/libc, under BSD-3 or MIT.

## Not affiliated with Signal

This is not a Signal product and has nothing to do with the Signal Foundation or Signal
Messenger LLC. It does not implement the Signal protocol and never speaks to Signal's servers
itself: it talks to [signal-cli](https://github.com/AsamK/signal-cli), which is a separate,
unofficial project, and signal-cli does the talking.

"Signal" appears throughout this documentation because that is the name of the service being
reached, and for no other reason.
