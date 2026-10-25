package app.lumen.tv.player

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent as AKey
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.lumen.tv.CrashReport
import app.lumen.tv.LumenApp
import app.lumen.tv.data.Channel
import app.lumen.tv.data.ContentType
import app.lumen.tv.data.EpgEntry
import app.lumen.tv.data.Episode
import app.lumen.tv.data.PlayRequest
import app.lumen.tv.data.Text as Txt
import app.lumen.tv.ui.AppController
import app.lumen.tv.ui.components.ChannelLogo
import app.lumen.tv.ui.components.Clickable
import app.lumen.tv.ui.components.PillButton
import app.lumen.tv.ui.components.ProgressLine
import app.lumen.tv.ui.components.RoundIconButton
import app.lumen.tv.ui.components.Safe
import app.lumen.tv.ui.components.Spinner
import app.lumen.tv.ui.components.rememberCenterPivot
import app.lumen.tv.ui.components.safeRequest
import app.lumen.tv.ui.theme.C as Pal
import app.lumen.tv.ui.theme.T
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private enum class Panel { NONE, AUDIO, SUBS, CHANNELS }

private fun seekStep(repeat: Int): Long = when {
    repeat < 3 -> 10_000L
    repeat < 12 -> 30_000L
    else -> 60_000L
}

@Composable
fun PlayerScreen(app: AppController, request: PlayRequest) {
    val context = LocalContext.current
    val repo = app.repo
    val prefs = app.prefs
    val engine = remember { PlayerEngine(context, (context.applicationContext as LumenApp).http, prefs.userAgent) }
    val player = engine.player

    val live = request as? PlayRequest.Live
    val movie = request as? PlayRequest.Movie
    val episodes = request as? PlayRequest.Episodes
    val isVod = live == null

    // ---------------- État ----------------
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var lastError by remember { mutableStateOf<PlaybackException?>(null) }
    var errorTick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var slowStart by remember { mutableStateOf(false) }
    var reconnecting by remember { mutableStateOf(false) }
    var retries by remember { mutableIntStateOf(0) }
    var controls by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var interaction by remember { mutableIntStateOf(0) }
    var resize by remember { mutableIntStateOf(prefs.resizeMode.coerceIn(0, 2)) }
    var resizeLabel by remember { mutableStateOf<String?>(null) }
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    var seekTick by remember { mutableIntStateOf(0) }
    var numberInput by remember { mutableStateOf("") }
    var nextPrompt by remember { mutableStateOf(false) }
    var nextDismissed by remember { mutableStateOf(false) }
    var liveIndex by remember { mutableIntStateOf(live?.index ?: 0) }
    var epIndex by remember { mutableIntStateOf(episodes?.index ?: 0) }
    var firstEpisode by remember { mutableStateOf(true) }
    var wasStopped by remember { mutableStateOf(false) }

    val rootFr = remember { FocusRequester() }
    val seekFr = remember { FocusRequester() }
    val firstButtonFr = remember { FocusRequester() }
    val nextFr = remember { FocusRequester() }
    val errorFr = remember { FocusRequester() }

    fun curChannel(): Channel? = live?.channels?.getOrNull(liveIndex)
    fun curEpisode(): Episode? = episodes?.episodes?.getOrNull(epIndex)
    fun nextEpisode(): Episode? = episodes?.episodes?.getOrNull(epIndex + 1)

    fun saveProgress(finished: Boolean = false) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val pos = if (finished) dur else player.currentPosition
        if (movie != null) {
            repo.saveMovieProgress(movie.tile, pos, dur)
        } else if (episodes != null) {
            val ep = curEpisode() ?: return
            repo.saveEpisodeProgress(episodes.series, ep, pos, dur, nextEpisode())
        }
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            controls = true
        } else {
            if (player.playbackState == Player.STATE_IDLE) engine.retry() else player.play()
        }
    }

    fun seekBy(delta: Long) {
        if (!isVod || duration <= 0) return
        val base = pendingSeek ?: player.currentPosition
        pendingSeek = (base + delta).coerceIn(0, (duration - 2_000).coerceAtLeast(0))
        seekTick++
    }

    fun zap(delta: Int) {
        val n = live?.channels?.size ?: return
        if (n == 0) return
        liveIndex = ((liveIndex + delta) % n + n) % n
        banner = true
    }

    fun goNext() {
        nextEpisode() ?: return
        val nearEnd = duration > 0 && duration - player.currentPosition < 90_000
        saveProgress(finished = nearEnd)
        nextPrompt = false
        nextDismissed = false
        epIndex += 1
    }

    fun cycleResize() {
        resize = (resize + 1) % 3
        resizeLabel = listOf("Adapté à l'écran", "Remplir l'écran", "Étiré")[resize]
    }

    // ---------------- Cycle de vie du lecteur ----------------

    DisposableEffect(Unit) {
        onDispose {
            saveProgress()
            engine.release()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_READY) {
                    retries = 0
                    reconnecting = false
                    error = null
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onTracksChanged(t: Tracks) {
                tracks = t
            }

            override fun onPlayerError(e: PlaybackException) {
                Log.e(CrashReport.TAG, "Erreur de lecture : ${PlayerEngine.technical(e)}", e)
                lastError = e
                errorTick++
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasStopped = true
                    saveProgress()
                    player.pause()
                }
                Lifecycle.Event.ON_START -> if (wasStopped) {
                    wasStopped = false
                    if (live != null) {
                        player.seekToDefaultPosition()
                        engine.retry()
                    } else {
                        controls = true
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---------------- Chargement des médias ----------------

    if (live != null) {
        LaunchedEffect(liveIndex) {
            val ch = curChannel() ?: return@LaunchedEffect
            error = null
            retries = 0
            reconnecting = false
            engine.playLive(repo.liveUrl(ch.id), prefs.liveFormat == "m3u8")
            repo.touchChannel(ch.id)
            banner = true
        }
    } else if (episodes != null) {
        LaunchedEffect(epIndex) {
            val ep = curEpisode() ?: return@LaunchedEffect
            val start = if (firstEpisode) episodes.startMs else 0L
            firstEpisode = false
            error = null
            retries = 0
            nextPrompt = false
            nextDismissed = false
            engine.playVod(repo.episodeUrl(ep.id, ep.ext), start)
        }
    } else if (movie != null) {
        LaunchedEffect(Unit) {
            engine.playVod(repo.movieUrl(movie.tile.id, movie.tile.ext), movie.startMs)
        }
    }

    // Erreurs : reconnexion automatique avec attente progressive, puis message clair.
    LaunchedEffect(errorTick) {
        val e = lastError ?: return@LaunchedEffect
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            engine.retry()
            return@LaunchedEffect
        }
        val maxRetries = if (live != null) 5 else 2
        if (retries < maxRetries) {
            retries++
            reconnecting = true
            delay((1000L shl (retries - 1)).coerceAtMost(8_000L))
            engine.retry()
        } else {
            reconnecting = false
            errorDetail = PlayerEngine.technical(e)
            error = PlayerEngine.describe(e)
        }
    }

    // Démarrage lent : on prévient au lieu de laisser tourner le cercle sans explication.
    LaunchedEffect(playbackState, reconnecting) {
        slowStart = false
        if (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE) {
            delay(12_000)
            slowStart = true
        }
    }

    // Fin de lecture.
    LaunchedEffect(playbackState) {
        if (playbackState != Player.STATE_ENDED) return@LaunchedEffect
        when {
            live != null -> {
                // Le serveur a fermé le flux : on se reconnecte.
                if (retries < 5) {
                    retries++
                    reconnecting = true
                    delay(800)
                    player.seekToDefaultPosition()
                    engine.retry()
                } else {
                    error = "Le flux de cette chaîne s'est interrompu."
                }
            }
            movie != null -> {
                saveProgress(finished = true)
                app.back()
            }
            episodes != null -> {
                saveProgress(finished = true)
                when {
                    nextEpisode() == null -> app.back()
                    prefs.autoNextEpisode -> goNext()
                    else -> nextPrompt = true
                }
            }
        }
    }

    // Position, sauvegarde régulière et proposition d'épisode suivant.
    LaunchedEffect(Unit) {
        var lastSave = SystemClock.elapsedRealtime()
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            buffered = player.bufferedPosition
            val now = SystemClock.elapsedRealtime()
            if (isVod && isPlaying && now - lastSave > 15_000) {
                saveProgress()
                lastSave = now
            }
            if (episodes != null && nextEpisode() != null && !nextDismissed && !nextPrompt &&
                duration > 300_000 && duration - position in 1..25_000
            ) {
                nextPrompt = true
            }
            delay(500)
        }
    }

    LaunchedEffect(seekTick) {
        val target = pendingSeek ?: return@LaunchedEffect
        delay(450)
        player.seekTo(target)
        position = target
        pendingSeek = null
    }

    LaunchedEffect(controls, interaction, panel, isPlaying, pendingSeek) {
        if (controls && panel == Panel.NONE && isPlaying && pendingSeek == null) {
            delay(5_000)
            controls = false
        }
    }

    LaunchedEffect(banner, liveIndex) {
        if (banner) {
            delay(4_500)
            banner = false
        }
    }

    LaunchedEffect(resizeLabel) {
        if (resizeLabel != null) {
            delay(1_600)
            resizeLabel = null
        }
    }

    LaunchedEffect(numberInput) {
        if (numberInput.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val num = numberInput.toIntOrNull()
        numberInput = ""
        val idx = live?.channels?.indexOfFirst { it.num == num } ?: -1
        if (idx >= 0) {
            liveIndex = idx
        } else {
            Toast.makeText(context, "Chaîne $num absente de cette liste", Toast.LENGTH_SHORT).show()
        }
    }

    // Focus : la racine reçoit les touches quand rien n'est affiché.
    LaunchedEffect(controls, panel, nextPrompt, error) {
        when {
            error != null -> errorFr.safeRequest(60)
            panel != Panel.NONE -> Unit
            nextPrompt -> nextFr.safeRequest(60)
            controls -> (if (isVod) seekFr else firstButtonFr).safeRequest(60)
            else -> rootFr.safeRequest(30)
        }
    }

    BackHandler {
        when {
            numberInput.isNotEmpty() -> numberInput = ""
            panel != Panel.NONE -> panel = Panel.NONE
            nextPrompt -> {
                nextPrompt = false
                nextDismissed = true
            }
            error != null -> app.back()
            controls -> controls = false
            else -> {
                saveProgress()
                app.back()
            }
        }
    }

    fun handleKey(ev: KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        val code = ev.nativeKeyEvent.keyCode
        val repeat = ev.nativeKeyEvent.repeatCount
        interaction++
        when (code) {
            AKey.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
            AKey.KEYCODE_MEDIA_PLAY -> { player.play(); return true }
            AKey.KEYCODE_MEDIA_PAUSE -> { player.pause(); controls = true; return true }
            AKey.KEYCODE_MEDIA_FAST_FORWARD -> { if (isVod) { seekBy(30_000); controls = true }; return true }
            AKey.KEYCODE_MEDIA_REWIND -> { if (isVod) { seekBy(-30_000); controls = true }; return true }
            AKey.KEYCODE_CHANNEL_UP, AKey.KEYCODE_MEDIA_NEXT -> {
                if (live != null) zap(1) else if (nextEpisode() != null) goNext()
                return true
            }
            AKey.KEYCODE_CHANNEL_DOWN, AKey.KEYCODE_MEDIA_PREVIOUS -> {
                if (live != null) zap(-1)
                return true
            }
        }
        if (live != null && panel == Panel.NONE && code in AKey.KEYCODE_0..AKey.KEYCODE_9) {
            numberInput = (numberInput + (code - AKey.KEYCODE_0)).takeLast(4)
            return true
        }
        if (error != null || panel != Panel.NONE || nextPrompt || controls) return false
        return when (code) {
            AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_NUMPAD_ENTER -> {
                if (isVod) togglePlay()
                controls = true
                true
            }
            AKey.KEYCODE_DPAD_LEFT -> {
                if (isVod) {
                    seekBy(-seekStep(repeat)); controls = true
                } else {
                    panel = Panel.CHANNELS
                }
                true
            }
            AKey.KEYCODE_DPAD_RIGHT -> {
                if (isVod) {
                    seekBy(seekStep(repeat)); controls = true
                } else {
                    controls = true
                }
                true
            }
            AKey.KEYCODE_DPAD_UP -> { if (live != null) zap(1) else controls = true; true }
            AKey.KEYCODE_DPAD_DOWN -> { if (live != null) zap(-1) else controls = true; true }
            AKey.KEYCODE_INFO, AKey.KEYCODE_MENU, AKey.KEYCODE_GUIDE -> { controls = true; true }
            else -> false
        }
    }

    // ---------------- Affichage ----------------

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFr)
            .onPreviewKeyEvent { handleKey(it) }
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    subtitleView?.apply {
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                android.graphics.Color.BLACK,
                                null,
                            )
                        )
                        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f)
                    }
                    this.player = engine.player
                }
            },
            update = { view ->
                view.resizeMode = when (resize) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val showSpinner = error == null && (reconnecting || playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE)
        if (showSpinner) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner(size = 52.dp)
                if (reconnecting) {
                    Spacer(Modifier.height(14.dp))
                    Text("Reconnexion… ($retries)", style = T.label, color = Color.White)
                } else if (slowStart) {
                    Spacer(Modifier.height(14.dp))
                    Text("Le serveur met du temps à répondre…", style = T.label, color = Color.White)
                    Text("Retour pour annuler", style = T.meta, color = Color(0x99FFFFFF))
                }
            }
        }

        val channel = curChannel()
        val episode = curEpisode()

        AnimatedVisibility(visible = controls, enter = fadeIn(tween(180)), exit = fadeOut(tween(220))) {
            if (live != null && channel != null) {
                LiveOverlay(
                    app = app, channel = channel, engine = engine, tracks = tracks,
                    firstButton = firstButtonFr,
                    onChannels = { panel = Panel.CHANNELS },
                    onAudio = { panel = Panel.AUDIO },
                    onSubs = { panel = Panel.SUBS },
                    onResize = { cycleResize() },
                )
            } else {
                val title = movie?.tile?.title ?: episodes?.series?.title ?: ""
                val subtitle = episode?.let { "${it.label} · ${it.title}" } ?: movie?.tile?.year
                VodOverlay(
                    title = title,
                    subtitle = subtitle,
                    position = pendingSeek ?: position,
                    duration = duration,
                    buffered = buffered,
                    isPlaying = isPlaying,
                    seeking = pendingSeek != null,
                    seekFr = seekFr,
                    hasNext = nextEpisode() != null,
                    onSeek = { delta, repeat -> seekBy(if (delta > 0) seekStep(repeat) else -seekStep(repeat)) },
                    onToggle = { togglePlay() },
                    onAudio = { panel = Panel.AUDIO },
                    onSubs = { panel = Panel.SUBS },
                    onResize = { cycleResize() },
                    onNext = { goNext() },
                )
            }
        }

        AnimatedVisibility(
            visible = banner && !controls && panel == Panel.NONE && channel != null,
            enter = fadeIn(tween(150)), exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (channel != null) {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))) {
                    LiveInfoCard(app, channel, Modifier.padding(horizontal = Safe.h, vertical = Safe.v).padding(top = 40.dp))
                }
            }
        }

        if (numberInput.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(Safe.h).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xCC000000)).padding(horizontal = 22.dp, vertical = 10.dp)
            ) {
                Text(numberInput, style = T.hero, color = Color.White)
            }
        }

        resizeLabel?.let {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = Safe.v * 2).clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC000000)).padding(horizontal = 18.dp, vertical = 8.dp)
            ) { Text(it, style = T.label, color = Color.White) }
        }

        when (panel) {
            Panel.AUDIO -> TrackPanel(
                title = "Langue audio",
                options = engine.audioOptions(tracks),
                empty = "Une seule piste audio est disponible.",
                onSelect = { engine.select(C.TRACK_TYPE_AUDIO, it); panel = Panel.NONE },
            )
            Panel.SUBS -> TrackPanel(
                title = "Sous-titres",
                options = engine.textOptions(tracks),
                empty = "Aucun sous-titre dans ce flux.",
                onSelect = { engine.select(C.TRACK_TYPE_TEXT, it); panel = Panel.NONE },
            )
            Panel.CHANNELS -> if (live != null) {
                ChannelPanel(app, live.channels, liveIndex) { idx ->
                    panel = Panel.NONE
                    if (idx != liveIndex) liveIndex = idx else banner = true
                }
            }
            Panel.NONE -> Unit
        }

        val next = nextEpisode()
        if (nextPrompt && next != null && episodes != null) {
            NextEpisodeCard(
                episode = next,
                fallbackImage = episodes.series.backdrop ?: episodes.series.image,
                auto = prefs.autoNextEpisode,
                focus = nextFr,
                onPlay = { goNext() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = Safe.h, bottom = Safe.v * 2),
            )
        }

        error?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color(0xD9000000)), contentAlignment = Alignment.Center) {
                Column(Modifier.width(520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.ErrorOutline, null, tint = Pal.Live, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(
                        channel?.name ?: movie?.tile?.title ?: episodes?.series?.title ?: "",
                        style = T.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(msg, style = T.body, color = Pal.Text2)
                    errorDetail?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = T.small, color = Pal.Text3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton(
                            "Réessayer", icon = Icons.Filled.Refresh, primary = true,
                            modifier = Modifier.focusRequester(errorFr),
                            onClick = {
                                error = null
                                retries = 0
                                if (live != null) player.seekToDefaultPosition()
                                engine.retry()
                            },
                        )
                        if (live != null) PillButton("Chaîne suivante", icon = Icons.Filled.SkipNext, onClick = { error = null; zap(1) })
                        PillButton("Retour", onClick = { app.back() })
                    }
                }
            }
        }
    }
}

// ---------------- Barre de progression ----------------

@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    buffered: Long,
    focusRequester: FocusRequester,
    onSeek: (Int, Int) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    AKey.KEYCODE_DPAD_LEFT -> { onSeek(-1, ev.nativeKeyEvent.repeatCount); true }
                    AKey.KEYCODE_DPAD_RIGHT -> { onSeek(1, ev.nativeKeyEvent.repeatCount); true }
                    AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_NUMPAD_ENTER -> { onToggle(); true }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        val played = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val buf = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val barH = if (focused) 6.dp else 4.dp
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val h = barH.toPx()
            val y = (size.height - h) / 2
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(Color(0x40FFFFFF), Offset(0f, y), Size(size.width, h), r)
            drawRoundRect(Color(0x66FFFFFF), Offset(0f, y), Size(size.width * buf, h), r)
            drawRoundRect(Pal.Gold, Offset(0f, y), Size(size.width * played, h), r)
            if (focused) {
                drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(size.width * played, size.height / 2))
            }
        }
    }
}

// ---------------- Surcouches ----------------

@Composable
private fun VodOverlay(
    title: String,
    subtitle: String?,
    position: Long,
    duration: Long,
    buffered: Long,
    isPlaying: Boolean,
    seeking: Boolean,
    seekFr: FocusRequester,
    hasNext: Boolean,
    onSeek: (Int, Int) -> Unit,
    onToggle: () -> Unit,
    onAudio: () -> Unit,
    onSubs: () -> Unit,
    onResize: () -> Unit,
    onNext: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .padding(start = Safe.h, end = Safe.h, top = Safe.v, bottom = 48.dp)
        ) {
            Text(title, style = T.display, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = T.bodyStrong, color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(start = Safe.h, end = Safe.h, bottom = Safe.v, top = 70.dp)
        ) {
            SeekBar(position, duration, buffered, seekFr, onSeek, onToggle)
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(Txt.clock(position), style = T.meta, color = if (seeking) Pal.Gold else Color.White)
                Spacer(Modifier.weight(1f))
                Text(if (duration > 0) "-" + Txt.clock(duration - position) else "", style = T.meta, color = Color(0xB3FFFFFF))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, onClick = onToggle)
                PillButton("Audio", icon = Icons.Filled.Audiotrack, onClick = onAudio)
                PillButton("Sous-titres", icon = Icons.Filled.Subtitles, onClick = onSubs)
                PillButton("Format", icon = Icons.Filled.AspectRatio, onClick = onResize)
                if (hasNext) PillButton("Épisode suivant", icon = Icons.Filled.SkipNext, onClick = onNext)
            }
        }
    }
}

@Composable
private fun LiveOverlay(
    app: AppController,
    channel: Channel,
    engine: PlayerEngine,
    tracks: Tracks,
    firstButton: FocusRequester,
    onChannels: () -> Unit,
    onAudio: () -> Unit,
    onSubs: () -> Unit,
    onResize: () -> Unit,
) {
    val context = LocalContext.current
    val favorites by app.repo.favorites.collectAsState()
    val fav = "live:${channel.id}" in favorites
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEB000000))))
                .padding(start = Safe.h, end = Safe.h, bottom = Safe.v, top = 80.dp)
        ) {
            LiveInfoCard(app, channel, Modifier)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Chaînes", icon = Icons.Filled.FormatListBulleted, onClick = onChannels, modifier = Modifier.focusRequester(firstButton))
                if (engine.audioOptions(tracks).size > 1) PillButton("Audio", icon = Icons.Filled.Audiotrack, onClick = onAudio)
                if (engine.textOptions(tracks).size > 1) PillButton("Sous-titres", icon = Icons.Filled.Subtitles, onClick = onSubs)
                PillButton("Format", icon = Icons.Filled.AspectRatio, onClick = onResize)
                RoundIconButton(
                    if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    active = fav,
                    onClick = {
                        val added = app.repo.toggleFavorite(ContentType.LIVE, channel.id)
                        Toast.makeText(context, if (added) "Ajoutée à Mes chaînes" else "Retirée de Mes chaînes", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}

@Composable
private fun LiveInfoCard(app: AppController, channel: Channel, modifier: Modifier) {
    val epg by produceState<List<EpgEntry>?>(app.repo.cachedEpg(channel.id), channel.id) {
        value = app.repo.epg(channel.id)
    }
    val now = System.currentTimeMillis()
    val current = epg?.firstOrNull { it.start <= now && it.end > now }
    val next = epg?.firstOrNull { it.start >= (current?.end ?: now) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width = 120.dp, height = 72.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x26FFFFFF)).padding(10.dp)
        ) { ChannelLogo(channel, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (channel.num > 0) {
                    Text("${channel.num}", style = T.title, color = Pal.Gold)
                    Spacer(Modifier.width(12.dp))
                }
                Text(channel.name, style = T.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (current != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${Txt.hour(current.start)} – ${Txt.hour(current.end)}", style = T.meta, color = Color(0xB3FFFFFF))
                    Spacer(Modifier.width(12.dp))
                    Text(current.title, style = T.bodyStrong, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                ProgressLine(current.progress(now), Modifier.width(360.dp))
            }
            if (next != null) {
                Spacer(Modifier.height(8.dp))
                Text("À suivre · ${Txt.hour(next.start)}  ${next.title}", style = T.meta, color = Color(0x99FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TrackPanel(
    title: String,
    options: List<PlayerEngine.TrackOption>,
    empty: String,
    onSelect: (PlayerEngine.TrackOption) -> Unit,
) {
    val firstFr = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000)))) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(380.dp)
                .background(Pal.Surface1.copy(alpha = 0.97f))
                .padding(horizontal = 24.dp, vertical = Safe.v + 10.dp)
        ) {
            Text(title, style = T.title, color = Color.White)
            Spacer(Modifier.height(16.dp))
            val usable = options.filter { it.group != null }
            if (usable.isEmpty() && options.none { it.group == null }) {
                Text(empty, style = T.body, color = Pal.Text2)
            }
            val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(options) { i, opt ->
                    Clickable(
                        onClick = { onSelect(opt) },
                        modifier = (if (i == selectedIndex) Modifier.focusRequester(firstFr) else Modifier).fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        container = if (opt.selected) Pal.GoldSoft else Color.Transparent,
                        focusedContainer = Color.White,
                        contentColor = Color.White,
                        focusedContentColor = Pal.Bg,
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(opt.label, style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                opt.detail?.let {
                                    Text(it, style = T.small, color = LocalContentColor.current.copy(alpha = 0.65f), maxLines = 1)
                                }
                            }
                            if (opt.selected) Icon(Icons.Filled.Check, null, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            if (usable.isEmpty() && options.size <= 1 && options.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(empty, style = T.meta, color = Pal.Text3)
            }
        }
    }
    LaunchedEffect(Unit) { firstFr.safeRequest(60) }
}

@Composable
private fun ChannelPanel(app: AppController, channels: List<Channel>, current: Int, onPick: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
    val currentFr = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xEB000000), 0.55f to Color(0x99000000), 1f to Color.Transparent))) {
        Column(Modifier.fillMaxHeight().width(460.dp).padding(start = Safe.h, top = Safe.v, bottom = Safe.v)) {
            Text("Chaînes", style = T.title, color = Color.White)
            Spacer(Modifier.height(12.dp))
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberCenterPivot()) {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
                        PanelChannelRow(
                            app, ch, i == current,
                            modifier = if (i == current) Modifier.focusRequester(currentFr) else Modifier,
                            onClick = { onPick(i) },
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { currentFr.safeRequest(80) }
}

@Composable
private fun PanelChannelRow(app: AppController, channel: Channel, isCurrent: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val epg by produceState<List<EpgEntry>?>(app.repo.cachedEpg(channel.id), channel.id) {
        if (value == null) {
            delay(200)
            value = app.repo.epg(channel.id)
        }
    }
    val now = System.currentTimeMillis()
    val currentProgram = epg?.firstOrNull { it.start <= now && it.end > now }
    Clickable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(12.dp),
        container = if (isCurrent) Pal.GoldSoft else Color.Transparent,
        focusedContainer = Color.White,
        contentColor = Color.White,
        focusedContentColor = Pal.Bg,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (channel.num > 0) "${channel.num}" else "", style = T.meta, color = LocalContentColor.current.copy(alpha = 0.55f), modifier = Modifier.width(40.dp))
            Box(Modifier.size(width = 56.dp, height = 36.dp)) { ChannelLogo(channel, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                currentProgram?.let {
                    Text(it.title, style = T.small, color = LocalContentColor.current.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun NextEpisodeCard(
    episode: Episode,
    fallbackImage: String?,
    auto: Boolean,
    focus: FocusRequester,
    onPlay: () -> Unit,
    modifier: Modifier,
) {
    var countdown by remember { mutableIntStateOf(10) }
    if (auto) {
        LaunchedEffect(episode.id) {
            for (i in 10 downTo 1) {
                countdown = i
                delay(1_000)
            }
            onPlay()
        }
    }
    Column(
        modifier.width(360.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xF014141A)).padding(16.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Pal.Surface2)) {
            val img = episode.image ?: fallbackImage
            if (img != null) AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(12.dp))
        Text("ÉPISODE SUIVANT", style = T.overline, color = Pal.Gold)
        Spacer(Modifier.height(4.dp))
        Text("${episode.label} · ${episode.title}", style = T.label, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        PillButton(
            if (auto) "Lecture dans $countdown s" else "Lire l'épisode suivant",
            icon = Icons.Filled.PlayArrow, primary = true, onClick = onPlay,
            modifier = Modifier.focusRequester(focus),
        )
    }
}
