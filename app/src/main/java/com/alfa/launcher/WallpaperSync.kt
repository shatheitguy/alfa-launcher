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
 * Keeps one wallpaper for everything: the ALFA carbon background is rendered at screen
 * size in the accent colour and set as the real Android wallpaper, so the launcher,
 * Recents and app transitions all show the same image.
 */
object WallpaperSync {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("alfa", Context.MODE_PRIVATE)

    /** Is the system wallpaper currently ALFA carbon in this accent? */
    fun isApplied(ctx: Context, accent: Int): Boolean =
        prefs(ctx).getInt("carbon_applied_accent", 0) == accent && prefs(ctx).getBoolean("carbon_applied", false)

    fun markCustom(ctx: Context) =
        prefs(ctx).edit().putBoolean("carbon_applied", false).apply()

    @Suppress("DEPRECATION")
    private fun screenSize(ctx: Context): Pair<Int, Int> {
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

    /** Renders the carbon background off-screen. */
    fun render(ctx: Context, accent: Int): Bitmap {
        val (w, h) = screenSize(ctx)
        val v = HudBackground(ctx, null)
        v.accent = accent
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    /** Blocking. Sets the carbon wallpaper on the home screen (and lock screen if [lock]). */
    fun applyCarbon(ctx: Context, accent: Int, lock: Boolean) {
        val bmp = render(ctx, accent)
        val wm = WallpaperManager.getInstance(ctx)
        var which = WallpaperManager.FLAG_SYSTEM
        if (lock) which = which or WallpaperManager.FLAG_LOCK
        wm.setBitmap(bmp, null, true, which)
        prefs(ctx).edit()
            .putBoolean("carbon_applied", true)
            .putInt("carbon_applied_accent", accent)
            .apply()
    }
}
