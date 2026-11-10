# Keep rules for the shrunk release build (M10, T10.2). Each rule says why. Room, WorkManager, Compose, Tink, the biometric library and Play
# services bring their own rules in their libraries; what is here is only what they do not cover.

# Iroh (computer.iroh) is Rust behind UniFFI bindings that call the native library through JNA. JNA finds its structure fields and callback methods
# by name through reflection, and the bindings are generated code that names its own classes, so neither can be renamed or removed. (The same
# rules ran in the spike of M0.)
-keep class com.sun.jna.** { *; }
-keep class computer.iroh.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Tink reaches for the platform's HPKE classes only on Android versions that have them and checks first; on the others the classes are absent
# and the code is never run.
-dontwarn android.crypto.hpke.Hpke
-dontwarn android.crypto.hpke.Message
