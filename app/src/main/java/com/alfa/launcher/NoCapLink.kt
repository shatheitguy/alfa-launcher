package com.alfa.launcher

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The phone's link to a NoCap server: server address, device token and the pinned TLS
 * certificate fingerprint. Everything lives in EncryptedSharedPreferences (keys in the
 * Android Keystore), never in the plain "alfa" prefs.
 */
object NoCapLink {

    private const val FILE = "nocap_link"

    data class Link(
        val server: String,
        val token: String,
        val fingerprint: String,
        val deviceId: String,
        val nocapId: String,
        val serverName: String,
        val username: String,
        val version: String,
    )

    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = ctx.applicationContext
            val p = try {
                open(app)
            } catch (e: Exception) {
                // keystore key lost (e.g. restored backup): the old data can't be read anyway
                app.deleteSharedPreferences(FILE)
                open(app)
            }
            cached = p
            return p
        }
    }

    private fun open(ctx: Context): SharedPreferences {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(ctx: Context): Link? {
        val p = try { prefs(ctx) } catch (e: Exception) { return null }
        val server = p.getString("server", null) ?: return null
        val token = p.getString("token", null) ?: return null
        val fp = p.getString("fingerprint", null) ?: return null
        return Link(
            server = server,
            token = token,
            fingerprint = fp,
            deviceId = p.getString("device_id", "") ?: "",
            nocapId = p.getString("nocap_id", "") ?: "",
            serverName = p.getString("server_name", "") ?: "",
            username = p.getString("username", "") ?: "",
            version = p.getString("version", "") ?: "",
        )
    }

    fun isLinked(ctx: Context) = get(ctx) != null

    fun save(ctx: Context, l: Link) {
        prefs(ctx).edit()
            .clear()
            .putString("server", l.server)
            .putString("token", l.token)
            .putString("fingerprint", l.fingerprint)
            .putString("device_id", l.deviceId)
            .putString("nocap_id", l.nocapId)
            .putString("server_name", l.serverName)
            .putString("username", l.username)
            .putString("version", l.version)
            .commit()
    }

    /** Refreshes the display info from /device/me without touching the secrets. */
    fun updateInfo(ctx: Context, serverName: String?, nocapId: String?, username: String?, version: String?) {
        val e = prefs(ctx).edit()
        if (!serverName.isNullOrEmpty()) e.putString("server_name", serverName)
        if (!nocapId.isNullOrEmpty()) e.putString("nocap_id", nocapId)
        if (!username.isNullOrEmpty()) e.putString("username", username)
        if (!version.isNullOrEmpty()) e.putString("version", version)
        e.apply()
    }

    /** Cached /device/apps response, so the home panel shows tiles instantly. */
    fun cachedApps(ctx: Context): String? = try { prefs(ctx).getString("apps", null) } catch (e: Exception) { null }

    fun cacheApps(ctx: Context, json: String) {
        try { prefs(ctx).edit().putString("apps", json).apply() } catch (e: Exception) { /* cache only */ }
    }

    fun clear(ctx: Context) {
        try { prefs(ctx).edit().clear().commit() } catch (e: Exception) { /* nothing stored */ }
        HeartbeatWorker.cancel(ctx)
        NoCapApi.forgetIcons(ctx)
    }
}
