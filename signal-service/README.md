# Signal's service layer, built from Signal's source

Six modules, copied verbatim from [Signal-Android](https://github.com/signalapp/Signal-Android)
at **`b92917acdb` (2026-09-10)** and compiled here:

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

**650 of the 651 files copied from upstream are byte-identical to it** — measured with `cmp`,
every file, every time this changes. Two edits to Signal's code, both forced, both commented at
the site:

1. **`network/src/main/java/org/signal/network/util/JsonUtil.java`** — `new KotlinModule()` is a
   constructor jackson removed after 2.12. Replaced with
   `ExtensionsKt.registerKotlinModule(objectMapper)`, which is what upstream's *other* JSON helper
   (`core/util/.../JsonUtils.java:25`) already does in the same tree.
2. **Nothing else.** The seven `build.gradle` files are ours — this repo has no version catalogue,
   so upstream's `libs.versions.toml` references are spelled out — and the publishing, ktlint,
   test-fixture and javadoc blocks are dropped because nothing here publishes or lints them.

Two dependency versions are **deliberately not** upstream's, and are commented in the root
`build.gradle`:

- **jackson 2.22.1**, not upstream's 2.12.0. The app already resolved 2.22.1 through the old fork,
  and jackson-databind is what a Signal envelope's JSON goes through. Taking upstream's number
  would have been a downgrade of a deserialiser.
- **libsignal-client 0.102.0**, not upstream's 0.102.1, to match the `libsignal-android` the app
  declares. The Java API and the native library ship together; moving them is its own change.

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
