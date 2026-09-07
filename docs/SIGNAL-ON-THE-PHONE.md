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

## The question this branch has not answered

Whether the phone can hold the Signal websocket without ruining the battery. That is
measurable on hardware before any of the above is paid for, and it decides whether the rest
is worth doing.
