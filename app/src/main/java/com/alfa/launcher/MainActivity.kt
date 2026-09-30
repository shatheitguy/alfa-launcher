package com.alfa.launcher

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var clock: TextView
    private lateinit var seconds: TextView
    private lateinit var date: TextView
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var logView: TextView
    private lateinit var appsHeader: TextView
    private lateinit var input: EditText
    private lateinit var grid: RecyclerView
    private lateinit var adapter: AppAdapter

    private var allApps: List<AppEntry> = emptyList()
    private var filtered: List<AppEntry> = emptyList()
    private var booted = false
    private var tickCount = 0

    private val logLines = ArrayDeque<String>()
    private var typing: Runnable? = null

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    private val secFmt = SimpleDateFormat(":ss", Locale.US)
    private val dateFmt = SimpleDateFormat("EEE dd.MM.yyyy", Locale.US)

    private val cyan = Color.parseColor("#00F0FF")
    private val magenta = Color.parseColor("#FF2BD6")
    private val green = Color.parseColor("#39FF14")
    private val red = Color.parseColor("#FF3355")

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = loadApps()
    }

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            if (tickCount % 5 == 0) updateStats()
            if (tickCount % 9 == 4) glitch()
            tickCount++
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        clock = findViewById(R.id.clock)
        seconds = findViewById(R.id.seconds)
        date = findViewById(R.id.date)
        status = findViewById(R.id.status)
        stats = findViewById(R.id.stats)
        logView = findViewById(R.id.log)
        appsHeader = findViewById(R.id.appsHeader)
        input = findViewById(R.id.input)
        grid = findViewById(R.id.apps)

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0"
        }
        findViewById<TextView>(R.id.header).text = "ALFA//OS  v$version"

        adapter = AppAdapter(::launch, ::showAppMenu)
        grid.layoutManager = GridLayoutManager(this, 4)
        grid.adapter = adapter

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_NULL) {
                execute(input.text.toString())
                true
            } else false
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pkgReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pkgReceiver, filter)
        }

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        tickCount = 0
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(pkgReceiver)
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        resetInput()
        grid.smoothScrollToPosition(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Home screen: never exit. Just clear the prompt.
        resetInput()
    }

    // ---------- apps ----------

    private fun loadApps() {
        io.execute {
            val pm = packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val list = pm.queryIntentActivities(query, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    AppEntry(
                        it.loadLabel(pm).toString(),
                        it.activityInfo.packageName,
                        it.activityInfo.name,
                        it.loadIcon(pm),
                    )
                }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
            handler.post {
                allApps = list
                applyFilter()
                if (!booted) {
                    booted = true
                    bootSequence()
                }
            }
        }
    }

    private fun applyFilter() {
        val q = input.text.toString().trim().lowercase(Locale.ROOT)
        filtered = if (q.isEmpty()) allApps else allApps.filter {
            it.label.lowercase(Locale.ROOT).contains(q) || it.pkg.lowercase(Locale.ROOT).contains(q)
        }
        adapter.submit(filtered)
        appsHeader.text = if (q.isEmpty()) {
            "// APPLICATIONS [${allApps.size}]"
        } else {
            "// GREP \"$q\" -> ${filtered.size} MATCH"
        }
    }

    private fun launch(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.pkg, app.cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        log("exec ${app.pkg}")
        startSafe(intent)
        resetInput()
    }

    private fun showAppMenu(anchor: View, app: AppEntry) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "[ INFO ]")
        menu.menu.add(0, 2, 1, "[ UNINSTALL ]")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                2 -> startSafe(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
            }
            true
        }
        menu.show()
    }

    // ---------- terminal ----------

    private fun execute(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        when (cmd.lowercase(Locale.ROOT)) {
            "help" -> log("cmds: settings wifi bt display battery home whoami date clear")
            "settings" -> startSafe(Intent(Settings.ACTION_SETTINGS))
            "wifi" -> startSafe(Intent(Settings.ACTION_WIFI_SETTINGS))
            "bt", "bluetooth" -> startSafe(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "display" -> startSafe(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            "battery" -> startSafe(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            "home", "launcher" -> startSafe(Intent(Settings.ACTION_HOME_SETTINGS))
            "whoami" -> log("root :: the IT guy")
            "date" -> log(SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).format(Date()))
            "clear", "cls" -> {
                typing?.let { handler.removeCallbacks(it) }
                logLines.clear()
                logView.text = ""
            }
            else -> {
                val first = filtered.firstOrNull()
                if (first != null) {
                    launch(first)
                    return
                }
                log("command not found: $cmd")
            }
        }
        resetInput()
    }

    private fun resetInput() {
        input.setText("")
        input.clearFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun log(line: String) {
        logLines.addLast("> $line")
        while (logLines.size > 4) logLines.removeFirst()
        typing?.let { handler.removeCallbacks(it) }

        val prefix = logLines.toList().dropLast(1).joinToString("\n")
        val target = logLines.last()
        var i = 0
        val r = object : Runnable {
            override fun run() {
                i = (i + 2).coerceAtMost(target.length)
                val head = if (prefix.isEmpty()) "" else "$prefix\n"
                logView.text = head + target.substring(0, i) + if (i < target.length) "█" else ""
                if (i < target.length) handler.postDelayed(this, 16)
            }
        }
        typing = r
        handler.post(r)
    }

    private fun bootSequence() {
        val lines = listOf(
            "ALFA//OS kernel online [${Build.VERSION.RELEASE}]",
            "mounting /data ... ok",
            "indexed ${allApps.size} packages",
            "uplink: ${netType()} :: type 'help'",
        )
        lines.forEachIndexed { i, l -> handler.postDelayed({ log(l) }, 250L + i * 550L) }
    }

    // ---------- HUD ----------

    private fun updateClock() {
        val now = Date()
        clock.text = timeFmt.format(now)
        seconds.text = secFmt.format(now)
        date.text = dateFmt.format(now).uppercase(Locale.US)
        status.alpha = if (tickCount % 2 == 0) 1f else 0.35f
    }

    private fun glitch() {
        clock.translationX = 7f
        clock.setTextColor(magenta)
        handler.postDelayed({
            clock.translationX = -4f
            clock.setTextColor(green)
        }, 60)
        handler.postDelayed({
            clock.translationX = 0f
            clock.setTextColor(cyan)
        }, 130)
    }

    private fun updateStats() {
        val bm = getSystemService(BatteryManager::class.java)
        val bat = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        val charging = bm?.isCharging == true

        val am = getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mem)
        val ramUsed = mem.totalMem - mem.availMem
        val ramPct = if (mem.totalMem > 0) (ramUsed * 100 / mem.totalMem).toInt() else 0

        val fs = StatFs(Environment.getDataDirectory().path)
        val stoTotal = fs.totalBytes
        val stoUsed = stoTotal - fs.availableBytes
        val stoPct = if (stoTotal > 0) (stoUsed * 100 / stoTotal).toInt() else 0

        val up = SystemClock.elapsedRealtime() / 1000
        val uptime = String.format(Locale.US, "%02dd %02d:%02d", up / 86400, (up / 3600) % 24, (up / 60) % 60)

        val net = netType()
        val online = net != "OFFLINE"
        status.text = if (online) "● ONLINE" else "● OFFLINE"
        status.setTextColor(if (online) green else red)

        stats.text = buildString {
            append("DEV  ${Build.MANUFACTURER.uppercase(Locale.US)} ${Build.MODEL} :: A${Build.VERSION.RELEASE}\n")
            append("BAT  ${bar(bat)} %3d%%${if (charging) " ⚡" else ""}\n".format(bat))
            append("RAM  ${bar(ramPct)} ${gb(ramUsed)}/${gb(mem.totalMem)}G\n")
            append("STO  ${bar(stoPct)} ${gb(stoUsed)}/${gb(stoTotal)}G\n")
            append("NET  %-8s UPT  %s".format(net, uptime))
        }
    }

    private fun bar(pct: Int, n: Int = 12): String {
        val f = (pct * n / 100).coerceIn(0, n)
        return "█".repeat(f) + "░".repeat(n - f)
    }

    private fun gb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1_073_741_824.0)

    private fun netType(): String {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return "N/A"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "OFFLINE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELL"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETH"
            else -> "LINK"
        }
    }

    private fun startSafe(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            log("ERR ${e.javaClass.simpleName}")
        }
    }
}
