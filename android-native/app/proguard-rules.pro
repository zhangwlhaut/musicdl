# R8/ProGuard rules for release builds.
# Most Compose/AndroidX/Media3/Retrofit/Moshi rules are bundled in their AARs/jars,
# so we only need to keep what is specific to this app.

# Keep gomobile-generated entry points.
-keep class go.** { *; }
-keep class mobile.** { *; }

# Keep Moshi-generated adapters lookup paths.
-keep class **JsonAdapter { *; }
-keepclassmembers class * {
    @com.squareup.moshi.* <fields>;
    @com.squareup.moshi.* <methods>;
}

# Retrofit + OkHttp recommended minima.
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**

# Coroutines internal continuation.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Compose lambda Synthetic classes.
-keep class androidx.compose.runtime.** { *; }
