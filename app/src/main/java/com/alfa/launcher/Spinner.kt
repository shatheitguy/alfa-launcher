package com.alfa.launcher

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Turns a circular drag into a rotation angle, with fling momentum.
 * The host view feeds it touch events and redraws in [onSpin].
 */
class Spinner(private val host: View, private val onSpin: (Float) -> Unit) {

    var enabled = true
    var angle = 0f
        private set
    var dragging = false
        private set

    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastA = 0f
    private var lastT = 0L
    private var velocity = 0f        // degrees per ms
    private var flinging = false
    private var lastFrame = 0L

    private val fling = object : Runnable {
        override fun run() {
            if (!flinging) return
            val now = System.nanoTime() / 1_000_000L
            val dt = (now - lastFrame).coerceIn(1L, 48L)
            lastFrame = now
            add(velocity * dt)
            velocity *= Math.pow(0.9965, dt.toDouble()).toFloat()
            if (abs(velocity) < 0.004f) flinging = false else host.postOnAnimation(this)
        }
    }

    private fun angleAt(x: Float, y: Float, cx: Float, cy: Float) =
        Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()

    fun add(deg: Float) {
        angle = (angle + deg) % 360f
        onSpin(angle)
    }

    fun stop() {
        flinging = false
        host.removeCallbacks(fling)
    }

    fun onDown(ev: MotionEvent, cx: Float, cy: Float) {
        stop()
        downX = ev.x
        downY = ev.y
        lastA = angleAt(ev.x, ev.y, cx, cy)
        lastT = ev.eventTime
        velocity = 0f
        dragging = false
    }

    /** True once the finger has moved far enough to start spinning. */
    fun checkStart(ev: MotionEvent, cx: Float, cy: Float): Boolean {
        if (!enabled || dragging) return dragging
        if (hypot(ev.x - downX, ev.y - downY) > slop) {
            dragging = true
            lastA = angleAt(ev.x, ev.y, cx, cy)
            lastT = ev.eventTime
        }
        return dragging
    }

    fun onMove(ev: MotionEvent, cx: Float, cy: Float) {
        if (!dragging) return
        val a = angleAt(ev.x, ev.y, cx, cy)
        var da = a - lastA
        if (da > 180f) da -= 360f
        if (da < -180f) da += 360f
        val dt = (ev.eventTime - lastT).coerceAtLeast(1L)
        velocity = 0.6f * velocity + 0.4f * (da / dt)
        lastA = a
        lastT = ev.eventTime
        add(da)
    }

    /** End the drag without momentum (e.g. the gesture turned out to be a page swipe). */
    fun endWithoutFling() {
        dragging = false
        velocity = 0f
    }

    fun onUp() {
        if (dragging && abs(velocity) > 0.03f) {
            velocity = velocity.coerceIn(-2.5f, 2.5f)
            flinging = true
            lastFrame = System.nanoTime() / 1_000_000L
            host.postOnAnimation(fling)
        }
        dragging = false
    }
}
