# R8 runs on release builds (see app/build.gradle.kts). Almost everything FRAD uses is
# referenced directly from code, which R8 follows by itself; what's listed here is reached
# by name from native code instead.

# gomobile (wide-range layer, p2p-go/build/p2pgo.aar): the Go runtime looks up these
# classes, fields and methods through JNI by their names, and calls back into listener
# implementations written in Kotlin (WideRangeNode's PeerFoundListener /
# IncomingStreamListener objects).
-keep class go.** { *; }
-keep class app.frad.chat.p2pgo.** { *; }
-keep class * implements app.frad.chat.p2pgo.** { *; }
-dontwarn go.**
-dontwarn app.frad.chat.p2pgo.**

# Bouncy Castle is used through its lightweight API only (no JCA provider registration),
# so nothing in it is looked up reflectively; it does reference optional classes that
# aren't on Android.
-dontwarn org.bouncycastle.**

# Tink (inside androidx.security-crypto, kept only to migrate the old identity storage)
# references annotation classes it needs only at compile time.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Keep line numbers in crash reports readable without shipping the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Release builds log no debug/verbose/info lines: those name Bluetooth addresses, session and
# peer ids and the like, which a bug report or anything else reading the system log shouldn't
# carry. Warnings and errors stay.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
