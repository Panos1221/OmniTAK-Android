# R8 keep rules for OmniTAK Android.
#
# Keep policy is conservative: minify only what's clearly safe, leave
# anything reflection-touched alone. If you remove a -keep from this
# file, smoke-test a release APK against the closed-test build before
# uploading — silent NoSuchMethodError at runtime is the failure mode.

# --- kotlinx.serialization ----------------------------------------------
# Generated $serializer companions are looked up reflectively. Keep them
# along with the @Serializable data class itself.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class * {
    static <1>$Companion Companion;
    public static <1>$$serializer INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }

# Our @Serializable model classes live under .data — keep their fields
# so DataStore JSON round-trips don't lose properties.
-keep class soy.engindearing.omnitak.mobile.data.** { *; }

# --- MapLibre -----------------------------------------------------------
# MapLibre ships consumer rules but its native bridge methods are looked
# up by name. Belt-and-suspenders keep on the public package.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }

# --- Compose ------------------------------------------------------------
# AGP applies Compose's consumer rules automatically. No app-side rules
# needed beyond the defaults in proguard-android-optimize.txt.

# --- JNI / native -------------------------------------------------------
# No app-owned JNI today. Library natives (MapLibre, datastore-shared-
# counter, androidx.graphics.path) are covered by their consumer rules.
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Misc ---------------------------------------------------------------
# Suppress notes about packages R8 sees but doesn't act on; clean output.
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
