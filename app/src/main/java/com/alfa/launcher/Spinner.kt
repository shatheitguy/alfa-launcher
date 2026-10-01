package com.alfa.launcher

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Turns a circular drag into a rotation angle with fling momentum.
 *
 * Smoothness: touch input only moves a *target* angle. A single frame-synced loop
 * (postOnAnimation, i.e. the display's vsync) eases the displayed [angle] toward the
 * target with exponential smoothing, and integrates fling momentum. That removes
 * touch jitter and the mismatch between touch rate and refresh rate.
 */
class Spinner(private val host: View, private val onSpin: (Float) -> Unit) {

    var enabled = true
    var haptics = true
    var tickDeg = 30f
    /** Displayed angle (what the host draws). */
    var angle = 0f
        private set
    var dragging = false
        private set
    /** Ignore rotation while the finger is this close to the centre (angles jump wildly there). */
    var minRadius = 0f

    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private var target = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastA = 0f
    private var lastT = 0L
    private var velocity = 0f        // degrees per ms
    private var flinging = false
    private var looping = false
    private var lastFrame = 0L
    private var lastTick = 0

    /** Smoothing time constant in ms: lower = snappier, higher = silkier. */
    private val followMs = 26f

    private val loop = object : Runnable {
        override fun run() {
            val now = System.nanoTime() / 1_000_000L
            val dt = (now - lastFrame).coerceIn(1L, 40L).toFloat()
            lastFrame = now

            if (flinging) {
                target += velocity * dt
                velocity *= Math.pow(0.9968, dt.toDouble()).toFloat()
                if (abs(velocity) < 0.004f) flinging = false
            }

            val diff = target - angle
            if (!flinging && !dragging && abs(diff) < 0.02f) {
                angle = target
                publish()
                looping = false
                return
            }
            angle += diff * (1f - exp(-dt / followMs))
            publish()
            host.postOnAnimation(this)
        }
    }

    private fun publish() {
        // keep numbers small without visible jumps
        if (abs(angle) > 3600f) {
            val k = (angle / 360f).toInt() * 360f
            angle -= k
            target -= k
        }
        val t = floor(angle / tickDeg).toInt()
        if (t != lastTick) {
            lastTick = t
            if (haptics && (dragging || flinging)) host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        onSpin(angle)
    }

    private fun ensureLoop() {
        if (looping) return
        looping = true
        lastFrame = System.nanoTime() / 1_000_000L
        host.postOnAnimation(loop)
    }

    private fun angleAt(x: Float, y: Float, cx: Float, cy: Float) =
        Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()

    /** Rotate by [deg] (eased). */
    fun add(deg: Float) {
        target += deg
        ensureLoop()
    }

    /** Stop any momentum and hold where it is now. */
    fun stop() {
        flinging = false
        velocity = 0f
        target = angle
    }

    // ----- coordinate-based API -----

    fun onDown(x: Float, y: Float, time: Long, cx: Float, cy: Float) {
        stop()
        downX = x
        downY = y
        lastA = angleAt(x, y, cx, cy)
        lastT = time
        dragging = false
    }

    /** True once the finger has moved far enough to start spinning. */
    fun checkStart(x: Float, y: Float, time: Long, cx: Float, cy: Float): Boolean {
        if (!enabled || dragging) return dragging
        if (hypot(x - downX, y - downY) > slop) {
            dragging = true
            lastA = angleAt(x, y, cx, cy)
            lastT = time
            ensureLoop()
        }
        return dragging
    }

    fun onMove(x: Float, y: Float, time: Long, cx: Float, cy: Float) {
        if (!dragging) return
        val a = angleAt(x, y, cx, cy)
        var da = a - lastA
        if (da > 180f) da -= 360f
        if (da < -180f) da += 360f
        lastA = a
        if (hypot(x - cx, y - cy) < minRadius) {
            lastT = time
            velocity *= 0.5f
            return
        }
        val dt = (time - lastT).coerceAtLeast(1L)
        lastT = time
        // velocity from the last few events, lightly smoothed
        velocity = 0.7f * velocity + 0.3f * (da / dt)
        target += da
        ensureLoop()
    }

    // ----- MotionEvent conveniences (view-local coordinates) -----

    fun onDown(ev: MotionEvent, cx: Float, cy: Float) = onDown(ev.x, ev.y, ev.eventTime, cx, cy)
    fun checkStart(ev: MotionEvent, cx: Float, cy: Float) = checkStart(ev.x, ev.y, ev.eventTime, cx, cy)
    fun onMove(ev: MotionEvent, cx: Float, cy: Float) = onMove(ev.x, ev.y, ev.eventTime, cx, cy)

    /** Start a momentum spin from outside. [degPerMs] signed. */
    fun flingWith(degPerMs: Float) {
        if (abs(degPerMs) < 0.02f) return
        velocity = degPerMs.coerceIn(-2.5f, 2.5f)
        flinging = true
        ensureLoop()
    }

    /** End the drag without momentum (page swipe, or a second finger took over). */
    fun endWithoutFling() {
        dragging = false
        velocity = 0f
        ensureLoop() // let the display settle on the target
    }

    fun onUp() {
        // a finger that paused before lifting shouldn't fling
        val idle = android.os.SystemClock.uptimeMillis() - lastT   // MotionEvent times use uptimeMillis
        if (dragging && abs(velocity) > 0.03f && idle < 80) {
            velocity = velocity.coerceIn(-2.5f, 2.5f)
            flinging = true
        }
        dragging = false
        ensureLoop()
    }
}
