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
    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            phase = (phase + 0.25f) % 360f
            invalidate()
            postDelayed(this, 40)
        }
    }

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
                setOnClickListener { onClick(app) }
                setOnLongClickListener { onLong(it, app); true }
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

        val n = icons.size
        icons.forEachIndexed { i, v ->
            // offset by half a step so the 3 and 9 o'clock gauges stay clear
            val a = Math.toRadians(-90.0 + (i + 0.5) * 360.0 / n)
            val x = (cx + orbitR * cos(a)).toInt() - iconSize / 2
            val y = (cy + orbitR * sin(a)).toInt() - iconSize / 2
            v.layout(x, y, x + iconSize, y + iconSize)
        }
        val hs = (hubR * 2).toInt()
        hub.layout((cx - hubR).toInt(), (cy - hubR).toInt(), (cx - hubR).toInt() + hs, (cy - hubR).toInt() + hs)
    }

    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(canvas: Canvas) {
        if (outerR <= 0f) return

        // soft core glow
        fill.shader = RadialGradient(cx, cy, outerR, alpha(accent, 60), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, outerR, fill)
        fill.shader = null

        // outer ring
        stroke.pathEffect = null
        stroke.strokeWidth = 1f * d
        stroke.color = Color.argb(50, 255, 255, 255)
        canvas.drawCircle(cx, cy, outerR, stroke)

        // rotating tick ring
        canvas.save()
        canvas.rotate(phase, cx, cy)
        for (i in 0 until 90) {
            val major = i % 15 == 0
            stroke.color = if (major) alpha(accent, 220) else Color.argb(55, 255, 255, 255)
            stroke.strokeWidth = (if (major) 2f else 1f) * d
            val len = (if (major) 9f else 4f) * d
            canvas.drawLine(cx, cy - outerR + 3 * d, cx, cy - outerR + 3 * d + len, stroke)
            canvas.rotate(4f, cx, cy)
        }
        canvas.restore()

        // RAM (left) and STORAGE (right) gauges on the outer ring
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = 3f * d
        rect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        stroke.color = Color.argb(40, 255, 255, 255)
        canvas.drawArc(rect, 145f, 70f, false, stroke)
        canvas.drawArc(rect, -35f, 70f, false, stroke)
        stroke.color = accent
        canvas.drawArc(rect, 215f, -70f * ram, false, stroke)
        canvas.drawArc(rect, 35f, -70f * storage, false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT

        text.color = Color.argb(170, 255, 255, 255)
        val ty = cy + text.textSize / 3f
        canvas.drawText("RAM", cx - outerR + 26 * d, ty - 6 * d, text)
        canvas.drawText("${(ram * 100).toInt()}%", cx - outerR + 26 * d, ty + 6 * d, text)
        canvas.drawText("STO", cx + outerR - 26 * d, ty - 6 * d, text)
        canvas.drawText("${(storage * 100).toInt()}%", cx + outerR - 26 * d, ty + 6 * d, text)

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

    private fun start() {
        if (running) return
        running = true
        post(frame)
    }

    private fun stop() {
        running = false
        removeCallbacks(frame)
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
