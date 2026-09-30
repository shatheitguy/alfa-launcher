package com.alfa.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * All-apps view in orbit form: three concentric rings of apps (6 / 12 / 18)
 * around a hub. Swipe left/right to change page; rings spin between pages.
 */
class GalaxyView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    var accent: Int = Color.rgb(255, 45, 61)
        set(v) { field = v; hubRange.setShadowLayer(16f * d, 0f, 0f, v); invalidate() }
    var onAppClick: (AppEntry) -> Unit = {}
    var onAppLongClick: (View, AppEntry) -> Unit = { _, _ -> }
    var onPageChanged: (page: Int, pages: Int) -> Unit = { _, _ -> }

    private val d = resources.displayMetrics.density
    private val rings = intArrayOf(6, 12, 18)
    private val perPage = rings.sum()

    private var apps: List<AppEntry> = emptyList()
    var page = 0
        private set
    val pages get() = if (apps.isEmpty()) 1 else (apps.size + perPage - 1) / perPage

    val hub = LinearLayout(context)
    private val hubRange = TextView(context)
    private val hubPage = TextView(context)
    private val slots = ArrayList<LinearLayout>()

    // geometry
    private var scale = 1f
    private var cx = 0f
    private var cy = 0f
    private val radii = FloatArray(3)
    private var hubR = 0f
    private var itemW = 0
    private var itemH = 0
    private var iconPx = 0

    // animation state
    private var spin = 0f        // degrees added to ring angles
    private var itemsAlpha = 1f
    private var phase = 0f
    private var running = false
    private var anim: ValueAnimator? = null

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = DashPathEffect(floatArrayOf(2f * d, 6f * d), 0f)

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            phase = (phase + 0.2f) % 360f
            invalidate()
            postDelayed(this, 40)
        }
    }

    init {
        setWillNotDraw(false)
        clipChildren = false

        hub.orientation = LinearLayout.VERTICAL
        hub.gravity = Gravity.CENTER
        hub.background = context.getDrawable(R.drawable.orb)
        hub.isClickable = true
        hubRange.apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        hubPage.apply {
            setTextColor(Color.argb(160, 255, 255, 255))
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.2f
            gravity = Gravity.CENTER
        }
        hub.addView(hubRange)
        hub.addView(hubPage)
        addView(hub)

        repeat(perPage) {
            val slot = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = context.getDrawable(R.drawable.ripple_item)
            }
            val icon = ImageView(context)
            val label = TextView(context).apply {
                setTextColor(Color.argb(210, 255, 255, 255))
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(4f * d, 0f, 1f, Color.BLACK)
            }
            slot.addView(icon)
            slot.addView(label)
            slots.add(slot)
            addView(slot)
        }
    }

    fun setApps(list: List<AppEntry>, resetPage: Boolean) {
        apps = list
        if (resetPage || page >= pages) page = 0
        bind()
    }

    fun next() = goTo(page + 1, 1)
    fun prev() = goTo(page - 1, -1)

    private fun goTo(target: Int, dir: Int) {
        if (pages <= 1) return
        val t = (target + pages) % pages
        anim?.cancel()
        val out = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 170
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                spin = -dir * 40f * f
                itemsAlpha = 1f - f
                applyAnim()
            }
        }
        out.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) {
                if (anim !== out) return
                page = t
                bind()
                val inn = ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = 260
                    interpolator = DecelerateInterpolator(2f)
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        spin = dir * 40f * f
                        itemsAlpha = 1f - f
                        applyAnim()
                    }
                }
                anim = inn
                inn.start()
            }
        })
        anim = out
        out.start()
    }

    private fun applyAnim() {
        slots.forEach { it.alpha = itemsAlpha }
        requestLayout()
        invalidate()
    }

    private fun bind() {
        val start = page * perPage
        val pageApps = apps.drop(start).take(perPage)
        slots.forEachIndexed { i, slot ->
            val app = pageApps.getOrNull(i)
            if (app == null) {
                slot.visibility = View.INVISIBLE
                slot.setOnClickListener(null)
                slot.setOnLongClickListener(null)
            } else {
                slot.visibility = View.VISIBLE
                (slot.getChildAt(0) as ImageView).setImageDrawable(app.icon)
                (slot.getChildAt(1) as TextView).text = app.label
                slot.contentDescription = app.label
                slot.setOnClickListener { onAppClick(app) }
                slot.setOnLongClickListener { onAppLongClick(it, app); true }
            }
        }
        if (pageApps.isEmpty()) {
            hubRange.text = "—"
        } else {
            val a = pageApps.first().label.firstOrNull()?.uppercaseChar() ?: '#'
            val b = pageApps.last().label.firstOrNull()?.uppercaseChar() ?: '#'
            hubRange.text = if (a == b) "$a" else "$a–$b"
        }
        hubPage.text = "${page + 1} / $pages"
        onPageChanged(page, pages)
        requestLayout()
    }

    // ---------- layout ----------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)

        // designed for a 430dp circle; scale to fit
        val newScale = (min(w, h) / (430f * d)).coerceIn(0.55f, 1.3f)
        if (newScale != scale || iconPx == 0) {
            scale = newScale
            radii[0] = 68f * d * scale
            radii[1] = 124f * d * scale
            radii[2] = 178f * d * scale
            hubR = 36f * d * scale
            iconPx = (38f * d * scale).toInt()
            itemW = (60f * d * scale).toInt()
            itemH = (iconPx + 16f * d * scale).toInt()
            val labelSp = 8.5f * scale.coerceAtMost(1.1f)
            slots.forEach { slot ->
                slot.getChildAt(0).layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
                val label = slot.getChildAt(1) as TextView
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
                label.layoutParams = LinearLayout.LayoutParams(itemW, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (2 * d).toInt()
                }
            }
            hubRange.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * scale)
            hubPage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f * scale)
        }
        slots.forEach { slot ->
            slot.measure(MeasureSpec.makeMeasureSpec(itemW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(itemH, MeasureSpec.EXACTLY))
        }
        val hs = MeasureSpec.makeMeasureSpec((hubR * 2).toInt(), MeasureSpec.EXACTLY)
        hub.measure(hs, hs)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        cx = (right - left) / 2f
        cy = (bottom - top) / 2f
        var idx = 0
        rings.forEachIndexed { r, n ->
            val dir = if (r % 2 == 0) 1f else -1f
            val offset = if (r % 2 == 0) 0.0 else 0.5
            for (k in 0 until n) {
                val slot = slots[idx++]
                val a = Math.toRadians(-90.0 + (k + offset) * 360.0 / n + spin * dir)
                val x = (cx + radii[r] * cos(a)).toInt() - itemW / 2
                // centre the icon (not the label) on the ring
                val y = (cy + radii[r] * sin(a)).toInt() - iconPx / 2
                slot.layout(x, y, x + itemW, y + itemH)
            }
        }
        val hs = (hubR * 2).toInt()
        val hx = (cx - hubR).toInt()
        val hy = (cy - hubR).toInt()
        hub.layout(hx, hy, hx + hs, hy + hs)
    }

    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(canvas: Canvas) {
        val outer = radii[2] + 34f * d * scale
        if (outer <= 0f) return
        fill.shader = RadialGradient(cx, cy, outer, alpha(accent, 55), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, outer, fill)
        fill.shader = null

        // orbit tracks
        stroke.pathEffect = null
        stroke.strokeWidth = 1f * d
        for (r in radii) {
            stroke.color = Color.argb(28, 255, 255, 255)
            canvas.drawCircle(cx, cy, r, stroke)
        }

        // outer rotating ticks
        canvas.save()
        canvas.rotate(phase, cx, cy)
        for (i in 0 until 120) {
            val major = i % 10 == 0
            stroke.color = if (major) alpha(accent, 220) else Color.argb(45, 255, 255, 255)
            stroke.strokeWidth = (if (major) 2f else 1f) * d
            val len = (if (major) 8f else 3f) * d
            canvas.drawLine(cx, cy - outer, cx, cy - outer + len, stroke)
            canvas.rotate(3f, cx, cy)
        }
        canvas.restore()

        // dashed ring around the hub, counter-rotating
        canvas.save()
        canvas.rotate(-phase * 2f, cx, cy)
        stroke.pathEffect = dash
        stroke.strokeWidth = 1.5f * d
        stroke.color = alpha(accent, 140)
        canvas.drawCircle(cx, cy, hubR + 8f * d, stroke)
        stroke.pathEffect = null
        canvas.restore()

        // page progress arc around the hub
        stroke.strokeWidth = 3f * d
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = Color.argb(35, 255, 255, 255)
        val rr = hubR + 2.5f * d
        canvas.drawCircle(cx, cy, rr, stroke)
        stroke.color = accent
        val sweep = 360f / pages
        canvas.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, -90f + page * sweep, sweep, false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    // ---------- horizontal swipe = page ----------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                if (abs(dx) > slop * 2 && abs(dx) > abs(ev.y - downY)) {
                    swiping = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > slop * 2 && abs(ev.x - downX) > abs(ev.y - downY)) swiping = true
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                if (swiping && abs(dx) > slop * 4) { if (dx < 0) next() else prev() }
                swiping = false
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return true
    }

    // ---------- lifecycle ----------

    private fun start() {
        if (running) return
        running = true
        post(frame)
    }

    private fun stop() {
        running = false
        removeCallbacks(frame)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) start() else stop()
    }

    override fun onDetachedFromWindow() {
        stop()
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}
