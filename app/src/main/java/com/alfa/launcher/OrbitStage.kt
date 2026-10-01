package com.alfa.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 3D stage around the all-apps orbit.
 * - Two fingers: pinch = zoom, move together = tilt.
 * - One finger (when [oneFingerTilt]): drag = tilt the orbit in 3D like a trackball,
 *   with momentum. Taps still reach the apps underneath.
 */
class OrbitStage(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    var onTransform: (zoom: Float, tiltX: Float, tiltY: Float) -> Unit = { _, _, _ -> }

    /** When true, a one-finger drag tilts in 3D instead of reaching the orbit (spin / page swipe). */
    var oneFingerTilt = true

    /** Tilt only lasts while a finger is down; on release it springs back flat. */
    var springBack = true

    /** Called when a one-finger 3D drag is released: position and velocity (px/ms) in stage coordinates. */
    var onRelease: (x: Float, y: Float, vx: Float, vy: Float) -> Unit = { _, _, _, _ -> }

    private var spring: android.animation.ValueAnimator? = null

    private fun springFlat() {
        if (tiltX == 0f && tiltY == 0f) return
        spring?.cancel()
        val fromX = tiltX
        val fromY = tiltY
        spring = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 560
            interpolator = android.view.animation.OvershootInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                tiltX = fromX * f
                tiltY = fromY * f
                apply()
            }
            start()
        }
    }

    var zoom = 1f
        private set
    var tiltX = 0f
        private set
    var tiltY = 0f
        private set

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxTilt = 60f
    private val degPerPx = 0.22f

    private var multi = false
    private var single = false
    private var reanchor = false
    private var downX = 0f
    private var downY = 0f
    private var lastCx = 0f
    private var lastCy = 0f
    private var startSpan = 1f
    private var startZoom = 1f
    private var tracker: VelocityTracker? = null

    // tilt momentum (degrees per ms)
    private var vx = 0f
    private var vy = 0f
    private var flinging = false
    private var lastFrame = 0L
    private val fling = object : Runnable {
        override fun run() {
            if (!flinging) return
            val now = System.nanoTime() / 1_000_000L
            val dt = (now - lastFrame).coerceIn(1L, 48L)
            lastFrame = now
            tiltY = (tiltY + vx * dt).coerceIn(-maxTilt, maxTilt)
            tiltX = (tiltX + vy * dt).coerceIn(-maxTilt, maxTilt)
            if (abs(tiltY) >= maxTilt) vx = 0f
            if (abs(tiltX) >= maxTilt) vy = 0f
            val decay = Math.pow(0.994, dt.toDouble()).toFloat()
            vx *= decay
            vy *= decay
            apply()
            if (abs(vx) < 0.002f && abs(vy) < 0.002f) flinging = false else postOnAnimation(this)
        }
    }

    private val target: View? get() = if (childCount > 0) getChildAt(0) else null

    private fun stopFling() {
        flinging = false
        removeCallbacks(fling)
        spring?.cancel()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                downX = ev.x
                downY = ev.y
                single = false
                multi = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (ev.pointerCount == 1 && oneFingerTilt && hypot(ev.x - downX, ev.y - downY) > slop) {
                    single = true
                    lastCx = ev.x
                    lastCy = ev.y
                    return true
                }
            }
        }
        if (ev.pointerCount >= 2) {
            begin(ev)
            return true
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        tracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                downX = ev.x
                downY = ev.y
                lastCx = ev.x
                lastCy = ev.y
                single = false
                if (tracker == null) tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount >= 2) {
                    if (!multi) begin(ev)
                    val (x, y) = centroid(ev)
                    tilt(x - lastCx, y - lastCy)
                    lastCx = x
                    lastCy = y
                    val span = span(ev)
                    if (startSpan > 10f * d) zoom = (startZoom * span / startSpan).coerceIn(0.6f, 2.4f)
                    apply()
                } else if (oneFingerTilt) {
                    if (reanchor) {
                        lastCx = ev.x; lastCy = ev.y; reanchor = false; single = true
                    }
                    if (!single && hypot(ev.x - downX, ev.y - downY) > slop) {
                        single = true
                        lastCx = ev.x
                        lastCy = ev.y
                    }
                    if (single) {
                        tilt(ev.x - lastCx, ev.y - lastCy)
                        lastCx = ev.x
                        lastCy = ev.y
                        apply()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                multi = false
                reanchor = true
            }
            MotionEvent.ACTION_UP -> {
                if (single && oneFingerTilt) {
                    // let go: the flick becomes a spinning roll, and the tilt springs back flat
                    val t = tracker
                    t?.computeCurrentVelocity(1) // px per ms
                    onRelease(ev.x, ev.y, t?.xVelocity ?: 0f, t?.yVelocity ?: 0f)
                }
                if (springBack) springFlat()
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (springBack) springFlat()
                endGesture()
            }
        }
        return true
    }

    private fun endGesture() {
        multi = false
        single = false
        reanchor = false
        tracker?.recycle()
        tracker = null
    }

    private fun tilt(dx: Float, dy: Float) {
        tiltY = (tiltY + dx * degPerPx).coerceIn(-maxTilt, maxTilt)
        tiltX = (tiltX - dy * degPerPx).coerceIn(-maxTilt, maxTilt)
    }

    private fun begin(ev: MotionEvent) {
        val (x, y) = centroid(ev)
        lastCx = x
        lastCy = y
        startSpan = span(ev)
        startZoom = zoom
        multi = true
        single = false
    }

    private fun centroid(ev: MotionEvent): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        val n = ev.pointerCount
        for (i in 0 until n) { sx += ev.getX(i); sy += ev.getY(i) }
        return Pair(sx / n, sy / n)
    }

    private fun span(ev: MotionEvent): Float {
        val (cx, cy) = centroid(ev)
        var sum = 0f
        val n = ev.pointerCount
        for (i in 0 until n) sum += hypot(ev.getX(i) - cx, ev.getY(i) - cy)
        return if (n == 0) 1f else sum / n
    }

    private fun apply() {
        val t = target ?: return
        t.cameraDistance = 9000f * d
        t.scaleX = zoom
        t.scaleY = zoom
        t.rotationX = tiltX
        t.rotationY = tiltY
        onTransform(zoom, tiltX, tiltY)
    }

    // eased live tilt: drags move a goal, a vsync loop glides the shown tilt toward it
    private var goalX = 0f
    private var goalY = 0f
    private var easing = false
    private var easeFrame = 0L
    private val ease = object : Runnable {
        override fun run() {
            if (!easing) return
            val now = System.nanoTime() / 1_000_000L
            val dt = (now - easeFrame).coerceIn(1L, 40L).toFloat()
            easeFrame = now
            val k = 1f - kotlin.math.exp(-dt / 45f)
            tiltX += (goalX - tiltX) * k
            tiltY += (goalY - tiltY) * k
            apply()
            if (abs(goalX - tiltX) < 0.05f && abs(goalY - tiltY) < 0.05f) easing = false
            else postOnAnimation(this)
        }
    }

    /** Live tilt driven by the orbit's own one-finger drag (screen-space deltas in px). */
    fun tiltBy(dx: Float, dy: Float) {
        spring?.cancel()
        if (!easing) { goalX = tiltX; goalY = tiltY }
        goalY = (goalY + dx * degPerPx).coerceIn(-maxTilt, maxTilt)
        goalX = (goalX - dy * degPerPx).coerceIn(-maxTilt, maxTilt)
        if (!easing) {
            easing = true
            easeFrame = System.nanoTime() / 1_000_000L
            postOnAnimation(ease)
        }
    }

    /** Finger lifted: spring back flat from wherever the tilt is now. */
    fun release() {
        easing = false
        removeCallbacks(ease)
        if (springBack) springFlat()
    }

    val isTransformed get() = zoom != 1f || tiltX != 0f || tiltY != 0f

    fun reset(animate: Boolean) {
        stopFling()
        val t = target ?: return
        zoom = 1f; tiltX = 0f; tiltY = 0f
        if (animate) {
            t.animate().scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f)
                .setDuration(420).setInterpolator(DecelerateInterpolator(2f))
                .setUpdateListener { onTransform(t.scaleX, t.rotationX, t.rotationY) }
                .start()
        } else {
            t.animate().cancel()
            t.scaleX = 1f; t.scaleY = 1f; t.rotationX = 0f; t.rotationY = 0f
            onTransform(1f, 0f, 0f)
        }
    }

    override fun onDetachedFromWindow() {
        stopFling()
        super.onDetachedFromWindow()
    }
}
