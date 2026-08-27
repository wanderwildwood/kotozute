<img src="icon.png" width="100" />

# eInk Messaging

An SMS and MMS app for the [Mudita Kompakt](https://mudita.com/products/kompakt/), restyled for the phone's e-ink screen, with Desktop Sync: reading and replying to texts from a browser on a computer.

It continues [QKSMS](https://github.com/moezbhatti/qksms) by way of [QUIK](https://github.com/octoshrimpy/quik), and branched off [InkMessage](https://github.com/lamdanAmiti/InkMessage), which is where the e-ink work started.

## Matching the stock Mudita SMS app

Measured against the stock app rather than guessed at:

- No avatars — the stock app identifies a conversation by its title, not by per-message circles
- Bubble borders: thin solid incoming, thick solid outgoing (upstream had these reversed, with a dashed outgoing border)
- Sender names always bold; message previews bold only when unread
- Lato is bundled statically. Upstream loaded it through Google Play Services' downloadable-fonts provider, which silently does nothing on a degoogled phone, so the font never rendered on a Kompakt
- Black and white only. E-ink dithers colour to muddy grey, so the accent-colour system always resolves to black
- Consistent icon weights, and chevrons on navigable settings rows

## Desktop Sync

An HTTP + WebSocket server runs *inside the app on the phone* and serves its own web dashboard. Your browser talks directly to the phone: no cloud service, no relay server in between.

- Conversation list with instant search
- Full thread history, paging back through long conversations
- Send replies, and start new conversations with contact autocomplete (by name or number)
- MMS pictures display inline
- Reading a thread in the browser marks it read on the phone and clears its notification
- Live updates over a WebSocket, with polling as a fallback
- The access link can be reset if it leaks

### Setup

1. In the app: **Settings → Desktop Sync**, and switch it on.
2. Tap **Show link** for the address to open on your computer. Bookmark it: the address and its token are stable.
3. Switch it off again with the same row, or **Stop** on the notification. It is off by default and never starts on its own.
4. If the link is ever shared or seen by someone else, **Reset Desktop Sync link** mints a new token. Existing bookmarks and open tabs stop working immediately.

### Access control

Two independent gates.

*Where you can connect from.* **Tailscale only** is on by default: any request from outside your [Tailscale](https://tailscale.com/) tailnet is refused before the token is even looked at, so another device on your café or home Wi-Fi cannot reach the dashboard even if it somehow had your link. This also means your messages are never in the clear on a network — the relay speaks plain HTTP, but every tailnet connection is encrypted end to end by Tailscale itself.

Turn the switch off and it also accepts LAN connections, which is worth knowing about for one case: **Tailscale does not start by itself after a reboot** on MuditaOS, which has no always-on VPN toggle. Until you open Tailscale, a restricted relay refuses everyone — Settings says so plainly when that happens.

The restriction is enforced per request rather than by binding only the tailnet address, deliberately: binding it would mean the server cannot start at all while Tailscale is down, so a reboot could leave the relay dead. This way the socket always binds and simply turns non-tailnet callers away.

*Who you are.* Every request also needs a random 144-bit token generated on first run, so being on the tailnet isn't enough by itself — useful if your tailnet has devices you don't fully control.

Don't port-forward the port, and don't expose it with Tailscale Funnel.

Desktop Sync needs the `INTERNET` permission, which InkMessage deliberately left disabled and this app re-enables for exactly this feature. Leave it off and no server is ever started.

### Launcher badges

Desktop Sync runs as a foreground service, which means Android keeps a notification in the shade for as long as the relay is up — that's how the platform works, and it's also your only visible sign the relay is running. Some minimalist launchers count *every* notification a package has posted when deciding whether to badge its icon, without checking whether the notification asked to be badged. On those launchers the relay's notification will light up an unread marker on eInk Messaging that never clears.

The notification lives on its own channel (`desktop_sync_v2`) and declares `setShowBadge(false)` plus `CATEGORY_SERVICE`, so a launcher that honours either one will behave. If yours doesn't, open the notification's channel settings — the cog next to it in the shade — and switch that one channel off. Real messages badge from a different channel and are unaffected. The cost is that you lose the shade indicator telling you the relay is running.

## Building

Requires **JDK 17** — the project's Kotlin/kapt toolchain fails on JDK 21 with an `IllegalAccessError` about `com.sun.tools.javac`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :presentation:assembleRelease
```

The APK lands in `presentation/build/outputs/apk/release/`.

A keystore is committed at `presentation/einkmessaging.keystore` with the password `android`, so that a fresh clone compiles and installs without any setup. It is a throwaway: its private half is public, its certificate says so, and the release workflow refuses to publish anything signed with it. Released builds are signed with a real key supplied through `signing/`, which is not in this repository.

## Installing

> **Running inkMessage+? Uninstall it first — there is no upgrade path.** This app has a
> different application ID, so Android treats it as unrelated software rather than a newer
> version, and installing over the old one stops with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
>
> **Your messages are safe.** They live in Android's own message store, not in this app, and it
> reads them back the first time it runs — on a long history that takes several minutes, showing
> "Syncing messages…" over an empty list until it finishes. What uninstalling clears is the app's
> own settings, so expect to make it your default SMS app again and to turn Desktop Sync back on.

Sideload the APK and let Android make it your default SMS app; it then imports your existing messages from the system SMS database. It installs *alongside* the stock Mudita SMS app rather than replacing it, so you can switch back whenever you like.

Also inherited from upstream: scheduled messages, backup/restore, blocking, archiving, voice messages, attachments of any file type, and delayed sending.

## Credits

Standing on a lot of other people's work:

- **QKSMS** — [Moez Bhatti](https://github.com/moezbhatti), where almost all of this code comes from
- **QUIK** — [Marcos Jones (octoshrimpy)](https://github.com/octoshrimpy), who kept it alive — [Liberapay](https://liberapay.com/octoshrimpy/donate) · [Ko-Fi](https://ko-fi.com/octoshrimpy/donate) · [Patreon](https://patreon.com/octoshrimpy)
- **InkMessage** — [lamdanAmiti](https://github.com/lamdanAmiti), who took it to e-ink first, and where this branched off
- **android-smsmms** — [Jake](https://github.com/klinker41) and [Luke Klinker](https://github.com/klinker24)
- Typography and visual language follow Mudita's [MMD](https://github.com/mudita) design system

## Licence

**GPL-3.0-only**, inherited from QKSMS by way of QUIK and InkMessage — see [LICENSE](LICENSE)
for the full text.

This is not a licence that can be changed here. The code this is built on is GPLv3, so this is
GPLv3, and so is anything built on this in turn: distributing it means carrying the same terms
and offering the corresponding source.

Copyright in the work added by this fork is held by wander wildwood. The upstream copyrights —
Moez Bhatti's on QKSMS, and those of the QUIK and InkMessage contributors — are untouched and
still apply to the code they cover.
