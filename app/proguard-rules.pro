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

# Keep line numbers in crash reports readable without shipping the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
