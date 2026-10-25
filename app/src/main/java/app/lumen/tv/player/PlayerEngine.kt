package app.lumen.tv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.exoplayer.util.EventLogger
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
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Pas de nouvelle tentative inutile quand le serveur refuse explicitement (4xx). */
private class StreamErrorPolicy : DefaultLoadErrorHandlingPolicy(3) {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = info.exception
        if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499) return C.TIME_UNSET
        return super.getRetryDelayMsFor(info)
    }
}

/** Lecteur Media3 réglé pour l'IPTV : démarrage rapide, flux TS tolérants, décodage audio logiciel en secours. */
class PlayerEngine(
    context: Context,
    http: OkHttpClient,
    userAgent: String,
    /** Adresse de diffusion annoncée par le serveur (server_info), si différente de l'adresse saisie. */
    private val alternateBase: String? = null,
    preferAlternate: Boolean = false,
) {

    val player: ExoPlayer

    // En cas de refus du serveur (4xx), on réessaie avec d'autres identités de lecteur,
    // puis avec l'adresse de diffusion annoncée par le serveur, et on retient ce qui marche.
    private val userAgents: List<String> = (listOf(userAgent) + FALLBACK_USER_AGENTS).distinct()
    private var uaIndex = 0
    private var altUrl = alternateBase?.toHttpUrlOrNull()
    private var altBase = alternateBase
    private var startedOnAlternate = preferAlternate && altUrl != null

    var usingAlternate: Boolean = startedOnAlternate
        private set

    val currentUserAgent: String get() = userAgents[uaIndex]
    val userAgentChanged: Boolean get() = uaIndex > 0
    val alternateChanged: Boolean get() = usingAlternate != startedOnAlternate

    /** Passe à la combinaison suivante (identité, puis adresse) ; null quand tout a été essayé. */
    fun nextAttempt(): String? {
        if (uaIndex + 1 < userAgents.size) {
            uaIndex++
            Log.i(CrashReport.TAG, "Essai avec l'identité « ${userAgents[uaIndex]} »")
            return userAgents[uaIndex]
        }
        if (altUrl != null && usingAlternate == startedOnAlternate) {
            usingAlternate = !usingAlternate
            uaIndex = 0
            Log.i(CrashReport.TAG, "Essai avec l'adresse ${if (usingAlternate) altBase else "d'origine"}")
            return altBase
        }
        return null
    }

    /** Revient à l'adresse et à l'identité d'origine. */
    fun resetAttempts() {
        usingAlternate = false
        startedOnAlternate = false
        uaIndex = 0
    }

    /** Bascule sur une adresse de diffusion trouvée automatiquement. */
    fun useBase(base: String) {
        altBase = base
        altUrl = base.toHttpUrlOrNull()
        usingAlternate = altUrl != null
        startedOnAlternate = usingAlternate
        uaIndex = 0
        Log.i(CrashReport.TAG, "Lecture via $base")
    }

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
            // Remplace l'identité du lecteur par celle en cours d'essai (voir nextUserAgent).
            .addInterceptor { chain ->
                var req = chain.request()
                val alt = altUrl
                if (usingAlternate && alt != null) {
                    req = req.newBuilder()
                        .url(req.url.newBuilder().scheme(alt.scheme).host(alt.host).port(alt.port).build())
                        .build()
                }
                chain.proceed(req.newBuilder().header("User-Agent", currentUserAgent).build())
            }
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val resp = chain.proceed(req)
                val body = if (!resp.isSuccessful) {
                    runCatching { cleanBody(resp.peekBody(600).string()) }.getOrNull()
                } else null
                Log.i(
                    CrashReport.TAG,
                    "Flux HTTP ${resp.code} ${req.url.host}:${req.url.port} …/${req.url.pathSegments.lastOrNull()} " +
                        "type=${resp.header("Content-Type")} taille=${resp.header("Content-Length")} " +
                        "ua=\"${req.header("User-Agent")}\"" + (body?.let { " réponse=\"$it\"" } ?: ""),
                )
                resp
            }
            .build()
        val dataSource = OkHttpDataSource.Factory(client).setUserAgent(userAgent)

        val mediaSources = DefaultMediaSourceFactory(dataSource, extractors)
            .setLoadErrorHandlingPolicy(StreamErrorPolicy())

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
        // Journal détaillé du lecteur (chargements, erreurs, pistes) pour le diagnostic.
        player.addAnalyticsListener(EventLogger("LumenPlayer"))
    }

    /** Adresse en cours de lecture (pour l'ouverture dans un lecteur externe). */
    var currentUrl: String? = null
        private set

    private fun logStart(kind: String, url: String) {
        val u = Uri.parse(url)
        Log.i(CrashReport.TAG, "Lecture $kind : ${u.scheme}://${u.host}:${u.port} …/${u.lastPathSegment}")
    }

    fun playLive(url: String, hls: Boolean) {
        currentUrl = url
        logStart(if (hls) "direct HLS" else "direct TS", url)
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    fun playVod(url: String, startMs: Long) {
        currentUrl = url
        logStart("VOD", url)
        val item = MediaItem.Builder().setUri(url).build()
        player.setMediaItem(item, startMs.coerceAtLeast(0))
        player.prepare()
        player.play()
    }

    fun retry() {
        Log.i(CrashReport.TAG, "Nouvelle tentative de lecture")
        player.prepare()
        player.play()
    }

    fun stop() {
        player.stop()
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
            val body = generateSequence(e as Throwable) { it.cause }
                .firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }
                ?.responseBody?.let { cleanBody(String(it, Charsets.UTF_8)) }
            return buildString {
                append(e.errorCodeName)
                if (http != null) append(" · HTTP ").append(http)
                if (!body.isNullOrBlank()) {
                    append(" · serveur : « ").append(body.take(140)).append(" »")
                } else {
                    if (root !== e) append(" · ").append(root.javaClass.simpleName)
                    root.message?.take(120)?.let { append(" : ").append(it) }
                }
            }
        }

        /** Code HTTP renvoyé par le serveur, s'il y en a un. */
        fun httpCode(e: PlaybackException): Int? = generateSequence(e as Throwable) { it.cause }
            .firstNotNullOfOrNull { (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode }

        internal fun cleanBody(raw: String): String = raw
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)

        val FALLBACK_USER_AGENTS = listOf(
            "IPTVSmartersPro",
            "VLC/3.0.21 LibVLC/3.0.21",
            "TiviMate/5.1.6 (Android 14)",
            "okhttp/4.12.0",
            System.getProperty("http.agent") ?: "Dalvik/2.1.0 (Linux; U; Android 14)",
            "Lavf/60.16.100",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
        )

        fun describe(e: PlaybackException): String = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (httpCode(e)) {
                401, 403, 456, 458 -> "Le serveur refuse la lecture de ce flux (même avec plusieurs identités de lecteur). " +
                    "Votre abonnement limite peut-être le nombre d'écrans ou les applications autorisées."
                404 -> "Ce contenu n'existe plus chez votre fournisseur."
                else -> "Le serveur refuse ce flux. Vérifiez qu'aucun autre écran n'utilise votre abonnement."
            }
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
