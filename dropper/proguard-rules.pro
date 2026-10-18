# Proguard rules for dropper module
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.system.service.booster.** { *; }
