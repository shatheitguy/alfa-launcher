package com.alfa.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Root of the home screen. Swipe up (anywhere) drags the app drawer in,
 * swipe down on home pulls the notification shade, swipe down on the drawer
 * (when its list is at the top) closes it. Long-press on empty space is reported.
 */
class SwipeLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    interface Listener {
        fun onDrawerOpened()
        fun onDrawerClosed()
        fun onPullDown()
        fun onLongPress(x: Float, y: Float)
    }

    var listener: Listener? = null
    var home: View? = null
    var drawer: View? = null
    var canDrawerScrollUp: () -> Boolean = { false }
    var isOpen = false
        private set

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val fling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 6
    private var downX = 0f
    private var downY = 0f
    private var startT = 0f
    private var mode = NONE
    private var tracker: VelocityTracker? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) {
            if (!isOpen && mode == NONE) listener?.onLongPress(e.x, e.y)
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        drawer?.let {
            it.animate().cancel()
            it.translationY = if (isOpen) 0f else h.toFloat()
        }
        updateProgress()
    }

    private fun begin(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        mode = NONE
        tracker?.recycle()
        tracker = VelocityTracker.obtain()
        tracker?.addMovement(ev)
    }

    /** Decides whether the current move becomes one of our gestures. */
    private fun decide(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dy) < slop && abs(dx) < slop) return false
        if (abs(dy) < abs(dx) * 1.2f) {
            mode = IGNORE
            return false
        }
        mode = when {
            !isOpen && dy < 0 -> DRAG
            !isOpen && dy > 0 -> PULL
            isOpen && dy > 0 && !canDrawerScrollUp() -> DRAG
            else -> IGNORE
        }
        if (mode == DRAG) {
            drawer?.let {
                it.animate().cancel()
                it.visibility = View.VISIBLE
                startT = it.translationY
            }
            downY = ev.y
        }
        return mode == DRAG || mode == PULL
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (mode == NONE) return decide(ev)
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (mode == NONE) decide(ev)
                if (mode == DRAG) {
                    val d = drawer ?: return true
                    d.translationY = (startT + ev.y - downY).coerceIn(0f, height.toFloat())
                    updateProgress()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(ev)
                if (mode == DRAG) {
                    val t = tracker
                    t?.computeCurrentVelocity(1000)
                    val vy = t?.yVelocity ?: 0f
                    val shouldOpen = when {
                        vy < -fling -> true
                        vy > fling -> false
                        else -> (drawer?.translationY ?: 0f) < height / 2f
                    }
                    if (shouldOpen) open() else close()
                } else if (mode == PULL && ev.actionMasked == MotionEvent.ACTION_UP) {
                    listener?.onPullDown()
                }
                mode = NONE
            }
        }
        return true
    }

    fun open() {
        val d = drawer ?: return
        d.visibility = View.VISIBLE
        d.animate().translationY(0f).setDuration(260)
            .setInterpolator(DecelerateInterpolator(2f))
            .setUpdateListener { updateProgress() }
            .start()
        if (!isOpen) {
            isOpen = true
            listener?.onDrawerOpened()
        }
    }

    fun close(animate: Boolean = true) {
        val d = drawer ?: return
        if (animate) {
            d.animate().translationY(height.toFloat()).setDuration(240)
                .setInterpolator(DecelerateInterpolator(2f))
                .setUpdateListener { updateProgress() }
                .withEndAction { if (!isOpen) d.visibility = View.INVISIBLE }
                .start()
        } else {
            d.animate().cancel()
            d.translationY = height.toFloat()
            d.visibility = View.INVISIBLE
            updateProgress()
        }
        if (isOpen) {
            isOpen = false
            listener?.onDrawerClosed()
        }
    }

    private fun updateProgress() {
        val d = drawer ?: return
        val h = home ?: return
        if (height == 0) return
        val frac = (d.translationY / height).coerceIn(0f, 1f) // 1 = closed
        h.alpha = 0.15f + 0.85f * frac
        val s = 0.94f + 0.06f * frac
        h.scaleX = s
        h.scaleY = s
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val PULL = 2
        private const val IGNORE = 3
    }
}
