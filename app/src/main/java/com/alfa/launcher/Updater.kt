package com.alfa.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks this app's GitHub Releases for a newer build. Release tags are v1.0.<versionCode>.
 * The APK itself is downloaded and installed by the browser / system installer, so the
 * launcher does not need the "install other apps" permission.
 */
object Updater {

    const val REPO = "shatheitguy/alfa-launcher"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val LATEST_APK = "https://github.com/$REPO/releases/latest/download/alfa-launcher.apk"
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

    /** Opens the APK download in the browser; the user installs it from the download. */
    fun downloadInBrowser(ctx: Context, rel: Release?) {
        val url = rel?.apkUrl ?: LATEST_APK
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
