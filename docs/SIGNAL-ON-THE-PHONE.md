# Signal on the phone — what the toolchain actually says

Branch `signal-on-the-phone`, pushed to the **private** mirror only. Nothing here is a plan;
it is the record of adding `org.signal:libsignal-android` to this project and writing down
what broke, in order. Each step was found by building, not by reasoning.

## The obstacles, in the order the build hits them

1. **Jetifier runs out of memory** on the 194 MB AAR. It rewrites support-library references
   by holding the artifact in memory, and there is nothing in libsignal for it to rewrite.
   → `android.jetifier.ignorelist=libsignal`.
2. **Jetifier cannot read Java 21 bytecode** — "Unsupported class file major version 65" —
   for `libsignal-client`, which is a separate artifact from the AAR. Same fix, wider list.
3. **The AAR metadata requires core library desugaring.** One flag and one dependency:
   `coreLibraryDesugaringEnabled true` plus `desugar_jdk_libs`. Not a toolchain change.
4. **D8 cannot desugar Java records here**: *"Attempt to create a global synthetic for
   'Record desugaring' without a global-synthetics consumer."* libsignal uses records
   (Java 16+). This one needs a **newer AGP** than 8.2.2.
5. **A transitive dependency carries Kotlin 2.1 metadata.** libsignal pulls
   `kotlinx-coroutines-core 1.10.2`, whose metadata this compiler refuses: *"binary version
   of its metadata is 2.1.0, expected version is 1.7.1."* This needs a **newer Kotlin** than
   1.7.21.

## What that means

Both upgrades, not one: **AGP 8.2.2 → newer, and Kotlin 1.7.21 → 2.x**, on a project using
Realm-Java and kapt. That is the real entry price, and it is paid before a single line of
Signal code is written.

Note for anyone reading the history: the Kotlin problem is real but not where it was first
looked for. libsignal's own API is Java — the AAR has one Kotlin-related entry out of 697.
The Kotlin 2.1 metadata arrives through **coroutines**, transitively. Checking the library's
own classes and concluding "not a Kotlin problem" was wrong, and only building revealed it.

## What is not a problem

- **minSdk.** The AAR declares 23, exactly this project's.
- **Size.** 22.5 MB packed for arm64, after excluding `libsignal_jni_testing.so`.
- **Licence.** AGPL §13 permits the combination and leaves the GPL part GPL. What attaches is
  an obligation to offer source to anyone using Desktop Sync — a footer link.

## Then the toolchain was actually upgraded, and it went further than expected

**Realm Java survives Kotlin 2.1 + AGP 8.7.3 + Gradle 8.9.** The whole project compiled --
every module, kapt and all -- which was the big unknown and is the good news here. Realm Java
has not been touched upstream since September 2025, so this was not safe to assume.

What it needed on the way: the JVM target said once for every module (Kotlin 2.x stops
defaulting it, and two of the five modules never said what they wanted).

**But libsignal wants newer than AGP 8.7.** D8 there cannot desugar its Java records:
*"Attempt to create a global synthetic for 'Record desugaring' without a global-synthetics
consumer."* `android.enableGlobalSyntheticGeneration=true` does not turn that pipeline on --
the dexing attributes still report `enableGlobalSynthetics=false`.

**Signal themselves ship on AGP 9.2.1, Kotlin 2.2.20, Gradle 9.4.1** (checked in their own
build files), with `-Xmx12g`. So the working configuration for this library is AGP 9, not 8.

**AGP 9 is a structural migration, and the Realm question reopens under it.** The first thing
it says is that `org.jetbrains.kotlin.android` must be removed from every module — Kotlin
support is built in from AGP 9.0. That is a rewrite of all five build files, and it is
*before* finding out whether the Realm Gradle plugin, last touched in 2025, works with AGP 9
at all.

## Under AGP 9: Realm's plugin does load

The gate was "does Realm survive AGP 9", and as far as the build has gone, yes.

Getting there took two things. AGP 9 refuses `kotlin-android` (Kotlin support is built in) and
then refuses `kotlin-kapt` alongside it. Every annotation processor here runs through kapt --
Dagger, Glide, Realm -- and none has a KSP path on the pinned versions, so the built-in
support is declined instead: `android.builtInKotlin=false` and `android.newDsl=false`, which
AGP documents for exactly this. With that, `apply plugin: 'realm-android'` applies without
complaint under **AGP 9.2.1, Gradle 9.4.1, Kotlin 2.2.20**.

**Be precise about what that proves.** The plugin *applies*. Whether Realm's bytecode
transformer runs correctly under AGP 9's pipeline is still unknown, because the build has not
reached it — it is still failing in this project's own build files. Plugin application is not
a working build, and the difference is where an EOL plugin would be expected to break.

What is failing now is an ordinary AGP 9 DSL migration, one removal at a time:

- `archivesBaseName` is gone from `defaultConfig` → moved to the `base` extension. Worth care:
  the APK name is load-bearing, since the release workflow and every published checksum are
  keyed to `kotozute-v<version>`.
- `proguardFiles getDefaultProguardFile(...)` is the next one, and there will be more.
- Realm's annotation processor is declared on `annotationProcessor` somewhere as well as
  `kapt`, which AGP 9 warns about.

That is bounded, tedious work rather than a wall — but it is a migration of this project's
build, and it should be done deliberately rather than as a side effect of an experiment.

## It builds, and it runs

**On Gradle 9.4.1, AGP 9.2.1 and Kotlin 2.2.20 — Signal's own toolchain — with Realm 10.15.0
and libsignal-android 0.102.0 in the APK, the app compiles, installs, launches, receives an
SMS and shows it.** Zero crashes.

Realm is the headline. It is end-of-life, untouched upstream since September 2025, and it came
through all three levels: the plugin applies, `debugRealmAccessorsTransformer` runs, and the
database reads and writes at runtime under the new toolchain.

**The Kotlin 1.7 → 2.2 jump cost nine compile errors in the whole app**, all mechanical:

- `QkPresenter`, `QkController`, `GlideCompletionListener` — unbounded type parameters meeting
  Java `@NonNull`. `State : Any` and `T : Any`. The values were never null; this only says so.
- One RxJava `withLatestFrom` combiner whose last expression is `Unit`, which Kotlin 2 will no
  longer infer. An explicit `Unit`.

Other AGP 9 removals met on the way: `archivesBaseName` off `defaultConfig` (careful — the APK
name is load-bearing for the release workflow and every published checksum), `resValues` now
off by default, a compileSdk that had to match across modules, and `kotlin-kapt` declined in
favour of keeping kapt via `android.builtInKotlin=false`.

⚠ **One change is behavioural, not cosmetic.** AGP 9 refuses `proguard-android.txt` because it
carries `-dontoptimize`, so the build now uses `proguard-android-optimize.txt`. R8 will
optimise where it previously did not. **A release built this way needs re-verifying, not just
rebuilding** — Realm, Dagger and anything reached reflectively are where that shows up. No
release build has been attempted here at all.

### What this does not show

- **No Signal code exists.** libsignal is linked and unused. This says the toolchain and the
  database survive; it says nothing about writing a client.
- **The APK is ~1 GB** because packaging was never tuned: four ABIs, the 134 MB testing
  engine, and Windows and macOS natives arriving through the JVM jar's resources. Excludes
  were attempted and partly worked; `abiFilters` did not take. None of this is interesting —
  the shippable figure is **22.5 MB packed for arm64**, measured from the AAR's own compressed
  entry, and it stands.
- Realm was exercised lightly: one SMS in, one list rendered. The encryption migration and the
  Signal rail were not re-run under the new toolchain.

## Where this leaves it

The entry price is an **AGP 9 migration**, and the open question underneath is whether Realm
survives it. If it does not, route 3 means replacing the persistence layer of the whole app --
both rails, every screen, the schema and its 22 migrations. That would no longer be "add
Signal to the app"; it would be rebuilding the app to add Signal.

That question is answerable on this branch, cheaply, before anything else: bump to AGP 9,
strip the kotlin plugins, and see whether Realm's plugin loads. Worth doing before the battery
measurement, because a negative answer ends the route regardless of what the battery says.

## The question this branch has not answered

Whether the phone can hold the Signal websocket without ruining the battery. That is
measurable on hardware before any of the above is paid for, and it decides whether the rest
is worth doing.
