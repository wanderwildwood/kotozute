-dontobfuscate

# android-smsmms
# -keep class android.net.** { *; }
-dontwarn android.net.ConnectivityManager
-dontwarn android.net.LinkProperties

# autodispose
-dontwarn com.uber.autodispose.**

# ez-vcard
-dontwarn ezvcard.**
-dontwarn org.apache.log.**
-dontwarn org.apache.log4j.**
-dontwarn org.python.core.**

# okio
-dontwarn okio.**

# okhttp3
# JSR 305 annotations are for embedding nullability information.
-dontwarn javax.annotation.**

# A resource is loaded with a relative path so the package of this class must be preserved.
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Animal Sniffer compileOnly dependency to ensure APIs are compatible with older versions of Java.
-dontwarn org.codehaus.mojo.animal_sniffer.*

# OkHttp platform used only on JVM and when Conscrypt dependency is available.
-dontwarn okhttp3.internal.platform.ConscryptPlatform

# moshi
# JSR 305 annotations are for embedding nullability information.
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory

-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}

-keep @com.squareup.moshi.JsonQualifier interface *

# Enum field names are used by the integrated EnumJsonAdapter.
# Annotate enums with @JsonClass(generateAdapter = false) to use them with Moshi.
-keepclassmembers @com.squareup.moshi.JsonClass class * extends java.lang.Enum {
    <fields>;
}

# The name of @JsonClass types is used to look up the generated adapter.
-keepnames @com.squareup.moshi.JsonClass class *

# Retain generated target class's synthetic defaults constructor and keep DefaultConstructorMarker's
# name. We will look this up reflectively to invoke the type's constructor.
#
# We can't _just_ keep the defaults constructor because Proguard/R8's spec doesn't allow wildcard
# matching preceding parameters.
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-keepclassmembers @com.squareup.moshi.JsonClass class * {
    <init>(...);
}

# Retain generated JsonAdapters if annotated type is retained.
-keep class **JsonAdapter {
    <init>(...);
    <fields>;
}

-if @com.squareup.moshi.JsonClass class *
-keep class <1>JsonAdapter {
    <init>(...);
    <fields>;
}
-if @com.squareup.moshi.JsonClass class **$*
-keep class <1>_<2>JsonAdapter {
    <init>(...);
    <fields>;
}
-if @com.squareup.moshi.JsonClass class **$*$*
-keep class <1>_<2>_<3>JsonAdapter {
    <init>(...);
    <fields>;
}
-if @com.squareup.moshi.JsonClass class **$*$*$*
-keep class <1>_<2>_<3>_<4>JsonAdapter {
    <init>(...);
    <fields>;
}
-if @com.squareup.moshi.JsonClass class **$*$*$*$*
-keep class <1>_<2>_<3>_<4>_<5>JsonAdapter {
    <init>(...);
    <fields>;
}
-if @com.squareup.moshi.JsonClass class **$*$*$*$*$*
-keep class <1>_<2>_<3>_<4>_<5>_<6>JsonAdapter {
    <init>(...);
    <fields>;
}
# Dagger
# This is to allow the restore functionality to work
-keep class dagger.** { *; }
-keep class * extends dagger.Module { *; }
-keep class * extends dagger.Component { *; }
-keep class * extends dagger.Subcomponent { *; }
-keep class * {
    @dagger.Provides <methods>;
}
-keep class io.reactivex.** { *; }
-keep class io.reactivex.subjects.** { *; }
-keep class androidx.activity.result.** { *; }
-keep class com.wanderwildwood.kotozute.** { *; }


# NanoHTTPD / NanoWSD — powers the Desktop Sync relay. Added for this fork;
# upstream had no rules because it didn't bundle an embedded server.
-dontwarn fi.iki.elonen.**
-keep class fi.iki.elonen.** { *; }

# AGP 9 refuses proguard-android.txt because it
# carries -dontoptimize, so the optimize variant is now in use and R8 optimises where it did
# not before. Realm does not survive that: its static initialiser dies with an
# ArrayIndexOutOfBoundsException before the app draws anything, because the optimiser has
# taken apart something the generated module lookup depends on.
#
# This is the escape hatch AGP's own error message points at. It buys back the old behaviour
# at the cost of the optimisation the newer file exists to enable -- which is the right trade
# only until someone works out what Realm actually needs kept.
-dontoptimize
-keep class io.realm.** { *; }
-keep class * extends io.realm.RealmObject { *; }
-keepnames class io.realm.** { *; }

# Second casualty of the same change. Room finds its generated implementation by building a
# class name from the canonical one and looking it up reflectively, which R8 cannot see, so
# under the newer optimiser WorkManager's database fails to instantiate before the app starts.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keepnames class androidx.work.impl.** { *; }

# Third casualty, and the expensive one: WorkManager builds a worker's input merger by
# constructing a class name and calling the no-argument constructor reflectively. Nothing
# references that constructor, so R8 removed it, and every worker started with input data
# died before it ran:
#
#   NoSuchMethodException: androidx.work.OverwritingInputMerger.<init> []
#   WM-WorkerWrapper: Could not create Input Merger
#
# What that cost was every incoming SMS. The broadcast arrived and the receiver filed the
# message, but the worker that puts it in front of anyone never started -- so texts sat
# unseen until something swept them in, and a whole day of them appeared at once. The rule
# above did not cover it twice over: this class is in `androidx.work`, not
# `androidx.work.impl`, and `-keepnames` permits members to be removed regardless.
#
# Kept by supertype rather than by name so a merger this app has not met yet is covered too.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class androidx.work.InputMerger { <init>(); }

# Signal's service layer brings Jackson, which
# references java.beans annotations that exist on the JVM and not on Android. R8 treats the
# dangling references as an error and refuses to build; they are never reached at runtime
# because the code that would use them is JVM-only.
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient

# libsignal is a JNI library: the Rust core calls back into these classes by name, and R8
# cannot see a call that originates outside the dex. Renaming or removing them produces a
# NoSuchMethodError from native code at the first cipher operation -- which reads as the
# library failing to load rather than as a keep rule being absent.
-keep class org.signal.libsignal.** { *; }
-keepclassmembers class org.signal.libsignal.** {
    native <methods>;
    <init>(...);
}

# The service layer's wire types are deserialised by name, by Jackson and by wire protobuf
# adapters that look up their generated companions reflectively.
-keep class org.whispersystems.signalservice.** { *; }
-keep class org.signal.network.** { *; }
-keepclassmembers class * extends com.squareup.wire.Message { *; }
