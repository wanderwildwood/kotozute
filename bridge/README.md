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
