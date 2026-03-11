# Add project specific ProGuard rules here.

# Firebase Firestore
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**

# ML Kit Text Recognition
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Kotlin Coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep data models
-keep class com.livewin.freefiretracker.models.** { *; }

# Keep service classes
-keep class com.livewin.freefiretracker.ScreenCaptureService { *; }
-keep class com.livewin.freefiretracker.OverlayHudService { *; }
-keep class com.livewin.freefiretracker.MainActivity { *; }
