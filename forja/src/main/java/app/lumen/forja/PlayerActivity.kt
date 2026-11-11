package app.lumen.forja

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C as MediaC
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ima.ImaAdsLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.lumen.forja.data.ForjaApi
import app.lumen.forja.data.Playable
import app.lumen.forja.data.Progress
import app.lumen.forja.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Lecteur natif (ExoPlayer) : vidéo HLS fournie par Forja, avec la publicité d'avant-vidéo
 * de Forja comme sur le site. Si une vidéo ne peut pas être lue ici, on bascule
 * automatiquement sur le lecteur officiel (WebPlayerActivity).
 *
 * Télécommande : OK pause / lecture ; ← → reculer / avancer de 15 s (maintenir pour aller vite) ;
 * ↑ ↓ afficher les infos ; Retour revenir à la fiche ; touches média prises en charge.
 */
class PlayerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dp by lazy { resources.displayMetrics.density }

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var splash: LinearLayout
    private lateinit var splashTitle: TextView
    private lateinit var splashSub: TextView
    private lateinit var osdView: TextView
    private lateinit var infoBar: LinearLayout
    private lateinit var infoTitle: TextView
    private lateinit var infoSub: TextView
    private lateinit var infoProgress: ProgressBar
    private lateinit var infoTime: TextView

    private var player: ExoPlayer? = null
    private var adsLoader: ImaAdsLoader? = null
    private var loadJob: Job? = null

    private var queue: List<Playable> = emptyList()
    private var index = 0
    private var showSlug: String? = null
    private var showTitle = ""
    private var showImage: String? = null
    private var adTag: String? = null

    private var contentStarted = false
    private var retried = false
    private var lastSaved = 0L
    private var countdown = 0
    private var seekTargetMs = -1L
    private var finishing = false

    private val hideOsdTask = Runnable { osdView.animate().alpha(0f).setDuration(250).start() }
    private val hideInfoTask = Runnable { if (player?.isPlaying == true) infoBar.animate().alpha(0f).setDuration(300).start() }
    private val applySeekTask = Runnable {
        val p = player
        if (p != null && seekTargetMs >= 0) p.seekTo(seekTargetMs)
        seekTargetMs = -1L
    }
    private val tickTask = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000L)
        }
    }
    private val countdownTask = object : Runnable {
        override fun run() {
            if (countdown <= 0) return
            countdown--
            if (countdown == 0) {
                playIndex(index + 1, 0L)
            } else {
                showNextCountdown()
                handler.postDelayed(this, 1000L)
            }
        }
    }

    // ------------------------------------------------------------------ cycle de vie

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = Store.queueFromJson(intent.getStringExtra(EXTRA_QUEUE))
        if (queue.isEmpty()) {
            finish()
            return
        }
        index = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0)).coerceIn(0, queue.lastIndex)
        showSlug = intent.getStringExtra(EXTRA_SHOW_SLUG)
        showTitle = intent.getStringExtra(EXTRA_SHOW_TITLE) ?: queue[index].title
        showImage = intent.getStringExtra(EXTRA_SHOW_IMAGE)
        adTag = intent.getStringExtra(EXTRA_AD_TAG)
        val start = savedInstanceState?.getLong(EXTRA_START) ?: intent.getLongExtra(EXTRA_START, 0L)
        buildUi()
        createPlayer()
        playIndex(index, start)
        handler.post(tickTask)
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        saveProgress(force = true)
        player?.playWhenReady = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
        outState.putLong(EXTRA_START, (player?.takeIf { !it.isPlayingAd }?.currentPosition ?: 0L) / 1000L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        releasePlayer()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ interface

    private fun px(v: Float) = (v * dp).toInt()

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(false)
        }
        root.addView(playerView, match())

        splashTitle = TextView(this).apply {
            setTextColor(CREAM)
            textSize = 34f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        splashSub = TextView(this).apply {
            setTextColor(MUTED)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, px(6f), 0, px(26f))
        }
        splash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(splashTitle)
            addView(splashSub)
            addView(ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(GOLD)
            }, LinearLayout.LayoutParams(px(34f), px(34f)))
        }
        root.addView(splash, match())

        infoTitle = TextView(this).apply {
            setTextColor(CREAM)
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = 1
        }
        infoSub = TextView(this).apply {
            setTextColor(MUTED)
            textSize = 15f
            maxLines = 1
            setPadding(0, px(2f), 0, px(12f))
        }
        infoProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(GOLD)
            secondaryProgressTintList = ColorStateList.valueOf(0x88F3E7D3.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
        }
        infoTime = TextView(this).apply {
            setTextColor(CREAM)
            textSize = 15f
            setPadding(0, px(8f), 0, 0)
        }
        infoBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(48f), px(70f), px(48f), px(30f))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0xCC000000.toInt(), 0xF0000000.toInt()),
            )
            alpha = 0f
            addView(infoTitle)
            addView(infoSub)
            addView(infoProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(5f)))
            addView(infoTime)
        }
        root.addView(infoBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ))

        osdView = TextView(this).apply {
            setTextColor(CREAM)
            textSize = 18f
            alpha = 0f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(0xE01A0B12.toInt())
                setStroke(px(1f), 0x55C9A45C)
            }
            setPadding(px(22f), px(11f), px(22f), px(11f))
        }
        root.addView(osdView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = px(34f) })

        setContentView(root)
    }

    // ------------------------------------------------------------------ lecteur

    private fun createPlayer() {
        val http = (application as ForjaApp).http
        val ads = ImaAdsLoader.Builder(this).build()
        val sources = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(OkHttpDataSource.Factory(http))
            .setLocalAdInsertionComponents({ ads }, playerView)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sources)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(15_000)
            .build()
        ads.setPlayer(p)
        playerView.player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !p.isPlayingAd) onEnded()
                updateInfo()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateKeepScreenOn()
                if (isPlaying && !p.isPlayingAd) {
                    hideSplash()
                    if (infoBar.alpha > 0f) {
                        handler.removeCallbacks(hideInfoTask)
                        handler.postDelayed(hideInfoTask, 2500L)
                    }
                }
                updateInfo()
            }

            override fun onRenderedFirstFrame() {
                hideSplash()
                if (!p.isPlayingAd) contentStarted = true
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // Fin de la publicité : retour à la vidéo.
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && !p.isPlayingAd) updateInfo()
            }

            override fun onPlayerError(error: PlaybackException) {
                onError(error)
            }
        })
        player = p
        adsLoader = ads
    }

    private fun releasePlayer() {
        adsLoader?.setPlayer(null)
        player?.release()
        adsLoader?.release()
        player = null
        adsLoader = null
    }

    private fun current(): Playable = queue[index]

    private fun playIndex(i: Int, startSecs: Long) {
        if (i !in queue.indices) {
            finish()
            return
        }
        saveProgress(force = true)
        handler.removeCallbacks(countdownTask)
        countdown = 0
        index = i
        contentStarted = false
        retried = false
        val item = current()
        showSplash(item, startSecs)
        loadJob?.cancel()
        loadJob = scope.launch {
            val url = safely("adresse de la vidéo") { ForjaApi.streamUrl(item.id) }
            if (url == null) {
                openOfficialPlayer(startSecs, "Vidéo indisponible ici : ouverture du lecteur Forja")
                return@launch
            }
            val builder = MediaItem.Builder()
                .setUri(url)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
            adTag?.let { tag ->
                // Publicité d'avant-vidéo uniquement au début d'une vidéo, comme sur le site.
                if (startSecs < 30) {
                    val fresh = tag.replace(Regex("correlator=\\d+"), "correlator=${System.currentTimeMillis()}")
                    builder.setAdsConfiguration(MediaItem.AdsConfiguration.Builder(Uri.parse(fresh)).build())
                }
            }
            val p = player ?: return@launch
            p.setMediaItem(builder.build(), startSecs * 1000L)
            p.prepare()
            p.playWhenReady = true
        }
    }

    /** Bascule sur le lecteur officiel de Forja pour la vidéo en cours (et la suite de la file). */
    private fun openOfficialPlayer(startSecs: Long, message: String, delayMs: Long = 900L) {
        if (finishing) return
        finishing = true
        showOsd(message, delayMs + 1500L)
        handler.postDelayed({
            WebPlayerActivity.start(this, queue, index, startSecs, showSlug, showTitle, showImage)
            finish()
        }, delayMs)
    }

    /** Explication lisible de l'échec, pour savoir s'il vient de Forja ou de la TV. */
    private fun describe(error: PlaybackException): String {
        var cause: Throwable? = error
        var http: Int? = null
        while (cause != null && http == null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) http = cause.responseCode
            cause = cause.cause
        }
        return when {
            http == 401 || http == 403 -> "Forja refuse la lecture en dehors de son site (erreur $http)"
            http != null -> "Le serveur vidéo de Forja répond « erreur $http »"
            else -> "Lecture native impossible (${error.errorCodeName})"
        }
    }

    private fun onError(error: PlaybackException) {
        val p = player ?: return
        val position = if (p.isPlayingAd) 0L else p.currentPosition / 1000L
        android.util.Log.e(CrashReport.TAG, "Lecture native impossible", error)
        if (!contentStarted) {
            openOfficialPlayer(position, describe(error) + "\nOuverture du lecteur Forja…", 5000L)
            return
        }
        if (!retried) {
            // Coupure réseau en cours de route : une nouvelle tentative à la même position.
            retried = true
            showOsd("Connexion perdue, reprise…", 2000L)
            p.prepare()
            p.playWhenReady = true
        } else {
            openOfficialPlayer(position, describe(error) + "\nOuverture du lecteur Forja…", 4000L)
        }
    }

    private fun onEnded() {
        val p = player ?: return
        saveProgress(force = true, finished = true)
        if (countdown > 0) return
        if (index + 1 < queue.size) {
            countdown = 6
            handler.removeCallbacks(countdownTask)
            handler.post(countdownTask)
        } else {
            p.playWhenReady = false
            showOsd(if (showSlug != null) "Fin de la série" else "Fin du film", 2500L)
            handler.postDelayed({ finish() }, 2600L)
        }
    }

    private fun tick() {
        val p = player ?: return
        updateKeepScreenOn()
        if (infoBar.alpha > 0f) updateInfo()
        saveProgress(force = false)
        if (p.isPlayingAd) hideSplash()
    }

    private fun saveProgress(force: Boolean, finished: Boolean = false) {
        val p = player ?: return
        if (queue.isEmpty() || (p.isPlayingAd && !finished)) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastSaved < 5000L) return
        val durMs = p.duration.takeIf { it != MediaC.TIME_UNSET && it > 0 } ?: (current().durationSecs * 1000L)
        val d = durMs / 1000L
        val t = if (finished) d else p.currentPosition / 1000L
        if (d <= 0 || t < 5 || !contentStarted && !finished) return
        lastSaved = now
        val item = current()
        Store.saveProgress(
            Progress(
                id = item.id,
                slug = item.slug,
                showSlug = showSlug,
                showTitle = showTitle,
                episodeTitle = if (showSlug != null) item.title else null,
                image = item.image ?: showImage,
                position = t,
                duration = d,
                updated = System.currentTimeMillis(),
                next = if (showSlug != null) queue.getOrNull(index + 1) else null,
            )
        )
    }

    private fun updateKeepScreenOn() {
        val p = player
        if (p != null && (p.isPlaying || p.isPlayingAd || p.playbackState == Player.STATE_BUFFERING)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ------------------------------------------------------------------ affichage

    private fun showSplash(item: Playable, startSecs: Long) {
        splash.animate().cancel()
        splash.alpha = 1f
        splash.visibility = View.VISIBLE
        splashTitle.text = if (showSlug != null) showTitle else item.title
        val resume = if (startSecs > 30) "reprise à ${clock(startSecs * 1000L)}" else null
        splashSub.text = listOfNotNull(if (showSlug != null) item.title else null, resume).joinToString(" · ")
    }

    private fun hideSplash() {
        if (splash.visibility != View.VISIBLE || splash.alpha < 1f) return
        splash.animate().alpha(0f).setDuration(300).withEndAction { splash.visibility = View.GONE }.start()
        if (!hintShown) {
            hintShown = true
            handler.postDelayed({ showOsd("OK : pause   ·   ← → : 15 s   ·   ↑ : infos   ·   Retour : quitter", 3500L) }, 600L)
        }
    }

    private fun updateInfo() {
        val p = player ?: return
        val item = current()
        infoTitle.text = if (showSlug != null) showTitle else item.title
        val paused = !p.isPlaying && p.playbackState == Player.STATE_READY
        infoSub.text = when {
            p.isPlayingAd -> "Publicité Forja"
            showSlug != null -> item.title + if (paused) " · en pause" else ""
            paused -> "En pause"
            else -> ""
        }
        if (p.isPlayingAd) {
            infoProgress.progress = 0
            infoProgress.secondaryProgress = 0
            infoTime.text = "La vidéo démarre juste après"
            return
        }
        val dur = p.duration.takeIf { it != MediaC.TIME_UNSET && it > 0 } ?: (item.durationSecs * 1000L)
        val pos = if (seekTargetMs >= 0) seekTargetMs else p.currentPosition
        infoProgress.progress = if (dur > 0) ((pos * 1000) / dur).toInt().coerceIn(0, 1000) else 0
        infoProgress.secondaryProgress = if (dur > 0) ((p.bufferedPosition * 1000) / dur).toInt().coerceIn(0, 1000) else 0
        infoTime.text = if (dur > 0) "${clock(pos)} / ${clock(dur)}  ·  ${clock(dur - pos)} restantes" else clock(pos)
    }

    private fun showInfo(stay: Boolean = false) {
        updateInfo()
        infoBar.animate().cancel()
        infoBar.alpha = 1f
        handler.removeCallbacks(hideInfoTask)
        if (!stay) handler.postDelayed(hideInfoTask, 3500L)
    }

    private fun showOsd(text: String, durationMs: Long = 1800L) {
        osdView.text = text
        osdView.animate().cancel()
        osdView.alpha = 1f
        handler.removeCallbacks(hideOsdTask)
        handler.postDelayed(hideOsdTask, durationMs)
    }

    private fun showNextCountdown() {
        val next = queue.getOrNull(index + 1) ?: return
        showOsd("À suivre : ${next.title} dans $countdown s\nOK : lancer maintenant · Retour : quitter", 1500L)
    }

    private fun clock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    // ------------------------------------------------------------------ télécommande

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(event)
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0

        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) finish()
            return true
        }
        // Pendant la publicité, OK et les flèches vont au bouton « Ignorer » de la publicité.
        if (p.isPlayingAd) {
            if (first && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                showOsd("Publicité Forja : la vidéo démarre juste après", 2000L)
            }
            return super.dispatchKeyEvent(event)
        }

        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                if (event.action == KeyEvent.ACTION_UP) onOk()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) {
                    val sign = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    when {
                        event.repeatCount == 0 -> seekBy(15 * sign)
                        event.repeatCount % 2 == 0 -> seekBy((if (event.repeatCount > 20) 60 else 30) * sign)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> {
                if (first) {
                    if (infoBar.alpha >= 1f && p.isPlaying) infoBar.animate().alpha(0f).setDuration(200).start()
                    else showInfo(stay = !p.isPlaying)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (first) togglePlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (first) {
                    p.play()
                    showInfo()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (first) {
                    p.pause()
                    showInfo(stay = true)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down && event.repeatCount % 2 == 0) seekBy(60)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down && event.repeatCount % 2 == 0) seekBy(-60)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (first && index + 1 < queue.size) playIndex(index + 1, 0L)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (first && index > 0) playIndex(index - 1, 0L)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onOk() {
        if (countdown > 0) {
            countdown = 0
            handler.removeCallbacks(countdownTask)
            playIndex(index + 1, 0L)
            return
        }
        togglePlay()
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            showOsd("Pause", 1000L)
            showInfo(stay = true)
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
            showOsd("Lecture", 1000L)
            showInfo()
        }
    }

    /** Avance ou recule ; les appuis rapprochés s'additionnent et la vidéo saute une seule fois. */
    private fun seekBy(seconds: Int) {
        val p = player ?: return
        if (!contentStarted && p.playbackState != Player.STATE_READY) return
        val dur = p.duration.takeIf { it != MediaC.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        val base = if (seekTargetMs >= 0) seekTargetMs else p.currentPosition
        seekTargetMs = (base + seconds * 1000L).coerceIn(0L, (dur - 2000L).coerceAtLeast(0L))
        val total = ((seekTargetMs - p.currentPosition) / 1000L).toInt()
        showOsd(if (total >= 0) "+$total s" else "−${-total} s", 900L)
        showInfo()
        handler.removeCallbacks(applySeekTask)
        handler.postDelayed(applySeekTask, 450L)
    }

    companion object {
        private const val EXTRA_QUEUE = "queue"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_START = "start"
        private const val EXTRA_SHOW_SLUG = "show_slug"
        private const val EXTRA_SHOW_TITLE = "show_title"
        private const val EXTRA_SHOW_IMAGE = "show_image"
        private const val EXTRA_AD_TAG = "ad_tag"

        private val CREAM = 0xFFF3E7D3.toInt()
        private val MUTED = 0xFFB9A08F.toInt()
        private val GOLD = 0xFFC9A45C.toInt()

        /** Rappel des touches, affiché une fois par lancement de l'app. */
        private var hintShown = false

        fun start(
            context: Context,
            queue: List<Playable>,
            index: Int,
            startSecs: Long,
            showSlug: String?,
            showTitle: String,
            showImage: String?,
            adTag: String?,
        ) {
            val intent = Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_QUEUE, Store.queueToJson(queue))
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_START, startSecs)
                .putExtra(EXTRA_SHOW_SLUG, showSlug)
                .putExtra(EXTRA_SHOW_TITLE, showTitle)
                .putExtra(EXTRA_SHOW_IMAGE, showImage)
                .putExtra(EXTRA_AD_TAG, adTag)
            context.startActivity(intent)
        }
    }
}
