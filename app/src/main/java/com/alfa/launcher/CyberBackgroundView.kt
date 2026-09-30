package com.alfa.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.abs
import kotlin.random.Random

/**
 * Animated HUD backdrop: faint grid, sparse "code rain" columns,
 * a sweeping scanline and corner brackets.
 */
class CyberBackgroundView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val bgColor = Color.rgb(5, 7, 13)
    private val chars = "01ABCDEF<>/\\{}[]#$%&*=+".toCharArray()

    private val gridStep = dp(32f)
    private val gridPaint = Paint().apply {
        color = Color.argb(16, 0, 240, 255)
        strokeWidth = 1f
    }
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 240, 255)
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }
    private val scanPaint = Paint()

    private val charH = rainPaint.textSize * 1.25f
    private val colW = rainPaint.textSize * 2.2f
    private val trail = 10
    private val scanH = dp(160f)

    private var cols = 0
    private var drops = FloatArray(0)
    private var speeds = FloatArray(0)
    private var scanY = 0f
    private var running = false

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            invalidate()
            postDelayed(this, 50)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cols = (w / colW).toInt() + 1
        drops = FloatArray(cols) { Random.nextFloat() * h * 1.5f - h * 0.5f }
        speeds = FloatArray(cols) { dp(1.5f) + Random.nextFloat() * dp(4f) }
        scanPaint.shader = LinearGradient(
            0f, 0f, 0f, scanH,
            intArrayOf(Color.TRANSPARENT, Color.argb(34, 0, 240, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        scanY = -scanH
    }

    private fun step() {
        val h = height.toFloat()
        for (i in 0 until cols) {
            drops[i] += speeds[i]
            if (drops[i] - trail * charH > h) {
                drops[i] = -Random.nextFloat() * h * 0.6f
                speeds[i] = dp(1.5f) + Random.nextFloat() * dp(4f)
            }
        }
        scanY += dp(3f)
        if (scanY > h) scanY = -scanH
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(bgColor)

        var x = 0f
        while (x < w) { canvas.drawLine(x, 0f, x, h, gridPaint); x += gridStep }
        var y = 0f
        while (y < h) { canvas.drawLine(0f, y, w, y, gridPaint); y += gridStep }

        for (i in 0 until cols) {
            val cx = i * colW + colW * 0.3f
            val head = drops[i]
            val headRow = (head / charH).toInt()
            for (t in 0 until trail) {
                val cy = head - t * charH
                if (cy < 0f || cy > h + charH) continue
                if (t == 0) {
                    rainPaint.color = Color.argb(90, 190, 255, 210)
                } else {
                    rainPaint.color = Color.argb(48 * (trail - t) / trail, 57, 255, 20)
                }
                val idx = abs(i * 31 + (headRow - t) * 17) % chars.size
                canvas.drawText(chars, idx, 1, cx, cy, rainPaint)
            }
        }

        canvas.save()
        canvas.translate(0f, scanY)
        canvas.drawRect(0f, 0f, w, scanH, scanPaint)
        canvas.restore()

        val inset = dp(6f)
        val len = dp(18f)
        canvas.drawLine(inset, inset, inset + len, inset, cornerPaint)
        canvas.drawLine(inset, inset, inset, inset + len, cornerPaint)
        canvas.drawLine(w - inset, inset, w - inset - len, inset, cornerPaint)
        canvas.drawLine(w - inset, inset, w - inset, inset + len, cornerPaint)
        canvas.drawLine(inset, h - inset, inset + len, h - inset, cornerPaint)
        canvas.drawLine(inset, h - inset, inset, h - inset - len, cornerPaint)
        canvas.drawLine(w - inset, h - inset, w - inset - len, h - inset, cornerPaint)
        canvas.drawLine(w - inset, h - inset, w - inset, h - inset - len, cornerPaint)
    }

    fun start() {
        if (running) return
        running = true
        post(frame)
    }

    fun stop() {
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
}
