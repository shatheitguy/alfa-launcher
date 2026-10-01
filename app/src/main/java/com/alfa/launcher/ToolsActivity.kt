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
        root.addView(HudBackground(this, null).also { it.accent = accent }, FrameLayout.LayoutParams(-1, -1))
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
        add(info, 14)

        var release: Updater.Release? = null
        val busy = false

        lateinit var action: TextView
        lateinit var check: () -> Unit

        fun setAction(label: String, enabled: Boolean) {
            action.text = label
            action.isEnabled = enabled
            action.alpha = if (enabled) 1f else 0.4f
        }

        action = button("Download update") {
            val rel = release ?: return@button
            try {
                Updater.downloadInBrowser(this, rel)
                toast("Downloading in your browser — open it when done and tap Update")
            } catch (e: Exception) {
                toast("No browser available")
            }
        }

        check = {
            setAction("Checking…", false)
            status.text = "checking GitHub…"
            bg {
                try {
                    val rel = Updater.fetchLatest()
                    Updater.remember(this, rel)
                    ui {
                        release = rel
                        val newer = rel.code > installedCode
                        status.text = buildString {
                            append("LATEST      ${rel.tag}  (build ${rel.code})\n")
                            append("SIZE        ").append(String.format(Locale.US, "%.1f MB", rel.size / 1048576.0)).append('\n')
                            append("STATUS      ").append(if (newer) "update available" else "up to date")
                            if (rel.notes.isNotEmpty()) append("\n\n").append(rel.notes)
                        }
                        if (newer) setAction("Download update ${rel.tag}", true)
                        else setAction("Up to date", false)
                    }
                } catch (e: Exception) {
                    ui {
                        status.text = "check failed: ${e.message}"
                        setAction("Download update", false)
                    }
                }
            }
        }

        row(action, top = 4)
        row(
            button("Check again", primary = false) { if (!busy) check() },
            button("Reinstall latest", primary = false) {
                try { Updater.downloadInBrowser(this, release) } catch (e: Exception) { toast("No browser available") }
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

        add(tv("Updates come from github.com/${Updater.REPO}. The APK downloads in your browser; open it and tap Update. " +
            "It is signed with the same key, so your pins, dock and settings are kept.",
            11f, dimmer), 16)

        check()
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
