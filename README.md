<img src="icon.png" width="100" />

# eInkMessage+

An SMS/MMS app for the **[Mudita Kompakt](https://mudita.com/products/kompakt/)** e-ink phone, restyled to match the phone's native look — plus **Desktop Sync**, which lets you read and reply to your texts from a web browser on your computer.

It is a fork of [InkMessage](https://github.com/lamdanAmiti/InkMessage), which is a fork of [QUIK](https://github.com/octoshrimpy/quik), which continues [QKSMS](https://github.com/moezbhatti/qksms).

## Why this fork

InkMessage already had a good e-ink UI and a strong feature set. This fork adds two things.

### 1. A closer match to the Kompakt's native (MMD) look

Measured against the stock Mudita SMS app rather than guessed at:

- **No avatars anywhere** — the stock app identifies a conversation by its title, not by per-message circles
- **Bubble borders match stock**: thin solid for incoming, thick solid for outgoing (upstream had these reversed, with a dashed outgoing border)
- Sender names always bold; message previews bold only when unread
- **Lato bundled statically.** Upstream loaded it through Google Play Services' downloadable-fonts provider, which silently does nothing on a degoogled phone — so the font never actually rendered on a Kompakt
- **Strictly black and white.** E-ink can't display colour (it dithers to muddy grey), so the accent-colour system now always resolves to black
- Consistent icon weights, and chevrons on navigable settings rows

### 2. Desktop Sync — texting from your computer

An HTTP + WebSocket server runs *inside the app on the phone* and serves its own web dashboard. Your browser talks directly to the phone: no cloud service, no relay server in between.

- Conversation list with instant search
- Full thread history, paging back through long conversations
- Send replies, and start new conversations with contact autocomplete (by name or number)
- MMS pictures display inline
- Reading a thread in the browser marks it read on the phone and clears its notification
- Live updates over a WebSocket, with polling as a fallback
- The access link can be reset if it leaks

**Setup**

1. In the app: **Settings → Desktop Sync**, and tap it.
2. It shows one or two URLs — a [Tailscale](https://tailscale.com/) address that works from anywhere, and a local Wi-Fi address for when you're at home.
3. Open either in a browser on your computer. Bookmark it: the address and its token are stable.
4. Tap the row again (or **Stop** on the notification) to turn it off. It is off by default and never starts on its own.
5. If the link is ever shared or seen by someone else, **Settings → Reset Desktop Sync link** mints a new token. Existing bookmarks and open tabs stop working immediately.

**Access control.** Every request requires a random token generated on first run, so simply being on the same network isn't enough. That said, the server listens on all interfaces, so anything on your LAN can reach the port *if it knows the token*. This is built for a private network — ideally a Tailscale tailnet. Don't port-forward it.

Desktop Sync needs the `INTERNET` permission, which upstream InkMessage deliberately left disabled. Leave the feature off and no server is ever started.

## Building

Requires **JDK 17** — the project's Kotlin/kapt toolchain fails on JDK 21 with an `IllegalAccessError` about `com.sun.tools.javac`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :presentation:assembleRelease
```

The APK lands in `presentation/build/outputs/apk/release/`.

Builds are signed with the keystore committed at `presentation/einkmessageplus.keystore`. Its credentials are **intentionally public**, so that any build — local or CI — installs as an in-place upgrade over a previous one. It is not a secret and shouldn't be treated as one.

## Installing

Sideload the APK and let Android make it your default SMS app; it then imports your existing messages from the system SMS database. It installs *alongside* the stock Mudita SMS app rather than replacing it, so you can switch back whenever you like.

Also inherited from upstream: scheduled messages, backup/restore, blocking, archiving, voice messages, attachments of any file type, and delayed sending.

## Credits

Standing on a lot of other people's work:

- **InkMessage** — [lamdanAmiti](https://github.com/lamdanAmiti), the direct parent of this fork
- **QUIK** — [Marcos Jones (octoshrimpy)](https://github.com/octoshrimpy) — [Liberapay](https://liberapay.com/octoshrimpy/donate) · [Ko-Fi](https://ko-fi.com/octoshrimpy/donate) · [Patreon](https://patreon.com/octoshrimpy)
- **QKSMS** — [Moez Bhatti](https://github.com/moezbhatti)
- **android-smsmms** — [Jake](https://github.com/klinker41) and [Luke Klinker](https://github.com/klinker24)
- Typography and visual language follow Mudita's [MMD](https://github.com/mudita) design system

## License

GPLv3, inherited from QKSMS and QUIK — see [LICENSE](LICENSE).
