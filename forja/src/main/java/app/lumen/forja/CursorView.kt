package app.lumen.forja

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View

/**
 * Pointeur piloté par les flèches de la télécommande, comme une souris.
 * Appui bref : petit pas. Appui maintenu : déplacement continu qui accélère.
 * Au bord de l'écran, le mouvement fait défiler la page (ou la rangée) sous le pointeur.
 */
class CursorView(context: Context) : View(context), Choreographer.FrameCallback {

    interface Listener {
        fun onCursorMove(x: Float, y: Float)
        fun onEdgeScroll(x: Float, y: Float, dx: Float, dy: Float, smooth: Boolean)
    }

    var listener: Listener? = null
    var speedFactor = 1f

    var cursorX = -1f
        private set
    var cursorY = -1f
        private set
    var cursorVisible = true
        private set

    private val dp = resources.displayMetrics.density

    private var keyLeft = false
    private var keyRight = false
    private var keyUp = false
    private var keyDown = false
    private var holdStart = 0L
    private var lastFrameNanos = 0L
    private var running = false
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var lastFlush = 0L
    private var pressedUntil = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF3E7D3.toInt()
        style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A0B12.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * dp
        strokeJoin = Paint.Join.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x77000000
        style = Paint.Style.FILL
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9A45C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * dp
    }
    private val arrow = Path().apply {
        val s = 1.45f * dp
        moveTo(0f, 0f)
        lineTo(0f, 17f * s)
        lineTo(4.6f * s, 13.2f * s)
        lineTo(7.6f * s, 20f * s)
        lineTo(10.6f * s, 18.7f * s)
        lineTo(7.7f * s, 12.2f * s)
        lineTo(13f * s, 12.2f * s)
        close()
    }

    init {
        isFocusable = false
        isClickable = false
    }

    private val anyKeyHeld: Boolean
        get() = keyLeft || keyRight || keyUp || keyDown

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cursorX < 0f || oldw == 0) {
            cursorX = w / 2f
            cursorY = h / 2f
        } else {
            cursorX = cursorX.coerceIn(0f, w - 1f)
            cursorY = cursorY.coerceIn(0f, h - 1f)
        }
    }

    /** Flèche enfoncée ([pressed] = true) ou relâchée. */
    fun onDirectionKey(keyCode: Int, pressed: Boolean) {
        if (!pressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> keyLeft = false
                KeyEvent.KEYCODE_DPAD_RIGHT -> keyRight = false
                KeyEvent.KEYCODE_DPAD_UP -> keyUp = false
                KeyEvent.KEYCODE_DPAD_DOWN -> keyDown = false
            }
            return
        }
        if (!cursorVisible) {
            show()
            return
        }
        val wasIdle = !anyKeyHeld
        var dx = 0
        var dy = 0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { keyLeft = true; dx = -1 }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { keyRight = true; dx = 1 }
            KeyEvent.KEYCODE_DPAD_UP -> { keyUp = true; dy = -1 }
            KeyEvent.KEYCODE_DPAD_DOWN -> { keyDown = true; dy = 1 }
            else -> return
        }
        if (wasIdle) holdStart = SystemClock.uptimeMillis()
        step(dx, dy)
        startLoop()
    }

    private fun step(dx: Int, dy: Int) {
        val s = STEP_DP * dp
        if (moveBy(dx * s, dy * s, accumulate = false)) {
            // Déjà au bord : on fait défiler d'un bloc.
            listener?.onEdgeScroll(cursorX, cursorY, dx * width * CHUNK, dy * height * CHUNK, true)
        }
    }

    /** Déplace le pointeur ; renvoie true si le mouvement a buté contre un bord. */
    private fun moveBy(dx: Float, dy: Float, accumulate: Boolean): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false
        val nx = cursorX + dx
        val ny = cursorY + dy
        val cx = nx.coerceIn(0f, w - 1f)
        val cy = ny.coerceIn(0f, h - 1f)
        val overX = nx - cx
        val overY = ny - cy
        if (cx != cursorX || cy != cursorY) {
            cursorX = cx
            cursorY = cy
            invalidate()
            listener?.onCursorMove(cursorX, cursorY)
        }
        if (accumulate) {
            pendingDx += overX * EDGE_GAIN
            pendingDy += overY * EDGE_GAIN
        }
        return overX != 0f || overY != 0f
    }

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!anyKeyHeld || !cursorVisible) {
            running = false
            flush(true)
            return
        }
        if (lastFrameNanos != 0L) {
            val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            val held = SystemClock.uptimeMillis() - holdStart
            if (held > HOLD_DELAY_MS) {
                val ramp = ((held - HOLD_DELAY_MS) / RAMP_MS.toFloat()).coerceIn(0f, 1f)
                val speed = (MIN_SPEED_DP + (MAX_SPEED_DP - MIN_SPEED_DP) * ramp) * dp * speedFactor
                val vx = (if (keyRight) 1f else 0f) - (if (keyLeft) 1f else 0f)
                val vy = (if (keyDown) 1f else 0f) - (if (keyUp) 1f else 0f)
                moveBy(vx * speed * dt, vy * speed * dt, accumulate = true)
                flush(false)
            }
        }
        lastFrameNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun flush(force: Boolean) {
        if (pendingDx == 0f && pendingDy == 0f) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastFlush < FLUSH_MS) return
        lastFlush = now
        val dx = pendingDx
        val dy = pendingDy
        pendingDx = 0f
        pendingDy = 0f
        listener?.onEdgeScroll(cursorX, cursorY, dx, dy, false)
    }

    fun show() {
        if (cursorVisible) return
        cursorVisible = true
        invalidate()
        listener?.onCursorMove(cursorX, cursorY)
    }

    fun hide() {
        keyLeft = false
        keyRight = false
        keyUp = false
        keyDown = false
        pendingDx = 0f
        pendingDy = 0f
        if (!cursorVisible) return
        cursorVisible = false
        invalidate()
    }

    /** Petit anneau doré au moment du clic. */
    fun press() {
        pressedUntil = SystemClock.uptimeMillis() + 220
        invalidate()
        postInvalidateDelayed(240)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!cursorVisible || cursorX < 0f) return
        canvas.save()
        canvas.translate(cursorX, cursorY)
        if (SystemClock.uptimeMillis() < pressedUntil) {
            canvas.drawCircle(0f, 0f, 16f * dp, ring)
        }
        canvas.save()
        canvas.translate(1.5f * dp, 2.2f * dp)
        canvas.drawPath(arrow, shadow)
        canvas.restore()
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, outline)
        canvas.restore()
    }

    companion object {
        private const val STEP_DP = 14f
        private const val CHUNK = 0.3f
        private const val HOLD_DELAY_MS = 150L
        private const val RAMP_MS = 700L
        private const val MIN_SPEED_DP = 420f
        private const val MAX_SPEED_DP = 1500f
        private const val EDGE_GAIN = 1.3f
        private const val FLUSH_MS = 33L
    }
}
