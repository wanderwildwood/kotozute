# Signal's service layer, built from Signal's source

Five modules, copied verbatim from [Signal-Android](https://github.com/signalapp/Signal-Android)
at **`2dd5703875` (2026-11-24, the commit of release tag `v8.37.3`)** and compiled here:

| here | upstream | what the app imports from it |
|---|---|---|
| `libsignal-service/` | `lib/libsignal-service` | `org.whispersystems.signalservice.*`, `org.signal.storageservice.*` — 65 distinct imports |
| `lib-network/` | `lib/network` | `org.signal.network.api`, `.rest`, `.service` — 4 |
| `network/` | `core/network` | `org.signal.network.config`, `.crypto`, `.util`, `NetworkResult` — 12 |
| `util-jvm/` | `core/util-jvm` | `org.signal.core.models`, `org.signal.core.util` — 8 |
| `serialization/` | `core/serialization` | nothing directly; the other modules need it |
| `wire-handler/` | `wire-handler/lib` | not a dependency — a build tool, see below |

(Imports counted in the app's main source on 2026-11-16; names the app spells out in full at
the call site are not in these numbers.)

There were six modules until 2026-11-16. Upstream merged `core/models-jvm` into `core/util-jvm`
on 2026-11-03 (`592c8c70cb`) and this tree follows upstream's layout, so `org.signal.core.models`
now lives in `util-jvm/`.

This replaces `com.github.turasa:signal-network:2.15.3_unofficial_152`, the prebuilt fork the app
took from jitpack until now.

`org.signal:libsignal-client` is **not** built here: it is the Rust library with a JNI shim,
published by Signal, and the app already declares `org.signal:libsignal-android` for the native
side.

## Signal's tests are here and they run

**1,327 of them, and they pass** (run 2026-11-16 against the copy at `90a6ddce46`; eight are
skipped by upstream's own annotations, seven in `libsignal-service` and one in `util-jvm`).
Restored from upstream along with the source, with the test dependency versions upstream's
`gradle/test-libs.versions.toml` gives:

| module | tests |
|---|---|
| `libsignal-service` | 712 |
| `util-jvm` | 338 |
| `lib-network` | 251 |
| `network` | 26 |

This is the strongest available check that the copy and the build are right — far better than
anything written here could be — and it is the **only** thing that exercises the receive-side
refusals an ordinary conversation never trips. `EnvelopeContentValidatorTest` alone is 83 tests,
including the pair that matters most to this app: a body of *exactly* 2048 bytes is valid, one
byte over is not, and the UTF-8 case is tested separately because 600 emoji are 1200 UTF-16 units
and 2400 bytes.

⚠ **Three refusals have no upstream test**: bad GV2 master key, missing GV2 master key, missing
GV2 revision. Upstream does not cover them, so neither do we; they are the three of the nine that
remain unexercised.

⚠ **A failure in this suite means the copy is wrong, not that upstream is.** Re-copy the file;
do not patch the test.

## Why

The app's whole Signal implementation sat on a jar built by somebody else from a source tree
nobody here had read, resolved from jitpack by a name and a version string. Every argument this
audit makes about reading upstream before writing anything stopped at that boundary. Now the code
that encrypts and sends is in the tree, at a commit that is written down, and `git log` over
`signal-service/` says exactly what changed and when.

Dependency verification (`gradle/verification-metadata.xml`) pins the bytes of everything that is
still fetched.

## Licence

Signal-Android is AGPL-3.0-**only**; this repo is AGPL-3.0-**or-later**, which can carry it. Every
copied file keeps its upstream `SPDX-License-Identifier` header and its copyright line. Nothing
here is presented as this project's own work.

## What was changed, and it is a short list

**679 of the 685 copied files in the five modules are byte-identical to upstream at
`90a6ddce46`**, and so are both files of `wire-handler/` — measured with `cmp` against a
`git archive` of that commit, every file, every time this changes (last measured 2026-11-16;
the five SOCKS files below were changed on 2026-11-19, and nothing else with them).
The six that differ:

1. **`network/src/main/java/org/signal/network/util/JsonUtil.java`** — `new KotlinModule()` is a
   constructor jackson removed after 2.12. Replaced with
   `ExtensionsKt.registerKotlinModule(objectMapper)`, which is what upstream's *other* JSON helper
   (`core/util/.../JsonUtils.java:25`) already does in the same tree. Forced by the build, and
   commented at the line.

2. **The SOCKS proxy** (2026-11-19, the app's "SOCKS proxy" setting, modelled on Molly's): one
   new, optional field on `network/.../config/SignalServiceConfiguration.kt` (`socksProxy`,
   default null), and one `SocksRouting.apply(builder, ...)` line where each OkHttp client is
   built — `lib-network/.../rest/SignalRestClient.kt`,
   `libsignal-service/.../api/SignalUrlExtensions.kt`,
   `libsignal-service/.../internal/push/PushServiceSocket.java` (the source threaded through its
   private helpers) and `libsignal-service/.../internal/websocket/OkHttpWebSocketConnection.java`.
   With the field null, as upstream never sets it, each builder is exactly upstream's. The
   routing itself is ours and new, in `network/.../config/SocksProxy.kt`, with its test
   `SocksRoutingTest.kt` beside upstream's.

One upstream file is **not copied**: `libsignal-service/src/test/resources/ias.jks`. It is a
keystore, no source in these modules refers to it, and this repository's `.gitignore` refuses
`*.jks` outright. Upstream has 686 files under the five modules' `src/`; 685 are here.

The six `build.gradle` files (five modules and the wire handler) are ours — this repo has no
version catalogue, so upstream's `libs.versions.toml` references are spelled out — and the
publishing, ktlint, idea and javadoc blocks are dropped because nothing here publishes or lints
them. The five module files were rewritten from upstream's `build.gradle.kts` at `90a6ddce46`
and say so; the wire handler's is unchanged, as upstream's is.

One dependency version is **deliberately not** upstream's, and is commented in the root
`build.gradle`:

- **jackson 2.22.x**, not upstream's 2.12.0. The app already resolved 2.22.1 through the old fork,
  and jackson-databind is what a Signal envelope's JSON goes through. Taking upstream's number
  would have been a downgrade of a deserialiser.

**libsignal is upstream's pin**: 0.107.0, the `libsignal-client` version in Signal-Android's
`gradle/libs.versions.toml` at `90a6ddce46`, for both `libsignal-client` here and
`libsignal-android` in the app. The source and the pin are now from the same commit. So are
RingRTC (2.74.0), OkHttp (5.3.2) and libphonenumber (9.0.41). Checksums in
`gradle/verification-metadata.xml` are the ones Signal-Android pins where it pins them.

## How it got here: three copies

| date | upstream commit | what |
|---|---|---|
| 2026-09-16 | `b92917acdb` (2026-09-10) | first copy, six modules, replacing the prebuilt fork |
| 2026-09-16 | `d555580a46` (2026-09-16) | re-vendored the same day, 75 commits on |
| 2026-11-16 | `90a6ddce46` (2026-11-15, `v8.36.2`) | re-vendored, 54 commits on in these modules; five modules |
| 2026-11-24 | `2dd5703875` (2026-11-24, `v8.37.3`) | re-copied, 3 commits on in these modules: libsignal 0.108.0 (c5ff562e2d, hand-ported 11-22, now byte-identical), a log-scrubber pattern (e8a6f2d81b), provisioning crypto delegated to libsignal (07761fa0e1). The six departures below unchanged upstream and kept. Signal's suite: 1,337 pass (libsignal-service 714, util-jvm 338, lib-network 251, network 34 with our SOCKS tests). |

Between the second and third copies, single upstream fixes were ported by hand: thirteen whole
files taken from later upstream commits and six files edited here. That stopped scaling — the
tree was nineteen files off its base and each port needed its own proof — so the third copy
takes everything. All nine upstream commits those ports came from (`f627988255`, `f8ba4ae848`,
`ba5ce0c411`, `c0c10dde4e`, `bc7a984923`, `d86d875c1e`, `a1c18d30c7`, `db2d1e13df`,
`daaae6be0e`) are ancestors of `90a6ddce46`, so each of those files is now simply upstream's.
The local `BlacklistingTrustManagerTest` that read its resource with Jackson is upstream's
again too: `libsignal-service` builds with kotlinx.serialization now, as upstream's does.

The target is a **release tag's commit**, not the tip of `main`: the newest release whose
`libsignal-client` pin is the version this app builds with.

## What the third copy changed for the app

Upstream's own app adapted to each of these in the commit named, and the app here was changed
the same way. `git log d555580a46..90a6ddce46` over the five module paths is the full list;
these are the ones a caller notices.

- **No manual keepalives** (`37f8b149dd`). `SignalWebSocket.sendKeepAlive`,
  `HealthMonitor.onKeepAliveResponse` and `WebSocketConnection.sendKeepAlive` are gone; Signal's
  health monitor lost its keepalive sender, and so did `SignalSocketHealthMonitor`. ⚠ That
  sender was this app's alarm-backed wake for the socket (30 s on screen, 180 s off) and its
  dead-socket check. What keeps the connection alive and notices a dead one is now libsignal's
  chat connection alone. See the note at the end of this section.
- **This device's own prekeys go through `KeysApiV2`** (`4af8108670`).
  `KeysApi.checkRepeatedUseKeysSync` and `getAvailablePreKeyCountsSync` no longer exist.
  `PreKeyUploader` asks for counts once for both identities, and uploads each kind of key in its
  own request in `PreKeysSyncJob`'s order: one-time EC, one-time Kyber, signed prekey, last-resort
  Kyber. Because the one-time batches are now separate requests, the refill path stores its keys
  before it sends them.
- **Attachment uploads left `PushServiceSocket`** (`4bd15366c2`, `451c1f54b8`). `CdnService` is
  built from a `CdnApi` and an `AttachmentApi`, and is the `AttachmentUploader` the message sender
  now takes as its last constructor argument.
- **Contacts sync is read with `DeviceContactsReader`** (`3676792cd8`), which replaced
  `DeviceContactsInputStream`. A record with no address is skipped and counted by the reader;
  the app no longer matches on an exception message to do that.
- **`SealedSenderAccess` and `UnidentifiedAccess` moved** to `org.signal.network.crypto`
  (`1d592c7b85`). Imports only.
- **`SignalServiceConfiguration` lost `systemHttpProxy`** (`77c79d011b`). The app passed an
  empty one and passes nothing now. Applying a proxy to libsignal's `Network` became the app's
  job upstream (`ProxyConfig.resolve`, `Network.configureProxy`); this app never applied one
  and still does not.
- **Every link code now says `nopni,nopni2`** (`cdd27e08fa`): `ProvisioningSocket.Mode.Link`
  tells the primary this device can join an account with no phone number. This app cannot
  (`SignalCapabilities.OPTIONAL_PHONE_NUMBER`), and the library gives no way to leave the claim
  out, so `DeviceLinker` now refuses such a provisioning message by name, before anything is
  registered, instead of failing on a missing key.
- **One more receive-side refusal** (`90cbc7a3de`): an attachment pointer whose audio waveform
  is longer than `SignalServiceMessageLimits.MAX_AUDIO_WAVEFORM_BAR_COUNT` makes its message
  invalid, as a pointer with no CDN id or key already did.
- **Group sends** (`075995b608`, `25d1d76f1c`, `b87b12ad97`): a 1:1 fan-out encrypts on the
  calling thread and has at most 32 sends in flight; a transport failure to one recipient is a
  network failure for that one (the fix this tree had ported as `f627988255`).
- **Storage service**: the sticker pack record is `installedStickerPack` now and there are two
  new record kinds, hidden sticker pack and favorite sticker. The app picks contacts,
  groups and the account record out of the manifest by type and leaves other records' ids
  as they are (`docs/DECISION-storage-write.md`).
- **Registration errors**: a 403 on a captcha is `UpdateSessionError.TokenNotAccepted`
  (`2be641e6e6`), and the 441 on registering is `RegisterAccountError.TwoFactorRequired`, which
  says whether the account has an authenticator app or passkeys (`eb47004f3a`).
- **"Connected elsewhere"** (`263d273213`): libsignal's `ConnectedElsewhereException` on the chat
  connection is reported as `NonSuccessfulResponseCodeException(4409)`. The app has no case
  for it; it is an I/O failure like any other there.

⚠ **Sends still go through `SignalServiceMessageSender`.** Upstream's app is moving its sends to
`IndividualMessageService` and `GroupMessageService` in `lib/network` (null messages
`30bb40675b`, several sync sends `9603c75ac3` and `bc7a984923`, group sends `25d1d76f1c`). The
old sender is still in the library at this commit and still what part of Signal's app calls;
this app has not been moved to the new services.

⚠ **The keepalive, plainly.** Signal's own no-push mode at this commit has no alarm-backed
keepalive either: its message thread uses `AlarmSleepTimer` only for reconnect backoff, as this
app does (`SignalRepositoryImpl`). So following upstream is the like-for-like choice — and it
still removes the thing this app measured (wakeups per hour, no keepalive ever missed, seven
hours on a Kompakt, 2026-10-01). How promptly a message arrives on a phone that has been
dozing for an hour has to be measured again on hardware before this is released.

## The wire handler is built, not vendored

Upstream generates its protobuf code with a custom schema handler and ships it **compiled**, as
`wire-handler/wire-handler-1.0.0.jar` on the buildscript classpath. Taking that jar would have
reproduced, in the middle of this change, exactly the thing this change exists to remove.

So `wire-handler/` holds its two Kotlin source files and builds them. It is an *included build*
rather than a module because the wire plugin loads `org.signal.wire.Factory` by name off the
buildscript classpath, which is resolved before any module is configured; `settings.gradle`
includes it and Gradle substitutes it for the `org.signal:wire-handler:1.0.0` classpath
dependency.

What it does: rewrites `countNonNull` to `countNonDefa` in generated Kotlin, so a protobuf `oneof`
counts non-*default* rather than non-null values. The replacement is defined in
`libsignal-service/src/main/java/com/squareup/wire/internal/CountNonDefault.kt`. Skipping it would
still compile and would quietly change protobuf semantics.

It ran: 16 generated files in `libsignal-service` carry `countNonDefa` and none carry
`countNonNull`. `countNonDefa` is not a name wire can emit, so its presence is the proof.

## ⚠ This was a six-month library upgrade as well as a provenance change

The fork was `_152`; upstream had moved on. Only three call sites failed to compile, but **API
compatibility is not behaviour compatibility**. The receive-side validator alone gained nine
refusals — envelopes the previous build accepted and this one drops:

- `[DataMessage]` / `[EditMessage] Body exceeds 2048 bytes!`
- `[DataMessage]` / `[EditMessage]` / `[TypingMessage] Timestamps don't match!`
- Missing or bad GV2 master key, missing GV2 revision
- `Invalid PLAINTEXT_CONTENT!`

Measured by diffing the string constants of `EnvelopeContentValidator` in the old jar against the
new source, with a control to prove the comparison could find a rule that was present.

The first of those is the receive half of a fault this audit already fixed on the send side: a
body over 2048 bytes was sent, acknowledged, and silently discarded by a modern client. This build
now discards it on the way in too, which is parity — and is still a drop.
