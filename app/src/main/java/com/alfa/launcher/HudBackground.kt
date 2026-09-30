package com.alfa.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** Static dark carbon-fibre backdrop with soft accent glows and a thin HUD frame. */
class HudBackground(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var accent: Int = Color.rgb(255, 45, 61)
        set(v) { field = v; rebuild(); invalidate() }

    private val d = resources.displayMetrics.density
    private val base = Paint()
    private val carbon = Paint()
    private val glowTop = Paint()
    private val glowBottom = Paint()
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }

    init {
        carbon.shader = BitmapShader(carbonTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun carbonTile(): Bitmap {
        val s = (4 * d).toInt().coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.color = Color.argb(28, 255, 255, 255); c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        c.drawRect(s.toFloat(), s.toFloat(), 2f * s, 2f * s, p)
        p.color = Color.argb(10, 255, 255, 255); c.drawRect(s.toFloat(), 0f, 2f * s, s.toFloat(), p)
        c.drawRect(0f, s.toFloat(), s.toFloat(), 2f * s, p)
        return bmp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    private fun rebuild() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        base.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(16, 16, 20), Color.rgb(4, 4, 6), Shader.TileMode.CLAMP)
        val a = Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent))
        glowTop.shader = RadialGradient(w * 0.85f, h * 0.12f, w * 0.8f, a, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        glowBottom.shader = RadialGradient(w * 0.1f, h * 0.9f, w * 0.9f, a, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        frame.color = Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, base)
        canvas.drawRect(0f, 0f, w, h, carbon)
        canvas.drawRect(0f, 0f, w, h, glowTop)
        canvas.drawRect(0f, 0f, w, h, glowBottom)

        // corner brackets
        val i = 10 * d
        val l = 26 * d
        canvas.drawLine(i, i + l, i, i, frame); canvas.drawLine(i, i, i + l, i, frame)
        canvas.drawLine(w - i - l, i, w - i, i, frame); canvas.drawLine(w - i, i, w - i, i + l, frame)
        canvas.drawLine(i, h - i - l, i, h - i, frame); canvas.drawLine(i, h - i, i + l, h - i, frame)
        canvas.drawLine(w - i - l, h - i, w - i, h - i, frame); canvas.drawLine(w - i, h - i, w - i, h - i - l, frame)
    }
}
