package com.alfa.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Circular sci-fi dial: apps orbit a central hub. The hub ring shows battery,
 * the outer left/right arcs show RAM and storage usage. Tick rings rotate slowly.
 */
class OrbitView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    var accent: Int = Color.rgb(255, 45, 61)
        set(v) { field = v; hubValue.setShadowLayer(18f * d, 0f, 0f, v); invalidate() }
    var battery = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var ram = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var storage = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val d = resources.displayMetrics.density
    private val iconSize = (54 * d).toInt()
    private val icons = mutableListOf<View>()

    val hub = LinearLayout(context)
    val hubValue = TextView(context)
    val hubLabel = TextView(context)
    val hubSub = TextView(context)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
    }
    private val dash = DashPathEffect(floatArrayOf(2f * d, 5f * d), 0f)
    private val rect = RectF()

    private var phase = 0f
    private var running = false
    private var lastFrameMs = 0L
    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime() / 1_000_000L
            val dt = (now - lastFrameMs).coerceIn(0L, 64L)
            lastFrameMs = now
            phase = (phase + dt * 0.006f) % 360f
            if (drift && !spinner.dragging) spinner.add(dt * 0.004f) else invalidate()
            postOnAnimation(this)
        }
    }

    /** Finger spin with momentum. */
    val spinner = Spinner(this) { positionIcons(); invalidate() }
    /** Slow automatic rotation of the app ring while idle. */
    var drift = false
    var spinEnabled: Boolean
        get() = spinner.enabled
        set(v) { spinner.enabled = v; if (!v) spinner.stop() }

    // geometry (computed in onLayout)
    private var cx = 0f
    private var cy = 0f
    private var outerR = 0f
    private var orbitR = 0f
    private var hubR = 0f

    init {
        setWillNotDraw(false)
        clipChildren = false

        hub.orientation = LinearLayout.VERTICAL
        hub.gravity = Gravity.CENTER
        hub.background = context.getDrawable(R.drawable.orb)
        hub.isClickable = true
        hubValue.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        hubLabel.apply {
            setTextColor(Color.argb(170, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.25f
            gravity = Gravity.CENTER
        }
        hubSub.apply {
            setTextColor(Color.argb(130, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, (4 * d).toInt(), 0, 0)
        }
        hub.addView(hubValue)
        hub.addView(hubLabel)
        hub.addView(hubSub)
        addView(hub)
    }

    fun setApps(apps: List<AppEntry>, onClick: (AppEntry) -> Unit, onLong: (View, AppEntry) -> Unit) {
        icons.forEach { removeView(it) }
        icons.clear()
        apps.take(MAX).forEach { app ->
            val iv = ImageView(context).apply {
                setImageDrawable(app.icon)
                background = context.getDrawable(R.drawable.ripple_orb)
                val p = (1 * d).toInt()
                setPadding(p, p, p, p)
                contentDescription = app.label
                setLayerType(View.LAYER_TYPE_HARDWARE, null) // smooth sub-pixel motion while spinning
                setOnClickListener { onClick(app) }
                setOnLongClickListener {
                    if (spinner.haptics) it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onLong(it, app); true
                }
            }
            icons.add(iv)
            addView(iv, LayoutParams(iconSize, iconSize))
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val size = min(w, h).toFloat()
        val oR = size / 2f - 6 * d
        val orR = oR - 16 * d - iconSize / 2f
        val hR = (orR - iconSize / 2f - 16 * d).coerceAtLeast(30 * d)
        val exact = MeasureSpec.makeMeasureSpec(iconSize, MeasureSpec.EXACTLY)
        icons.forEach { it.measure(exact, exact) }
        val hs = MeasureSpec.makeMeasureSpec((hR * 2).toInt(), MeasureSpec.EXACTLY)
        hub.measure(hs, hs)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        cx = w / 2f
        cy = h / 2f
        val size = min(w, h).toFloat()
        outerR = size / 2f - 6 * d
        orbitR = outerR - 16 * d - iconSize / 2f
        hubR = (orbitR - iconSize / 2f - 16 * d).coerceAtLeast(30 * d)

        // icons sit at the origin; positionIcons() moves them with translations (no relayout while spinning)
        icons.forEach { it.layout(0, 0, iconSize, iconSize) }
        val hs = (hubR * 2).toInt()
        hub.layout((cx - hubR).toInt(), (cy - hubR).toInt(), (cx - hubR).toInt() + hs, (cy - hubR).toInt() + hs)
        positionIcons()
    }

    private fun positionIcons() {
        val n = icons.size
        icons.forEachIndexed { i, v ->
            // offset by half a step so the 3 and 9 o'clock gauges stay clear
            val a = Math.toRadians(-90.0 + (i + 0.5) * 360.0 / n + spinner.angle)
            v.translationX = (cx + orbitR * cos(a)).toFloat() - iconSize / 2f
            v.translationY = (cy + orbitR * sin(a)).toFloat() - iconSize / 2f
        }
    }

    private var glow: RadialGradient? = null
    private var glowKey = 0L

    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(canvas: Canvas) {
        if (outerR <= 0f) return

        // soft core glow
        val key = (cx.toLong() shl 40) xor (cy.toLong() shl 20) xor outerR.toLong() xor accent.toLong()
        if (glow == null || key != glowKey) {
            glowKey = key
            glow = RadialGradient(cx, cy, outerR, alpha(accent, 60), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        fill.shader = glow
        canvas.drawCircle(cx, cy, outerR, fill)
        fill.shader = null

        // outer ring
        stroke.pathEffect = null
        stroke.strokeWidth = 1f * d
        stroke.color = Color.argb(50, 255, 255, 255)
        canvas.drawCircle(cx, cy, outerR, stroke)

        // rotating tick ring (follows the finger spin too)
        canvas.save()
        canvas.rotate(phase + spinner.angle, cx, cy)
        for (i in 0 until 90) {
            val major = i % 15 == 0
            stroke.color = if (major) alpha(accent, 220) else Color.argb(55, 255, 255, 255)
            stroke.strokeWidth = (if (major) 2f else 1f) * d
            val len = (if (major) 9f else 4f) * d
            canvas.drawLine(cx, cy - outerR + 3 * d, cx, cy - outerR + 3 * d + len, stroke)
            canvas.rotate(4f, cx, cy)
        }
        canvas.restore()

        // (RAM and storage now live in the info block next to the clock)

        // dashed inner ring, counter-rotating
        canvas.save()
        canvas.rotate(-phase * 2f, cx, cy)
        stroke.pathEffect = dash
        stroke.strokeWidth = 1f * d
        stroke.color = alpha(accent, 110)
        canvas.drawCircle(cx, cy, hubR + 9 * d, stroke)
        stroke.pathEffect = null
        canvas.restore()

        // battery ring around hub
        rect.set(cx - hubR - 3 * d, cy - hubR - 3 * d, cx + hubR + 3 * d, cy + hubR + 3 * d)
        stroke.strokeWidth = 3f * d
        stroke.color = Color.argb(35, 255, 255, 255)
        canvas.drawArc(rect, 0f, 360f, false, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = accent
        canvas.drawArc(rect, -90f, 360f * battery, false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    // ---------- spin gesture ----------

    private fun inRing(x: Float, y: Float): Boolean {
        val dist = kotlin.math.hypot(x - cx, y - cy)
        return dist > hubR + 4 * d && dist < outerR + 12 * d
    }

    private var tracking = false

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                tracking = spinner.enabled && inRing(ev.x, ev.y)
                if (tracking) {
                    spinner.onDown(ev, cx, cy)
                    // keep the home swipe (notifications / long-press) from stealing the drag
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> if (tracking) return spinner.checkStart(ev, cx, cy)
        }
        return false
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                tracking = spinner.enabled && inRing(ev.x, ev.y)
                if (!tracking) return false
                spinner.onDown(ev, cx, cy)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                spinner.checkStart(ev, cx, cy)
                spinner.onMove(ev, cx, cy)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (tracking) spinner.onUp()
                tracking = false
            }
        }
        return tracking
    }

    private fun start() {
        if (running) return
        running = true
        lastFrameMs = System.nanoTime() / 1_000_000L
        postOnAnimation(frame)
    }

    private fun stop() {
        running = false
        removeCallbacks(frame)
        spinner.stop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    companion object {
        const val MAX = 8
    }
}
