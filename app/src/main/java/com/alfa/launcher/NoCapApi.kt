package com.alfa.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Talks to a NoCap server (/api/v1/...). All calls are blocking: run them off the main thread.
 *
 * TLS is trust-on-first-use. NoCap servers use self-signed certificates, so the very first
 * /pair/info request accepts whatever certificate the server presents and records its SHA-256.
 * The user sees that fingerprint before linking, and from then on every request to that server
 * accepts only that exact certificate. Nothing here changes the app-wide or system trust.
 */
object NoCapApi {

    const val DEFAULT_SERVER = "https://nocap.local"
    private const val TIMEOUT = 8000

    // ---------------- results / errors ----------------

    data class Info(val nocapId: String, val name: String, val version: String, val fingerprint: String)
    data class AppTile(val id: String, val name: String, val iconUrl: String, val url: String)
    data class Me(val deviceName: String, val username: String, val nocapId: String, val serverName: String, val version: String)

    /** Every failure, with a message that is already fit to show. */
    open class NoCapException(msg: String) : IOException(msg)
    class Unlinked : NoCapException("Your server unlinked this phone")
    class PinMismatch : NoCapException("Server's cert changed — sus. Unlink and relink if that's legit")
    class HttpError(val status: Int, val code: String?, msg: String) : NoCapException(msg)

    private class PinMismatchCert : CertificateException("certificate does not match the pinned fingerprint")

    // ---------------- address helpers ----------------

    /** "nocap.local" -> "https://nocap.local", trims spaces and trailing slashes. Null if unusable. */
    fun normalize(raw: String): String? {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val u = try { Uri.parse(s) } catch (e: Exception) { return null }
        if (u.scheme?.lowercase(Locale.ROOT) != "https" || u.host.isNullOrEmpty()) return null
        val port = if (u.port > 0) ":${u.port}" else ""
        val path = u.path?.trimEnd('/').orEmpty()
        return "https://${u.host}$port$path"
    }

    /** Resolves a possibly relative URL (e.g. "/apps/files") against the server address. */
    fun resolve(server: String, url: String): String =
        try { URL(URL("$server/"), url).toString() } catch (e: Exception) { url }

    fun sameServer(server: String, url: String): Boolean = try {
        val a = URL(server); val b = URL(url)
        a.protocol == b.protocol && a.host.equals(b.host, true) && portOf(a) == portOf(b)
    } catch (e: Exception) { false }

    private fun portOf(u: URL) = if (u.port > 0) u.port else u.defaultPort

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    /** "AB12 CD34 EF56 7890": the first 64 bits, easy to compare by eye. */
    fun shortFingerprint(fp: String): String = fp.take(16).chunked(4).joinToString(" ")

    // ---------------- TLS ----------------

    /** Accepts exactly one certificate ([expected]), or, when null, records whatever is presented. */
    private class PinTrust(val expected: String?) : X509TrustManager {
        @Volatile var seen: String? = null
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            val fp = sha256Hex(leaf.encoded)
            seen = fp
            if (expected != null && !fp.equals(expected, ignoreCase = true)) throw PinMismatchCert()
        }
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client certificates are not used")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class Pinned(val factory: SSLSocketFactory, val trust: PinTrust, val verifier: HostnameVerifier)

    private val pinnedCache = HashMap<String, Pinned>()

    private fun build(expected: String?): Pinned {
        val trust = PinTrust(expected)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        // The certificate itself is pinned, which is stronger than a name check (and the
        // self-signed cert's name rarely matches the IP address the phone uses).
        val verifier = HostnameVerifier { _, session ->
            val leaf = try { session.peerCertificates.firstOrNull() } catch (e: Exception) { null }
            leaf != null && (expected == null || sha256Hex(leaf.encoded).equals(expected, true))
        }
        return Pinned(ctx.socketFactory, trust, verifier)
    }

    private fun pinned(fp: String): Pinned = synchronized(pinnedCache) {
        pinnedCache.getOrPut(fp.uppercase(Locale.ROOT)) { build(fp) }
    }

    private fun open(url: String, pin: Pinned?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT
        c.readTimeout = TIMEOUT
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "NoCap-OS-Android")
        if (c is HttpsURLConnection && pin != null) {
            c.sslSocketFactory = pin.factory
            c.hostnameVerifier = pin.verifier
        }
        return c
    }

    private fun friendly(e: Exception): NoCapException = when {
        e is NoCapException -> e
        e.causeChain().any { it is PinMismatchCert } -> PinMismatch()
        e is UnknownHostException -> NoCapException("Can't find that server — check the address (or use its IP)")
        e is ConnectException || e is NoRouteToHostException || e is SocketTimeoutException ->
            NoCapException("Server's ghosting us — check Wi-Fi")
        e is SSLException -> NoCapException("Secure connection flopped — is that the https address?")
        e is org.json.JSONException -> NoCapException("Server sent something weird — is that a NoCap server?")
        else -> NoCapException("Server's ghosting us — check Wi-Fi")
    }

    /** optString, but JSON null (e.g. unknown battery / last_seen) reads as "" instead of "null". */
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private fun body(c: HttpURLConnection): String {
        val s = try { c.inputStream } catch (e: IOException) { c.errorStream } ?: return ""
        return s.bufferedReader().use { it.readText() }
    }

    private fun errorOf(status: Int, text: String): HttpError {
        val err = try { JSONObject(text).optJSONObject("error") } catch (e: Exception) { null }
        val code = err?.str("code")?.takeIf { it.isNotEmpty() }
        val msg = when {
            status == 429 -> "Too many tries — chill for a minute, then go again"
            status == 404 && code == null -> "That's not a NoCap server (or it needs an update)"
            status >= 500 -> "Server tripped — try again in a sec"
            else -> err?.str("message")?.takeIf { it.isNotEmpty() } ?: "Server said no (HTTP $status)"
        }
        return HttpError(status, code, msg)
    }

    // ---------------- pairing ----------------

    /** First contact. Accepts any certificate for this one request and reports its fingerprint. */
    fun pairInfo(server: String): Info {
        try {
            val pin = build(null)
            val c = open("$server/api/v1/pair/info", pin)
            try {
                val status = c.responseCode
                val text = body(c)
                if (status != 200) throw errorOf(status, text)
                val fp = pin.trust.seen ?: throw NoCapException("Secure connection flopped — is that the https address?")
                val j = JSONObject(text)
                return Info(
                    nocapId = j.str("nocap_id"),
                    name = j.str("name").ifEmpty { "NoCap server" },
                    version = j.str("version"),
                    fingerprint = fp,
                )
            } finally { c.disconnect() }
        } catch (e: Exception) { throw friendly(e) }
    }

    /** Claims the 6-digit code over a connection pinned to the fingerprint the user just saw. */
    fun claim(server: String, fingerprint: String, code: String, deviceName: String): NoCapLink.Link {
        try {
            val c = open("$server/api/v1/pair/claim", pinned(fingerprint))
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                val payload = JSONObject()
                    .put("code", code)
                    .put("device_name", deviceName)
                    .put("platform", "android")
                c.outputStream.use { it.write(payload.toString().toByteArray()) }
                val status = c.responseCode
                val text = body(c)
                if (status !in 200..299) {
                    val err = errorOf(status, text)
                    if (status == 429) {
                        throw HttpError(status, err.code, "Too many wrong codes — you're on a 15-min timeout. Try again after")
                    }
                    if (err.code == "code_expired") {
                        throw HttpError(status, err.code, "That code expired — make a fresh one on the server")
                    }
                    if (status in 400..499 && !(status == 404 && err.code == null)) {
                        throw HttpError(status, err.code, "That code's not it, try again")
                    }
                    throw err
                }
                val j = JSONObject(text)
                val token = j.str("token")
                if (token.isEmpty()) throw NoCapException("Server sent something weird — is that a NoCap server?")
                return NoCapLink.Link(
                    server = server,
                    token = token,
                    fingerprint = fingerprint,
                    deviceId = j.str("device_id"),
                    nocapId = j.str("nocap_id"),
                    serverName = j.str("server_name"),
                    username = j.optJSONObject("user")?.str("username").orEmpty(),
                    version = "",
                )
            } finally { c.disconnect() }
        } catch (e: Exception) { throw friendly(e) }
    }

    // ---------------- device calls (Authorization: Device <token>) ----------------

    private fun authed(link: NoCapLink.Link, path: String, method: String = "GET", json: JSONObject? = null): String {
        try {
            val c = open(link.server + path, pinned(link.fingerprint))
            try {
                c.requestMethod = method
                c.setRequestProperty("Authorization", "Device ${link.token}")
                if (json != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(json.toString().toByteArray()) }
                }
                val status = c.responseCode
                val text = body(c)
                if (status == 401 || status == 403) throw Unlinked()
                if (status !in 200..299) throw errorOf(status, text)
                return text
            } finally { c.disconnect() }
        } catch (e: Exception) { throw friendly(e) }
    }

    fun me(link: NoCapLink.Link): Me {
        val j = JSONObject(authed(link, "/api/v1/device/me"))
        val server = j.optJSONObject("server")
        return Me(
            deviceName = j.optJSONObject("device")?.str("name").orEmpty(),
            username = j.optJSONObject("user")?.str("username").orEmpty(),
            nocapId = server?.str("nocap_id").orEmpty(),
            serverName = server?.str("name").orEmpty(),
            version = server?.str("version").orEmpty(),
        )
    }

    /** Raw JSON of /device/apps (cached by the caller). */
    fun appsJson(link: NoCapLink.Link): String {
        val text = authed(link, "/api/v1/device/apps")
        parseApps(text) // validate before it gets cached
        return text
    }

    fun parseApps(text: String): List<AppTile> {
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.str("url")
            if (url.isEmpty()) null
            else AppTile(o.str("id"), o.str("name").ifEmpty { "App" }, o.str("icon_url"), url)
        }
    }

    fun heartbeat(link: NoCapLink.Link, battery: Int, appVersion: String) {
        authed(link, "/api/v1/device/heartbeat", "POST", JSONObject().put("battery", battery).put("app_version", appVersion))
    }

    // ---------------- app icons ----------------

    private val iconMem = HashMap<String, Bitmap>()

    /**
     * Icon for a server app. Icons on the linked server go through the pinned connection;
     * icons hosted elsewhere use the normal system trust. Cached in memory and on disk.
     */
    fun icon(ctx: Context, link: NoCapLink.Link, rawUrl: String, sizePx: Int): Bitmap? {
        if (rawUrl.isEmpty()) return null
        val url = resolve(link.server, rawUrl)
        synchronized(iconMem) { iconMem[url]?.let { return it } }
        val file = File(iconDir(ctx), sha256Hex(url.toByteArray()).take(32))
        var bytes: ByteArray? = if (file.exists()) try { file.readBytes() } catch (e: Exception) { null } else null
        if (bytes == null) {
            bytes = try {
                val c = open(url, if (sameServer(link.server, url)) pinned(link.fingerprint) else null)
                if (sameServer(link.server, url)) c.setRequestProperty("Authorization", "Device ${link.token}")
                c.setRequestProperty("Accept", "image/*")
                c.instanceFollowRedirects = !sameServer(link.server, url)
                try {
                    if (c.responseCode != 200) null
                    else c.inputStream.use { s -> s.readBytes() }
                } finally { c.disconnect() }
            } catch (e: Exception) { null } ?: return null
            if (bytes.size < 2_000_000) try { file.writeBytes(bytes) } catch (e: Exception) { /* cache only */ }
        }
        val bmp = decode(bytes, sizePx) ?: return null
        synchronized(iconMem) { iconMem[url] = bmp }
        return bmp
    }

    private fun decode(bytes: ByteArray, sizePx: Int): Bitmap? {
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null // SVG etc.: letter tile instead
        if (raw.width <= sizePx * 2) return raw
        return Bitmap.createScaledBitmap(raw, sizePx * 2, sizePx * 2 * raw.height / raw.width, true)
    }

    private fun iconDir(ctx: Context) = File(ctx.cacheDir, "nocap_icons").apply { mkdirs() }

    fun forgetIcons(ctx: Context) {
        synchronized(iconMem) { iconMem.clear() }
        synchronized(pinnedCache) { pinnedCache.clear() }
        try { iconDir(ctx).deleteRecursively() } catch (e: Exception) { /* cache only */ }
    }
}
