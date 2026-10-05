package com.alfa.launcher

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import kotlin.math.ln

class ToolsActivity : Activity() {

    companion object {
        const val EXTRA_TOOL = "tool"
        const val TOOL_NETWORK = "network"
        const val TOOL_PING = "ping"
        const val TOOL_DNS = "dns"
        const val TOOL_PORTS = "ports"
        const val TOOL_SUBNET = "subnet"
        const val TOOL_DEVICE = "device"
        const val TOOL_HASH = "hash"
        const val TOOL_PASSWORD = "password"
        const val TOOL_SHORTCUTS = "shortcuts"
        const val TOOL_UPDATE = "update"
        const val TOOL_QR = "qr"
        const val TOOL_SPEED = "speed"
        const val TOOL_WIFI = "wifi"
        const val TOOL_SSL = "ssl"
        const val TOOL_WOL = "wol"
        private const val REQ_LOCATION = 71
        private const val REQ_SAVE_QR = 61

        private val SERVICES = mapOf(
            20 to "ftp-data", 21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp", 53 to "dns",
            67 to "dhcp", 80 to "http", 110 to "pop3", 123 to "ntp", 135 to "msrpc", 139 to "netbios",
            143 to "imap", 161 to "snmp", 389 to "ldap", 443 to "https", 445 to "smb", 465 to "smtps",
            587 to "submission", 631 to "ipp", 993 to "imaps", 995 to "pop3s", 1433 to "mssql",
            1521 to "oracle", 1883 to "mqtt", 3306 to "mysql", 3389 to "rdp", 5432 to "postgres",
            5900 to "vnc", 6379 to "redis", 8080 to "http-alt", 8443 to "https-alt", 9100 to "printer",
            27017 to "mongodb",
        )
        private const val COMMON_PORTS = "21,22,23,25,53,80,110,135,139,143,443,445,3306,3389,5432,5900,8080,8443"
    }

    private data class Tool(val id: String, val name: String, val desc: String)

    private val tools = listOf(
        Tool(TOOL_NETWORK, "Network info", "IP, gateway, DNS, link"),
        Tool(TOOL_SPEED, "Speed test", "Download, upload, ping, jitter"),
        Tool(TOOL_WIFI, "Wi-Fi details", "Live signal, band, channel, speed"),
        Tool(TOOL_SSL, "SSL checker", "Certificate, expiry, issuer, TLS"),
        Tool(TOOL_WOL, "Wake-on-LAN", "Wake your saved PCs and servers"),
        Tool(TOOL_QR, "QR generator", "Wi-Fi, link or text → QR, save PNG"),
        Tool(TOOL_PING, "Ping", "ICMP echo to any host"),
        Tool(TOOL_DNS, "DNS lookup", "Resolve and reverse-resolve"),
        Tool(TOOL_PORTS, "Port check", "TCP ports on one host"),
        Tool(TOOL_SUBNET, "Subnet calc", "CIDR to range, mask, hosts"),
        Tool(TOOL_DEVICE, "Device info", "Hardware and build details"),
        Tool(TOOL_HASH, "Hash / Base64", "MD5, SHA, CRC32, Base64"),
        Tool(TOOL_PASSWORD, "Password gen", "Strong random passwords"),
        Tool(TOOL_SHORTCUTS, "System panels", "Jump straight to settings"),
        Tool(TOOL_UPDATE, "App update", "Get the latest ALFA build"),
    )

    private val main = Handler(Looper.getMainLooper())

    // self-update: download waiting for the "install apps" permission
    private var pendingInstall = -1L
    private var installNow: ((Long) -> Unit)? = null

    override fun onResume() {
        super.onResume()
        val id = pendingInstall
        if (id >= 0 && current == TOOL_UPDATE && Updater.canSelfInstall(this)) {
            pendingInstall = -1L
            installNow?.invoke(id)
        }
    }
    private val pool = Executors.newCachedThreadPool()
    private var accent = 0
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var current: String? = null
    private var openedDirect = false

    @Volatile private var pingProc: Process? = null
    @Volatile private var cancelled = false

    private val white = Color.WHITE
    private val dim = Color.argb(179, 255, 255, 255)
    private val dimmer = Color.argb(128, 255, 255, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accent = MainActivity.accentOf(this)

        val root = FrameLayout(this)
        root.addView(HudBackground(this, null).also { it.accent = accent; it.style = WallpaperSync.style(this) }, FrameLayout.LayoutParams(-1, -1))
        scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(32))
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val t = intent.getStringExtra(EXTRA_TOOL)
        if (t != null) {
            openedDirect = true
            showTool(t)
        } else {
            showList()
        }
    }

    /** Already running in its own task: jump to a requested tool, otherwise keep the current screen. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val t = intent?.getStringExtra(EXTRA_TOOL) ?: return
        if (t != current) {
            stopWork()
            openedDirect = true
            showTool(t)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        stopWork()
        if (current != null && !openedDirect) showList() else finish()
    }

    override fun onDestroy() {
        stopWork()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun stopWork() {
        cancelled = true
        pingProc?.destroy()
        pingProc = null
    }

    // ================= UI helpers =================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: CharSequence, size: Float = 14f, color: Int = white, mono: Boolean = false, font: String = "sans-serif") =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = if (mono) Typeface.MONOSPACE else Typeface.create(font, Typeface.NORMAL)
        }

    private fun lp(top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun <T : View> add(v: T, top: Int = 12): T {
        content.addView(v, lp(top))
        return v
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.glass_card)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun field(hint: String, value: String = "", number: Boolean = false, multi: Boolean = false) =
        EditText(this).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(dimmer)
            setTextColor(white)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundResource(R.drawable.field)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = when {
                number -> InputType.TYPE_CLASS_NUMBER
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (multi) {
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            } else {
                isSingleLine = true
            }
        }

    private fun button(label: String, primary: Boolean = true, onClick: () -> Unit) =
        tv(label, 14f, if (primary) Color.BLACK else white, font = "sans-serif-medium").apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundResource(R.drawable.pill_accent)
            backgroundTintList = ColorStateList.valueOf(if (primary) accent else Color.argb(38, 255, 255, 255))
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View, top: Int = 12): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEachIndexed { i, v ->
            r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        return add(r, top)
    }

    private fun output(initial: String = ""): TextView {
        val c = card()
        val out = tv(initial, 12f, Color.argb(220, 255, 255, 255), mono = true).apply {
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        c.addView(out)
        add(c, 14)
        return out
    }

    private fun header(title: String, sub: String, back: Boolean) {
        if (back) {
            add(tv("‹  ALL TOOLS", 12f, accent, mono = true).apply {
                setPadding(0, dp(6), dp(12), dp(6))
                setOnClickListener { stopWork(); openedDirect = false; showList() }
            }, 0)
        }
        add(tv(title, 30f, white, font = "sans-serif-light"), if (back) 6 else 0)
        add(tv(sub, 11f, dim, mono = true).apply { letterSpacing = 0.12f }, 4)
        content.addView(View(this).apply { setBackgroundColor(accent) },
            LinearLayout.LayoutParams(dp(40), dp(2)).apply { topMargin = dp(14); bottomMargin = dp(6) })
    }

    private fun grid(cards: List<View>) {
        cards.chunked(2).forEach { pair ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, v ->
                r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = dp(10)
                })
            }
            if (pair.size == 1) {
                r.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(10) })
            }
            add(r, 10)
        }
    }

    private fun tile(tag: String, title: String, desc: String, onClick: () -> Unit) = card().apply {
        addView(tv(tag, 10f, accent, mono = true))
        addView(tv(title, 16f, white, font = "sans-serif-medium"), lp(10))
        addView(tv(desc, 11f, Color.argb(153, 255, 255, 255)), lp(4))
        foreground = getDrawable(R.drawable.ripple_item)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun bg(block: () -> Unit) {
        try { pool.execute(block) } catch (e: RejectedExecutionException) { /* closing */ }
    }

    private fun ui(block: () -> Unit) {
        main.post { if (!isDestroyed) block() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("ALFA", s))
        toast("Copied")
    }

    private fun startSafe(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { toast("Not available on this device") }
    }

    private fun validHost(h: String) = h.isNotEmpty() && !h.startsWith("-") && Regex("^[A-Za-z0-9.:\\-]{1,253}$").matches(h)

    // ================= screens =================

    private fun showList() {
        current = null
        content.removeAllViews()
        scroll.scrollTo(0, 0)
        header("IT Tools", "ALFA//TOOLKIT  ·  ${tools.size} MODULES", back = false)
        grid(tools.mapIndexed { i, t -> tile(String.format(Locale.US, "%02d", i + 1), t.name, t.desc) { showTool(t.id) } })
    }

    private fun showTool(id: String) {
        val t = tools.firstOrNull { it.id == id } ?: return showList()
        cancelled = false
        current = id
        content.removeAllViews()
        scroll.scrollTo(0, 0)
        header(t.name, t.desc.uppercase(Locale.US), back = true)
        when (id) {
            TOOL_NETWORK -> networkTool()
            TOOL_QR -> qrTool()
            TOOL_SPEED -> speedTool()
            TOOL_WIFI -> wifiTool()
            TOOL_SSL -> sslTool()
            TOOL_WOL -> wolTool()
            TOOL_PING -> pingTool()
            TOOL_DNS -> dnsTool()
            TOOL_PORTS -> portsTool()
            TOOL_SUBNET -> subnetTool()
            TOOL_DEVICE -> deviceTool()
            TOOL_HASH -> hashTool()
            TOOL_PASSWORD -> passwordTool()
            TOOL_SHORTCUTS -> shortcutsTool()
            TOOL_UPDATE -> updateTool()
        }
    }

    // ---------- self update ----------

    private fun updateTool() {
        val prefs = getSharedPreferences("alfa", MODE_PRIVATE)
        val installedCode = Updater.currentCode(this)
        val info = card()
        val status = tv("checking GitHub…", 12f, Color.argb(220, 255, 255, 255), mono = true).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        info.addView(tv("INSTALLED   v${Updater.currentName(this)}  (build $installedCode)", 12f, white, mono = true))
        info.addView(status, lp(8))
        // Problems go on their own line so the release details above never disappear.
        val note = tv("", 11f, Color.rgb(255, 140, 140), mono = true).apply { visibility = View.GONE }
        info.addView(note, lp(8))
        add(info, 14)

        var release: Updater.Release? = null
        var busy = false
        var downloaded = false
        var downloadId = -1L
        val progress = add(tv("", 11f, accent, mono = true), 12)

        lateinit var action: TextView
        lateinit var check: () -> Unit

        fun setAction(label: String, enabled: Boolean) {
            action.text = label
            action.isEnabled = enabled
            action.alpha = if (enabled) 1f else 0.4f
        }

        fun install(id: Long) {
            if (!Updater.canSelfInstall(this)) {
                pendingInstall = id
                progress.text = "${bar(1, 1)}  downloaded ✓\n\nAllow ALFA to install apps (one time), then come back — the install continues by itself."
                setAction("Allow & install", true)
                try { Updater.requestInstallPermission(this) } catch (e: Exception) { Updater.openDownloads(this) }
                return
            }
            setAction("Installing…", false)
            bg {
                try {
                    Updater.installDownloaded(this, id)
                    ui {
                        progress.text = "${bar(1, 1)}  ready — confirm “Update” on the Android prompt."
                        setAction("Install update", true)
                    }
                } catch (e: Exception) {
                    ui {
                        progress.text = "install failed (${e.message}). You can also tap the download notification."
                        setAction("Install update", true)
                    }
                }
            }
        }
        installNow = { id -> install(id) }

        action = button("Download update") {
            if (downloaded) {
                if (downloadId >= 0) install(downloadId) else Updater.openDownloads(this)
                return@button
            }
            val rel = release ?: return@button
            if (busy) return@button
            val id = try { Updater.enqueue(this, rel) } catch (e: Exception) { -1L }
            if (id < 0) {
                progress.text = "download manager unavailable — opening browser"
                try { Updater.downloadInBrowser(this, rel) } catch (e: Exception) { toast("No browser available") }
                return@button
            }
            busy = true
            downloadId = id
            setAction("Downloading…", false)
            val poll = object : Runnable {
                override fun run() {
                    if (isDestroyed) return
                    val st = Updater.progress(this@ToolsActivity, id)
                    when (st.state) {
                        Updater.DlState.RUNNING -> {
                            progress.text = if (st.total > 0) {
                                String.format(Locale.US, "%s  %.1f / %.1f MB  %d%%", bar(st.done, st.total),
                                    st.done / 1048576.0, st.total / 1048576.0, (st.done * 100 / st.total).toInt())
                            } else "starting…"
                            main.postDelayed(this, 250)
                        }
                        Updater.DlState.DONE -> {
                            busy = false
                            downloaded = true
                            progress.text = "${bar(1, 1)}  downloaded ✓"
                            install(id)
                        }
                        Updater.DlState.FAILED -> {
                            busy = false
                            progress.text = "download failed (${st.reason}). Tap to retry."
                            setAction("Retry download", true)
                        }
                    }
                }
            }
            main.post(poll)
        }

        fun showRelease(rel: Updater.Release) {
            val newer = rel.code > installedCode
            val text = buildString {
                append("LATEST      ${rel.tag}  (build ${rel.code})\n")
                append("SIZE        ").append(if (rel.size > 0) String.format(Locale.US, "%.1f MB", rel.size / 1048576.0) else "—").append('\n')
                append("STATUS      ").append(if (newer) "update available" else "up to date")
                if (rel.notes.isNotEmpty()) append("\n\n").append(rel.notes)
            }
            if (status.text.toString() != text) status.text = text   // unchanged text = no relayout, no flicker
            if (newer) setAction("Download update ${rel.tag}", true) else setAction("Up to date", false)
        }

        check = {
            // Keep the current details on screen while checking. Replacing them with one line
            // collapsed the card and made every button jump, then jump back: the flicker.
            setAction("Checking…", false)
            if (release == null) status.text = "checking GitHub…"
            bg {
                try {
                    val rel = Updater.fetchLatest()
                    Updater.remember(this, rel)
                    ui {
                        release = rel
                        note.visibility = View.GONE
                        showRelease(rel)
                    }
                } catch (e: Exception) {
                    ui {
                        val known = release
                        if (known == null) {
                            status.text = "check failed: ${e.message}"
                            setAction("Download update", false)
                        } else {
                            note.text = "couldn't refresh: ${e.message}"
                            note.visibility = View.VISIBLE
                            showRelease(known)
                        }
                    }
                }
            }
        }

        row(action, top = 4)
        row(
            button("Check again", primary = false) { if (!busy) check() },
            button("Reinstall latest", primary = false) {
                if (release == null || busy) return@button
                downloaded = false
                setAction("Download update", true)
                action.performClick()
            },
            top = 8,
        )

        val auto = button("", primary = false) {}
        fun paintAuto() {
            val on = prefs.getBoolean("auto_update", true)
            auto.text = if (on) "Auto-check: ON" else "Auto-check: OFF"
            auto.backgroundTintList = ColorStateList.valueOf(if (on) accent else Color.argb(38, 255, 255, 255))
            auto.setTextColor(if (on) Color.BLACK else white)
        }
        auto.setOnClickListener {
            prefs.edit().putBoolean("auto_update", !prefs.getBoolean("auto_update", true)).apply()
            paintAuto()
        }
        paintAuto()
        row(auto, button("Release page", primary = false) {
            startSafe(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/${Updater.REPO}/releases")))
        }, top = 8)

        add(tv("Updates come from github.com/${Updater.REPO}. ALFA downloads and installs them itself; you just confirm Update. " +
            "It is signed with the same key, so your pins, dock and settings are kept.",
            11f, dimmer), 16)

        check()
    }

    private fun bar(done: Long, total: Long, n: Int = 16): String {
        val f = ((done * n) / total.coerceAtLeast(1)).toInt().coerceIn(0, n)
        return "█".repeat(f) + "░".repeat(n - f)
    }

    // ---------- network ----------

    private fun networkTool() {
        lateinit var out: TextView
        row(
            button("Refresh") { out.text = networkReport() },
            button("Public IP", primary = false) {
                out.text = networkReport() + "\n\nPUBLIC IP   fetching…"
                bg {
                    val ip = try { httpGet("https://api.ipify.org") } catch (e: Exception) { "error: ${e.message}" }
                    ui { out.text = networkReport() + "\n\nPUBLIC IP   $ip" }
                }
            },
            button("Copy", primary = false) { copy(out.text.toString()) },
        )
        out = output(networkReport())
    }

    private fun networkReport(): String {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return "Connectivity service unavailable."
        val n = cm.activeNetwork ?: return "No active network."
        val caps = cm.getNetworkCapabilities(n)
        val lp = cm.getLinkProperties(n)
        val sb = StringBuilder()
        fun r(k: String, v: Any?) { sb.append(k.padEnd(12)).append(v?.toString() ?: "—").append('\n') }

        r("TRANSPORT", when {
            caps == null -> null
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        })
        r("INTERFACE", lp?.interfaceName)
        lp?.linkAddresses?.forEach { la ->
            r(if (la.address is Inet4Address) "IPV4" else "IPV6", "${la.address.hostAddress}/${la.prefixLength}")
        }
        r("GATEWAY", lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress)
        lp?.dnsServers?.forEachIndexed { i, a -> r("DNS ${i + 1}", a.hostAddress) }
        if (Build.VERSION.SDK_INT >= 28) {
            r("PRIVATE DNS", if (lp != null && lp.isPrivateDnsActive) (lp.privateDnsServerName ?: "automatic") else "off")
        }
        if (!lp?.domains.isNullOrEmpty()) r("DOMAINS", lp?.domains)
        if (Build.VERSION.SDK_INT >= 29 && lp != null && lp.mtu > 0) r("MTU", lp.mtu)
        if (Build.VERSION.SDK_INT >= 29 && caps != null && caps.signalStrength != Int.MIN_VALUE) {
            r("SIGNAL", "${caps.signalStrength} dBm")
        }
        r("INTERNET", if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) "validated" else "not validated")
        r("METERED", if (cm.isActiveNetworkMetered) "yes" else "no")
        if (caps != null) {
            r("DOWNLINK", "${caps.linkDownstreamBandwidthKbps / 1000} Mbps (est)")
            r("UPLINK", "${caps.linkUpstreamBandwidthKbps / 1000} Mbps (est)")
        }
        return sb.toString().trimEnd()
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        return try {
            c.inputStream.bufferedReader().use { it.readText().trim() }
        } finally {
            c.disconnect()
        }
    }

    // ---------- Speed test (Cloudflare speed servers) ----------

    private fun speedTool() {
        val big = tv("—", 52f, white, font = "sans-serif-thin")
        val unit = tv("Mbps", 14f, dim, mono = true)
        val phaseTv = tv("READY", 11f, accent, mono = true).apply { letterSpacing = 0.2f }
        val gauge = card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(phaseTv)
            addView(big, lp(6).apply { width = -2 })
            addView(unit)
        }
        add(gauge, 14)
        lateinit var out: TextView
        lateinit var start: TextView
        start = button("Start test") {
            if (start.alpha < 1f) return@button
            stopWork(); cancelled = false
            start.alpha = 0.4f
            out.text = ""
            bg { runSpeedTest(big, phaseTv, out) { ui { start.alpha = 1f } } }
        }
        row(start, button("Stop", primary = false) { cancelled = true })
        out = output("Measures latency, download and upload against Cloudflare's public speed servers " +
            "(speed.cloudflare.com). Uses roughly 30–150 MB of data depending on your speed.")
    }

    private fun speedConn(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 8000
            useCaches = false
            setRequestProperty("User-Agent", "ALFA-Launcher")
        }

    private fun runSpeedTest(big: TextView, phaseTv: TextView, out: TextView, done: () -> Unit) {
        val lines = StringBuilder()
        fun log(s: String) { lines.append(s).append('\n'); val t = lines.toString(); ui { out.text = t.trimEnd() } }
        fun show(phase: String, value: String) = ui { phaseTv.text = phase; big.text = value }
        try {
            // 1) latency: several tiny requests, median + jitter
            show("PING", "…")
            val pings = ArrayList<Long>()
            var colo = ""
            repeat(8) {
                if (cancelled) return@repeat
                val t0 = System.nanoTime()
                val c = speedConn("https://speed.cloudflare.com/__down?bytes=0")
                c.inputStream.use { it.readBytes() }
                pings.add((System.nanoTime() - t0) / 1_000_000)
                if (colo.isEmpty()) colo = c.getHeaderField("cf-ray")?.substringAfterLast('-') ?: ""
                c.disconnect()
            }
            if (cancelled) throw InterruptedException()
            pings.sort()
            val ping = pings[pings.size / 2]
            val jitter = pings.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average()
            show("PING", "$ping")
            log("SERVER      Cloudflare ${if (colo.isNotEmpty()) colo else ""}")
            log(String.format(Locale.US, "PING        %d ms   (jitter %.1f ms)", ping, jitter))

            // 2) download: 4 parallel streams for up to 8 s
            val down = transfer(upload = false, seconds = 8) { mbps -> show("DOWNLOAD", String.format(Locale.US, "%.1f", mbps)) }
            if (cancelled) throw InterruptedException()
            log(String.format(Locale.US, "DOWNLOAD    %.1f Mbps", down))

            // 3) upload: 3 parallel streams for up to 8 s
            val up = transfer(upload = true, seconds = 8) { mbps -> show("UPLOAD", String.format(Locale.US, "%.1f", mbps)) }
            if (cancelled) throw InterruptedException()
            log(String.format(Locale.US, "UPLOAD      %.1f Mbps", up))
            show("DONE", String.format(Locale.US, "%.0f", down))
            log("\n" + verdict(down, ping))
        } catch (e: InterruptedException) {
            show("STOPPED", "—"); log("stopped.")
        } catch (e: Exception) {
            show("ERROR", "—"); log("error: ${e.message}")
        } finally {
            done()
        }
    }

    private fun verdict(down: Double, ping: Long) = when {
        down >= 100 && ping < 40 -> "Excellent — 4K streaming, big downloads and video calls on many devices."
        down >= 25 && ping < 80 -> "Good — HD streaming and video calls are fine."
        down >= 8 -> "OK — browsing and SD video; video calls may struggle on several devices."
        else -> "Slow — expect buffering. Try moving closer to the router or check the line."
    }

    /** Parallel time-boxed transfer; reports live Mbps, returns the overall Mbps. */
    private fun transfer(upload: Boolean, seconds: Int, live: (Double) -> Unit): Double {
        val total = AtomicInteger(0)                  // in KiB to stay well inside Int
        val bytes = java.util.concurrent.atomic.AtomicLong(0)
        val streams = if (upload) 3 else 4
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        val start = System.nanoTime()
        val exec = Executors.newFixedThreadPool(streams)
        repeat(streams) {
            exec.execute {
                val buf = ByteArray(64 * 1024)
                while (!cancelled && System.nanoTime() < deadline) {
                    try {
                        if (upload) {
                            java.util.Random().nextBytes(buf)
                            val size = 8 * 1024 * 1024
                            val c = speedConn("https://speed.cloudflare.com/__up")
                            c.doOutput = true
                            c.requestMethod = "POST"
                            c.setFixedLengthStreamingMode(size)
                            c.setRequestProperty("Content-Type", "application/octet-stream")
                            c.outputStream.use { os ->
                                var sent = 0
                                while (sent < size && !cancelled && System.nanoTime() < deadline) {
                                    val n = minOf(buf.size, size - sent)
                                    os.write(buf, 0, n); sent += n; bytes.addAndGet(n.toLong())
                                }
                            }
                            if (!cancelled && System.nanoTime() < deadline) c.responseCode
                            c.disconnect()
                        } else {
                            val c = speedConn("https://speed.cloudflare.com/__down?bytes=25000000")
                            c.inputStream.use { ins ->
                                while (!cancelled && System.nanoTime() < deadline) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    bytes.addAndGet(n.toLong())
                                }
                            }
                            c.disconnect()
                        }
                    } catch (e: Exception) {
                        // a stream cut off at the deadline is expected; anything else ends this stream
                        if (System.nanoTime() < deadline && !cancelled) break
                    }
                }
            }
        }
        exec.shutdown()
        while (!exec.awaitTermination(250, TimeUnit.MILLISECONDS)) {
            val secs = (System.nanoTime() - start) / 1e9
            if (secs > 0.3) live(bytes.get() * 8 / secs / 1e6)
        }
        total.set(0)
        val secs = ((minOf(System.nanoTime(), deadline) - start) / 1e9).coerceAtLeast(0.1)
        return bytes.get() * 8 / secs / 1e6
    }

    // ---------- Wi-Fi details ----------

    private fun wifiTool() {
        val dbm = tv("—", 52f, white, font = "sans-serif-thin")
        val quality = tv("", 12f, accent, mono = true).apply { letterSpacing = 0.2f }
        val meter = tv("", 14f, accent, mono = true)
        add(card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(tv("SIGNAL", 11f, dim, mono = true).apply { letterSpacing = 0.2f })
            addView(dbm, lp(4).apply { width = -2 })
            addView(meter, lp(2).apply { width = -2 })
            addView(quality, lp(6).apply { width = -2 })
        }, 14)
        lateinit var out: TextView
        val perm = button("Show network name", primary = false) {
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
        }
        row(perm, button("Wi-Fi settings", primary = false) { startSafe(Intent(Settings.ACTION_WIFI_SETTINGS)) })
        out = output("reading…")
        add(tv("Updates every second — walk around to find dead spots. Android only reveals the network name (SSID) " +
            "to apps with location permission; everything else works without it.", 11f, dimmer), 12)

        val loop = object : Runnable {
            override fun run() {
                if (isDestroyed || current != TOOL_WIFI) return
                val w = wifiSnapshot()
                out.text = w.report
                perm.visibility = if (w.needsLocation) View.VISIBLE else View.GONE
                if (w.rssi != null) {
                    dbm.text = "${w.rssi} dBm"
                    val f = ((w.rssi + 100) / 60f).coerceIn(0f, 1f)
                    val n = (f * 20).toInt()
                    meter.text = "█".repeat(n) + "░".repeat(20 - n)
                    quality.text = when {
                        w.rssi >= -55 -> "EXCELLENT"
                        w.rssi >= -67 -> "GOOD"
                        w.rssi >= -75 -> "FAIR"
                        w.rssi >= -85 -> "WEAK"
                        else -> "VERY WEAK"
                    }
                } else {
                    dbm.text = "—"; meter.text = ""; quality.text = "NOT CONNECTED"
                }
                main.postDelayed(this, 1000)
            }
        }
        main.post(loop)
    }

    private class WifiSnap(val report: String, val rssi: Int?, val needsLocation: Boolean)

    @Suppress("DEPRECATION")
    private fun wifiSnapshot(): WifiSnap {
        val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            ?: return WifiSnap("Wi-Fi service unavailable.", null, false)
        if (!wm.isWifiEnabled) return WifiSnap("Wi-Fi is turned off.", null, false)
        val info = wm.connectionInfo ?: return WifiSnap("Not connected to Wi-Fi.", null, false)
        if (info.networkId == -1 && info.ipAddress == 0) return WifiSnap("Not connected to Wi-Fi.", null, false)

        val ssidRaw = info.ssid?.trim('"') ?: ""
        val hiddenName = ssidRaw.isEmpty() || ssidRaw == "<unknown ssid>"
        val bssid = info.bssid?.takeIf { it != "02:00:00:00:00:00" }
        val freq = info.frequency
        val band = when (freq) { in 2400..2500 -> "2.4 GHz"; in 4900..5900 -> "5 GHz"; in 5925..7125 -> "6 GHz"; else -> "$freq MHz" }
        val channel = when (freq) {
            2484 -> 14
            in 2412..2472 -> (freq - 2407) / 5
            in 5000..5900 -> (freq - 5000) / 5
            in 5955..7115 -> (freq - 5950) / 5
            else -> 0
        }
        val sb = StringBuilder()
        fun r(k: String, v: Any?) { sb.append(k.padEnd(12)).append(v?.toString() ?: "—").append('\n') }
        r("NETWORK", if (hiddenName) "hidden — tap “Show network name”" else ssidRaw)
        r("ACCESS PT", bssid ?: "—")
        r("SIGNAL", "${info.rssi} dBm")
        r("BAND", band)
        r("CHANNEL", if (channel > 0) channel else "—")
        r("FREQUENCY", "$freq MHz")
        r("LINK SPEED", "${info.linkSpeed} Mbps")
        if (Build.VERSION.SDK_INT >= 29) {
            r("TX / RX", "${info.txLinkSpeedMbps} / ${info.rxLinkSpeedMbps} Mbps")
        }
        if (Build.VERSION.SDK_INT >= 30) {
            r("STANDARD", when (info.wifiStandard) {
                1 -> "802.11a/b/g (legacy)"
                4 -> "Wi-Fi 4 (802.11n)"
                5 -> "Wi-Fi 5 (802.11ac)"
                6 -> "Wi-Fi 6 / 6E (802.11ax)"
                7 -> "802.11ad"
                8 -> "Wi-Fi 7 (802.11be)"
                else -> "unknown"
            })
            r("MAX SPEED", "${info.maxSupportedTxLinkSpeedMbps} / ${info.maxSupportedRxLinkSpeedMbps} Mbps")
        }
        val ip = info.ipAddress
        if (ip != 0) r("IP", "${ip and 255}.${(ip shr 8) and 255}.${(ip shr 16) and 255}.${(ip shr 24) and 255}")
        r("GATEWAY", gateway())
        return WifiSnap(sb.toString().trimEnd(), info.rssi, hiddenName)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.none { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            toast("Without location permission Android hides the network name")
        }
    }

    // ---------- SSL / certificate checker ----------

    private fun sslTool() {
        val host = add(field("domain, e.g. google.com", "google.com"), 14)
        val port = field("port", "443", number = true)
        lateinit var out: TextView
        row(port, button("Check") {
            val h = host.text.toString().trim().removePrefix("https://").removePrefix("http://")
                .substringBefore('/').substringBefore('?')
            val hostOnly = h.substringBefore(':')
            val p = (if (h.contains(':')) h.substringAfter(':') else port.text.toString()).toIntOrNull()?.coerceIn(1, 65535) ?: 443
            if (!validHost(hostOnly)) { out.text = "invalid host"; return@button }
            out.text = "connecting to $hostOnly:$p …"
            bg {
                val report = try { sslReport(hostOnly, p) } catch (e: Exception) { "error: ${e.message}" }
                ui { out.text = report }
            }
        }, button("Copy", primary = false) { copy(out.text.toString()) })
        out = output()
    }

    private fun sslHandshake(host: String, port: Int, trustAll: Boolean): javax.net.ssl.SSLSession {
        val factory = if (trustAll) {
            // only used to *display* a certificate that already failed normal validation
            val tm = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
            javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }.socketFactory
        } else {
            javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        }
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 7000)
        raw.soTimeout = 7000
        val s = factory.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket
        try {
            s.startHandshake()
            return s.session
        } finally {
            try { s.close() } catch (e: Exception) {}
        }
    }

    private fun sslReport(host: String, port: Int): String {
        var trustError: String? = null
        val session = try {
            sslHandshake(host, port, trustAll = false)
        } catch (e: javax.net.ssl.SSLException) {
            trustError = e.message ?: e.javaClass.simpleName
            sslHandshake(host, port, trustAll = true)
        }
        val certs = session.peerCertificates.filterIsInstance<java.security.cert.X509Certificate>()
        val leaf = certs.firstOrNull() ?: return "No certificate returned."
        val nameOk = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
        val now = System.currentTimeMillis()
        val daysLeft = (leaf.notAfter.time - now) / 86_400_000L
        val fmt = java.text.SimpleDateFormat("dd MMM yyyy", Locale.US)
        fun cn(dn: String) = Regex("CN=([^,]+)").find(dn)?.groupValues?.get(1) ?: dn
        fun org(dn: String) = Regex("O=([^,]+)").find(dn)?.groupValues?.get(1)
        val sans = try {
            leaf.subjectAlternativeNames?.filter { it.size >= 2 && it[0] == 2 }?.map { it[1].toString() } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        val key = leaf.publicKey
        val keyDesc = when (key) {
            is java.security.interfaces.RSAPublicKey -> "RSA ${key.modulus.bitLength()}"
            is java.security.interfaces.ECPublicKey -> "EC ${key.params.curve.field.fieldSize}"
            else -> key.algorithm
        }
        val status = when {
            trustError != null -> "✗ NOT TRUSTED — $trustError"
            !nameOk -> "✗ NAME MISMATCH — certificate is not for $host"
            daysLeft < 0 -> "✗ EXPIRED"
            daysLeft < 30 -> "⚠ VALID, expires soon"
            else -> "✓ VALID & TRUSTED"
        }
        val sb = StringBuilder()
        fun r(k: String, v: Any?) { sb.append(k.padEnd(12)).append(v?.toString() ?: "—").append('\n') }
        r("HOST", "$host:$port")
        r("STATUS", status)
        r("EXPIRES", "${fmt.format(leaf.notAfter)}  (${if (daysLeft >= 0) "$daysLeft days left" else "${-daysLeft} days ago"})")
        r("VALID FROM", fmt.format(leaf.notBefore))
        r("SUBJECT", cn(leaf.subjectX500Principal.name))
        r("ISSUER", listOfNotNull(org(leaf.issuerX500Principal.name), cn(leaf.issuerX500Principal.name)).distinct().joinToString(" · "))
        r("PROTOCOL", session.protocol)
        r("CIPHER", session.cipherSuite)
        r("KEY", keyDesc)
        r("SIGNATURE", leaf.sigAlgName)
        r("SERIAL", leaf.serialNumber.toString(16).uppercase(Locale.US))
        if (sans.isNotEmpty()) {
            sb.append("\nCOVERS (${sans.size})\n")
            sans.take(25).forEach { sb.append("  ").append(it).append('\n') }
            if (sans.size > 25) sb.append("  … +${sans.size - 25} more\n")
        }
        sb.append("\nCHAIN (${certs.size})\n")
        certs.forEachIndexed { i, c -> sb.append("  ${i + 1}. ").append(cn(c.subjectX500Principal.name)).append('\n') }
        return sb.toString().trimEnd()
    }

    // ---------- Wake-on-LAN ----------

    private data class WolDevice(val name: String, val mac: String, val host: String, val port: Int)

    private fun wolLoad(): MutableList<WolDevice> {
        val raw = getSharedPreferences("alfa", MODE_PRIVATE).getString("wol_devices", "[]") ?: "[]"
        return try {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                WolDevice(o.getString("name"), o.getString("mac"), o.optString("host", "255.255.255.255"), o.optInt("port", 9))
            }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun wolSave(list: List<WolDevice>) {
        val a = org.json.JSONArray()
        list.forEach { d -> a.put(org.json.JSONObject().put("name", d.name).put("mac", d.mac).put("host", d.host).put("port", d.port)) }
        getSharedPreferences("alfa", MODE_PRIVATE).edit().putString("wol_devices", a.toString()).apply()
    }

    /** Broadcast address of the current IPv4 network (falls back to 255.255.255.255). */
    private fun localBroadcast(): String {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return "255.255.255.255"
        val la = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.firstOrNull { it.address is Inet4Address }
            ?: return "255.255.255.255"
        val ip = parseIp(la.address.hostAddress ?: return "255.255.255.255") ?: return "255.255.255.255"
        val cidr = la.prefixLength
        val all = 0xFFFFFFFFL
        val mask = if (cidr == 0) 0L else (all shl (32 - cidr)) and all
        return ipStr((ip and mask) or (mask.inv() and all))
    }

    private fun normalizeMac(s: String): String? {
        val hex = s.trim().replace(Regex("[^0-9A-Fa-f]"), "")
        if (hex.length != 12) return null
        return hex.uppercase(Locale.US).chunked(2).joinToString(":")
    }

    private fun sendMagicPacket(d: WolDevice) {
        val macBytes = d.mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)
        val addr = InetAddress.getByName(d.host)
        java.net.DatagramSocket().use { s ->
            s.broadcast = true
            repeat(3) { s.send(java.net.DatagramPacket(packet, packet.size, addr, d.port)) }
        }
    }

    private fun wolTool() {
        val listCard = card()
        add(listCard, 14)

        fun renderList() {
            listCard.removeAllViews()
            val devices = wolLoad()
            if (devices.isEmpty()) {
                listCard.addView(tv("No devices yet — add one below.", 13f, dim))
                return
            }
            devices.forEachIndexed { i, d ->
                if (i > 0) listCard.addView(View(this).apply { setBackgroundColor(Color.argb(20, 255, 255, 255)) },
                    LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(10); bottomMargin = dp(10) })
                val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(tv(d.name, 15f, white, font = "sans-serif-medium"))
                col.addView(tv("${d.mac}  ·  ${d.host}:${d.port}", 11f, dim, mono = true), lp(2))
                r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(button("Wake") {
                    bg {
                        val msg = try { sendMagicPacket(d); "Magic packet sent to ${d.name}" } catch (e: Exception) { "Send failed: ${e.message}" }
                        ui { toast(msg) }
                    }
                }.apply { setPadding(dp(16), dp(9), dp(16), dp(9)) })
                r.addView(tv("✕", 16f, dim).apply {
                    setPadding(dp(14), dp(8), dp(4), dp(8))
                    setOnClickListener {
                        android.app.AlertDialog.Builder(this@ToolsActivity, android.R.style.Theme_Material_Dialog_Alert)
                            .setMessage("Remove ${d.name}?")
                            .setPositiveButton("Remove") { _, _ -> wolSave(wolLoad().also { l -> l.removeAll { it == d } }); renderList() }
                            .setNegativeButton("Cancel", null).show()
                    }
                })
                listCard.addView(r)
            }
        }
        renderList()

        add(tv("ADD DEVICE", 11f, accent, mono = true).apply { letterSpacing = 0.2f }, 22)
        val name = add(field("Name, e.g. Office PC"), 8)
        val mac = add(field("MAC address, e.g. 3C:7C:3F:12:AB:9E"), 8)
        val host = field("Broadcast address", localBroadcast())
        val port = field("Port", "9", number = true)
        row(host, port, top = 8)
        row(button("Save device") {
            val m = normalizeMac(mac.text.toString())
            if (m == null) { toast("Enter a valid MAC address (12 hex digits)"); return@button }
            val h = host.text.toString().trim().ifEmpty { "255.255.255.255" }
            if (!validHost(h)) { toast("Invalid broadcast address"); return@button }
            val n = name.text.toString().trim().ifEmpty { m }
            val p = port.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: 9
            wolSave(wolLoad().apply { add(WolDevice(n, m, h, p)) })
            name.setText(""); mac.setText("")
            renderList()
            toast("Saved $n")
        })
        add(tv("The target must have Wake-on-LAN enabled in its BIOS/UEFI and network adapter settings, " +
            "and be on the same network as your phone (or reachable through a directed broadcast).", 11f, dimmer), 14)
    }

    // ---------- QR generator ----------

    private var qrBitmap: android.graphics.Bitmap? = null
    private var qrName = "alfa-qr"

    private fun qrTool() {
        var type = "wifi"
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val preview = android.widget.ImageView(this).apply {
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        val previewCard = card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(preview, LinearLayout.LayoutParams(dp(240), dp(240)))
        }
        val caption = tv("", 11f, dim, mono = true).apply { gravity = Gravity.CENTER }

        // fields
        val ssid = field("Wi-Fi name (SSID)")
        val pass = field("Password")
        var security = "WPA"
        var hidden = false
        val url = field("https://example.com")
        val text = field("Any text", multi = true)

        fun seg(options: List<Pair<String, String>>, selected: () -> String, pick: (String) -> Unit): LinearLayout {
            val r = LinearLayout(this)
            fun paint() {
                for (i in 0 until r.childCount) {
                    val v = r.getChildAt(i) as TextView
                    val on = v.tag == selected()
                    v.backgroundTintList = ColorStateList.valueOf(if (on) accent else Color.argb(38, 255, 255, 255))
                    v.setTextColor(if (on) Color.BLACK else white)
                }
            }
            options.forEachIndexed { i, (label, value) ->
                r.addView(button(label, primary = false) { pick(value); paint() }.apply { tag = value },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
            }
            paint()
            return r
        }

        fun buildForm() {
            form.removeAllViews()
            fun addF(v: View, top: Int = 10) = form.addView(v, lp(top))
            when (type) {
                "wifi" -> {
                    addF(ssid, 0); addF(pass)
                    addF(seg(listOf("WPA/WPA2/WPA3" to "WPA", "WEP" to "WEP", "Open" to "nopass"), { security }) {
                        security = it
                        pass.visibility = if (it == "nopass") View.GONE else View.VISIBLE
                    })
                    addF(seg(listOf("Visible network" to "no", "Hidden network" to "yes"), { if (hidden) "yes" else "no" }) {
                        hidden = it == "yes"
                    }, 8)
                }
                "link" -> addF(url, 0)
                else -> addF(text, 0)
            }
        }

        add(seg(listOf("Wi-Fi" to "wifi", "Link" to "link", "Text" to "text"), { type }) {
            type = it; buildForm()
            previewCard.visibility = View.GONE; caption.text = ""; qrBitmap = null
        }, 14)
        add(form, 12)
        buildForm()

        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
            .replace(":", "\\:").replace("\"", "\\\"")

        row(button("Generate QR") {
            val payload: String
            when (type) {
                "wifi" -> {
                    val s = ssid.text.toString()
                    if (s.isBlank()) { toast("Enter the Wi-Fi name"); return@button }
                    val p = pass.text.toString()
                    if (security != "nopass" && p.isEmpty()) { toast("Enter the password (or choose Open)"); return@button }
                    payload = "WIFI:T:$security;S:${esc(s)};" + (if (security != "nopass") "P:${esc(p)};" else "") +
                        (if (hidden) "H:true;" else "") + ";"
                    qrName = "wifi-" + s.replace(Regex("[^A-Za-z0-9_-]"), "_")
                    caption.text = "Scan to join \u201C$s\u201D"
                }
                "link" -> {
                    var u = url.text.toString().trim()
                    if (u.isEmpty()) { toast("Enter a link"); return@button }
                    if (!u.contains("://")) u = "https://$u"
                    payload = u
                    qrName = "link-qr"
                    caption.text = u
                }
                else -> {
                    val t = text.text.toString()
                    if (t.isBlank()) { toast("Enter some text"); return@button }
                    payload = t
                    qrName = "text-qr"
                    caption.text = if (t.length > 60) t.take(60) + "\u2026" else t
                }
            }
            val bmp = try { renderQr(payload, 1024) } catch (e: Exception) { toast("Too much data for one QR code"); return@button }
            qrBitmap = bmp
            preview.setImageBitmap(bmp)
            previewCard.visibility = View.VISIBLE
        })

        add(previewCard, 16)
        add(caption, 8)
        row(
            button("Save PNG", primary = false) { qrBitmap?.let { saveQr(it) } ?: toast("Generate a QR first") },
            button("Share", primary = false) { qrBitmap?.let { shareQr(it) } ?: toast("Generate a QR first") },
            top = 12,
        )
        add(tv("Wi-Fi codes use the standard format, so the phone camera on Android and iPhone offers to join the network.",
            11f, dimmer), 14)
    }

    /** Encodes [content] into a crisp black-on-white QR bitmap with a quiet zone. */
    private fun renderQr(content: String, size: Int): android.graphics.Bitmap {
        val hints = mapOf(
            com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            com.google.zxing.EncodeHintType.MARGIN to 2,
        )
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) px[o + x] = if (m.get(x, y)) Color.rgb(10, 10, 14) else Color.WHITE
        }
        return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun pngBytes(bmp: android.graphics.Bitmap): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    /** Android 10+: straight into Pictures/ALFA. Older: let the user pick where to save. */
    @Suppress("DEPRECATION")
    private fun saveQr(bmp: android.graphics.Bitmap): android.net.Uri? {
        val name = "$qrName-${System.currentTimeMillis() / 1000}.png"
        if (Build.VERSION.SDK_INT >= 29) {
            return try {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ALFA")
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.IOException("no media store")
                contentResolver.openOutputStream(uri)?.use { it.write(pngBytes(bmp)) }
                toast("Saved to Pictures/ALFA")
                uri
            } catch (e: Exception) {
                toast("Couldn't save: ${e.message}")
                null
            }
        }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/png").putExtra(Intent.EXTRA_TITLE, name)
        try { startActivityForResult(i, REQ_SAVE_QR) } catch (e: Exception) { toast("No file picker available") }
        return null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE_QR && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val bmp = qrBitmap ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(pngBytes(bmp)) }
                toast("QR saved")
            } catch (e: Exception) {
                toast("Couldn't save: ${e.message}")
            }
        }
    }

    private fun shareQr(bmp: android.graphics.Bitmap) {
        if (Build.VERSION.SDK_INT < 29) { toast("Save the PNG first, then share it from Gallery"); saveQr(bmp); return }
        val uri = saveQr(bmp) ?: return
        val send = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startSafe(Intent.createChooser(send, "Share QR code"))
    }

    // ---------- ping ----------

    private fun pingTool() {
        val host = add(field("host or IP", "8.8.8.8"), 14)
        val count = field("count", "4", number = true)
        lateinit var out: TextView
        row(count,
            button("Run") {
                val h = host.text.toString().trim()
                if (!validHost(h)) { out.text = "invalid host"; return@button }
                stopWork()
                cancelled = false
                val c = count.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 4
                out.text = ""
                bg {
                    try {
                        val p = ProcessBuilder("ping", "-c", "$c", "-W", "3", h).redirectErrorStream(true).start()
                        pingProc = p
                        p.inputStream.bufferedReader().forEachLine { line -> ui { out.append(line + "\n") } }
                        val code = p.waitFor()
                        ui { out.append(if (cancelled) "\n[stopped]" else "\n[exit $code]") }
                    } catch (e: Exception) {
                        ui { out.append("error: ${e.message}") }
                    } finally {
                        pingProc = null
                    }
                }
            },
            button("Stop", primary = false) { stopWork() },
        )
        row(
            button("Google DNS", primary = false) { host.setText("8.8.8.8") },
            button("Cloudflare", primary = false) { host.setText("1.1.1.1") },
            button("Gateway", primary = false) { gateway()?.let { host.setText(it) } ?: toast("No gateway") },
            top = 8,
        )
        out = output("ready.")
    }

    private fun gateway(): String? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
    }

    // ---------- dns ----------

    private fun dnsTool() {
        val host = add(field("domain or IP", "google.com"), 14)
        lateinit var out: TextView
        row(button("Resolve") {
            val h = host.text.toString().trim()
            if (!validHost(h)) { out.text = "invalid host"; return@button }
            out.text = "resolving $h …"
            bg {
                val t0 = SystemClock.elapsedRealtime()
                val text = try {
                    val addrs = InetAddress.getAllByName(h)
                    val ms = SystemClock.elapsedRealtime() - t0
                    buildString {
                        append("${addrs.size} record(s) in $ms ms\n\n")
                        addrs.forEach { a -> append(if (a is Inet4Address) "A     " else "AAAA  ").append(a.hostAddress).append('\n') }
                        if (h.first().isDigit() || h.contains(':')) {
                            append("PTR   ").append(InetAddress.getByName(h).canonicalHostName).append('\n')
                        }
                    }.trimEnd()
                } catch (e: Exception) {
                    "unresolved: ${e.message}"
                }
                ui { out.text = text }
            }
        }, button("Copy", primary = false) { copy(out.text.toString()) })
        out = output()
    }

    // ---------- ports ----------

    private fun portsTool() {
        add(tv("Only scan hosts you own or are authorised to test.", 11f, dimmer), 10)
        val host = add(field("host or IP", gateway() ?: "192.168.1.1"), 10)
        val ports = add(field("ports: 22,80,443 or 1-1024", COMMON_PORTS), 8)
        val timeout = field("timeout ms", "400", number = true)
        lateinit var out: TextView
        lateinit var status: TextView
        row(timeout,
            button("Scan") {
                val h = host.text.toString().trim()
                if (!validHost(h)) { out.text = "invalid host"; return@button }
                val list = parsePorts(ports.text.toString())
                if (list == null) { out.text = "invalid port list (max 4096 ports)"; return@button }
                val to = timeout.text.toString().toIntOrNull()?.coerceIn(50, 5000) ?: 400
                stopWork()
                cancelled = false
                out.text = ""
                status.text = "resolving…"
                bg { scan(h, list, to, out, status) }
            },
            button("Stop", primary = false) { stopWork() },
        )
        row(
            button("Common", primary = false) { ports.setText(COMMON_PORTS) },
            button("Web", primary = false) { ports.setText("80,443,8000,8080,8443,8888") },
            button("1-1024", primary = false) { ports.setText("1-1024") },
            top = 8,
        )
        status = add(tv("", 11f, accent, mono = true), 14)
        out = output("ready.")
    }

    private fun parsePorts(s: String): List<Int>? {
        val set = sortedSetOf<Int>()
        for (part in s.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            if (part.contains('-')) {
                val (a, b) = part.split('-', limit = 2).map { it.trim().toIntOrNull() ?: return null }
                if (a !in 1..65535 || b !in 1..65535 || a > b) return null
                for (p in a..b) set.add(p)
            } else {
                val p = part.toIntOrNull() ?: return null
                if (p !in 1..65535) return null
                set.add(p)
            }
            if (set.size > 4096) return null
        }
        return if (set.isEmpty()) null else set.toList()
    }

    private fun scan(host: String, ports: List<Int>, timeout: Int, out: TextView, status: TextView) {
        val addr = try { InetAddress.getByName(host) } catch (e: Exception) {
            ui { status.text = ""; out.text = "unresolved: ${e.message}" }
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        val open = ConcurrentSkipListSet<Int>()
        val done = AtomicInteger()
        val exec = Executors.newFixedThreadPool(48)
        ui { out.text = "SCAN ${addr.hostAddress}  ·  ${ports.size} port(s)\n\n" }
        for (p in ports) {
            exec.execute {
                if (cancelled) return@execute
                try {
                    Socket().use { it.connect(InetSocketAddress(addr, p), timeout) }
                    open.add(p)
                    ui { out.append("OPEN  ${p.toString().padEnd(6)}${SERVICES[p] ?: ""}\n") }
                } catch (e: Exception) {
                    // closed / filtered
                }
                val d = done.incrementAndGet()
                if (d % 16 == 0 || d == ports.size) ui { status.text = "scanned $d / ${ports.size}" }
            }
        }
        exec.shutdown()
        exec.awaitTermination(30, TimeUnit.MINUTES)
        val ms = SystemClock.elapsedRealtime() - t0
        ui {
            out.append("\n${open.size} open  ·  ${done.get()} checked  ·  $ms ms${if (cancelled) "  ·  stopped" else ""}")
        }
    }

    // ---------- subnet ----------

    private fun subnetTool() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val mine = cm?.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }?.let { "${it.address.hostAddress}/${it.prefixLength}" }
        val input = add(field("a.b.c.d/cidr", mine ?: "192.168.1.0/24"), 14)
        lateinit var out: TextView
        row(button("Calculate") { out.text = subnetReport(input.text.toString()) },
            button("Copy", primary = false) { copy(out.text.toString()) })
        out = output(subnetReport(input.text.toString()))
    }

    private fun parseIp(s: String): Long? {
        val p = s.trim().split('.')
        if (p.size != 4) return null
        var v = 0L
        for (x in p) {
            val n = x.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    private fun ipStr(v: Long) = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

    private fun subnetReport(s: String): String {
        val parts = s.trim().split('/')
        val ip = parseIp(parts[0]) ?: return "invalid IPv4 address"
        val cidr = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: return "invalid prefix" else 24
        if (cidr !in 0..32) return "prefix must be 0-32"
        val all = 0xFFFFFFFFL
        val mask = if (cidr == 0) 0L else (all shl (32 - cidr)) and all
        val net = ip and mask
        val bcast = net or (mask.inv() and all)
        val total = 1L shl (32 - cidr)
        val usable = when (cidr) { 32 -> 1L; 31 -> 2L; else -> total - 2 }
        val first = if (cidr >= 31) net else net + 1
        val last = if (cidr >= 31) bcast else bcast - 1
        val a = (ip shr 24).toInt()
        val b = ((ip shr 16) and 255).toInt()
        val cls = when { a < 128 -> "A"; a < 192 -> "B"; a < 224 -> "C"; a < 240 -> "D (multicast)"; else -> "E" }
        val type = when {
            a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) -> "private (RFC1918)"
            a == 127 -> "loopback"
            a == 169 && b == 254 -> "link-local"
            a == 100 && b in 64..127 -> "CGNAT (RFC6598)"
            a >= 224 -> "multicast / reserved"
            else -> "public"
        }
        val bin = (0 until 32).joinToString("") { i -> if ((mask shr (31 - i)) and 1L == 1L) "1" else "0" }.chunked(8).joinToString(".")
        fun r(k: String, v: String) = k.padEnd(12) + v
        return listOf(
            r("ADDRESS", ipStr(ip)),
            r("NETMASK", "${ipStr(mask)}  (/$cidr)"),
            r("WILDCARD", ipStr(mask.inv() and all)),
            r("NETWORK", ipStr(net)),
            r("BROADCAST", ipStr(bcast)),
            r("FIRST HOST", ipStr(first)),
            r("LAST HOST", ipStr(last)),
            r("HOSTS", String.format(Locale.US, "%,d usable / %,d total", usable, total)),
            r("CLASS", cls),
            r("TYPE", type),
            "",
            "MASK BITS",
            bin,
        ).joinToString("\n")
    }

    // ---------- device ----------

    private fun deviceTool() {
        val report = deviceReport()
        row(button("Copy report") { copy(report) },
            button("About phone", primary = false) { startSafe(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) })
        output(report)
    }

    private fun deviceReport(): String {
        val sb = StringBuilder()
        fun r(k: String, v: Any?) { sb.append(k.padEnd(12)).append(v?.toString()?.ifEmpty { "—" } ?: "—").append('\n') }
        fun gb(b: Long) = String.format(Locale.US, "%.1f GB", b / 1_073_741_824.0)

        r("MAKER", Build.MANUFACTURER)
        r("MODEL", Build.MODEL)
        r("DEVICE", Build.DEVICE)
        r("BRAND", Build.BRAND)
        r("BOARD", Build.BOARD)
        r("HARDWARE", Build.HARDWARE)
        if (Build.VERSION.SDK_INT >= 31) r("SOC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        r("ANDROID", "${Build.VERSION.RELEASE}  (API ${Build.VERSION.SDK_INT})")
        r("PATCH", Build.VERSION.SECURITY_PATCH)
        r("BUILD", Build.ID)
        r("KERNEL", System.getProperty("os.version"))
        r("ABI", Build.SUPPORTED_ABIS.joinToString(", "))
        r("CPU CORES", Runtime.getRuntime().availableProcessors())
        val maxFreq = try {
            File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000
        } catch (e: Exception) { null }
        if (maxFreq != null) r("CPU0 MAX", "$maxFreq MHz")

        val am = getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        r("RAM", "${gb(mem.totalMem - mem.availMem)} used / ${gb(mem.totalMem)}")
        val fs = StatFs(Environment.getDataDirectory().path)
        r("STORAGE", "${gb(fs.totalBytes - fs.availableBytes)} used / ${gb(fs.totalBytes)}")

        val dm = resources.displayMetrics
        r("DISPLAY", "${dm.widthPixels} x ${dm.heightPixels} px")
        r("DENSITY", "${dm.densityDpi} dpi")
        r("REFRESH", String.format(Locale.US, "%.0f Hz", windowManager.defaultDisplay.refreshRate))

        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bat != null) {
            val health = when (bat.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                else -> "unknown"
            }
            r("BATTERY", "${bat.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)}%  ·  $health")
            r("BATT TECH", bat.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY))
            r("BATT TEMP", String.format(Locale.US, "%.1f °C", bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f))
            r("BATT VOLT", "${bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)} mV")
        }
        val up = SystemClock.elapsedRealtime() / 1000
        r("UPTIME", String.format(Locale.US, "%dd %02dh %02dm", up / 86400, (up / 3600) % 24, (up / 60) % 60))
        val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/app/Superuser.apk")
        r("ROOT", if (suPaths.any { File(it).exists() }) "su binary found" else "not detected")
        r("BOOTLOADER", Build.BOOTLOADER)
        sb.append("\nFINGERPRINT\n").append(Build.FINGERPRINT)
        return sb.toString()
    }

    // ---------- hash / base64 ----------

    private fun hashTool() {
        val input = add(field("text", multi = true), 14)
        lateinit var out: TextView
        row(
            button("Hash") { out.text = hashReport(input.text.toString()) },
            button("B64 enc", primary = false) {
                out.text = Base64.encodeToString(input.text.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            },
            button("B64 dec", primary = false) {
                out.text = try {
                    String(Base64.decode(input.text.toString().trim(), Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: Exception) { "invalid base64" }
            },
        )
        row(button("Copy output", primary = false) { copy(out.text.toString()) }, top = 8)
        out = output("")
    }

    private fun hashReport(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        fun h(alg: String) = MessageDigest.getInstance(alg).digest(bytes).joinToString("") { "%02x".format(it) }
        val crc = CRC32().apply { update(bytes) }.value
        return buildString {
            append("BYTES   ${bytes.size}\n")
            append("CRC32   ${"%08x".format(crc)}\n\n")
            append("MD5\n${h("MD5")}\n\n")
            append("SHA-1\n${h("SHA-1")}\n\n")
            append("SHA-256\n${h("SHA-256")}\n\n")
            append("SHA-512\n${h("SHA-512")}")
        }
    }

    // ---------- password ----------

    private fun passwordTool() {
        val sets = listOf(
            "a-z" to "abcdefghijklmnopqrstuvwxyz",
            "A-Z" to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "0-9" to "0123456789",
            "#\$%" to "!@#\$%^&*()-_=+[]{};:,.?/",
        )
        val on = booleanArrayOf(true, true, true, true)
        var noLookAlikes = true
        val length = field("length", "20", number = true)

        val toggles = sets.mapIndexed { i, (label, _) ->
            button(label, primary = true) {}.also { b ->
                b.setOnClickListener {
                    on[i] = !on[i]
                    b.backgroundTintList = ColorStateList.valueOf(if (on[i]) accent else Color.argb(38, 255, 255, 255))
                    b.setTextColor(if (on[i]) Color.BLACK else white)
                }
            }
        }
        row(*toggles.toTypedArray(), top = 14)
        val look = button("No look-alikes (l 1 O 0)", primary = true) {}
        look.setOnClickListener {
            noLookAlikes = !noLookAlikes
            look.backgroundTintList = ColorStateList.valueOf(if (noLookAlikes) accent else Color.argb(38, 255, 255, 255))
            look.setTextColor(if (noLookAlikes) Color.BLACK else white)
        }
        row(look, top = 8)

        val list = card()
        val info = tv("", 11f, dim, mono = true)
        row(length, button("Generate") {
            val len = length.text.toString().toIntOrNull()?.coerceIn(4, 128) ?: 20
            val chosen = sets.filterIndexed { i, _ -> on[i] }.map { (_, chars) ->
                if (noLookAlikes) chars.filterNot { it in "lI1O0o" } else chars
            }
            if (chosen.isEmpty()) { toast("Pick at least one character set"); return@button }
            val charPool = chosen.joinToString("")
            val rnd = SecureRandom()
            list.removeAllViews()
            repeat(5) {
                val chars = chosen.map { set -> set[rnd.nextInt(set.length)] }.toMutableList()
                while (chars.size < len) chars.add(charPool[rnd.nextInt(charPool.length)])
                for (k in chars.indices.reversed()) {
                    val j = rnd.nextInt(k + 1)
                    val t = chars[k]; chars[k] = chars[j]; chars[j] = t
                }
                val pw = chars.take(len).joinToString("")
                list.addView(tv(pw, 15f, white, mono = true).apply {
                    setPadding(0, dp(9), 0, dp(9))
                    setOnClickListener { copy(pw) }
                })
            }
            val bits = len * (ln(charPool.length.toDouble()) / ln(2.0))
            info.text = String.format(Locale.US, "POOL %d chars  ·  ~%.0f bits entropy  ·  tap to copy", charPool.length, bits)
        })
        add(info, 14)
        add(list, 8)
    }

    // ---------- system panels ----------

    private fun shortcutsTool() {
        val items = listOf(
            Triple("NET", "Wi-Fi", Settings.ACTION_WIFI_SETTINGS),
            Triple("NET", "Network & internet", Settings.ACTION_WIRELESS_SETTINGS),
            Triple("NET", "Mobile data", Settings.ACTION_DATA_ROAMING_SETTINGS),
            Triple("NET", "VPN", Settings.ACTION_VPN_SETTINGS),
            Triple("NET", "Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
            Triple("NET", "NFC", Settings.ACTION_NFC_SETTINGS),
            Triple("SYS", "Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Triple("SYS", "Date & time", Settings.ACTION_DATE_SETTINGS),
            Triple("SYS", "Display", Settings.ACTION_DISPLAY_SETTINGS),
            Triple("SYS", "Battery saver", Settings.ACTION_BATTERY_SAVER_SETTINGS),
            Triple("SYS", "Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            Triple("SYS", "About phone", Settings.ACTION_DEVICE_INFO_SETTINGS),
            Triple("APP", "All apps", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
            Triple("APP", "Default apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Triple("APP", "Home app", Settings.ACTION_HOME_SETTINGS),
            Triple("APP", "Usage access", Settings.ACTION_USAGE_ACCESS_SETTINGS),
            Triple("SEC", "Security", Settings.ACTION_SECURITY_SETTINGS),
            Triple("SEC", "Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            Triple("SEC", "Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Triple("SEC", "Privacy", Settings.ACTION_PRIVACY_SETTINGS),
        )
        grid(items.map { (tag, name, action) -> tile(tag, name, "open ›") { startSafe(Intent(action)) } })
    }
}
