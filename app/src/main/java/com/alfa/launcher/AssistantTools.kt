package com.alfa.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.AlarmClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/** One action the assistant may take. [params] is a JSON-Schema "properties" object. */
class ToolSpec(
    val name: String,
    val description: String,
    val params: JSONObject,
    val required: List<String>,
    val run: (Context, JSONObject) -> String,
)

/**
 * Everything ALFA Assistant can do. It acts only through these launcher / Android actions,
 * never by driving other apps' screens. Results are short plain-text summaries the model
 * reads back.
 */
object AssistantTools {

    private fun prefs(c: Context) = c.getSharedPreferences("alfa", Context.MODE_PRIVATE)

    private fun str(desc: String, enum: List<String>? = null) = JSONObject().put("type", "string").put("description", desc).apply {
        if (enum != null) put("enum", JSONArray(enum))
    }
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    private fun props(vararg p: Pair<String, JSONObject>) = JSONObject().apply { p.forEach { put(it.first, it.second) } }

    // ---------------- apps ----------------

    class App(val label: String, val pkg: String, val cls: String, val user: UserHandle, val key: String, val clone: Boolean)

    fun apps(c: Context): List<App> {
        val la = c.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val um = c.getSystemService(UserManager::class.java) ?: return emptyList()
        val me = Process.myUserHandle()
        val out = ArrayList<App>()
        for (u in try { um.userProfiles } catch (e: Exception) { listOf(me) }) {
            val main = u == me
            val tag = if (main) "" else um.getSerialNumberForUser(u).toString()
            for (a in try { la.getActivityList(null, u) } catch (e: Exception) { emptyList() }) {
                val cn = a.componentName
                if (cn.packageName == c.packageName) continue
                val label = a.label?.toString() ?: cn.packageName
                val key = if (tag.isEmpty()) "${cn.packageName}/${cn.className}" else "${cn.packageName}/${cn.className}#$tag"
                out.add(App(if (main) label else "$label (dual)", cn.packageName, cn.className, u, key, !main))
            }
        }
        return out.sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    /** Best match for a spoken/typed app name ("whatsapp", "dual whatsapp", "settings"). */
    fun findApp(c: Context, query: String): App? {
        val q = query.lowercase(Locale.ROOT).trim()
        val wantClone = q.contains("dual") || q.contains("clone") || q.contains("second") || q.contains("2nd")
        val core = q.replace(Regex("\\b(dual|clone|cloned|second|2nd|app|the|my)\\b"), "").trim()
        val all = apps(c).filter { it.clone == wantClone }.ifEmpty { apps(c) }
        return all.firstOrNull { it.label.lowercase(Locale.ROOT).removeSuffix(" (dual)") == core }
            ?: all.firstOrNull { it.label.lowercase(Locale.ROOT).startsWith(core) }
            ?: all.firstOrNull { it.label.lowercase(Locale.ROOT).contains(core) }
            ?: all.firstOrNull { it.pkg.lowercase(Locale.ROOT).contains(core.replace(" ", "")) }
    }

    private fun launch(c: Context, a: App) {
        val la = c.getSystemService(LauncherApps::class.java)
        la.startMainActivity(ComponentName(a.pkg, a.cls), a.user, null, null)
    }

    private fun list(c: Context, key: String): MutableList<String> =
        prefs(c).getString(key, null)?.split("|")?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()

    private fun saveList(c: Context, key: String, l: List<String>) = prefs(c).edit().putString(key, l.joinToString("|")).apply()

    // ---------------- helpers ----------------

    private fun start(c: Context, i: Intent): String = try {
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "ok"
    } catch (e: Exception) { "not available on this device" }

    private fun accentNames() = MainActivity.ACCENTS.map { it.first.lowercase(Locale.ROOT) }

    private val settingKeys = mapOf(
        "orbit_spin" to "spin", "3d_tilt" to "galaxy_3d", "idle_drift" to "drift", "vibration" to "haptics",
        "it_tools_button" to "show_tools", "all_clone_profile_apps" to "clone_show_all", "auto_update_check" to "auto_update",
    )

    private val panels = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS, "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "mobile_data" to Settings.ACTION_DATA_ROAMING_SETTINGS, "vpn" to Settings.ACTION_VPN_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS, "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "apps" to Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS,
        "developer_options" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "date_time" to Settings.ACTION_DATE_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS, "security" to Settings.ACTION_SECURITY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS, "about_phone" to Settings.ACTION_DEVICE_INFO_SETTINGS,
        "android_settings" to Settings.ACTION_SETTINGS,
    )

    private val itTools = listOf("network", "speed", "wifi", "ssl", "wol", "qr", "ping", "dns", "ports", "subnet", "device", "hash", "password", "shortcuts", "update")

    // ---------------- the tool list ----------------

    val all: List<ToolSpec> by lazy {
        listOf(
            ToolSpec("open_app", "Open an installed app by name. Add 'dual' to open a cloned copy (e.g. 'dual whatsapp').",
                props("name" to str("App name as the user said it")), listOf("name")) { c, a ->
                val app = findApp(c, a.getString("name")) ?: return@ToolSpec "No app matching \"${a.getString("name")}\". Use find_apps to list candidates."
                launch(c, app); "Opened ${app.label}."
            },
            ToolSpec("find_apps", "Search installed apps by name. Returns up to 20 matches; empty query lists the first 40 apps.",
                props("query" to str("Part of an app name, or empty")), listOf("query")) { c, a ->
                val q = a.optString("query").lowercase(Locale.ROOT)
                val m = apps(c).filter { q.isEmpty() || it.label.lowercase(Locale.ROOT).contains(q) }
                if (m.isEmpty()) "No matching apps." else m.take(if (q.isEmpty()) 40 else 20).joinToString(", ") { it.label }
            },
            ToolSpec("set_accent", "Change ALFA's accent colour (icons, rings, wallpaper).",
                props("color" to str("Accent colour", accentNames())), listOf("color")) { c, a ->
                val name = a.getString("color").lowercase(Locale.ROOT)
                val hex = MainActivity.ACCENTS.firstOrNull { it.first.lowercase(Locale.ROOT) == name }?.second
                    ?: return@ToolSpec "Unknown colour. Options: ${accentNames().joinToString()}"
                val col = Color.parseColor(hex)
                prefs(c).edit().putInt("accent", col).apply()
                if (!prefs(c).getBoolean("wallpaper", false)) {
                    try { WallpaperSync.applyCarbon(c, col, prefs(c).getBoolean("wall_lock", false)) } catch (e: Exception) {}
                }
                "Accent set to $name."
            },
            ToolSpec("set_wallpaper", "Set one of ALFA's wallpaper styles as the system wallpaper, in the accent colour.",
                props("style" to str("Wallpaper style", HudBackground.STYLES.map { it.first })), listOf("style")) { c, a ->
                val s = a.getString("style").lowercase(Locale.ROOT)
                if (HudBackground.STYLES.none { it.first == s }) return@ToolSpec "Unknown style. Options: ${HudBackground.STYLES.joinToString { it.first }}"
                prefs(c).edit().putBoolean("wallpaper", false).apply()
                WallpaperSync.setStyle(c, s)
                WallpaperSync.applyCarbon(c, MainActivity.accentOf(c), prefs(c).getBoolean("wall_lock", false))
                "Wallpaper set to $s."
            },
            ToolSpec("set_icon_style", "Change how app icons look.",
                props("style" to str("Icon style", IconStyler.STYLES.map { it.first })), listOf("style")) { c, a ->
                val s = a.getString("style").lowercase(Locale.ROOT)
                if (IconStyler.STYLES.none { it.first == s }) return@ToolSpec "Unknown style."
                prefs(c).edit().putString("icon_style", s).apply(); "Icon style set to $s."
            },
            ToolSpec("set_setting", "Turn an ALFA setting on or off.",
                props("setting" to str("Setting", settingKeys.keys.toList()), "on" to bool("true = on, false = off")),
                listOf("setting", "on")) { c, a ->
                val k = settingKeys[a.getString("setting")] ?: return@ToolSpec "Unknown setting."
                prefs(c).edit().putBoolean(k, a.getBoolean("on")).apply()
                "${a.getString("setting")} is now ${if (a.getBoolean("on")) "on" else "off"}."
            },
            ToolSpec("edit_home", "Add or remove an app on the home orbit (max 8) or the dock (max 5).",
                props("app" to str("App name"), "place" to str("Where", listOf("orbit", "dock")),
                    "action" to str("What to do", listOf("add", "remove"))), listOf("app", "place", "action")) { c, a ->
                val app = findApp(c, a.getString("app")) ?: return@ToolSpec "No app matching that name."
                val place = a.getString("place")
                val l = list(c, place)
                val max = if (place == "orbit") 8 else 5
                if (a.getString("action") == "remove") {
                    if (!l.remove(app.key)) return@ToolSpec "${app.label} isn't in the $place."
                } else {
                    if (app.key in l) return@ToolSpec "${app.label} is already in the $place."
                    if (l.size >= max) return@ToolSpec "The $place is full ($max apps). Remove one first."
                    l.add(app.key)
                    val other = if (place == "orbit") "dock" else "orbit"
                    saveList(c, other, list(c, other).apply { remove(app.key) })
                }
                saveList(c, place, l)
                "${if (a.getString("action") == "add") "Added" else "Removed"} ${app.label} ${if (a.getString("action") == "add") "to" else "from"} the $place."
            },
            ToolSpec("open_it_tool", "Open one of ALFA's IT Tools screens (speed test, QR generator, port check, etc.).",
                props("tool" to str("Tool", itTools)), listOf("tool")) { c, a ->
                val t = a.getString("tool")
                if (t !in itTools) return@ToolSpec "Unknown tool."
                start(c, Intent(c, ToolsActivity::class.java).putExtra(ToolsActivity.EXTRA_TOOL, t)); "Opened the $t tool."
            },
            ToolSpec("open_settings_panel", "Open an Android system settings screen.",
                props("panel" to str("Panel", panels.keys.toList())), listOf("panel")) { c, a ->
                val act = panels[a.getString("panel")] ?: return@ToolSpec "Unknown panel."
                "Opening ${a.getString("panel")}: " + start(c, Intent(act))
            },
            ToolSpec("open_alfa_settings", "Open ALFA OS Settings.", props(), emptyList()) { c, _ ->
                start(c, Intent(c, SettingsActivity::class.java)); "Opened ALFA OS Settings."
            },
            ToolSpec("set_timer", "Start a countdown timer in the clock app.",
                props("minutes" to int("Length in minutes"), "seconds" to int("Extra seconds, usually 0"), "label" to str("Optional label")),
                listOf("minutes")) { c, a ->
                val secs = a.optInt("minutes") * 60 + a.optInt("seconds")
                if (secs <= 0) return@ToolSpec "Timer length must be more than 0."
                start(c, Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "ALFA timer")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                "Timer set for ${secs / 60} min ${secs % 60} s."
            },
            ToolSpec("set_alarm", "Set an alarm in the clock app (24-hour time).",
                props("hour" to int("0-23"), "minute" to int("0-59"), "label" to str("Optional label")), listOf("hour", "minute")) { c, a ->
                val h = a.getInt("hour"); val m = a.getInt("minute")
                if (h !in 0..23 || m !in 0..59) return@ToolSpec "Invalid time."
                start(c, Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "ALFA alarm")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                String.format(Locale.US, "Alarm set for %02d:%02d.", h, m)
            },
            ToolSpec("web_search", "Search the web in the browser.", props("query" to str("Search terms")), listOf("query")) { c, a ->
                val q = a.getString("query")
                val r = start(c, Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q))
                if (r == "ok") "Searching for \"$q\"." else start(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))).let { "Searching for \"$q\"." }
            },
            ToolSpec("device_status", "Battery, charging, temperature, RAM, storage, uptime and network of this phone.", props(), emptyList()) { c, _ ->
                status(c)
            },
            ToolSpec("network_info", "Current network: type, local IP, gateway, DNS servers.", props(), emptyList()) { c, _ ->
                netInfo(c)
            },
            ToolSpec("ping", "Ping a host (4 packets) and return the summary.", props("host" to str("Hostname or IP")), listOf("host")) { _, a ->
                ping(a.getString("host"))
            },
            ToolSpec("dns_lookup", "Resolve a domain to its IP addresses.", props("host" to str("Domain")), listOf("host")) { _, a ->
                val h = a.getString("host").trim()
                if (!validHost(h)) return@ToolSpec "Invalid host."
                try { InetAddress.getAllByName(h).joinToString(", ") { it.hostAddress ?: "" } } catch (e: Exception) { "Could not resolve $h: ${e.message}" }
            },
            ToolSpec("ssl_check", "Check a website's TLS certificate: validity, expiry date and issuer.",
                props("host" to str("Domain, e.g. example.com")), listOf("host")) { _, a ->
                ssl(a.getString("host"))
            },
            ToolSpec("wake_device", "Send Wake-on-LAN to a device saved in ALFA's Wake-on-LAN tool.",
                props("name" to str("Saved device name")), listOf("name")) { c, a ->
                wake(c, a.getString("name"))
            },
        )
    }

    fun byName(name: String) = all.firstOrNull { it.name == name }

    // ---------------- implementations ----------------

    private fun validHost(h: String) = h.isNotEmpty() && !h.startsWith("-") && Regex("^[A-Za-z0-9.:\\-]{1,253}$").matches(h)

    private fun status(c: Context): String {
        val bm = c.getSystemService(BatteryManager::class.java)
        val bat = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val am = c.getSystemService(android.app.ActivityManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val fs = StatFs(Environment.getDataDirectory().path)
        val gb = 1_073_741_824.0
        val up = SystemClock.elapsedRealtime() / 1000
        return String.format(Locale.US,
            "Battery %d%% (%s, %.0f°C). RAM %.1f of %.1f GB used. Storage %.0f of %.0f GB used. Uptime %dd %dh. Network: %s.",
            bat, if (charging) "charging" else "not charging", temp,
            (mem.totalMem - mem.availMem) / gb, mem.totalMem / gb,
            (fs.totalBytes - fs.availableBytes) / gb, fs.totalBytes / gb,
            up / 86400, (up / 3600) % 24, netType(c))
    }

    private fun netType(c: Context): String {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "connected"
        }
    }

    private fun netInfo(c: Context): String {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return "Unavailable."
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return "Not connected."
        val ip = lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.let { "${it.address.hostAddress}/${it.prefixLength}" }
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val dns = lp.dnsServers.joinToString(", ") { it.hostAddress ?: "" }
        return "Type ${netType(c)}, interface ${lp.interfaceName}, IP ${ip ?: "none"}, gateway ${gw ?: "none"}, DNS $dns."
    }

    private fun ping(host: String): String {
        val h = host.trim()
        if (!validHost(h)) return "Invalid host."
        return try {
            val p = ProcessBuilder("ping", "-c", "4", "-W", "2", h).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.lines().filter { it.contains("packet") || it.contains("rtt") || it.contains("min/avg") || it.contains("unknown") }
                .joinToString(" ").ifEmpty { out.takeLast(300) }
        } catch (e: Exception) { "Ping failed: ${e.message}" }
    }

    private fun ssl(host: String): String {
        val h = host.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        if (!validHost(h)) return "Invalid host."
        return try {
            val raw = Socket().apply { connect(InetSocketAddress(h, 443), 7000); soTimeout = 7000 }
            val f = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            val s = f.createSocket(raw, h, 443, true) as javax.net.ssl.SSLSocket
            s.startHandshake()
            val cert = s.session.peerCertificates.first() as java.security.cert.X509Certificate
            val nameOk = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(h, s.session)
            s.close()
            val days = (cert.notAfter.time - System.currentTimeMillis()) / 86_400_000L
            val issuer = Regex("O=([^,]+)").find(cert.issuerX500Principal.name)?.groupValues?.get(1) ?: cert.issuerX500Principal.name
            "Certificate for $h is trusted${if (nameOk) "" else " BUT does not match the name"}. Expires " +
                java.text.SimpleDateFormat("dd MMM yyyy", Locale.US).format(cert.notAfter) + " ($days days left). Issuer: $issuer."
        } catch (e: javax.net.ssl.SSLException) {
            "Certificate for $h is NOT trusted: ${e.message}"
        } catch (e: Exception) {
            "Could not connect to $h:443: ${e.message}"
        }
    }

    private fun wake(c: Context, name: String): String {
        val raw = prefs(c).getString("wol_devices", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val q = name.lowercase(Locale.ROOT)
        var dev: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("name").lowercase(Locale.ROOT).contains(q)) { dev = o; break }
        }
        val d = dev ?: return if (arr.length() == 0) "No Wake-on-LAN devices saved yet. Add one in IT Tools > Wake-on-LAN."
            else "No saved device matches \"$name\". Saved: " + (0 until arr.length()).joinToString { arr.getJSONObject(it).getString("name") }
        return try {
            val mac = d.getString("mac").split(":").map { it.toInt(16).toByte() }.toByteArray()
            val packet = ByteArray(102)
            for (i in 0 until 6) packet[i] = 0xFF.toByte()
            for (i in 0 until 16) System.arraycopy(mac, 0, packet, 6 + i * 6, 6)
            val addr = InetAddress.getByName(d.optString("host", "255.255.255.255"))
            java.net.DatagramSocket().use { s ->
                s.broadcast = true
                repeat(3) { s.send(java.net.DatagramPacket(packet, packet.size, addr, d.optInt("port", 9))) }
            }
            "Magic packet sent to ${d.getString("name")}."
        } catch (e: Exception) { "Send failed: ${e.message}" }
    }
}
