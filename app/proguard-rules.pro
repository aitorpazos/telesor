# Remoty ProGuard rules
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep class dev.remoty.data.** { *; }
