package com.alfa.launcher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager

/**
 * Keeps one wallpaper for everything: the chosen ALFA style is rendered at screen size in
 * the accent colour and set as the real Android wallpaper, so the launcher, Recents and app
 * transitions all show the same image.
 */
object WallpaperSync {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("alfa", Context.MODE_PRIVATE)

    fun style(ctx: Context): String = prefs(ctx).getString("wall_style", HudBackground.CARBON) ?: HudBackground.CARBON

    fun setStyle(ctx: Context, s: String) = prefs(ctx).edit().putString("wall_style", s).apply()

    /** Is the system wallpaper currently this ALFA style in this accent? */
    fun isApplied(ctx: Context, accent: Int): Boolean {
        val p = prefs(ctx)
        return p.getBoolean("carbon_applied", false) &&
            p.getInt("carbon_applied_accent", 0) == accent &&
            p.getString("carbon_applied_style", HudBackground.CARBON) == style(ctx)
    }

    fun markCustom(ctx: Context) =
        prefs(ctx).edit().putBoolean("carbon_applied", false).apply()

    @Suppress("DEPRECATION")
    fun screenSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30 && wm != null) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            wm?.defaultDisplay?.getRealMetrics(dm)
            Pair(dm.widthPixels.coerceAtLeast(720), dm.heightPixels.coerceAtLeast(1280))
        }
    }

    /** Renders [style] off-screen. [scale] < 1 makes a proportionally identical thumbnail. */
    fun render(ctx: Context, accent: Int, style: String, scale: Float = 1f): Bitmap {
        val (sw, sh) = screenSize(ctx)
        val w = (sw * scale).toInt().coerceAtLeast(1)
        val h = (sh * scale).toInt().coerceAtLeast(1)
        val v = HudBackground(ctx, null)
        v.unit = ctx.resources.displayMetrics.density * scale
        v.accent = accent
        v.style = style
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    /** Blocking. Sets the current ALFA style as wallpaper on home (and lock screen if [lock]). */
    fun applyCarbon(ctx: Context, accent: Int, lock: Boolean) {
        val style = style(ctx)
        val bmp = render(ctx, accent, style)
        val wm = WallpaperManager.getInstance(ctx)
        var which = WallpaperManager.FLAG_SYSTEM
        if (lock) which = which or WallpaperManager.FLAG_LOCK
        wm.setBitmap(bmp, null, true, which)
        prefs(ctx).edit()
            .putBoolean("carbon_applied", true)
            .putInt("carbon_applied_accent", accent)
            .putString("carbon_applied_style", style)
            .apply()
    }
}
