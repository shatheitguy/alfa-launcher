package com.alfa.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** User profile shown on the home screen: name + logo (or generated initials). */
object Profile {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("alfa", Context.MODE_PRIVATE)

    fun logoFile(ctx: Context) = File(ctx.filesDir, "profile_logo.png")

    fun name(ctx: Context): String = prefs(ctx).getString("profile_name", "")?.trim().orEmpty()

    fun setName(ctx: Context, name: String) = prefs(ctx).edit().putString("profile_name", name.trim()).apply()

    fun hasLogo(ctx: Context) = logoFile(ctx).exists()

    fun removeLogo(ctx: Context) { logoFile(ctx).delete() }

    /** The saved logo, or an initials badge in the accent colour. */
    fun avatar(ctx: Context, accent: Int, sizePx: Int): Bitmap {
        val f = logoFile(ctx)
        if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { return it }
        val s = sizePx
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(s / 2f, s * 0.3f, s * 0.8f, Color.rgb(44, 44, 52), Color.rgb(10, 10, 13), Shader.TileMode.CLAMP)
        c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.shader = null
        val initials = name(ctx).split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
        p.color = accent
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        p.textSize = s * if (initials.length > 1) 0.42f else 0.5f
        c.drawText(initials.ifEmpty { "α" }, s / 2f, s / 2f - (p.descent() + p.ascent()) / 2f, p)
        return bmp
    }

    @Suppress("DEPRECATION")
    fun pickIntent(): Intent =
        if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)

    /** Blocking: centre-crops the picked image to a square, scales and saves it. */
    fun importLogo(ctx: Context, uri: Uri) {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw IOException("unreadable image")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) sample *= 2
        val src = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("unreadable image")
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(square, 384, 384, true)
        logoFile(ctx).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
