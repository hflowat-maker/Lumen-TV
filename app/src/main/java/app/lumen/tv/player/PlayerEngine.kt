package app.lumen.tv.player

import android.content.Context
import android.util.Log
import app.lumen.tv.CrashReport
import androidx.media3.datasource.HttpDataSource
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Lecteur Media3 réglé pour l'IPTV : démarrage rapide, flux TS tolérants, décodage audio logiciel en secours. */
class PlayerEngine(context: Context, http: OkHttpClient, userAgent: String) {

    val player: ExoPlayer

    init {
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setConstantBitrateSeekingEnabled(true)

        val client = http.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dispatcher(Dispatcher())
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val resp = chain.proceed(req)
                Log.i(
                    CrashReport.TAG,
                    "Flux HTTP ${resp.code} ${req.url.host}:${req.url.port} …/${req.url.pathSegments.lastOrNull()} " +
                        "type=${resp.header("Content-Type")} taille=${resp.header("Content-Length")} range=${req.header("Range")}",
                )
                resp
            }
            .build()
        val dataSource = OkHttpDataSource.Factory(client).setUserAgent(userAgent)

        val mediaSources = DefaultMediaSourceFactory(dataSource, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(3))

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_200, 2_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val trackSelector = DefaultTrackSelector(context)

        player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(mediaSources)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages("fr")
            .build()
        player.playWhenReady = true
    }

    fun playLive(url: String, hls: Boolean) {
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    fun playVod(url: String, startMs: Long) {
        val item = MediaItem.Builder().setUri(url).build()
        player.setMediaItem(item, startMs.coerceAtLeast(0))
        player.prepare()
        player.play()
    }

    fun retry() {
        player.prepare()
        player.play()
    }

    fun release() {
        player.release()
    }

    // ---------------- Pistes ----------------

    data class TrackOption(val label: String, val detail: String?, val selected: Boolean, val group: Tracks.Group?, val index: Int)

    fun audioOptions(tracks: Tracks): List<TrackOption> =
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                val f = g.getTrackFormat(i)
                TrackOption(languageName(f) ?: "Piste ${i + 1}", audioDetail(f), g.isTrackSelected(i), g, i)
            }
        }

    fun textOptions(tracks: Tracks): List<TrackOption> {
        val list = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                val f = g.getTrackFormat(i)
                val forced = (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                TrackOption(
                    languageName(f) ?: "Sous-titres ${i + 1}",
                    listOfNotNull(f.label?.takeIf { it.isNotBlank() }, if (forced) "forcés" else null).joinToString(" · ").ifBlank { null },
                    g.isTrackSelected(i), g, i,
                )
            }
        }
        val anySelected = list.any { it.selected }
        return listOf(TrackOption("Désactivés", null, !anySelected, null, -1)) + list
    }

    fun select(type: Int, option: TrackOption) {
        val builder = player.trackSelectionParameters.buildUpon()
        val group = option.group
        if (group == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            builder.setTrackTypeDisabled(type, false)
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.index))
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun languageName(f: Format): String? {
        val code = f.language?.takeIf { it.isNotBlank() && it != "und" } ?: return f.label?.takeIf { it.isNotBlank() }
        val name = Locale(code).getDisplayLanguage(Locale.FRENCH)
        val pretty = if (name.isNullOrBlank() || name.equals(code, ignoreCase = true)) code.uppercase() else name.replaceFirstChar { it.titlecase(Locale.FRENCH) }
        val label = f.label?.takeIf { it.isNotBlank() && !it.equals(pretty, ignoreCase = true) }
        return if (label != null) "$pretty · $label" else pretty
    }

    private fun audioDetail(f: Format): String? {
        val codec = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MPEG"
            MimeTypes.AUDIO_OPUS -> "Opus"
            else -> null
        }
        val channels = when (f.channelCount) {
            1 -> "Mono"
            2 -> "Stéréo"
            6 -> "5.1"
            8 -> "7.1"
            Format.NO_VALUE -> null
            else -> "${f.channelCount} canaux"
        }
        return listOfNotNull(codec, channels).joinToString(" · ").ifBlank { null }
    }

    companion object {
        /** Détail technique affiché sous le message (code d'erreur, code HTTP). */
        fun technical(e: PlaybackException): String {
            var cause: Throwable? = e
            var http: Int? = null
            while (cause != null && http == null) {
                http = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                cause = cause.cause
            }
            val root = generateSequence(e as Throwable) { it.cause }.last()
            return buildString {
                append(e.errorCodeName)
                if (http != null) append(" · HTTP ").append(http)
                if (root !== e) append(" · ").append(root.javaClass.simpleName)
                root.message?.take(120)?.let { append(" : ").append(it) }
            }
        }

        fun describe(e: PlaybackException): String = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "Le serveur refuse ce flux. Vérifiez qu'aucun autre écran n'utilise votre abonnement."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Connexion au serveur impossible. Vérifiez la connexion internet de la TV."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Ce contenu n'existe plus chez votre fournisseur."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "Ce format vidéo n'est pas pris en charge par votre TV."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                "Le flux reçu est illisible. Essayez le format HLS dans les réglages."
            else -> "Lecture impossible (${e.errorCodeName})."
        }
    }
}
