package com.alfa.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ALFA's procedural backgrounds, tinted with the accent colour.
 * Used live behind ALFA screens and rendered off-screen as the system wallpaper.
 */
class HudBackground(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    companion object {
        const val CARBON = "carbon"
        val STYLES = listOf(
            CARBON to "Carbon",
            "circuit" to "Circuit",
            "hex" to "Hex",
            "topo" to "Topo",
            "nebula" to "Nebula",
            "blueprint" to "Blueprint",
            "waves" to "Waves",
            "orbit" to "Orbit",
        )
    }

    var accent: Int = Color.rgb(255, 45, 61)
        set(v) { field = v; rebuild(); invalidate() }

    var style: String = CARBON
        set(v) { field = v; rebuild(); invalidate() }

    /** Pixels per dp. Thumbnails use a smaller value so they look like a shrunken wallpaper. */
    var unit: Float = resources.displayMetrics.density
        set(v) { field = v; rebuild(); invalidate() }

    private val base = Paint()
    private val tex = Paint()
    private val glowTop = Paint()
    private val glowBottom = Paint()
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    // cached pattern for the heavy styles (redrawn only when size / accent / style change)
    private var pattern: Bitmap? = null

    private fun a(c: Int, alpha: Int) = Color.argb(alpha.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    /** Accent rotated around the hue wheel, for two-tone styles. */
    private fun shifted(deg: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        hsv[0] = (hsv[0] + deg + 360f) % 360f
        return Color.HSVToColor(hsv)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    private fun rebuild() {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val wf = w.toFloat()
        val hf = h.toFloat()
        base.shader = LinearGradient(0f, 0f, 0f, hf, Color.rgb(16, 16, 20), Color.rgb(4, 4, 6), Shader.TileMode.CLAMP)
        glowTop.shader = RadialGradient(wf * 0.85f, hf * 0.12f, wf * 0.8f, a(accent, 70), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        glowBottom.shader = RadialGradient(wf * 0.1f, hf * 0.9f, wf * 0.9f, a(accent, 70), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        frame.color = a(accent, 90)
        frame.strokeWidth = 1f * unit
        tex.shader = if (style == CARBON) BitmapShader(carbonTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) else null
        pattern?.recycle()
        pattern = null
        if (style != CARBON) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawPattern(Canvas(bmp), wf, hf)
            pattern = bmp
        }
    }

    private fun carbonTile(): Bitmap {
        val s = (4 * unit).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.color = Color.argb(28, 255, 255, 255); c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        c.drawRect(s.toFloat(), s.toFloat(), 2f * s, 2f * s, p)
        p.color = Color.argb(10, 255, 255, 255); c.drawRect(s.toFloat(), 0f, 2f * s, s.toFloat(), p)
        c.drawRect(0f, s.toFloat(), s.toFloat(), 2f * s, p)
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, base)
        if (style == CARBON) canvas.drawRect(0f, 0f, w, h, tex)
        pattern?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (style != "nebula" && style != "orbit") {
            canvas.drawRect(0f, 0f, w, h, glowTop)
            canvas.drawRect(0f, 0f, w, h, glowBottom)
        }
        if (style == CARBON || style == "blueprint" || style == "circuit") corners(canvas, w, h)
    }

    private fun corners(canvas: Canvas, w: Float, h: Float) {
        val i = 10 * unit
        val l = 26 * unit
        canvas.drawLine(i, i + l, i, i, frame); canvas.drawLine(i, i, i + l, i, frame)
        canvas.drawLine(w - i - l, i, w - i, i, frame); canvas.drawLine(w - i, i, w - i, i + l, frame)
        canvas.drawLine(i, h - i - l, i, h - i, frame); canvas.drawLine(i, h - i, i + l, h - i, frame)
        canvas.drawLine(w - i - l, h - i, w - i, h - i, frame); canvas.drawLine(w - i, h - i, w - i, h - i - l, frame)
    }

    // ---------------- styles ----------------

    private fun drawPattern(c: Canvas, w: Float, h: Float) {
        val rnd = Random(0xA1FA)          // fixed seed: the same wallpaper every time
        when (style) {
            "circuit" -> circuit(c, w, h, rnd)
            "hex" -> hex(c, w, h, rnd)
            "topo" -> topo(c, w, h)
            "nebula" -> nebula(c, w, h, rnd)
            "blueprint" -> blueprint(c, w, h)
            "waves" -> waves(c, w, h)
            "orbit" -> orbit(c, w, h)
        }
    }

    /** PCB traces: random walks on a grid with 45° bends, pads at the ends, a few lit nodes. */
    private fun circuit(c: Canvas, w: Float, h: Float, rnd: Random) {
        val g = 18f * unit
        val cols = (w / g).toInt() + 1
        val rows = (h / g).toInt() + 1
        val dirs = listOf(0 to 1, 1 to 1, 1 to 0, 1 to -1, 0 to -1, -1 to -1, -1 to 0, -1 to 1)
        line.strokeWidth = 1.4f * unit
        repeat((cols * rows) / 9) {
            var x = rnd.nextInt(cols)
            var y = rnd.nextInt(rows)
            var d = rnd.nextInt(4) * 2               // start straight
            val path = Path().apply { moveTo(x * g, y * g) }
            val steps = 3 + rnd.nextInt(9)
            repeat(steps) {
                if (rnd.nextFloat() < 0.3f) d = (d + if (rnd.nextBoolean()) 1 else 7) % 8
                x += dirs[d].first; y += dirs[d].second
                path.lineTo(x * g, y * g)
            }
            // brighter near the glow corners
            val fx = x * g / w; val fy = y * g / h
            val near = max(1f - hypot(fx - 0.85f, fy - 0.12f), 1f - hypot(fx - 0.1f, fy - 0.9f)).coerceIn(0f, 1f)
            line.color = a(accent, (25 + 110 * near * near).toInt())
            c.drawPath(path, line)
            fill.color = a(accent, (60 + 150 * near).toInt())
            c.drawCircle(x * g, y * g, 2.6f * unit, fill)
            fill.color = Color.rgb(8, 8, 11)
            c.drawCircle(x * g, y * g, 1.1f * unit, fill)
        }
        // a few glowing chips
        repeat(5) {
            val cx = rnd.nextFloat() * w; val cy = rnd.nextFloat() * h
            fill.shader = RadialGradient(cx, cy, 40f * unit, a(accent, 70), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, 40f * unit, fill)
            fill.shader = null
        }
    }

    /** Honeycomb outlines fading from the glow, with a few filled cells. */
    private fun hex(c: Canvas, w: Float, h: Float, rnd: Random) {
        val r = 22f * unit
        val hw = sqrt(3f) * r
        line.strokeWidth = 1f * unit
        var row = 0
        var y = 0f
        while (y < h + r) {
            var x = if (row % 2 == 0) 0f else hw / 2
            while (x < w + hw) {
                val fx = x / w; val fy = y / h
                val near = max(1f - hypot(fx - 0.85f, fy - 0.12f) * 1.3f, 1f - hypot(fx - 0.1f, fy - 0.9f) * 1.3f).coerceIn(0f, 1f)
                val p = Path()
                for (k in 0..5) {
                    val ang = (PI / 3 * k + PI / 6).toFloat()
                    val px = x + r * 0.92f * cos(ang); val py = y + r * 0.92f * sin(ang)
                    if (k == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                p.close()
                if (rnd.nextFloat() < 0.035f * (0.3f + near)) {
                    fill.color = a(accent, (40 + 90 * near).toInt()); c.drawPath(p, fill)
                }
                line.color = Color.argb((10 + 55 * near).toInt(), 255, 255, 255)
                c.drawPath(p, line)
                x += hw
            }
            y += r * 1.5f
            row++
        }
    }

    /** Topographic contour lines from a few smooth "hills". */
    private fun topo(c: Canvas, w: Float, h: Float) {
        val hills = listOf(Triple(0.72f, 0.28f, 1.0f), Triple(0.25f, 0.68f, 0.85f), Triple(0.62f, 0.86f, 0.6f))
        fun height(px: Float, py: Float): Float {
            var v = 0f
            for ((hx, hy, s) in hills) {
                val dx = px / w - hx; val dy = (py / h - hy) * (h / w)
                v += s * kotlin.math.exp(-(dx * dx + dy * dy) * 7f)
            }
            return v + 0.04f * sin(px / w * 9f) * cos(py / h * 7f)
        }
        // marching squares on a coarse grid
        val step = 6f * unit
        val cols = (w / step).toInt() + 2
        val rows = (h / step).toInt() + 2
        val field = Array(rows) { j -> FloatArray(cols) { i -> height(i * step, j * step) } }
        line.strokeWidth = 1.1f * unit
        val levels = 22
        for (l in 1 until levels) {
            val iso = l / levels.toFloat() * 1.1f
            val major = l % 5 == 0
            line.color = if (major) a(accent, 150) else Color.argb(38, 255, 255, 255)
            line.strokeWidth = (if (major) 1.6f else 1f) * unit
            for (j in 0 until rows - 1) for (i in 0 until cols - 1) {
                val v0 = field[j][i]; val v1 = field[j][i + 1]; val v2 = field[j + 1][i + 1]; val v3 = field[j + 1][i]
                val idx = (if (v0 > iso) 1 else 0) or (if (v1 > iso) 2 else 0) or (if (v2 > iso) 4 else 0) or (if (v3 > iso) 8 else 0)
                if (idx == 0 || idx == 15) continue
                val x0 = i * step; val y0 = j * step
                fun lerp(a1: Float, b1: Float) = ((iso - a1) / (b1 - a1)).coerceIn(0f, 1f)
                val top = floatArrayOf(x0 + step * lerp(v0, v1), y0)
                val right = floatArrayOf(x0 + step, y0 + step * lerp(v1, v2))
                val bottom = floatArrayOf(x0 + step * lerp(v3, v2), y0 + step)
                val left = floatArrayOf(x0, y0 + step * lerp(v0, v3))
                val segs = when (idx) {
                    1, 14 -> listOf(left to top)
                    2, 13 -> listOf(top to right)
                    3, 12 -> listOf(left to right)
                    4, 11 -> listOf(right to bottom)
                    5 -> listOf(left to top, right to bottom)
                    6, 9 -> listOf(top to bottom)
                    7, 8 -> listOf(left to bottom)
                    10 -> listOf(top to right, bottom to left)
                    else -> emptyList()
                }
                for ((p, q) in segs) c.drawLine(p[0], p[1], q[0], q[1], line)
            }
        }
    }

    /** Deep space: two-tone nebula clouds and a starfield. */
    private fun nebula(c: Canvas, w: Float, h: Float, rnd: Random) {
        val second = shifted(-55f)
        val clouds = listOf(
            Triple(0.78f, 0.22f, accent), Triple(0.3f, 0.45f, second), Triple(0.6f, 0.62f, accent),
            Triple(0.15f, 0.85f, second), Triple(0.9f, 0.88f, accent),
        )
        for ((cx, cy, col) in clouds) {
            val rad = w * (0.45f + rnd.nextFloat() * 0.35f)
            fill.shader = RadialGradient(cx * w, cy * h, rad, a(col, 70 + rnd.nextInt(40)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(cx * w, cy * h, rad, fill)
        }
        fill.shader = null
        val stars = (w * h / (unit * unit * 900f)).toInt().coerceIn(150, 1600)
        repeat(stars) {
            val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
            val big = rnd.nextFloat() < 0.04f
            fill.color = Color.argb(if (big) 230 else 60 + rnd.nextInt(150), 255, 255, 255)
            c.drawCircle(x, y, (if (big) 1.6f else 0.4f + rnd.nextFloat() * 0.7f) * unit, fill)
            if (big) {
                fill.shader = RadialGradient(x, y, 7f * unit, Color.argb(90, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawCircle(x, y, 7f * unit, fill); fill.shader = null
            }
        }
    }

    /** Engineering grid with technical marks. */
    private fun blueprint(c: Canvas, w: Float, h: Float) {
        val minor = 12f * unit
        val major = minor * 5
        line.strokeWidth = 1f
        var x = 0f
        while (x < w) {
            val m = (x / minor).toInt() % 5 == 0
            line.color = if (m) a(accent, 46) else Color.argb(14, 255, 255, 255)
            c.drawLine(x, 0f, x, h, line); x += minor
        }
        var y = 0f
        while (y < h) {
            val m = (y / minor).toInt() % 5 == 0
            line.color = if (m) a(accent, 46) else Color.argb(14, 255, 255, 255)
            c.drawLine(0f, y, w, y, line); y += minor
        }
        line.strokeWidth = 1.3f * unit
        line.color = a(accent, 120)
        // compass / crosshair marks
        fun mark(cx: Float, cy: Float, r: Float) {
            c.drawCircle(cx, cy, r, line)
            c.drawCircle(cx, cy, r * 0.62f, line)
            c.drawLine(cx - r * 1.3f, cy, cx + r * 1.3f, cy, line)
            c.drawLine(cx, cy - r * 1.3f, cx, cy + r * 1.3f, line)
        }
        mark(w * 0.78f, h * 0.24f, major * 1.2f)
        mark(w * 0.22f, h * 0.74f, major * 0.8f)
        // dimension lines
        line.color = a(accent, 80)
        val dy = h * 0.5f
        c.drawLine(w * 0.12f, dy, w * 0.88f, dy, line)
        c.drawLine(w * 0.12f, dy - 6 * unit, w * 0.12f, dy + 6 * unit, line)
        c.drawLine(w * 0.88f, dy - 6 * unit, w * 0.88f, dy + 6 * unit, line)
    }

    /** Flowing layered wave lines. */
    private fun waves(c: Canvas, w: Float, h: Float) {
        val n = 34
        line.strokeWidth = 1.3f * unit
        for (k in 0 until n) {
            val t = k / (n - 1f)
            val baseY = h * (0.35f + 0.55f * t)
            val amp = h * (0.03f + 0.05f * sin(t * PI.toFloat()))
            val p = Path()
            var x = 0f
            val step = 6f * unit
            while (x <= w + step) {
                val fx = x / w
                val yy = baseY + amp * sin(fx * 6.2f + t * 3.1f) + amp * 0.5f * sin(fx * 13f - t * 5f)
                if (x == 0f) p.moveTo(x, yy) else p.lineTo(x, yy)
                x += step
            }
            val glow = (1f - kotlin.math.abs(t - 0.45f) * 1.6f).coerceIn(0f, 1f)
            line.color = a(accent, (20 + 150 * glow * glow).toInt())
            c.drawPath(p, line)
        }
    }

    /** Minimal: soft gradient, one big glow and orbit rings like ALFA's dial. */
    private fun orbit(c: Canvas, w: Float, h: Float) {
        val cx = w * 0.62f
        val cy = h * 0.42f
        fill.shader = RadialGradient(cx, cy, w * 0.9f, a(accent, 80), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, w * 0.9f, fill)
        fill.shader = null
        line.strokeWidth = 1.2f * unit
        val radii = listOf(0.22f, 0.36f, 0.52f, 0.7f, 0.9f)
        radii.forEachIndexed { i, r ->
            line.color = if (i == 1) a(accent, 140) else Color.argb(26, 255, 255, 255)
            c.drawCircle(cx, cy, w * r, line)
        }
        // a few "satellites" on the rings
        listOf(0.36f to 300.0, 0.52f to 140.0, 0.7f to 220.0, 0.22f to 40.0).forEach { (r, deg) ->
            val ang = Math.toRadians(deg)
            val px = cx + (w * r * cos(ang)).toFloat(); val py = cy + (w * r * sin(ang)).toFloat()
            fill.color = a(accent, 220); c.drawCircle(px, py, 3.5f * unit, fill)
            fill.shader = RadialGradient(px, py, 14f * unit, a(accent, 90), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(px, py, 14f * unit, fill); fill.shader = null
        }
        // tick ring
        line.color = Color.argb(60, 255, 255, 255)
        line.strokeWidth = 1f * unit
        val tr = w * 0.36f
        for (k in 0 until 72) {
            val ang = (k * 5.0).let { Math.toRadians(it) }
            val l = if (k % 6 == 0) 9f * unit else 4f * unit
            val x1 = cx + (tr + 6 * unit) * cos(ang).toFloat(); val y1 = cy + (tr + 6 * unit) * sin(ang).toFloat()
            val x2 = cx + (tr + 6 * unit + l) * cos(ang).toFloat(); val y2 = cy + (tr + 6 * unit + l) * sin(ang).toFloat()
            c.drawLine(x1, y1, x2, y2, line)
        }
        min(w, h) // keep import used
    }
}
