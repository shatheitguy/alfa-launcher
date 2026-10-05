package com.alfa.launcher

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
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
            val code = c.responseCode
            if (code == 403 || code == 429) {
                // The GitHub API allows ~60 unauthenticated checks per hour per network. Fall back to
                // the releases page redirect, which isn't rate-limited the same way.
                return fetchViaRedirect() ?: throw IOException(rateLimitMessage(c))
            }
            if (code != 200) throw IOException("GitHub returned HTTP $code")
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

    /** Latest tag from github.com/…/releases/latest -> 302 …/tag/v1.0.N. No notes or size. */
    private fun fetchViaRedirect(): Release? {
        val c = URL("https://github.com/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "ALFA-Launcher")
        return try {
            val tag = c.getHeaderField("Location")?.substringAfterLast("/tag/", "").orEmpty()
            if (tag.isEmpty()) null
            else Release(tag = tag, code = tag.substringAfterLast('.').toLongOrNull() ?: 0L, name = tag,
                notes = "", apkUrl = LATEST_APK, size = 0L)
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun rateLimitMessage(c: HttpURLConnection): String {
        val reset = c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
        val mins = if (reset != null) ((reset * 1000 - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1) else null
        return "GitHub's update-check limit was reached" + (if (mins != null) " — try again in $mins min" else " — try again later")
    }

    // ---- in-app download via the system DownloadManager ----
    // ALFA downloads the APK itself; the install is started from the system download
    // (notification / Downloads app), so ALFA needs no "install other apps" permission.

    enum class DlState { RUNNING, DONE, FAILED }
    data class DlProgress(val state: DlState, val done: Long, val total: Long, val reason: String = "")

    private const val APK_MIME = "application/vnd.android.package-archive"

    fun enqueue(ctx: Context, rel: Release): Long {
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return -1L
        val name = "alfa-launcher-${rel.tag}.apk"
        val req = DownloadManager.Request(Uri.parse(rel.apkUrl))
            .setTitle("ALFA Launcher ${rel.tag}")
            .setDescription("Tap to install the update")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (Build.VERSION.SDK_INT >= 29) {
            // public Downloads folder needs no storage permission on Android 10+
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        } else {
            req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name)
        }
        return dm.enqueue(req)
    }

    fun progress(ctx: Context, id: Long): DlProgress {
        val dm = ctx.getSystemService(DownloadManager::class.java)
            ?: return DlProgress(DlState.FAILED, 0, 0, "no download manager")
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return DlProgress(DlState.FAILED, 0, 0, "cancelled")
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DlProgress(DlState.DONE, total, total)
                DownloadManager.STATUS_FAILED -> {
                    val r = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    DlProgress(DlState.FAILED, done, total, "code $r")
                }
                else -> DlProgress(DlState.RUNNING, done, total)
            }
        }
        return DlProgress(DlState.FAILED, 0, 0, "query failed")
    }

    // ---- self-install via PackageInstaller (needs "Install unknown apps" allowed for ALFA) ----

    fun canSelfInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows ALFA to install apps. */
    fun requestInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Blocking: streams the finished download into a PackageInstaller session and commits it.
     * Android then shows its "Update this app?" confirmation (see [InstallReceiver]).
     */
    fun installDownloaded(ctx: Context, downloadId: Long) {
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: throw IOException("no download manager")
        val installer = ctx.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            dm.openDownloadedFile(downloadId).use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                    session.openWrite("alfa-update.apk", 0, pfd.statSize).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
            }
            var flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or android.app.PendingIntent.FLAG_MUTABLE
            val pending = android.app.PendingIntent.getBroadcast(
                ctx, sessionId, Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName), flags
            )
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    /** Shows the system Downloads list, where tapping the APK opens the installer. */
    fun openDownloads(ctx: Context) {
        try {
            ctx.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // some ROMs have no Downloads UI; the completed-download notification still works
        }
    }

    /** Fallback: opens the APK download in the browser. */
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
