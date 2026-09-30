package com.alfa.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Self-update from this app's GitHub Releases. Release tags are v1.0.<versionCode>. */
object Updater {

    const val REPO = "shatheitguy/alfa-launcher"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    data class Release(
        val tag: String,
        val code: Long,
        val name: String,
        val notes: String,
        val apkUrl: String,
        val size: Long,
    )

    fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Blocking network call. */
    fun fetchLatest(): Release {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "ALFA-Launcher")
        try {
            if (c.responseCode != 200) throw IOException("GitHub returned HTTP ${c.responseCode}")
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            var url = ""
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    url = a.getString("browser_download_url")
                    size = a.optLong("size")
                    break
                }
            }
            if (url.isEmpty()) throw IOException("Latest release has no APK")
            return Release(
                tag = tag,
                code = tag.substringAfterLast('.').toLongOrNull() ?: 0L,
                name = json.optString("name", tag),
                notes = json.optString("body", "").trim(),
                apkUrl = url,
                size = size,
            )
        } finally {
            c.disconnect()
        }
    }

    private fun apkFile(ctx: Context) = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "alfa-update.apk")

    /** Blocking download; reuses an already-complete file. */
    fun download(ctx: Context, rel: Release, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): File {
        val out = apkFile(ctx)
        val marker = File(out.parentFile, "tag.txt")
        if (out.exists() && rel.size > 0 && out.length() == rel.size && marker.exists() && marker.readText() == rel.tag) {
            progress(rel.size, rel.size)
            return out
        }
        out.delete()
        val c = URL(rel.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "ALFA-Launcher")
        try {
            if (c.responseCode != 200) throw IOException("Download failed: HTTP ${c.responseCode}")
            val total = if (rel.size > 0) rel.size else c.contentLengthLong
            var done = 0L
            c.inputStream.use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
            if (rel.size > 0 && out.length() != rel.size) throw IOException("Download incomplete")
            marker.writeText(rel.tag)
            return out
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** Returns false if the user first has to allow installs from this app. */
    fun install(activity: Activity, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(i)
        return true
    }

    // ---- background check bookkeeping (used by the home screen) ----

    fun shouldAutoCheck(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("alfa", Context.MODE_PRIVATE)
        return p.getBoolean("auto_update", true) &&
            System.currentTimeMillis() - p.getLong("update_checked_at", 0L) > CHECK_INTERVAL_MS
    }

    fun remember(ctx: Context, rel: Release) {
        ctx.getSharedPreferences("alfa", Context.MODE_PRIVATE).edit()
            .putLong("update_checked_at", System.currentTimeMillis())
            .putLong("latest_code", rel.code)
            .putString("latest_tag", rel.tag)
            .apply()
    }

    /** Tag of a newer release we already know about, or null. */
    fun knownUpdate(ctx: Context): String? {
        val p = ctx.getSharedPreferences("alfa", Context.MODE_PRIVATE)
        return if (p.getLong("latest_code", 0L) > currentCode(ctx)) p.getString("latest_tag", null) else null
    }
}
