package com.alfa.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * 3D stage around the all-apps orbit. One finger goes to the orbit (spin / page);
 * two fingers here: pinch = zoom, move together = tilt the orbit in 3D.
 */
class OrbitStage(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    var onTransform: (zoom: Float, tiltX: Float, tiltY: Float) -> Unit = { _, _, _ -> }

    var zoom = 1f
        private set
    var tiltX = 0f
        private set
    var tiltY = 0f
        private set

    private val d = resources.displayMetrics.density
    private var multi = false
    private var lastCx = 0f
    private var lastCy = 0f
    private var startSpan = 1f
    private var startZoom = 1f

    private val target: View? get() = if (childCount > 0) getChildAt(0) else null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2) {
            begin(ev)
            return true
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> if (ev.pointerCount >= 2) {
                if (!multi) begin(ev)
                val (x, y) = centroid(ev, -1)
                tiltY = (tiltY + (x - lastCx) * 0.20f).coerceIn(-55f, 55f)
                tiltX = (tiltX - (y - lastCy) * 0.20f).coerceIn(-55f, 55f)
                lastCx = x
                lastCy = y
                val span = span(ev, -1)
                if (startSpan > 10f * d) zoom = (startZoom * span / startSpan).coerceIn(0.6f, 2.4f)
                apply()
            }
            MotionEvent.ACTION_POINTER_UP -> multi = false // re-anchor on the next move
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> multi = false
        }
        return true
    }

    private fun begin(ev: MotionEvent) {
        val (x, y) = centroid(ev, -1)
        lastCx = x
        lastCy = y
        startSpan = span(ev, -1)
        startZoom = zoom
        multi = true
    }

    private fun centroid(ev: MotionEvent, skip: Int): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until ev.pointerCount) {
            if (i == skip) continue
            sx += ev.getX(i); sy += ev.getY(i); n++
        }
        return if (n == 0) Pair(0f, 0f) else Pair(sx / n, sy / n)
    }

    private fun span(ev: MotionEvent, skip: Int): Float {
        val (cx, cy) = centroid(ev, skip)
        var sum = 0f
        var n = 0
        for (i in 0 until ev.pointerCount) {
            if (i == skip) continue
            sum += hypot(ev.getX(i) - cx, ev.getY(i) - cy); n++
        }
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

    val isTransformed get() = zoom != 1f || tiltX != 0f || tiltY != 0f

    fun reset(animate: Boolean) {
        val t = target ?: return
        zoom = 1f; tiltX = 0f; tiltY = 0f
        if (animate) {
            t.animate().scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f)
                .setDuration(380).setInterpolator(DecelerateInterpolator(2f))
                .setUpdateListener { onTransform(t.scaleX, t.rotationX, t.rotationY) }
                .start()
        } else {
            t.animate().cancel()
            t.scaleX = 1f; t.scaleY = 1f; t.rotationX = 0f; t.rotationY = 0f
            onTransform(1f, 0f, 0f)
        }
    }
}
