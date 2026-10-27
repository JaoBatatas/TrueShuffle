# Keep Glance ActionCallbacks and Receivers
-keep class * implements androidx.glance.appwidget.action.ActionCallback { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# Keep Spotify App Remote SDK
-keep class com.spotify.android.appremote.** { *; }
-keep interface com.spotify.android.appremote.** { *; }

# Keep Gson DTOs
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.example.trueshuffle.data.remote.** { *; }

# Keep Retrofit & Annotations
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*

# Keep Local Entities & Dao
-keep class com.example.trueshuffle.data.local.** { *; }
