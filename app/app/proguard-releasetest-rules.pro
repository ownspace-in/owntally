# Only for the releaseTest build type, which is never shipped (M10, T10.2): the Android test libraries come in with the Compose test manifest, in the
# app and again in the test APK. They must keep their names in both, or the test APK calls methods the app's copy has renamed.
-keep class androidx.test.** { *; }
-keep class androidx.tracing.** { *; }
-dontwarn androidx.test.**
# Libraries the test libraries call into, which the app itself may not use (so shrinking would remove what the tests need).
-keep class com.google.common.** { *; }
-dontwarn com.google.common.**
# The test libraries and the tests are Kotlin and use parts of its runtime that the app itself does not (shrinking would remove them, or rename what they call by name).
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**
# The tests call hooks that only tests use (resetContainerForTests, the flags that keep a test phone off the network) and reach into our modules and
# into the AndroidX pieces the test libraries are built on, none of which the app itself calls. So in THIS build type our own code and those pieces are
# kept; what is still shrunk and renamed is everything else: Room and its SQLite support, Tink and protobuf, OkHttp and Okio, the biometric library, Play
# services, WorkManager's internals (apart from what its test library needs), JNA and Iroh (by their own rules). Our code has no reflection of its
# own (ShrinkingSafetyTest), so renaming it cannot break it; the shipped build shrinks and renames all of it, and tools/smoke_release.py runs that.
-keep class owntally.** { *; }
-keep class androidx.work.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.fragment.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.concurrent.** { *; }
-dontwarn androidx.compose.**
# The rest of the AndroidX pieces the test libraries sit on (the packages found in the app): kept for the same reason. Room, SQLite support, the
# biometric library, Tink, OkHttp, Okio, the QR generator, Play services, JNA and Iroh stay shrunk and renamed in this build type.
-keep class androidx.collection.** { *; }
-keep class androidx.savedstate.** { *; }
-keep class androidx.appcompat.** { *; }
-keep class androidx.emoji2.** { *; }
-keep class androidx.profileinstaller.** { *; }
-keep class androidx.startup.** { *; }
-keep class androidx.annotation.** { *; }
-keep class androidx.arch.** { *; }
-keep class androidx.customview.** { *; }
-keep class androidx.interpolator.** { *; }
-keep class androidx.versionedparcelable.** { *; }
-dontwarn androidx.**
