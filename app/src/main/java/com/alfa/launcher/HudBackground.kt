package com.alfa.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * ALFA wallpapers, tinted with the accent colour: fluid glass, 3D depth, matte minimal,
 * or the user's own photos. Shown behind ALFA screens and rendered as the system wallpaper.
 * Everything is drawn once into a bitmap (software canvas, so blur filters work).
 */
class HudBackground(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    companion object {
        const val DEFAULT = "liquid"
        const val PHOTO_PREFIX = "photo:"
        val STYLES = listOf(
            "liquid" to "Liquid",
            "aurora" to "Aurora",
            "prism" to "Prism",
            "layers" to "Layers",
            "dots" to "Dots",
            "beam" to "Beam",
            "eclipse" to "Eclipse",
        )
        fun isKnown(id: String) = id.startsWith(PHOTO_PREFIX) || STYLES.any { it.first == id }
    }

    var accent: Int = Color.rgb(255, 45, 61)
        set(v) { field = v; rebuild(); invalidate() }

    var style: String = DEFAULT
        set(v) { field = v; rebuild(); invalidate() }

    /** Pixels per dp. Thumbnails use a smaller value so they look like a shrunken wallpaper. */
    var unit: Float = resources.displayMetrics.density
        set(v) { field = v; rebuild(); invalidate() }

    private var image: Bitmap? = null

    private fun a(c: Int, alpha: Int) = Color.argb(alpha.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun hueShift(c: Int, deg: Float, sat: Float = 1f, value: Float = 1f): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[0] = (hsv[0] + deg + 360f) % 360f
        hsv[1] = (hsv[1] * sat).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * value).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun mix(c1: Int, c2: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * u).toInt(),
            (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * u).toInt(),
            (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * u).toInt(),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    override fun onDraw(canvas: Canvas) {
        val img = image
        if (img != null) canvas.drawBitmap(img, 0f, 0f, null) else canvas.drawColor(Color.rgb(7, 7, 10))
    }

    private fun rebuild() {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        image?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()
        try {
            when {
                style.startsWith(PHOTO_PREFIX) -> photo(c, wf, hf, style.removePrefix(PHOTO_PREFIX))
                style == "aurora" -> aurora(c, wf, hf)
                style == "prism" -> prism(c, wf, hf)
                style == "layers" -> layers(c, wf, hf)
                style == "dots" -> dots(c, wf, hf)
                style == "beam" -> beam(c, wf, hf)
                style == "eclipse" -> eclipse(c, wf, hf)
                else -> liquid(c, wf, hf)
            }
        } catch (e: Exception) {
            c.drawColor(Color.rgb(7, 7, 10))
        }
        if (!style.startsWith(PHOTO_PREFIX)) grain(c, wf, hf)
        image = bmp
    }

    // ---------------- shared helpers ----------------

    private fun soft(c: Canvas, x: Float, y: Float, r: Float, color: Int, mode: PorterDuff.Mode? = PorterDuff.Mode.SCREEN) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = RadialGradient(x, y, r, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        if (mode != null) p.xfermode = PorterDuffXfermode(mode)
        c.drawCircle(x, y, r, p)
    }

    private fun vignette(c: Canvas, w: Float, h: Float, strength: Int = 170) {
        val p = Paint(Paint.DITHER_FLAG)
        p.shader = RadialGradient(w * 0.5f, h * 0.45f, max(w, h) * 0.78f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(strength, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
    }

    /** Fine film grain: hides 8-bit banding in smooth gradients and gives a matte finish. */
    private fun grain(c: Canvas, w: Float, h: Float) {
        val s = 96
        val tile = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val rnd = Random(7)
        val px = IntArray(s * s) {
            val v = rnd.nextInt(256)
            if (rnd.nextBoolean()) Color.argb(9, 255, 255, 255) else Color.argb(11 + (v % 6), 0, 0, 0)
        }
        tile.setPixels(px, 0, s, 0, 0, s, s)
        val p = Paint()
        p.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        c.drawRect(0f, 0f, w, h, p)
        tile.recycle()
    }

    private fun blurPaint(radius: Float, color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        this.color = color
        if (radius > 0.5f) maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
    }

    // ---------------- fluid glass ----------------

    /** Deep colour blobs blending like liquid glass, with a soft glass highlight. */
    private fun liquid(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.rgb(6, 6, 9))
        val deep = hueShift(accent, 0f, 1f, 0.35f)
        val warm = hueShift(accent, 28f, 0.9f, 0.9f)
        val cool = hueShift(accent, -42f, 0.85f, 0.75f)
        soft(c, w * 0.18f, h * 0.22f, w * 1.05f, a(deep, 255), null)
        soft(c, w * 0.85f, h * 0.30f, w * 0.80f, a(accent, 210))
        soft(c, w * 0.30f, h * 0.70f, w * 0.85f, a(cool, 180))
        soft(c, w * 0.80f, h * 0.88f, w * 0.70f, a(warm, 150))
        soft(c, w * 0.55f, h * 0.48f, w * 0.40f, a(Color.WHITE, 26))
        // glass highlight streak
        val streak = Path().apply {
            moveTo(-w * 0.2f, h * 0.58f)
            cubicTo(w * 0.3f, h * 0.40f, w * 0.7f, h * 0.52f, w * 1.2f, h * 0.30f)
            lineTo(w * 1.2f, h * 0.36f)
            cubicTo(w * 0.7f, h * 0.58f, w * 0.3f, h * 0.46f, -w * 0.2f, h * 0.64f)
            close()
        }
        c.drawPath(streak, blurPaint(28f * unit, Color.argb(34, 255, 255, 255)))
        vignette(c, w, h, 190)
    }

    /** Soft blurred light curtains rising from the bottom. */
    private fun aurora(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.rgb(5, 5, 8))
        soft(c, w * 0.5f, h * 1.05f, w * 1.2f, a(hueShift(accent, 0f, 1f, 0.45f), 255), null)
        val bands = listOf(
            Triple(0.15f, accent, 0.62f), Triple(0.42f, hueShift(accent, 35f), 0.5f),
            Triple(0.68f, hueShift(accent, -30f), 0.58f), Triple(0.9f, accent, 0.45f),
        )
        for ((cx, col, top) in bands) {
            val p = Path()
            val x0 = cx * w
            val bw = w * 0.22f
            p.moveTo(x0 - bw, h)
            p.cubicTo(x0 - bw * 1.4f, h * (top + 0.25f), x0 + bw * 0.2f, h * (top + 0.05f), x0 - bw * 0.3f, h * top)
            p.cubicTo(x0 + bw * 0.6f, h * (top + 0.02f), x0 + bw * 1.6f, h * (top + 0.3f), x0 + bw, h)
            p.close()
            val paint = blurPaint(46f * unit, Color.WHITE)
            paint.shader = LinearGradient(0f, h * top, 0f, h, a(col, 0), a(col, 190), Shader.TileMode.CLAMP)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            c.drawPath(p, paint)
        }
        vignette(c, w, h, 150)
    }

    // ---------------- 3D depth ----------------

    /** Low-poly facets with real light and shadow, lit from the top-right in the accent colour. */
    private fun prism(c: Canvas, w: Float, h: Float) {
        val rnd = Random(0xA1FA)
        val cell = 64f * unit
        val cols = (w / cell).toInt() + 2
        val rows = (h / cell).toInt() + 2
        val xs = Array(rows) { j -> FloatArray(cols) { i -> (i + (rnd.nextFloat() - 0.5f) * 0.8f) * cell } }
        val ys = Array(rows) { j -> FloatArray(cols) { i -> (j + (rnd.nextFloat() - 0.5f) * 0.8f) * cell } }
        val zs = Array(rows) { FloatArray(cols) { rnd.nextFloat() } }
        val lx = w * 0.95f
        val ly = h * 0.05f
        val dark = Color.rgb(9, 9, 13)
        val lit = hueShift(accent, 0f, 0.95f, 0.85f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f * unit; color = Color.argb(14, 255, 255, 255) }
        fun tri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float) {
            // face normal from the 3 lifted vertices
            val ux = bx - ax; val uy = by - ay; val uz = (bz - az) * cell
            val vx = cx - ax; val vy = cy - ay; val vz = (cz - az) * cell
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-3f)
            nx /= nl; ny /= nl; nz /= nl
            if (nz < 0) { nx = -nx; ny = -ny; nz = -nz }
            val mx = (ax + bx + cx) / 3f; val my = (ay + by + cy) / 3f
            // light direction (top-right, slightly toward viewer)
            var ldx = lx - mx; var ldy = ly - my; var ldz = w * 0.6f
            val ll = kotlin.math.sqrt(ldx * ldx + ldy * ldy + ldz * ldz)
            ldx /= ll; ldy /= ll; ldz /= ll
            val diffuse = (nx * ldx + ny * ldy + nz * ldz).coerceIn(0f, 1f)
            val dist = (hypot(mx - lx, my - ly) / hypot(w, h)).coerceIn(0f, 1f)
            val intensity = (diffuse * diffuse * (1f - dist) * 1.15f).coerceIn(0f, 1f)
            fill.color = mix(dark, lit, intensity * 0.85f)
            val p = Path().apply { moveTo(ax, ay); lineTo(bx, by); lineTo(cx, cy); close() }
            c.drawPath(p, fill)
            c.drawPath(p, edge)
        }
        for (j in 0 until rows - 1) for (i in 0 until cols - 1) {
            val flip = (i + j) % 2 == 0
            if (flip) {
                tri(xs[j][i], ys[j][i], zs[j][i], xs[j][i + 1], ys[j][i + 1], zs[j][i + 1], xs[j + 1][i], ys[j + 1][i], zs[j + 1][i])
                tri(xs[j][i + 1], ys[j][i + 1], zs[j][i + 1], xs[j + 1][i + 1], ys[j + 1][i + 1], zs[j + 1][i + 1], xs[j + 1][i], ys[j + 1][i], zs[j + 1][i])
            } else {
                tri(xs[j][i], ys[j][i], zs[j][i], xs[j][i + 1], ys[j][i + 1], zs[j][i + 1], xs[j + 1][i + 1], ys[j + 1][i + 1], zs[j + 1][i + 1])
                tri(xs[j][i], ys[j][i], zs[j][i], xs[j + 1][i + 1], ys[j + 1][i + 1], zs[j + 1][i + 1], xs[j + 1][i], ys[j + 1][i], zs[j + 1][i])
            }
        }
        soft(c, lx, ly, w * 0.7f, a(accent, 70))
        vignette(c, w, h, 150)
    }

    /** Stacked paper-cut waves with drop shadows and an accent rim light on each edge. */
    private fun layers(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.rgb(8, 8, 12))
        soft(c, w * 0.7f, h * 0.18f, w * 0.9f, a(accent, 120))
        val n = 7
        for (k in 0 until n) {
            val t = k / (n - 1f)                      // 0 = back, 1 = front
            val baseY = h * (0.30f + 0.11f * k)
            val amp = h * (0.035f + 0.01f * k)
            val phase = k * 1.3f
            val p = Path()
            p.moveTo(0f, h)
            var x = 0f
            val step = 8f * unit
            p.lineTo(0f, baseY)
            val edge = Path().apply { moveTo(0f, baseY) }
            while (x <= w + step) {
                val fx = x / w
                val y = baseY + amp * sin(fx * 5.5f + phase) + amp * 0.45f * sin(fx * 11f - phase)
                p.lineTo(x, y); edge.lineTo(x, y)
                x += step
            }
            p.lineTo(w, h); p.close()
            // shadow cast on the layer behind
            c.save(); c.translate(0f, -6f * unit)
            c.drawPath(p, blurPaint(16f * unit, Color.argb(160, 0, 0, 0)))
            c.restore()
            // body: darker toward the front, faintly lit at the top
            val body = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
            val top = mix(Color.rgb(24, 22, 28), hueShift(accent, 0f, 0.6f, 0.35f), 0.55f - 0.4f * t)
            val bottom = Color.rgb(6, 6, 9)
            body.shader = LinearGradient(0f, baseY - amp, 0f, baseY + h * 0.25f, top, bottom, Shader.TileMode.CLAMP)
            c.drawPath(p, body)
            // rim light on the crest
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = (1.2f + 0.6f * (1f - t)) * unit
                color = a(accent, (190 - 120 * t).toInt())
            }
            c.drawPath(edge, rim)
        }
        vignette(c, w, h, 120)
    }

    // ---------------- matte minimal ----------------

    /** Nothing-OS-style dot matrix: dots fade in from a soft glow; a ring of accent dots. */
    private fun dots(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.rgb(9, 9, 11))
        val g = 11f * unit
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val gx = w * 0.68f
        val gy = h * 0.34f
        val ringR = w * 0.34f
        var y = g / 2
        while (y < h) {
            var x = g / 2
            while (x < w) {
                val d = hypot(x - gx, y - gy)
                val fade = (1f - d / (max(w, h) * 0.75f)).coerceIn(0f, 1f)
                val onRing = kotlin.math.abs(d - ringR) < g * 0.6f
                if (onRing) {
                    p.color = a(accent, 235)
                    c.drawCircle(x, y, 1.9f * unit, p)
                } else {
                    p.color = Color.argb((14 + 70 * fade * fade).toInt(), 255, 255, 255)
                    c.drawCircle(x, y, 1.15f * unit, p)
                }
                x += g
            }
            y += g
        }
        soft(c, gx, gy, ringR * 1.25f, a(accent, 40))
    }

    /** Matte black with one soft diagonal light beam in the accent colour. */
    private fun beam(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.rgb(7, 7, 9))
        val p = Path().apply {
            moveTo(w * 0.78f, -h * 0.05f)
            lineTo(w * 0.98f, -h * 0.05f)
            lineTo(w * 0.35f, h * 1.05f)
            lineTo(-w * 0.15f, h * 1.05f)
            close()
        }
        val glow = blurPaint(60f * unit, Color.WHITE).apply {
            shader = LinearGradient(w * 0.88f, 0f, w * 0.1f, h, a(accent, 170), a(accent, 0), Shader.TileMode.CLAMP)
        }
        c.drawPath(p, glow)
        val core = Path().apply {
            moveTo(w * 0.86f, -h * 0.05f); lineTo(w * 0.9f, -h * 0.05f)
            lineTo(w * 0.22f, h * 1.05f); lineTo(w * 0.14f, h * 1.05f); close()
        }
        val corePaint = blurPaint(14f * unit, Color.WHITE).apply {
            shader = LinearGradient(w * 0.88f, 0f, w * 0.2f, h, Color.argb(70, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawPath(core, corePaint)
        soft(c, w * 0.9f, 0f, w * 0.5f, a(accent, 90))
        vignette(c, w, h, 120)
    }

    /** A thin glowing arc of light rising over black, like a planet's edge at sunrise. */
    private fun eclipse(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.rgb(4, 4, 6))
        val r = w * 1.35f
        val cx = w * 0.5f
        val cy = h * 0.78f + r
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        // wide bloom, then the bright thin edge
        val bloom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 46f * unit; color = a(accent, 120)
            maskFilter = BlurMaskFilter(40f * unit, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawArc(oval, 200f, 140f, false, bloom)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.2f * unit
            shader = LinearGradient(0f, 0f, w, 0f,
                intArrayOf(Color.TRANSPARENT, a(accent, 255), Color.WHITE, a(accent, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawArc(oval, 200f, 140f, false, edge)
        // the dark planet body below the arc
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(2, 2, 3) }
        c.drawCircle(cx, cy + 3f * unit, r - 1.5f * unit, body)
        soft(c, cx, h * 0.78f, w * 0.6f, a(accent, 60))
        // a few distant stars
        val rnd = Random(3)
        val sp = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(70) {
            val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h * 0.7f
            sp.color = Color.argb(30 + rnd.nextInt(110), 255, 255, 255)
            c.drawCircle(x, y, (0.4f + rnd.nextFloat() * 0.8f) * unit, sp)
        }
    }

    // ---------------- your photos ----------------

    /** User photo, centre-cropped to the screen, optionally darkened and accent-tinted. */
    private fun photo(c: Canvas, w: Float, h: Float, name: String) {
        c.drawColor(Color.rgb(7, 7, 10))
        val f = File(File(context.filesDir, "wallpapers"), name)
        if (!f.exists()) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        val scale = max(w / src.width, h / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        c.drawBitmap(src, Rect(0, 0, src.width, src.height), dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        src.recycle()
        val prefs = context.getSharedPreferences("alfa", Context.MODE_PRIVATE)
        if (prefs.getBoolean("photo_tint", false)) {
            soft(c, w * 0.5f, h, max(w, h) * 0.8f, a(accent, 110))
            val t = Paint().apply { color = a(accent, 40); xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY) }
            c.drawRect(0f, 0f, w, h, t)
        }
        if (prefs.getBoolean("photo_dim", true)) {
            val d = Paint(Paint.DITHER_FLAG)
            d.shader = LinearGradient(0f, 0f, 0f, h,
                intArrayOf(Color.argb(110, 0, 0, 0), Color.argb(40, 0, 0, 0), Color.argb(130, 0, 0, 0)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, d)
        }
        min(w, h); cos(0f) // keep imports used
    }
}
