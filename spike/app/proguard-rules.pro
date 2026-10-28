# JNA and the Iroh bindings are reached through reflection and native code.
-keep class com.sun.jna.** { *; }
-keep class computer.iroh.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
