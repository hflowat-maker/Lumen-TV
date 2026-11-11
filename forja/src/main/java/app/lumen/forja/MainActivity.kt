package app.lumen.forja

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * Forja TV : le site forja.ma (SNRT) en plein écran sur Google TV, piloté à la télécommande.
 *
 * Télécommande :
 *  - flèches : déplacer le pointeur (au bord de l'écran : défilement) ;
 *  - OK : cliquer ; appui long sur OK : menu ;
 *  - Retour : page précédente, sortie du plein écran, deux fois pour quitter ;
 *  - vidéo en plein écran : ←/→ reculer/avancer de 10 s, OK pause, ↑/↓ réafficher le pointeur.
 */
class MainActivity : Activity(), CursorView.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("forja_tv", MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var fullscreenBox: FrameLayout
    private lateinit var cursor: CursorView
    private lateinit var splash: LinearLayout
    private lateinit var errorBox: TextView
    private lateinit var osdView: TextView

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var webViewGone = false
    private var splashHidden = false
    private var errorShown = false
    private var mainFrameFailed = false
    private var isPlaying = false
    private var isEditing = false
    private var currentUrl: String? = null
    private var lastBackAt = 0L
    private var okDownAt = 0L
    private var okLongFired = false

    private val hideCursorTask = Runnable {
        if (customView != null || isPlaying) {
            cursor.hide()
            hover(MotionEvent.ACTION_HOVER_EXIT, cursor.cursorX, cursor.cursorY)
        }
    }
    private val hideOsdTask = Runnable { osdView.animate().alpha(0f).setDuration(250).start() }

    // ------------------------------------------------------------------ cycle de vie

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setupWebView()
        val start = savedInstanceState?.getString(KEY_URL)?.takeIf { it.startsWith("http") } ?: HOME_URL
        webView.loadUrl(start)
    }

    override fun onResume() {
        super.onResume()
        if (!webViewGone) webView.onResume()
    }

    override fun onPause() {
        if (!webViewGone) {
            webView.evaluateJavascript(Scripts.PAUSE_ALL, null)
            webView.onPause()
        }
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentUrl?.let { outState.putString(KEY_URL, it) }
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

    private fun match() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        webView = WebView(this)
        root.addView(webView, match())

        fullscreenBox = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(fullscreenBox, match())

        splash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            addView(TextView(context).apply {
                text = "forja"
                setTextColor(CREAM)
                textSize = 60f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "Séries et films marocains"
                setTextColor(MUTED)
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(0, (4 * dp).toInt(), 0, (30 * dp).toInt())
            })
            addView(ProgressBar(context).apply { isIndeterminate = true },
                LinearLayout.LayoutParams((34 * dp).toInt(), (34 * dp).toInt()))
        }
        root.addView(splash, match())

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
            it.speedFactor = prefs.getFloat(KEY_SPEED, 1f)
            it.visibility = View.INVISIBLE
        }
        root.addView(cursor, match())

        osdView = TextView(this).apply {
            setTextColor(CREAM)
            textSize = 18f
            alpha = 0f
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(0xE01A0B12.toInt())
                setStroke((1 * dp).toInt(), 0x55C9A45C)
            }
            setPadding((22 * dp).toInt(), (11 * dp).toInt(), (22 * dp).toInt(), (11 * dp).toInt())
        }
        root.addView(osdView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = (34 * dp).toInt() })

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
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgent(prefs.getBoolean(KEY_DESKTOP, true))
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

    /** Version « ordinateur » par défaut : mise en page large, pas de bandeau « installez l'app ». */
    private fun userAgent(desktop: Boolean): String {
        val def = try { WebSettings.getDefaultUserAgent(this) } catch (e: Exception) { "" }
        if (!desktop) return def.replace("; wv", "")
        val chrome = Regex("Chrome/[\\d.]+").find(def)?.value ?: "Chrome/130.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    private fun inject() {
        if (webViewGone) return
        webView.evaluateJavascript(Scripts.CORE, null)
        val w = prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH)
        if (w > 0) webView.evaluateJavascript(Scripts.viewport(w), null)
    }

    private fun hideSplash() {
        if (splashHidden) return
        splashHidden = true
        cursor.visibility = View.VISIBLE
        splash.animate().alpha(0f).setDuration(350).withEndAction { splash.visibility = View.GONE }.start()
    }

    private fun showError() {
        errorShown = true
        errorBox.text = "Impossible d'ouvrir Forja.\nVérifiez la connexion Internet de la TV.\n\nOK : réessayer      Retour : quitter"
        errorBox.visibility = View.VISIBLE
        hideSplash()
    }

    private fun hideError() {
        if (!errorShown) return
        errorShown = false
        errorBox.visibility = View.GONE
    }

    private fun showOsd(text: String, durationMs: Long = 1800L) {
        osdView.text = text
        osdView.animate().cancel()
        osdView.alpha = 1f
        handler.removeCallbacks(hideOsdTask)
        handler.postDelayed(hideOsdTask, durationMs)
    }

    private fun updateKeepScreenOn() {
        if (isPlaying || customView != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun scheduleCursorHide() {
        handler.removeCallbacks(hideCursorTask)
        if (customView != null || isPlaying) handler.postDelayed(hideCursorTask, CURSOR_IDLE_MS)
    }

    private fun onPlaying(value: Boolean) {
        if (isPlaying == value) return
        isPlaying = value
        updateKeepScreenOn()
        if (value) scheduleCursorHide() else if (customView == null) cursor.show()
    }

    // ------------------------------------------------------------------ plein écran vidéo

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
        hideCursorTask.run()
        showOsd("←  →  reculer / avancer      OK  pause      ↑ ↓  pointeur", 3500L)
    }

    private fun leaveFullscreen(fromWeb: Boolean) {
        val v = customView ?: return
        customView = null
        val cb = customCallback
        customCallback = null
        if (!fromWeb) cb?.onCustomViewHidden()
        fullscreenBox.removeView(v)
        fullscreenBox.visibility = View.GONE
        cursor.show()
        updateKeepScreenOn()
        scheduleCursorHide()
        webView.requestFocus()
    }

    /** Plein écran vidéo et pointeur masqué : les flèches pilotent la lecture. */
    private fun videoMode(): Boolean = customView != null && !cursor.cursorVisible

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
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Champ de recherche actif : Entrée valide la saisie.
                if (isEditing) return super.dispatchKeyEvent(event)
                onOk(event)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                onOk(event)
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (first) showMenu()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (first) video("toggle")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (first) video("play")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (first) video("pause")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down && event.repeatCount % 4 == 0) seek(30)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down && event.repeatCount % 4 == 0) seek(-30)
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
        if (videoMode()) {
            val horizontal = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            if (horizontal) {
                val sign = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                when {
                    event.repeatCount == 0 -> seek(10 * sign)
                    event.repeatCount % 4 == 0 -> seek((if (event.repeatCount > 16) 30 else 10) * sign)
                }
            } else if (event.repeatCount == 0) {
                cursor.show()
                scheduleCursorHide()
            }
            return
        }
        if (event.repeatCount > 0) return // le déplacement continu est géré par le pointeur
        cursor.onDirectionKey(code, true)
        scheduleCursorHide()
    }

    private fun onOk(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                okDownAt = SystemClock.uptimeMillis()
                okLongFired = false
                if (!videoMode() && cursor.cursorVisible && !errorShown) cursor.press()
            } else if (!okLongFired && SystemClock.uptimeMillis() - okDownAt >= LONG_PRESS_MS) {
                okLongFired = true
                showMenu()
            }
            return
        }
        if (okLongFired) {
            okLongFired = false
            return
        }
        when {
            errorShown -> {
                hideError()
                webView.loadUrl(currentUrl ?: HOME_URL)
            }
            videoMode() -> video("toggle")
            !cursor.cursorVisible -> {
                cursor.show()
                scheduleCursorHide()
            }
            else -> {
                tap(cursor.cursorX, cursor.cursorY)
                scheduleCursorHide()
            }
        }
    }

    private fun onBack() {
        when {
            customView != null -> leaveFullscreen(fromWeb = false)
            !errorShown && webView.canGoBack() -> webView.goBack()
            errorShown && webView.canGoBack() -> {
                hideError()
                webView.goBack()
            }
            else -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastBackAt < 2000L) {
                    finish()
                } else {
                    lastBackAt = now
                    showOsd("Appuyez encore sur Retour pour quitter")
                }
            }
        }
    }

    // ------------------------------------------------------------------ pointeur → page

    private fun target(): View = if (customView != null) fullscreenBox else webView

    private fun tap(x: Float, y: Float) {
        val t = target()
        val downTime = SystemClock.uptimeMillis()
        sendTouch(t, MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        handler.postDelayed({
            sendTouch(t, MotionEvent.ACTION_UP, x, y, downTime, SystemClock.uptimeMillis())
        }, 45L)
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
        val ev = MotionEvent.obtain(
            downTime, eventTime, action, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try { v.dispatchTouchEvent(ev) } catch (e: Exception) { }
        ev.recycle()
    }

    /** Survol « souris » : fait apparaître les flèches des carrousels et les menus au survol. */
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
        val ev = MotionEvent.obtain(
            now, now, action, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try { target().dispatchGenericMotionEvent(ev) } catch (e: Exception) { }
        ev.recycle()
    }

    override fun onCursorMove(x: Float, y: Float) {
        hover(MotionEvent.ACTION_HOVER_MOVE, x, y)
        scheduleCursorHide()
    }

    override fun onEdgeScroll(x: Float, y: Float, dx: Float, dy: Float, smooth: Boolean) {
        if (customView != null || webViewGone) return
        val w = webView.width.toFloat()
        val h = webView.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val js = String.format(
            Locale.US, "window.__ftvScroll&&window.__ftvScroll(%.5f,%.5f,%.5f,%.5f,%b)",
            x / w, y / h, dx / w, dy / h, smooth
        )
        webView.evaluateJavascript(js, null)
    }

    // ------------------------------------------------------------------ lecture vidéo

    private fun seek(seconds: Int) {
        val label = if (seconds > 0) "+$seconds s" else "−${-seconds} s"
        video("seek", seconds, label)
    }

    private fun video(cmd: String, arg: Int = 0, label: String? = null) {
        if (webViewGone) return
        webView.evaluateJavascript("window.__ftvVideo?window.__ftvVideo('$cmd',$arg):'none'") { raw ->
            showVideoState(raw, label)
        }
    }

    private fun showVideoState(raw: String?, label: String?) {
        val s = try { JSONTokener(raw ?: "null").nextValue() as? String } catch (e: Exception) { null }
        if (s == null || s == "none") {
            showOsd("Aucune vidéo détectée sur la page")
            return
        }
        val o = try { JSONObject(s) } catch (e: Exception) { return }
        val t = o.optDouble("t", 0.0)
        val d = o.optDouble("d", 0.0)
        val pos = if (d > 0) "${clock(t)} / ${clock(d)}" else clock(t)
        val head = when (o.optString("cmd")) {
            "play" -> "Lecture"
            "pause" -> "Pause"
            else -> label ?: ""
        }
        showOsd("$head     $pos")
    }

    private fun clock(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    // ------------------------------------------------------------------ menu (appui long sur OK)

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun showMenu() {
        val desktop = prefs.getBoolean(KEY_DESKTOP, true)
        val items = arrayOf<CharSequence>(
            "Accueil Forja",
            "Recharger la page",
            "Taille de l'affichage",
            "Vitesse du pointeur",
            if (desktop) "Passer à la version mobile du site" else "Revenir à la version ordinateur du site",
            "Quitter Forja TV",
        )
        dialog()
            .setTitle("Forja TV")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        leaveFullscreen(fromWeb = false)
                        hideError()
                        webView.loadUrl(HOME_URL)
                    }
                    1 -> {
                        hideError()
                        webView.reload()
                    }
                    2 -> showWidthMenu()
                    3 -> showSpeedMenu()
                    4 -> {
                        prefs.edit().putBoolean(KEY_DESKTOP, !desktop).apply()
                        webView.settings.userAgentString = userAgent(!desktop)
                        webView.reload()
                    }
                    5 -> finish()
                }
            }
            .show()
    }

    private fun showWidthMenu() {
        val widths = intArrayOf(960, 1100, 1280, 1440, 1600, 0)
        val labels = arrayOf<CharSequence>(
            "Très grande",
            "Grande",
            "Normale (recommandée)",
            "Petite",
            "Très petite",
            "Celle du site (automatique)",
        )
        val current = widths.indexOf(prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH)).coerceAtLeast(0)
        dialog()
            .setTitle("Taille de l'affichage")
            .setSingleChoiceItems(labels, current) { d, which ->
                val w = widths[which]
                prefs.edit().putInt(KEY_WIDTH, w).apply()
                d.dismiss()
                if (w > 0) inject() else webView.reload()
            }
            .show()
    }

    private fun showSpeedMenu() {
        val speeds = floatArrayOf(0.65f, 1f, 1.45f)
        val labels = arrayOf<CharSequence>("Lente", "Normale", "Rapide")
        val saved = prefs.getFloat(KEY_SPEED, 1f)
        val current = speeds.indexOfFirst { kotlin.math.abs(it - saved) < 0.01f }.coerceAtLeast(0)
        dialog()
            .setTitle("Vitesse du pointeur")
            .setSingleChoiceItems(labels, current) { d, which ->
                prefs.edit().putFloat(KEY_SPEED, speeds[which]).apply()
                cursor.speedFactor = speeds[which]
                d.dismiss()
            }
            .show()
    }

    // ------------------------------------------------------------------ WebView

    inner class Bridge {
        @JavascriptInterface
        fun playing(value: Boolean) {
            handler.post { onPlaying(value) }
        }

        @JavascriptInterface
        fun editing(value: Boolean) {
            handler.post { isEditing = value }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            // Liens « ouvrir l'application » (intent://, market://…) : ignorés, on reste dans la page.
            val scheme = request?.url?.scheme?.lowercase(Locale.ROOT) ?: return false
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            mainFrameFailed = false
            if (url != null) currentUrl = url
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            inject()
            hideSplash()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            inject()
            if (!mainFrameFailed) {
                hideSplash()
                hideError()
            }
            CookieManager.getInstance().flush()
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            if (url != null) currentUrl = url
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) {
                mainFrameFailed = true
                showError()
            }
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // Le moteur web a planté (manque de mémoire sur la TV) : on relance proprement.
            webViewGone = true
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
            if (view == null) return
            enterFullscreen(view, callback)
        }

        override fun onHideCustomView() {
            leaveFullscreen(fromWeb = true)
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            // Contenus protégés (DRM Widevine) : autorisés ; le reste (micro, caméra) : refusé.
            val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (newProgress >= 70) hideSplash()
        }

        // Évite l'affiche grise par défaut avant le démarrage d'une vidéo.
        override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private const val HOME_URL = "https://forja.ma/"
        private const val DEFAULT_WIDTH = 1280
        private const val LONG_PRESS_MS = 600L
        private const val CURSOR_IDLE_MS = 3500L

        private const val KEY_URL = "url"
        private const val KEY_WIDTH = "page_width"
        private const val KEY_SPEED = "cursor_speed"
        private const val KEY_DESKTOP = "desktop_site"

        private val BG = 0xFF24101A.toInt()
        private val CREAM = 0xFFF3E7D3.toInt()
        private val MUTED = 0xFFB9A08F.toInt()
    }
}
