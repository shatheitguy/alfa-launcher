package com.alfa.launcher

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/** Renders app icons into the launcher's sci-fi orb style. */
object IconStyler {

    const val NEON = "neon"
    const val COLOUR = "colour"
    const val ORIGINAL = "original"

    val STYLES = listOf(NEON to "Neon glyph", COLOUR to "Colour orb", ORIGINAL to "Original icons")

    fun render(res: Resources, src: Drawable, style: String, accent: Int): Drawable {
        if (style == ORIGINAL) return src
        val s = (64 * res.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = s / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val ringW = s * 0.035f

        // glass disc
        p.shader = RadialGradient(r, r * 0.55f, r, Color.rgb(40, 40, 48), Color.rgb(9, 9, 12), Shader.TileMode.CLAMP)
        c.drawCircle(r, r, r - ringW, p)
        p.shader = null

        // top sheen
        p.shader = RadialGradient(r, 0f, r * 1.1f, Color.argb(40, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(r, r, r - ringW, p)
        p.shader = null

        // glyph
        val glyphBox = (s * 0.60f).toInt()
        val glyph = if (style == NEON) neonGlyph(src, glyphBox, accent) else colourGlyph(src, glyphBox)
        val off = (s - glyphBox) / 2f

        if (style == NEON) {
            // soft glow behind the glyph
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                alpha = 170
                maskFilter = BlurMaskFilter(s * 0.07f, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawBitmap(glyph.extractAlpha(), off, off, glowPaint)
        }
        c.drawBitmap(glyph, off, off, p)

        // accent ring + faint inner ring
        val ringAlpha = if (style == NEON) 230 else 170
        val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        ring.style = Paint.Style.STROKE
        ring.strokeWidth = ringW
        ring.color = accent
        ring.alpha = ringAlpha
        c.drawCircle(r, r, r - ringW / 2f - 1f, ring)
        ring.color = Color.argb(35, 255, 255, 255)
        ring.strokeWidth = 1f * res.displayMetrics.density
        c.drawCircle(r, r, r * 0.84f, ring)

        // small HUD notch at the top of the ring
        ring.color = Color.WHITE
        ring.alpha = 200
        ring.strokeWidth = ringW * 1.2f
        ring.strokeCap = Paint.Cap.ROUND
        c.drawArc(ringW, ringW, s - ringW, s - ringW, -100f, 20f, false, ring)

        return BitmapDrawable(res, bmp)
    }

    /** Full colour icon clipped to a circle. */
    private fun colourGlyph(src: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val clip = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
        c.clipPath(clip)
        if (src is AdaptiveIconDrawable) {
            // draw background + foreground slightly oversized so the circle is filled
            val extra = (size * 0.25f).toInt()
            src.setBounds(-extra, -extra, size + extra, size + extra)
            src.background?.let { it.bounds = src.bounds; it.draw(c) }
            src.foreground?.let { it.bounds = src.bounds; it.draw(c) }
        } else {
            src.setBounds(0, 0, size, size)
            src.draw(c)
        }
        return bmp
    }

    /** Single-colour glowing glyph: themed monochrome layer if available, else a luminance-tinted foreground. */
    private fun neonGlyph(src: Drawable, size: Int, accent: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // adaptive layers are 108dp with a 72dp safe zone -> scale so the safe zone fills the box
        val layer = (size * 108f / 72f).toInt()
        val lo = (size - layer) / 2
        val bright = lighten(accent)

        if (src is AdaptiveIconDrawable) {
            val mono = if (Build.VERSION.SDK_INT >= 33) src.monochrome else null
            if (mono != null) {
                val m = mono.constantState?.newDrawable()?.mutate() ?: mono.mutate()
                m.setBounds(lo, lo, lo + layer, lo + layer)
                m.setTint(bright)
                m.draw(c)
                return bmp
            }
            val fg = src.foreground
            if (fg != null) {
                val f = fg.constantState?.newDrawable()?.mutate() ?: fg.mutate()
                f.setBounds(lo, lo, lo + layer, lo + layer)
                f.colorFilter = tintFilter(bright)
                f.draw(c)
                return bmp
            }
        }
        val d = src.constantState?.newDrawable()?.mutate() ?: src.mutate()
        d.setBounds(0, 0, size, size)
        d.colorFilter = tintFilter(bright)
        d.draw(c)
        return bmp
    }

    /** Maps luminance onto the accent colour (keeps alpha). */
    private fun tintFilter(color: Int): ColorMatrixColorFilter {
        val rr = Color.red(color) / 255f * 1.35f
        val gg = Color.green(color) / 255f * 1.35f
        val bb = Color.blue(color) / 255f * 1.35f
        val lum = floatArrayOf(0.30f, 0.59f, 0.11f)
        val m = ColorMatrix(floatArrayOf(
            rr * lum[0], rr * lum[1], rr * lum[2], 0f, 40f * rr,
            gg * lum[0], gg * lum[1], gg * lum[2], 0f, 40f * gg,
            bb * lum[0], bb * lum[1], bb * lum[2], 0f, 40f * bb,
            0f, 0f, 0f, 1f, 0f,
        ))
        return ColorMatrixColorFilter(m)
    }

    private fun lighten(c: Int): Int {
        fun ch(v: Int) = (v + (255 - v) * 0.25f).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }
}
