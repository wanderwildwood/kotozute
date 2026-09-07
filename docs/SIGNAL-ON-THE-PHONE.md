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
