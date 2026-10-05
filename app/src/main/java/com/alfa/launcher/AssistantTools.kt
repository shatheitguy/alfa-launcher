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
            makeQr,
            ToolSpec("remember", "Save a short fact the user asked you to remember. It is kept for all future chats.",
                props("fact" to str("The fact, in the user's words, e.g. 'my home server is 192.168.0.170'")), listOf("fact")) { c, a ->
                val fact = a.getString("fact")
                if (!said(fact)) return@ToolSpec "ASK:What should I remember?"
                if (!AssistantMemory.enabled(c)) return@ToolSpec "Memory is turned off in ALFA OS Settings → ALFA Assistant."
                if (AssistantMemory.add(c, fact)) "Remembered: $fact" else "Nothing to remember."
            },
        ) + appActions
    }

    // ---------------- acting inside apps (prefilled; the user taps Send) ----------------
    // Android doesn't let one app press buttons in another (short of Accessibility control),
    // so these open the right chat / screen with everything filled in.

    /** Set when an action needed the Contacts permission; the UI asks for it. */
    @Volatile var needsContacts = false

    private fun hasContacts(c: Context) =
        c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private val CALLING_CODES = mapOf(
        "ae" to "971", "in" to "91", "us" to "1", "ca" to "1", "gb" to "44", "sa" to "966", "pk" to "92", "bd" to "880",
        "lk" to "94", "ph" to "63", "eg" to "20", "qa" to "974", "kw" to "965", "om" to "968", "bh" to "973", "jo" to "962",
        "au" to "61", "de" to "49", "fr" to "33", "it" to "39", "es" to "34", "np" to "977", "my" to "60", "sg" to "65", "id" to "62",
    )

    /** Digits with country code, no "+" (WhatsApp format). */
    private fun intlDigits(c: Context, number: String): String {
        val trimmed = number.trim()
        var d = trimmed.filter { it.isDigit() }
        if (trimmed.startsWith("+")) return d
        if (d.startsWith("00")) return d.drop(2)
        if (d.startsWith("0")) {
            val iso = (c.getSystemService(android.telephony.TelephonyManager::class.java)?.simCountryIso ?: "")
                .ifEmpty { Locale.getDefault().country }.lowercase(Locale.ROOT)
            CALLING_CODES[iso]?.let { d = it + d.drop(1) }
        }
        return d
    }

    private class Person(val name: String, val number: String)

    /** A phone number, or a contact name looked up in the address book. */
    private fun person(c: Context, who: String): Person? {
        val w = who.trim()
        if (w.count { it.isDigit() } >= 6 && w.all { it.isDigit() || it in "+ -()" }) return Person(w, w)
        if (!hasContacts(c)) { needsContacts = true; return null }

        // Match on any word of the query, so "Ahmed" finds "Mohammed Ahmed" and
        // "John S" finds "John Smith". Query all contacts once, rank in code.
        val words = w.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.length >= 2 }
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
        val all = ArrayList<Person>()
        try {
            c.contentResolver.query(uri, proj, null, null,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(0) ?: continue
                    val num = cur.getString(1) ?: continue
                    if (num.isNotBlank()) all.add(Person(name, num))
                }
            }
        } catch (e: Exception) { return null }
        if (all.isEmpty()) return null

        val lw = w.lowercase(Locale.ROOT)
        fun score(p: Person): Int {
            val n = p.name.lowercase(Locale.ROOT)
            val tokens = n.split(Regex("\\s+"))
            return when {
                n == lw -> 100
                tokens.any { it == lw } -> 90
                n.startsWith(lw) -> 80
                tokens.any { t -> words.any { t == it } } -> 70   // a whole name word matches
                n.contains(lw) -> 60
                words.isNotEmpty() && words.all { word -> n.contains(word) } -> 50
                words.any { word -> n.contains(word) } -> 30
                else -> 0
            }
        }
        return all.map { it to score(it) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    private fun noContact(who: String) =
        if (needsContacts) "ASK:I need access to your contacts to find \u201C$who\u201D. Please allow it, then ask me again."
        else "ASK:I couldn\u2019t find \u201C$who\u201D in your contacts. What\u2019s their exact name or phone number?"

    private fun installed(c: Context, pkg: String) = try { c.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private val appActions: List<ToolSpec> = listOf(
        ToolSpec("whatsapp_message", "Open a WhatsApp chat with a contact (name or number) with the message typed in. The user taps Send.",
            props("to" to str("Contact name or phone number"), "message" to str("Message text")), listOf("to", "message")) { c, a ->
            if (!said(a.getString("to"))) return@ToolSpec "ASK:Who should I message on WhatsApp?"
            if (!said(a.getString("message"))) return@ToolSpec "ASK:What should the message say?"
            val p = person(c, a.getString("to")) ?: return@ToolSpec noContact(a.getString("to"))
            val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(c, it) }
                ?: return@ToolSpec "WhatsApp isn't installed."
            val digits = intlDigits(c, p.number)
            val text = Uri.encode(a.getString("message"))
            // Try wa.me (most reliable), then the api.whatsapp.com form, both pinned to the WhatsApp app.
            val urls = listOf("https://wa.me/$digits?text=$text", "https://api.whatsapp.com/send?phone=$digits&text=$text")
            var opened = false
            for (u in urls) {
                if (start(c, Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(pkg)) == "ok") { opened = true; break }
            }
            if (opened) "Opened WhatsApp with ${p.name} and the message ready. They need to tap Send."
            else "Couldn't open WhatsApp for ${p.name}. Their number may not be on WhatsApp."
        },
        ToolSpec("sms_message", "Prepare a text message (SMS) to a contact. It appears in the chat and is sent when the user taps Send.",
            props("to" to str("Contact name or phone number"), "message" to str("Message text")), listOf("to", "message")) { c, a ->
            if (!said(a.getString("to"))) return@ToolSpec "ASK:Who should I text?"
            if (!said(a.getString("message"))) return@ToolSpec "ASK:What should the text say?"
            val p = person(c, a.getString("to")) ?: return@ToolSpec noContact(a.getString("to"))
            pendingSms = Sms(p.name, p.number, a.getString("message"))
            "The text to ${p.name} is ready in the chat. It is sent when the user taps Send."
        },
        ToolSpec("call_contact", "Open the phone dialer with a contact's number ready. The user taps Call.",
            props("who" to str("Contact name or phone number")), listOf("who")) { c, a ->
            if (!said(a.getString("who"))) return@ToolSpec "ASK:Who should I call?"
            val p = person(c, a.getString("who")) ?: return@ToolSpec noContact(a.getString("who"))
            val r = start(c, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.number))))
            if (r == "ok") "Dialer open with ${p.name} (${p.number}). Tap Call." else r
        },
        ToolSpec("send_email", "Open the email app with recipient, subject and body filled in. The user taps Send.",
            props("to" to str("Email address"), "subject" to str("Subject"), "body" to str("Message body")), listOf("to")) { c, a ->
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + a.getString("to")))
                .putExtra(Intent.EXTRA_SUBJECT, a.optString("subject")).putExtra(Intent.EXTRA_TEXT, a.optString("body"))
            val r = start(c, i)
            if (r == "ok") "Email to ${a.getString("to")} is ready. Tap Send." else r
        },
        ToolSpec("share_text", "Share text into another app (Telegram, Slack, Teams, Messenger, Notes, ...) or the share sheet.",
            props("text" to str("Text to share"), "app" to str("Optional app name, e.g. telegram")), listOf("text")) { c, a ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.getString("text"))
            val appName = a.optString("app").trim()
            if (appName.isNotEmpty()) {
                val app = findApp(c, appName)
                if (app != null && !app.clone) {
                    send.setPackage(app.pkg)
                    if (start(c, send) == "ok") return@ToolSpec "Opened ${app.label} with the text ready to send."
                }
            }
            start(c, Intent.createChooser(send, "Share"))
            "Share sheet opened; the user picks where to send it."
        },
        ToolSpec("navigate_to", "Start navigation (Google Maps) to a place or address.",
            props("place" to str("Destination")), listOf("place")) { c, a ->
            val q = Uri.encode(a.getString("place"))
            val r = start(c, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")))
            if (r == "ok") "Navigating to ${a.getString("place")}." else start(c, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))).let { "Opened maps for ${a.getString("place")}." }
        },
        ToolSpec("play_music", "Play a song, artist or album in the music app.",
            props("query" to str("Song, artist or album")), listOf("query")) { c, a ->
            val i = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(android.app.SearchManager.QUERY, a.getString("query"))
            val r = start(c, i)
            if (r == "ok") "Playing ${a.getString("query")}." else "No music app here can play from search."
        },
        ToolSpec("add_calendar_event", "Open a new calendar event filled in. start is local time 'YYYY-MM-DD HH:MM'.",
            props("title" to str("Event title"), "start" to str("YYYY-MM-DD HH:MM"), "minutes" to int("Length in minutes, default 60"),
                "location" to str("Optional location")), listOf("title", "start")) { c, a ->
            val t0 = try { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(a.getString("start"))?.time } catch (e: Exception) { null }
                ?: return@ToolSpec "Couldn't read the start time; use YYYY-MM-DD HH:MM."
            val dur = (if (a.has("minutes")) a.optInt("minutes") else 60).coerceAtLeast(5)
            val i = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                .putExtra(android.provider.CalendarContract.Events.TITLE, a.getString("title"))
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, t0)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, t0 + dur * 60_000L)
                .putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, a.optString("location"))
            val r = start(c, i)
            if (r == "ok") "New event \"${a.getString("title")}\" is filled in. Tap Save." else r
        },
        ToolSpec("open_url", "Open a website or link in the browser.", props("url" to str("Web address")), listOf("url")) { c, a ->
            var u = a.getString("url").trim()
            if (!u.contains("://")) u = "https://$u"
            start(c, Intent(Intent.ACTION_VIEW, Uri.parse(u))); "Opened $u."
        },
        ToolSpec("open_camera", "Open the camera to take a photo or selfie.", props(), emptyList()) { c, _ ->
            start(c, Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); "Camera open."
        },
    )

    fun byName(name: String) = all.firstOrNull { it.name == name }

    // ---------------- picking the right tools ----------------
    // Small on-device models choose badly from a long tool list. Each action has trigger words;
    // the model is only offered actions whose words appear in the request (plus app opening),
    // and an action whose words are absent is refused, so "make a QR" can never set a timer.

    private val KEYWORDS: Map<String, List<String>> = mapOf(
        "open_app" to listOf("open", "launch", "start ", "run ", "go to", "show me", "take me to"),
        "find_apps" to listOf(" app", "apps", "installed"),
        "set_accent" to listOf("accent", "colour", "color", "theme", "crimson", "red", "blue", "ice", "mint", "green", "amber", "orange", "violet", "purple", "mono", "white"),
        "set_wallpaper" to listOf("wallpaper", "background") + HudBackground.STYLES.map { it.first },
        "set_icon_style" to listOf("icon"),
        "set_setting" to listOf("vibrat", "haptic", "spin", "tilt", "3d", "drift", "tools button", "it tools button", "clone", "dual", "auto-check", "auto check", "auto update", "turn on", "turn off", "enable", "disable", "switch on", "switch off"),
        "edit_home" to listOf("orbit", "dock", "home screen", "pin", "unpin"),
        "open_it_tool" to listOf("tool", "speed", "speedtest", "port", "subnet", "hash", "base64", "password", "wake-on-lan", "wifi details", "wi-fi details", "update", "device info", "panels"),
        "open_settings_panel" to listOf("setting", "wifi", "wi-fi", "bluetooth", "display", "brightness", "battery saver", "storage", "developer", "location", "vpn", "sound", "about phone", "mobile data", "apps list"),
        "open_alfa_settings" to listOf("alfa setting", "launcher setting", "alfa os setting"),
        "set_timer" to listOf("timer", "countdown", "count down"),
        "set_alarm" to listOf("alarm", "wake me"),
        "web_search" to listOf("search", "google", "look up", "lookup online", "find online"),
        "device_status" to listOf("battery", "ram", "memory", "storage", "status", "uptime", "temperature", "temp", "how is my phone", "phone health"),
        "network_info" to listOf("ip", "network", "gateway", "dns server", "connection", "connected", "subnet mask"),
        "ping" to listOf("ping", "latency", "reachable", "online?", "is up", "is down"),
        "dns_lookup" to listOf("dns", "resolve", "nslookup", "a record"),
        "ssl_check" to listOf("ssl", "certificate", "cert", "tls", "https"),
        "wake_device" to listOf("wake", "wol", "magic packet", "boot my", "turn on my pc", "power on"),
        "whatsapp_message" to listOf("whatsapp", "whats app", "wa "),
        "sms_message" to listOf("sms", "text ", "message ", "msg "),
        "call_contact" to listOf("call ", "call", "dial", "ring ", "phone "),
        "send_email" to listOf("email", "e-mail", "mail "),
        "share_text" to listOf("share", "send to", "post to"),
        "navigate_to" to listOf("navigate", "directions", "route", "drive to", "maps", "how do i get to"),
        "play_music" to listOf("play ", "music", "song", "spotify", "album"),
        "add_calendar_event" to listOf("calendar", "event", "meeting", "appointment", "schedule"),
        "open_url" to listOf("http", "www.", ".com", ".org", ".net", ".io", ".ae", ".in", "website", "link"),
        "open_camera" to listOf("camera", "photo", "picture", "selfie"),
        "make_qr" to listOf("qr", "barcode", "scan code", "share wifi", "share wi-fi", "share the wifi"),
        "remember" to listOf("remember", "don't forget", "dont forget", "note that", "keep in mind", "save this"),
    )

    fun matches(name: String, text: String): Boolean {
        val words = KEYWORDS[name] ?: return true
        val t = " " + text.lowercase(Locale.ROOT) + " "
        return words.any { t.contains(it) }
    }

    /**
     * The tools worth offering for this request. When nothing matches (a question, small talk)
     * the list is empty and the model simply answers in conversation.
     */
    fun relevant(text: String): List<ToolSpec> = all.filter { matches(it.name, text) }

    // ---------------- QR shown in the chat ----------------

    class Qr(val bitmap: android.graphics.Bitmap, val caption: String, val name: String)

    @Volatile private var pendingQr: Qr? = null

    /** What the user actually said this turn (+ the previous turn), set by the engine before running a tool. */
    @Volatile var currentRequest = ""

    /** Did the user really say this value? Stops small models from filling in made-up examples. */
    private fun said(value: String): Boolean {
        val v = value.lowercase(Locale.ROOT).replace(Regex("^https?://"), "").trim().trimEnd('/')
        if (v.isEmpty()) return false
        val req = currentRequest.lowercase(Locale.ROOT)
        if (req.contains(v)) return true
        val words = v.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
        if (words.isEmpty()) return false
        return words.count { req.contains(it) } >= (words.size * 0.7).coerceAtLeast(1.0)
    }

    private fun mentions(vararg w: String) = w.any { currentRequest.lowercase(Locale.ROOT).contains(it) }

    /** The QR code made during the last request, if any (consumed once). */
    fun takeQr(): Qr? = pendingQr.also { pendingQr = null }

    // ---------------- SMS confirmed in the chat ----------------

    class Sms(val name: String, val number: String, val text: String)

    @Volatile private var pendingSms: Sms? = null

    /** The text prepared during the last request, if any (consumed once). The chat shows it with a Send button. */
    fun takeSms(): Sms? = pendingSms.also { pendingSms = null }

    /** Sends [s] with the default SIM. Only called after the user taps Send. Throws on failure. */
    fun sendSms(c: Context, s: Sms) {
        val sm = smsManager(c)
        val parts = sm.divideMessage(s.text)
        if (parts.size > 1) sm.sendMultipartTextMessage(s.number, null, parts, null, null)
        else sm.sendTextMessage(s.number, null, s.text, null, null)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(c: Context): android.telephony.SmsManager =
        if (android.os.Build.VERSION.SDK_INT >= 31) c.getSystemService(android.telephony.SmsManager::class.java)
        else android.telephony.SmsManager.getDefault()

    fun openSmsApp(c: Context, s: Sms): String =
        start(c, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(s.number))).putExtra("sms_body", s.text))

    private val makeQr = ToolSpec("make_qr",
        "Create a QR code and show it to the user. type 'wifi' = scan-to-join Wi-Fi (needs ssid, password, security), " +
            "'link' = a web address (content), 'text' = any text (content).",
        props(
            "type" to str("Kind of QR code", listOf("wifi", "link", "text")),
            "content" to str("The link or text (for type link/text)"),
            "ssid" to str("Wi-Fi network name (type wifi)"),
            "password" to str("Wi-Fi password (type wifi; empty for an open network)"),
            "security" to str("Wi-Fi security (type wifi)", listOf("WPA", "WEP", "open")),
        ), listOf("type")) { _, a ->
        var type = a.getString("type").lowercase(Locale.ROOT)
        // decide the kind from what the user said, not what the model guessed
        val saidWifi = mentions("wifi", "wi-fi", "wi fi", "network", "ssid", "password", "hotspot")
        val saidLink = mentions("http", "www.", ".com", ".org", ".net", ".io", ".ae", "link", "url", "website", "site")
        if (type == "wifi" && !saidWifi) type = if (saidLink) "link" else "text"
        if (!saidWifi && !saidLink && !said(a.optString("content")))
            return@ToolSpec "ASK:What should the QR code be for: a Wi-Fi network, a link, or some text? And what should it contain?"
        val (payload, caption, name) = when (type) {
            "wifi" -> {
                val ssid = a.optString("ssid").trim()
                if (ssid.isEmpty() || !said(ssid)) return@ToolSpec "ASK:What\u2019s the Wi-Fi network name and its password?"
                val pass = a.optString("password")
                val open = mentions("open", "no password", "without password", "passwordless")
                if (!open && (pass.isEmpty() || !said(pass))) return@ToolSpec "ASK:What\u2019s the password for \u201C$ssid\u201D? (Or say it\u2019s an open network.)"
                val sec = if (open) "open" else a.optString("security", "WPA")
                Triple(QrUtil.wifi(ssid, pass, sec), "Scan to join “$ssid”", "wifi-$ssid")
            }
            "link" -> {
                var u = a.optString("content").trim()
                if (u.isEmpty() || !said(u)) return@ToolSpec "ASK:Which link should the QR code open?"
                if (!u.contains("://")) u = "https://$u"
                Triple(u, u, "link-qr")
            }
            else -> {
                val t = a.optString("content").trim()
                if (t.isEmpty() || !said(t)) return@ToolSpec "ASK:What text should the QR code contain?"
                Triple(t, if (t.length > 50) t.take(50) + "…" else t, "text-qr")
            }
        }
        pendingQr = Qr(QrUtil.render(payload), caption, name.replace(Regex("[^A-Za-z0-9_-]"), "_"))
        "QR code created and shown on screen ($caption). The user can save or share it."
    }

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
