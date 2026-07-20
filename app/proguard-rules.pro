# Keep Moshi generated adapters and model fields
-keep class com.fizaan.kimaitimer.data.** { *; }
-keepclassmembers class ** {
    @com.squareup.moshi.Json <fields>;
}
