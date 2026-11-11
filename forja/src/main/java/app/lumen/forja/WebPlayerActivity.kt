package app.lumen.forja

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.lumen.forja.data.ForjaApi
import app.lumen.forja.data.Playable
import app.lumen.forja.data.Progress
import app.lumen.forja.data.Store
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * Lecteur de secours : la page du lecteur officiel de Forja en plein écran (publicités comprises),
 * utilisée quand une vidéo ne peut pas être lue par le lecteur natif.
 * pilotée à la télécommande. OK : pause / lecture ; ← → : reculer / avancer de 15 s ;
 * ↑ ↓ : pointeur pour cliquer dans le lecteur ; Retour : revenir à la fiche.
 * La position est mémorisée pour « Continuer à regarder » et l'épisode suivant s'enchaîne.
 */
class WebPlayerActivity : Activity(), CursorView.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private val dp by lazy { resources.displayMetrics.density }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var fullscreenBox: FrameLayout
    private lateinit var cursor: CursorView
    private lateinit var splash: LinearLayout
    private lateinit var splashTitle: TextView
    private lateinit var splashSub: TextView
    private lateinit var errorBox: TextView
    private lateinit var osdView: TextView
    private lateinit var infoBar: LinearLayout
    private lateinit var infoTitle: TextView
    private lateinit var infoSub: TextView
    private lateinit var infoBarProgress: ProgressBar
    private lateinit var infoTime: TextView

    private var queue: List<Playable> = emptyList()
    private var index = 0
    private var showSlug: String? = null
    private var showTitle = ""
    private var showImage: String? = null

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var webViewGone = false
    private var errorShown = false
    private var splashHidden = false

    // État rapporté par la page.
    private var position = 0.0
    private var duration = 0.0
    private var playing = false
    private var adActive = false
    private var lastSaved = 0L
    private var countdown = 0
    private var cursorMode = false

    private val hideOsdTask = Runnable { osdView.animate().alpha(0f).setDuration(250).start() }
    private val hideInfoTask = Runnable { if (playing || adActive) infoBar.animate().alpha(0f).setDuration(300).start() }
    private val cursorIdleTask = Runnable { leaveCursorMode() }
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
        val start = savedInstanceState?.getLong(EXTRA_START) ?: intent.getLongExtra(EXTRA_START, 0L)
        buildUi()
        setupWebView()
        playIndex(index, start)
    }

    override fun onResume() {
        super.onResume()
        if (!webViewGone) webView.onResume()
    }

    override fun onPause() {
        saveProgress(force = true)
        if (!webViewGone) {
            webView.evaluateJavascript(Scripts.PAUSE_ALL, null)
            webView.onPause()
        }
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
        outState.putLong(EXTRA_START, position.toLong())
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!webViewGone) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
            webViewGone = true
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ interface

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun px(v: Float) = (v * dp).toInt()

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        webView = WebView(this)
        root.addView(webView, match())

        fullscreenBox = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(fullscreenBox, match())

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

        // Bandeau d'informations en bas : titre, progression, temps.
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
        infoBarProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(GOLD)
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        }
        infoTime = TextView(this).apply {
            setTextColor(CREAM)
            textSize = 15f
            setPadding(0, px(8f), 0, 0)
        }
        infoBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(48f), px(60f), px(48f), px(30f))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0xCC000000.toInt(), 0xF0000000.toInt()),
            )
            alpha = 0f
            addView(infoTitle)
            addView(infoSub)
            addView(infoBarProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(5f)))
            addView(infoTime)
        }
        root.addView(infoBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ))

        errorBox = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(CREAM)
            textSize = 20f
            setLineSpacing(0f, 1.25f)
            setBackgroundColor(0xF21A0B12.toInt())
            visibility = View.GONE
        }
        root.addView(errorBox, match())

        cursor = CursorView(this).also {
            it.listener = this
            it.hide()
        }
        root.addView(cursor, match())

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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = desktopUserAgent()
        }
        webView.setBackgroundColor(Color.BLACK)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(Bridge(), "ForjaTV")
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.requestFocus()
    }

    /** Navigateur de bureau, avec la vraie version du moteur web de la TV. */
    private fun desktopUserAgent(): String {
        val def = try { WebSettings.getDefaultUserAgent(this) } catch (e: Exception) { "" }
        val chrome = Regex("Chrome/[\\d.]+").find(def)?.value ?: return ForjaApp.USER_AGENT
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    // ------------------------------------------------------------------ lecture

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
        position = startSecs.toDouble()
        duration = current().durationSecs.toDouble()
        playing = false
        adActive = false
        val p = current()
        splashHidden = false
        splash.animate().cancel()
        splash.alpha = 1f
        splash.visibility = View.VISIBLE
        splashTitle.text = if (showSlug != null) showTitle else p.title
        splashSub.text = if (showSlug != null) p.title else (if (startSecs > 30) "Reprise à ${clock(startSecs.toDouble())}" else "")
        if (showSlug != null && startSecs > 30) splashSub.text = "${p.title} · reprise à ${clock(startSecs.toDouble())}"
        hideError()
        webView.tag = startSecs
        webView.loadUrl(ForjaApi.playerUrl(p.slug))
    }

    private fun inject() {
        if (webViewGone) return
        val start = (webView.tag as? Long) ?: 0L
        webView.evaluateJavascript("if(typeof window.__ftvStart!=='number'||!window.__ftv){window.__ftvStart=$start;}", null)
        webView.evaluateJavascript(Scripts.PLAYER, null)
    }

    private fun hideSplash() {
        if (splashHidden) return
        splashHidden = true
        if (!hintShown) {
            hintShown = true
            handler.postDelayed({ showOsd("OK : pause   ·   ← → : 15 s   ·   ↑ : pointeur pour cliquer dans le lecteur", 4000L) }, 400L)
        }
        splash.animate().alpha(0f).setDuration(350).withEndAction { splash.visibility = View.GONE }.start()
    }

    private fun onState(t: Double, d: Double, isPlaying: Boolean, ad: Boolean) {
        val wasAd = adActive
        val wasPlaying = playing
        position = t
        if (d > 0) duration = d
        playing = isPlaying
        adActive = ad
        if (isPlaying || ad) hideSplash()
        if (ad && !wasAd) showOsd("Publicité Forja : la vidéo démarre juste après", 3000L)
        updateKeepScreenOn()
        updateInfo()
        if (!isPlaying && !ad && t > 1 && infoBar.alpha < 1f && countdown == 0) showInfo(stay = true)
        if (isPlaying && !wasPlaying && infoBar.alpha > 0f) {
            handler.removeCallbacks(hideInfoTask)
            handler.postDelayed(hideInfoTask, 2500L)
        }
        saveProgress(force = false)
    }

    private fun onEnded() {
        position = duration
        saveProgress(force = true, finished = true)
        if (index + 1 < queue.size) {
            countdown = 6
            handler.removeCallbacks(countdownTask)
            handler.post(countdownTask)
        } else {
            showOsd("Fin de la série", 2500L)
            handler.postDelayed({ finish() }, 2600L)
        }
    }

    private fun showNextCountdown() {
        val next = queue.getOrNull(index + 1) ?: return
        showOsd("À suivre : ${next.title} dans $countdown s\nOK : lancer maintenant · Retour : quitter", 1500L)
    }

    private fun saveProgress(force: Boolean, finished: Boolean = false) {
        if (queue.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastSaved < 5000L) return
        if (adActive && !finished) return
        val d = duration.toLong()
        val t = if (finished) d else position.toLong()
        if (d <= 0 || t < 5) return
        lastSaved = now
        val p = current()
        Store.saveProgress(
            Progress(
                id = p.id,
                slug = p.slug,
                showSlug = showSlug,
                showTitle = showTitle,
                episodeTitle = if (showSlug != null) p.title else null,
                image = p.image ?: showImage,
                position = t,
                duration = d,
                updated = System.currentTimeMillis(),
                next = if (showSlug != null) queue.getOrNull(index + 1) else null,
            )
        )
    }

    private fun updateKeepScreenOn() {
        if (playing || adActive || customView != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ------------------------------------------------------------------ bandeau et messages

    private fun updateInfo() {
        val p = current()
        infoTitle.text = if (showSlug != null) showTitle else p.title
        infoSub.text = when {
            adActive -> "Publicité"
            showSlug != null -> p.title + if (!playing) " · en pause" else ""
            !playing -> "En pause"
            else -> ""
        }
        val d = duration
        infoBarProgress.progress = if (d > 0) ((position / d) * 1000).toInt().coerceIn(0, 1000) else 0
        infoTime.text = if (d > 0) "${clock(position)} / ${clock(d)}  ·  ${clock(d - position)} restantes" else clock(position)
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

    private fun showError() {
        errorShown = true
        errorBox.text = "Impossible d'ouvrir le lecteur Forja.\nVérifiez la connexion Internet de la TV.\n\nOK : réessayer      Retour : revenir"
        errorBox.visibility = View.VISIBLE
        hideSplash()
    }

    private fun hideError() {
        if (!errorShown) return
        errorShown = false
        errorBox.visibility = View.GONE
    }

    private fun clock(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    // ------------------------------------------------------------------ télécommande

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (webViewGone) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onDirection(event)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                if (!down) onOk()
                else if (first && cursorMode) cursor.press()
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (first) command("toggle")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (first) command("play")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (first) command("pause")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down && event.repeatCount % 3 == 0) seek(60)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down && event.repeatCount % 3 == 0) seek(-60)
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
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> {
                if (first) showInfo()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onDirection(event: KeyEvent) {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (!down) {
            cursor.onDirectionKey(code, false)
            return
        }
        if (cursorMode) {
            if (event.repeatCount == 0) cursor.onDirectionKey(code, true)
            scheduleCursorIdle()
            return
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val sign = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                when {
                    event.repeatCount == 0 -> seek(15 * sign)
                    event.repeatCount % 3 == 0 -> seek((if (event.repeatCount > 15) 60 else 15) * sign)
                }
            }
            else -> if (event.repeatCount == 0) enterCursorMode()
        }
    }

    private fun onOk() {
        when {
            errorShown -> {
                hideError()
                playIndex(index, position.toLong())
            }
            countdown > 0 -> {
                countdown = 0
                handler.removeCallbacks(countdownTask)
                playIndex(index + 1, 0L)
            }
            cursorMode -> {
                tap(cursor.cursorX, cursor.cursorY)
                scheduleCursorIdle()
            }
            else -> command("toggle")
        }
    }

    private fun onBack() {
        when {
            countdown > 0 -> finish()
            cursorMode -> leaveCursorMode()
            customView != null -> leaveFullscreen(fromWeb = false)
            else -> finish()
        }
    }

    private fun enterCursorMode() {
        cursorMode = true
        cursor.show()
        showOsd("Pointeur : OK pour cliquer · Retour pour revenir à la vidéo", 2500L)
        scheduleCursorIdle()
    }

    private fun leaveCursorMode() {
        handler.removeCallbacks(cursorIdleTask)
        if (!cursorMode) return
        cursorMode = false
        cursor.hide()
        hover(MotionEvent.ACTION_HOVER_EXIT, cursor.cursorX, cursor.cursorY)
    }

    private fun scheduleCursorIdle() {
        handler.removeCallbacks(cursorIdleTask)
        handler.postDelayed(cursorIdleTask, 8000L)
    }

    private fun seek(seconds: Int) {
        val label = if (seconds > 0) "+$seconds s" else "−${-seconds} s"
        command("seek", seconds, label)
    }

    private fun command(cmd: String, arg: Int = 0, label: String? = null) {
        if (webViewGone) return
        webView.evaluateJavascript("window.__ftvCmd?window.__ftvCmd('$cmd',$arg):null") { raw ->
            val s = try { JSONTokener(raw ?: "null").nextValue() as? String } catch (e: Exception) { null }
            if (s == null) {
                showOsd("Le lecteur se charge…")
                return@evaluateJavascript
            }
            val o = try { JSONObject(s) } catch (e: Exception) { return@evaluateJavascript }
            if (!o.optBoolean("ok")) {
                showOsd(if (o.optString("reason") == "ad") "Publicité en cours, la vidéo démarre juste après" else "Le lecteur se charge…")
                return@evaluateJavascript
            }
            position = o.optDouble("t", position)
            val d = o.optDouble("d", 0.0)
            if (d > 0) duration = d
            playing = !o.optBoolean("paused", !playing)
            when (o.optString("cmd")) {
                "play" -> showOsd("Lecture", 1200L)
                "pause" -> showOsd("Pause", 1200L)
                else -> label?.let { showOsd(it, 1000L) }
            }
            showInfo(stay = !playing)
            updateKeepScreenOn()
        }
    }

    // ------------------------------------------------------------------ pointeur → page

    private fun target(): View = if (customView != null) fullscreenBox else webView

    private fun tap(x: Float, y: Float) {
        val t = target()
        val downTime = SystemClock.uptimeMillis()
        sendTouch(t, MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        handler.postDelayed({ sendTouch(t, MotionEvent.ACTION_UP, x, y, downTime, SystemClock.uptimeMillis()) }, 45L)
    }

    private fun sendTouch(v: View, action: Int, px: Float, py: Float, downTime: Long, eventTime: Long) {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = px
            y = py
            pressure = 1f
            size = 1f
        })
        val ev = MotionEvent.obtain(downTime, eventTime, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { v.dispatchTouchEvent(ev) } catch (e: Exception) { }
        ev.recycle()
    }

    /** Survol « souris » : fait apparaître les commandes du lecteur sous le pointeur. */
    private fun hover(action: Int, px: Float, py: Float) {
        if (webViewGone || px < 0f) return
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = px
            y = py
        })
        val ev = MotionEvent.obtain(now, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        try { target().dispatchGenericMotionEvent(ev) } catch (e: Exception) { }
        ev.recycle()
    }

    override fun onCursorMove(x: Float, y: Float) {
        hover(MotionEvent.ACTION_HOVER_MOVE, x, y)
        scheduleCursorIdle()
    }

    override fun onEdgeScroll(x: Float, y: Float, dx: Float, dy: Float, smooth: Boolean) = Unit

    // ------------------------------------------------------------------ plein écran du lecteur

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        fullscreenBox.addView(view, match())
        fullscreenBox.visibility = View.VISIBLE
        updateKeepScreenOn()
    }

    private fun leaveFullscreen(fromWeb: Boolean) {
        val v = customView ?: return
        customView = null
        val cb = customCallback
        customCallback = null
        if (!fromWeb) cb?.onCustomViewHidden()
        fullscreenBox.removeView(v)
        fullscreenBox.visibility = View.GONE
        updateKeepScreenOn()
        webView.requestFocus()
    }

    // ------------------------------------------------------------------ WebView

    inner class Bridge {
        @JavascriptInterface
        fun state(t: Double, d: Double, isPlaying: Boolean, ad: Boolean) {
            handler.post { if (!webViewGone) onState(t, d, isPlaying, ad) }
        }

        @JavascriptInterface
        fun ended() {
            handler.post { if (!webViewGone && countdown == 0) onEnded() }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url ?: return false
            val scheme = url.scheme?.lowercase(Locale.ROOT) ?: return false
            if (scheme != "http" && scheme != "https") return true
            // Le lecteur reste sur Forja : un lien vers une autre page du site ramène à l'app.
            if (request?.isForMainFrame == true && url.host?.endsWith("forja.ma") == true && url.path?.contains("/player/") != true) {
                showOsd("Cette vidéo n'est pas disponible dans le lecteur Forja", 2500L)
                handler.postDelayed({ finish() }, 2500L)
                return true
            }
            return false
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            inject()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            inject()
            handler.postDelayed({ hideSplash() }, 6000L)
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) showError()
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // Le moteur web a planté (manque de mémoire sur la TV) : on revient à la fiche proprement.
            saveProgress(force = true)
            webViewGone = true
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
            finish()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
            if (view != null) enterFullscreen(view, callback)
        }

        override fun onHideCustomView() {
            leaveFullscreen(fromWeb = true)
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            // Contenus protégés : autorisés comme dans un navigateur ; micro et caméra : refusés.
            val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
        }

        // Évite l'affiche grise par défaut avant le démarrage d'une vidéo.
        override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private const val EXTRA_QUEUE = "queue"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_START = "start"
        private const val EXTRA_SHOW_SLUG = "show_slug"
        private const val EXTRA_SHOW_TITLE = "show_title"
        private const val EXTRA_SHOW_IMAGE = "show_image"

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
        ) {
            val intent = Intent(context, WebPlayerActivity::class.java)
                .putExtra(EXTRA_QUEUE, Store.queueToJson(queue))
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_START, startSecs)
                .putExtra(EXTRA_SHOW_SLUG, showSlug)
                .putExtra(EXTRA_SHOW_TITLE, showTitle)
                .putExtra(EXTRA_SHOW_IMAGE, showImage)
            context.startActivity(intent)
        }
    }
}
