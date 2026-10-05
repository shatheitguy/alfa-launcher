package com.alfa.launcher

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** QR helpers shared by the QR tool and ALFA Assistant. */
object QrUtil {

    fun render(content: String, size: Int = 1024): Bitmap {
        val hints = mapOf(
            com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            com.google.zxing.EncodeHintType.MARGIN to 2,
        )
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) px[y * m.width + x] = if (m.get(x, y)) Color.rgb(10, 10, 14) else Color.WHITE
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")

    /** Standard Wi-Fi join payload; security is WPA, WEP or nopass. */
    fun wifi(ssid: String, password: String, security: String, hidden: Boolean = false): String {
        val sec = when (security.uppercase()) { "WEP" -> "WEP"; "NOPASS", "OPEN", "NONE" -> "nopass"; else -> "WPA" }
        return "WIFI:T:$sec;S:${esc(ssid)};" + (if (sec != "nopass") "P:${esc(password)};" else "") + (if (hidden) "H:true;" else "") + ";"
    }

    /** Android 10+: saves to Pictures/ALFA and returns the content Uri (null on older Android). */
    fun save(c: Context, bmp: Bitmap, name: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name-${System.currentTimeMillis() / 1000}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ALFA")
        }
        val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        c.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri
    }
}
