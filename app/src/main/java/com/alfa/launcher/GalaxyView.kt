package com.alfa.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.view.HapticFeedbackConstants
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
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * All-apps view in orbit form: three concentric rings of apps (6 / 12 / 18)
 * around a hub. Circle-drag spins, a straight left/right swipe changes page.
 * Items are moved with translations (no relayout) so spinning stays smooth.
 * [setDepth] adds parallax between rings when the stage is tilted in 3D.
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
    private var scale = 0f
    private var cx = 0f
    private var cy = 0f
    private val radii = FloatArray(3)
    private var hubR = 0f
    private var itemW = 0
    private var itemH = 0
    private var iconPx = 0

    // 3D parallax: offset per depth layer (hub = 3, inner = 2, middle = 1, outer = 0)
    private var depthX = 0f
    private var depthY = 0f

    // animation state
    private var pageSpin = 0f
    private var itemsAlpha = 1f
    private var phase = 0f
    private var running = false
    private var lastFrameMs = 0L
    private var anim: ValueAnimator? = null

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = DashPathEffect(floatArrayOf(2f * d, 6f * d), 0f)
    private var glowShader: RadialGradient? = null
    private var glowKey = 0L

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime() / 1_000_000L
            val dt = (now - lastFrameMs).coerceIn(0L, 64L)
            lastFrameMs = now
            phase = (phase + dt * 0.005f) % 360f
            invalidate()
            postOnAnimation(this)
        }
    }

    /** Finger spin with momentum; when disabled, any horizontal swipe changes page. */
    val spinner = Spinner(this) { positionItems(); invalidate() }
    var spinEnabled: Boolean
        get() = spinner.enabled
        set(v) { spinner.enabled = v; if (!v) spinner.stop() }
    private var spinTracking = false
    private val path = ArrayList<Float>()

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
        addView(hub) // on top
    }

    fun setApps(list: List<AppEntry>, resetPage: Boolean) {
        apps = list
        if (resetPage || page >= pages) page = 0
        bind()
    }

    /** tiltX / tiltY in degrees from the 3D stage. */
    fun setDepth(tiltX: Float, tiltY: Float) {
        depthX = (tiltY / 55f) * 9f * d
        depthY = (-tiltX / 55f) * 9f * d
        positionItems()
        invalidate()
    }

    /**
     * Turns a released drag (position + velocity in px/ms) into a spinning roll:
     * the tangential part of the flick around the centre becomes angular momentum.
     */
    fun rollFrom(x: Float, y: Float, vx: Float, vy: Float) {
        val rx = x - cx
        val ry = y - cy
        val r2 = (rx * rx + ry * ry).coerceAtLeast((90f * d) * (90f * d))
        val omegaRad = (rx * vy - ry * vx) / r2
        spinner.flingWith(Math.toDegrees(omegaRad.toDouble()).toFloat() * 0.9f)
    }

    fun next() = goTo(page + 1, 1)
    fun prev() = goTo(page - 1, -1)

    private fun goTo(target: Int, dir: Int) {
        if (pages <= 1) return
        if (haptics) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        val t = (target + pages) % pages
        anim?.cancel()
        val out = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                pageSpin = -dir * 45f * f
                itemsAlpha = 1f - f
                applyAnim()
            }
        }
        out.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                if (anim !== out) return
                page = t
                bind()
                val inn = ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = 300
                    interpolator = DecelerateInterpolator(2.2f)
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        pageSpin = dir * 45f * f
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
        val s = 0.85f + 0.15f * itemsAlpha
        slots.forEach {
            it.alpha = itemsAlpha
            it.scaleX = s
            it.scaleY = s
        }
        positionItems()
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
                slot.setOnLongClickListener {
                    if (haptics) it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onAppLongClick(it, app); true
                }
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
        invalidate()
    }

    // ---------- layout ----------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)

        // designed for a 430dp circle; scale to fit
        val newScale = (min(w, h) / (430f * d)).coerceIn(0.55f, 1.3f)
        if (newScale != scale) {
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
        val ws = MeasureSpec.makeMeasureSpec(itemW, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(itemH, MeasureSpec.EXACTLY)
        slots.forEach { it.measure(ws, hs) }
        val hub2 = MeasureSpec.makeMeasureSpec((hubR * 2).toInt(), MeasureSpec.EXACTLY)
        hub.measure(hub2, hub2)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        cx = (right - left) / 2f
        cy = (bottom - top) / 2f
        // every item sits at the origin; positionItems() moves it with translations
        slots.forEach { it.layout(0, 0, itemW, itemH) }
        val hs = (hubR * 2).toInt()
        val hx = (cx - hubR).toInt()
        val hy = (cy - hubR).toInt()
        hub.layout(hx, hy, hx + hs, hy + hs)
        positionItems()
    }

    private fun layerX(depth: Int) = cx + depthX * depth
    private fun layerY(depth: Int) = cy + depthY * depth

    private fun positionItems() {
        if (itemW == 0) return
        var idx = 0
        rings.forEachIndexed { r, n ->
            val dir = if (r % 2 == 0) 1f else -1f
            val offset = if (r % 2 == 0) 0.0 else 0.5
            // outer rings turn a little faster than inner ones for a parallax feel
            val user = spinner.angle * (0.8f + 0.1f * r)
            val lx = layerX(2 - r)
            val ly = layerY(2 - r)
            for (k in 0 until n) {
                val slot = slots[idx++]
                val a = Math.toRadians(-90.0 + (k + offset) * 360.0 / n + pageSpin * dir + user)
                slot.translationX = (lx + radii[r] * cos(a)).toFloat() - itemW / 2f
                // centre the icon (not the label) on the ring
                slot.translationY = (ly + radii[r] * sin(a)).toFloat() - iconPx / 2f
            }
        }
        hub.translationX = depthX * 3
        hub.translationY = depthY * 3
    }

    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(canvas: Canvas) {
        val outer = radii[2] + 34f * d * scale
        if (outer <= 0f) return

        val key = (cx.toLong() shl 40) xor (cy.toLong() shl 20) xor outer.toLong() xor accent.toLong()
        if (glowShader == null || key != glowKey) {
            glowKey = key
            glowShader = RadialGradient(0f, 0f, outer, alpha(accent, 55), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.save()
        canvas.translate(layerX(0), layerY(0))
        fill.shader = glowShader
        canvas.drawCircle(0f, 0f, outer, fill)
        fill.shader = null
        canvas.restore()

        // orbit tracks, each on its own depth layer
        stroke.pathEffect = null
        stroke.strokeWidth = 1f * d
        stroke.color = Color.argb(28, 255, 255, 255)
        for (r in radii.indices) canvas.drawCircle(layerX(2 - r), layerY(2 - r), radii[r], stroke)

        // outer rotating ticks
        val ox = layerX(0)
        val oy = layerY(0)
        canvas.save()
        canvas.rotate(phase + spinner.angle, ox, oy)
        for (i in 0 until 120) {
            val major = i % 10 == 0
            stroke.color = if (major) alpha(accent, 220) else Color.argb(45, 255, 255, 255)
            stroke.strokeWidth = (if (major) 2f else 1f) * d
            val len = (if (major) 8f else 3f) * d
            canvas.drawLine(ox, oy - outer, ox, oy - outer + len, stroke)
            canvas.rotate(3f, ox, oy)
        }
        canvas.restore()

        // dashed ring around the hub, counter-rotating
        val hx = layerX(3)
        val hy = layerY(3)
        canvas.save()
        canvas.rotate(-phase * 2f, hx, hy)
        stroke.pathEffect = dash
        stroke.strokeWidth = 1.5f * d
        stroke.color = alpha(accent, 140)
        canvas.drawCircle(hx, hy, hubR + 8f * d, stroke)
        stroke.pathEffect = null
        canvas.restore()

        // page progress arc around the hub
        stroke.strokeWidth = 3f * d
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = Color.argb(35, 255, 255, 255)
        val rr = hubR + 2.5f * d
        canvas.drawCircle(hx, hy, rr, stroke)
        stroke.color = accent
        val sweep = 360f / pages
        canvas.drawArc(hx - rr, hy - rr, hx + rr, hy + rr, -90f + page * sweep, sweep, false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    // ---------- touch ----------
    // One finger, one gesture: while moving, the rings spin with the finger and (if
    // [tiltOnDrag]) the stage tilts in 3D. On release: a curved path around the centre
    // keeps rolling; any other swipe (straight, any direction) changes page.
    // Tracking uses SCREEN coordinates so the 3D tilt can't distort the finger path.

    /** Tilt the 3D stage while dragging. */
    var tiltOnDrag = true
    var onDragTilt: (dx: Float, dy: Float) -> Unit = { _, _ -> }
    var onDragEnd: () -> Unit = {}
    /** Vibration on page change / long-press (spin ticks are [Spinner.haptics]). */
    var haptics = true

    private val loc = IntArray(2)
    private var ocx = 0f            // orbit centre on screen
    private var ocy = 0f
    private var rawDownX = 0f
    private var rawDownY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var active = false      // finger moved past the touch slop

    private fun overHub(x: Float, y: Float) = hypot(x - cx, y - cy) < hubR

    private fun record(ev: MotionEvent) {
        if (path.size < 600) { path.add(ev.rawX); path.add(ev.rawY) }
    }

    private fun beginTrack(ev: MotionEvent) {
        val p = parent as? View ?: this
        p.getLocationOnScreen(loc)
        ocx = loc[0] + cx
        ocy = loc[1] + cy
        rawDownX = ev.rawX
        rawDownY = ev.rawY
        lastRawX = ev.rawX
        lastRawY = ev.rawY
        path.clear()
        record(ev)
        spinner.minRadius = hubR * 1.4f
        spinner.onDown(ev.rawX, ev.rawY, ev.eventTime, ocx, ocy)
        active = false
        spinTracking = true
    }

    private fun maybeStart(ev: MotionEvent): Boolean {
        if (!active && hypot(ev.rawX - rawDownX, ev.rawY - rawDownY) > slop) {
            active = true
            lastRawX = ev.rawX
            lastRawY = ev.rawY
        }
        if (active && spinner.enabled) spinner.checkStart(ev.rawX, ev.rawY, ev.eventTime, ocx, ocy)
        return active
    }

    /**
     * 1 = next page, -1 = previous page, 0 = it was a spin (or too short).
     * A page swipe is long and nearly straight in any direction; a spin curves around the orbit.
     */
    private fun pageSwipe(): Int {
        if (path.size < 4) return 0
        val x0 = path[0]
        val y0 = path[1]
        val dx = path[path.size - 2] - x0
        val dy = path[path.size - 1] - y0
        val len = hypot(dx, dy)
        if (len < min(width, height) * 0.22f) return 0
        var maxDev = 0f
        var i = 2
        while (i < path.size) {
            val dev = abs((path[i] - x0) * dy - (path[i + 1] - y0) * dx) / len
            if (dev > maxDev) maxDev = dev
            i += 2
        }
        if (maxDev > len * 0.12f) return 0
        return if (abs(dx) >= abs(dy)) { if (dx < 0) 1 else -1 } else { if (dy < 0) 1 else -1 }
    }

    private fun endTrack() {
        if (active) onDragEnd()
        active = false
        spinTracking = false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!overHub(ev.x, ev.y)) beginTrack(ev) else spinTracking = false
            MotionEvent.ACTION_MOVE -> if (spinTracking && ev.pointerCount == 1) {
                record(ev)
                return maybeStart(ev)
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginTrack(ev)
            MotionEvent.ACTION_MOVE -> if (spinTracking && ev.pointerCount == 1) {
                record(ev)
                if (maybeStart(ev)) {
                    if (spinner.enabled) spinner.onMove(ev.rawX, ev.rawY, ev.eventTime, ocx, ocy)
                    if (tiltOnDrag) onDragTilt(ev.rawX - lastRawX, ev.rawY - lastRawY)
                    lastRawX = ev.rawX
                    lastRawY = ev.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                if (spinTracking && active) {
                    record(ev)
                    when (pageSwipe()) {
                        1 -> { spinner.endWithoutFling(); next() }
                        -1 -> { spinner.endWithoutFling(); prev() }
                        else -> if (spinner.enabled) spinner.onUp() else spinner.endWithoutFling()
                    }
                }
                endTrack()
            }
            MotionEvent.ACTION_CANCEL -> {
                // usually a second finger: the 3D stage takes over for pinch
                spinner.endWithoutFling()
                endTrack()
            }
        }
        return true
    }

    // ---------- lifecycle ----------

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
