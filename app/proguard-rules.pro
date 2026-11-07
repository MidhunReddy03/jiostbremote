# dadb speaks the ADB protocol over a raw socket and relies on its own Okio internals,
# so keep the whole library when R8 shrinks the release build.
-keep class dadb.** { *; }
-keep interface dadb.** { *; }
-keepclassmembers class dadb.** { *; }
-dontwarn dadb.**
-dontwarn okio.**

-keepattributes Signature
-keepattributes *Annotation*
