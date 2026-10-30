# Décodeur FFmpeg chargé par réflexion par Media3
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn org.jellyfin.**

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Salon (l'écran d'accueil, classes io.freemytv) : lancé aussi par app_process (BtShell) et par le système par nom
-keep class io.freemytv.** { *; }
-keep class io.nayuki.** { *; }
