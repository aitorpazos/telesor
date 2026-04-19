# Remoty ProGuard rules

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.remoty.data.**$$serializer { *; }
-keepclassmembers class dev.remoty.data.** {
    *** Companion;
}
-keepclasseswithmembers class dev.remoty.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Shizuku
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# VirtualDeviceManager (accessed via reflection through Shizuku)
-keep class android.companion.virtual.** { *; }
