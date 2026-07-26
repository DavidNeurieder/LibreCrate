# LibreCrate ProGuard rules
-keepattributes *Annotation*

# Readium2
-keep class org.readium.** { *; }
-dontwarn org.readium.**

# SQLCipher
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Keep Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ZXing
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Argon2Kt
-keep class com.lambdapioneer.argon2kt.** { *; }
-dontwarn com.lambdapioneer.argon2kt.**

# Apache Commons Compress
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# CommonMark
-keep class org.commonmark.** { *; }
-dontwarn org.commonmark.**

# Coil
-keep class coil.** { *; }
-dontwarn coil.**

# SLF4J (used by junrar / commons-compress, no binding needed on Android)
-dontwarn org.slf4j.impl.StaticLoggerBinder

# UniFFI / JNA
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-keep class uniffi.vault_native.** { *; }
-dontwarn uniffi.vault_native.**
