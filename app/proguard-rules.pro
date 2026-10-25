# Décodeur FFmpeg chargé par réflexion par Media3
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn org.jellyfin.**

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
