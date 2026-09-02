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

## Setting it up

```
sudo ./install.sh
```

It installs signal-cli, builds and installs the bridge, writes both systemd units, starts
them, and prints the line you paste into the phone. Re-running is safe: anything already in
place is left alone. If it cannot work out this machine's address on your network, pass one:
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

`--export <dir>` writes the store back out in the same shape, which is how you back it up
or move it to another machine. It round-trips exactly.

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
| `/var/lib/signal-cli` | the Signal account. If this machine registered the number rather than linking to a phone, **this directory is the account** — back it up, encrypted. |
| `/var/lib/kotozute-bridge` | messages, and `token`, which is the only credential the phone uses. Treat it as the account. |
| `127.0.0.1:7583` | signal-cli's JSON-RPC. No authentication; never expose it. |
| `:8422` | the bridge. TLS, pinned certificate, bearer token. Meant to be reachable. |
