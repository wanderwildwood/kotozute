# Signal's service layer, built from Signal's source

Six modules, copied verbatim from [Signal-Android](https://github.com/signalapp/Signal-Android)
at **`d555580a46` (2026-09-16)** and compiled here:

| here | upstream | what the app uses from it |
|---|---|---|
| `libsignal-service/` | `lib/libsignal-service` | `org.whispersystems.signalservice.*` — 57 imports |
| `lib-network/` | `lib/network` | `org.signal.network.api`, `.rest`, `.service` — 11 imports |
| `network/` | `core/network` | `org.signal.network.NetworkResult` — 1 import |
| `models-jvm/` | `core/models-jvm` | `org.signal.core.models` — 3 imports |
| `util-jvm/` | `core/util-jvm` | `org.signal.core.util` — 2 imports |
| `serialization/` | `core/serialization` | nothing directly; `lib-network` needs it |
| `wire-handler/` | `wire-handler/lib` | not a dependency — a build tool, see below |

This replaces `com.github.turasa:signal-network:2.15.3_unofficial_152`, the prebuilt fork the app
took from jitpack until now.

`org.signal:libsignal-client` is **not** built here: it is the Rust library with a JNI shim,
published by Signal, and the app already declares `org.signal:libsignal-android` for the native
side.

## Signal's tests are here and they run

**1,015 of them, and they pass.** Restored from upstream along with the source, with the test
dependency versions upstream's `gradle/test-libs.versions.toml` gives:

| module | tests |
|---|---|
| `libsignal-service` | 634 |
| `util-jvm` | 280 |
| `lib-network` | 80 |
| `models-jvm` | 17 |
| `network` | 4 |

This is the strongest available check that the copy and the build are right — far better than
anything written here could be — and it is the **only** thing that exercises the receive-side
refusals an ordinary conversation never trips. `EnvelopeContentValidatorTest` alone is 73 tests,
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

**630 of the 645 files in the six modules are byte-identical to upstream at `d555580a46`**, and
so are both files of `wire-handler/` — measured with `cmp`, every file, every time this changes
(last measured 2026-11-16). Of the other fifteen, nine are whole files taken from a later
upstream commit and match upstream at that commit byte for byte, and six carry an edit made
here. Both lists are below. The first edit was forced by the build; the rest are ports of
single upstream fixes, each named here with the upstream commit it follows (the first two are
also commented at the line; the others are named in the commit message that made them):

1. **`network/src/main/java/org/signal/network/util/JsonUtil.java`** — `new KotlinModule()` is a
   constructor jackson removed after 2.12. Replaced with
   `ExtensionsKt.registerKotlinModule(objectMapper)`, which is what upstream's *other* JSON helper
   (`core/util/.../JsonUtils.java:25`) already does in the same tree.
2. **`libsignal-service/.../api/SignalServiceMessageSender.java`** — upstream `f627988255`: a
   transport failure to one recipient of a group send is a network failure for that one, not a
   throw that loses everybody else's result. Five lines.
3. **`libsignal-service/.../api/messages/EnvelopeContentValidator.kt`** and its test — upstream
   `f8ba4ae848`: poll question and option limits counted in graphemes.
4. **`libsignal-service/.../internal/push/PushServiceSocket.java`** — upstream `ba5ce0c411`:
   `sendRemoteManifestOnConflict=false` on the storage write. One line.
5. **`libsignal-service/src/test/.../internal/util/BlacklistingTrustManagerTest.kt`** — upstream's
   test from `c0c10dde4e`, reading its JSON resource with Jackson where upstream uses
   kotlinx.serialization, which this module does not build with.

The seven `build.gradle` files are ours — this repo has no version catalogue, so upstream's
`libs.versions.toml` references are spelled out — and the publishing, ktlint, test-fixture and
javadoc blocks are dropped because nothing here publishes or lints them. They were first written
from upstream's files at `b92917acdb` (2026-09-10) and say so; the source beside them is at
`d555580a46`.

One dependency version is **deliberately not** upstream's, and is commented in the root
`build.gradle`:

- **jackson 2.22.x**, not upstream's 2.12.0. The app already resolved 2.22.1 through the old fork,
  and jackson-databind is what a Signal envelope's JSON goes through. Taking upstream's number
  would have been a downgrade of a deserialiser.

**libsignal is upstream's pin**: 0.107.0, the `libsignal-client` version in Signal-Android's
`gradle/libs.versions.toml` at `90a6ddce46` (2026-11-15), for both `libsignal-client` here and
`libsignal-android` in the app. The source here is older than that pin (2026-09-16), so the one
source change upstream made for a libsignal bump in between is taken with it: the two cipher
wrappers in the table below, from the commit that moved upstream to 0.107.0. Checksums for both
artifacts in `gradle/verification-metadata.xml` are the ones Signal-Android pins.

## Files taken from a later upstream commit

Single fixes are ported by copying the whole file at the upstream commit that made them, so
each file still matches upstream byte for byte, only at a newer commit than the base:

| file | upstream commit | why |
|---|---|---|
| `libsignal-service/.../multidevice/DeviceContactsInputStream.java` | `bc7a984923` (2026-10-23) | an avatar closed by its reader no longer closes the whole contacts sync; an unread avatar is skipped; a contact with only a number is read |
| `libsignal-service/.../multidevice/DeviceContactsInputStreamTest.java` | `bc7a984923` | the tests for the above |
| `util-jvm/.../stream/NonClosingInputStream.kt` | `bc7a984923` | new with the above |
| `libsignal-service/.../api/storage/StorageServiceApi.kt` | `ba5ce0c411` (2026-10-05) | no manifest sent back on a storage conflict the app ignores |
| `libsignal-service/.../api/websocket/SignalWebSocket.kt` | `d86d875c1e` (2026-09-17) | chat requests are time-limited |
| `libsignal-service/.../internal/util/BlacklistingTrustManager.java` | `a1c18d30c7` (2026-10-22) | refuses the known compromised TLS intermediates |
| `libsignal-service/src/test/resources/emudhra-impacted-intermediates.json` | `c0c10dde4e` (2026-10-21) | the list the test for the above reads |
| `libsignal-service/.../api/crypto/SignalGroupCipher.java` | `daaae6be0e` (2026-11-11) | libsignal 0.107.0: `GroupCipher.decrypt` declares `InvalidVersionException` and `InvalidKeyException` instead of wrapping them in an `AssertionError` |
| `libsignal-service/.../api/crypto/SignalSessionCipher.java` | `daaae6be0e` | libsignal 0.107.0: the same for `SessionCipher.decrypt(PreKeySignalMessage)` and `InvalidVersionException` |

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
