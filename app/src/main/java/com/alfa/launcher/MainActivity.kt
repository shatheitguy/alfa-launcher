package com.alfa.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val REQ_HOME = 42
        private const val MAX_DOCK = 5
        val ACCENTS = listOf(
            "Crimson" to "#FF2D3D",
            "Ice blue" to "#4DA3FF",
            "Mint" to "#3DDC97",
            "Amber" to "#FFB547",
            "Violet" to "#9D8CFF",
            "Mono" to "#E8E8E8",
        )
        private val DEFAULT_ORBIT = listOf(
            "com.android.settings",
            "com.google.android.apps.photos", "com.sec.android.gallery3d",
            "com.google.android.gm",
            "com.google.android.apps.maps",
            "com.google.android.calendar", "com.samsung.android.calendar",
            "com.google.android.deskclock", "com.sec.android.app.clockpackage",
            "com.android.vending",
            "com.google.android.youtube",
            "com.whatsapp",
            "com.google.android.apps.docs",
            "com.google.android.calculator", "com.sec.android.app.popupcalculator",
        )

        fun accentOf(ctx: Context): Int =
            ctx.getSharedPreferences("alfa", MODE_PRIVATE).getInt("accent", Color.parseColor(ACCENTS[0].second))
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("alfa", MODE_PRIVATE) }

    private lateinit var swipe: SwipeLayout
    private lateinit var home: View
    private lateinit var homeContent: View
    private lateinit var hud: HudBackground
    private lateinit var drawer: View
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var sysInfo: TextView
    private lateinit var techLine: TextView
    private lateinit var netDot: View
    private lateinit var banner: View
    private lateinit var updateChip: TextView
    private lateinit var orbit: OrbitView
    private lateinit var toolsRow: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var drawerSearch: EditText
    private lateinit var drawerCount: TextView
    private lateinit var drawerGrid: RecyclerView
    private lateinit var menuAnchor: View
    private lateinit var drawerAdapter: AppAdapter

    private var allApps: List<AppEntry> = emptyList()
    private var byKey: Map<String, AppEntry> = emptyMap()
    private var drawerFiltered: List<AppEntry> = emptyList()
    private var accent = 0
    private var tickCount = 0

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = loadApps()
    }

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            if (tickCount % 5 == 0) updateStats()
            tickCount++
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupWindow()

        swipe = findViewById(R.id.swipe)
        home = findViewById(R.id.home)
        homeContent = findViewById(R.id.homeContent)
        hud = findViewById(R.id.hud)
        drawer = findViewById(R.id.drawer)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        sysInfo = findViewById(R.id.sysInfo)
        techLine = findViewById(R.id.techLine)
        netDot = findViewById(R.id.netDot)
        banner = findViewById(R.id.banner)
        updateChip = findViewById(R.id.updateChip)
        updateChip.setOnClickListener { openTool(ToolsActivity.TOOL_UPDATE) }
        orbit = findViewById(R.id.orbit)
        toolsRow = findViewById(R.id.toolsRow)
        dock = findViewById(R.id.dock)
        drawerSearch = findViewById(R.id.drawerSearch)
        drawerCount = findViewById(R.id.drawerCount)
        drawerGrid = findViewById(R.id.drawerGrid)
        menuAnchor = findViewById(R.id.menuAnchor)

        accent = accentOf(this)

        swipe.home = home
        swipe.drawer = drawer
        swipe.canDrawerScrollUp = { drawerGrid.canScrollVertically(-1) }
        swipe.listener = object : SwipeLayout.Listener {
            override fun onDrawerOpened() {}
            override fun onDrawerClosed() {
                drawerSearch.setText("")
                hideKeyboard()
                drawerGrid.scrollToPosition(0)
            }
            override fun onPullDown() = expandNotifications()
            override fun onLongPress(x: Float, y: Float) = showHomeMenu(x, y)
        }
        swipe.setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets); insets }

        drawerAdapter = AppAdapter(::launch, ::showAppMenu)
        drawerGrid.layoutManager = GridLayoutManager(this, (resources.configuration.screenWidthDp / 84).coerceIn(4, 6))
        drawerGrid.adapter = drawerAdapter

        drawerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filterDrawer()
        })
        drawerSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_NULL) {
                drawerFiltered.firstOrNull()?.let { launch(it) }
                true
            } else false
        }

        findViewById<View>(R.id.searchPill).setOnClickListener { openSearch() }
        orbit.hub.setOnClickListener { swipe.open() }
        orbit.hub.setOnLongClickListener { startSafe(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)); true }
        clock.setOnClickListener { startSafe(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        date.setOnClickListener {
            startSafe(Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()))
        }
        sysInfo.setOnClickListener { openTool(ToolsActivity.TOOL_DEVICE) }
        techLine.setOnClickListener { openTool(ToolsActivity.TOOL_NETWORK) }
        findViewById<View>(R.id.bannerSet).setOnClickListener { requestDefaultLauncher() }
        findViewById<View>(R.id.bannerLater).setOnClickListener {
            prefs.edit().putBoolean("banner_dismissed", true).apply()
            banner.visibility = View.GONE
        }

        buildToolsRow()

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

        applyAccent()
        applyBackground()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        tickCount = 0
        handler.removeCallbacks(tick)
        handler.post(tick)
        val newAccent = accentOf(this)
        if (newAccent != accent) { accent = newAccent; applyAccent() }
        banner.visibility =
            if (!isDefaultLauncher() && !prefs.getBoolean("banner_dismissed", false)) View.VISIBLE else View.GONE
        showUpdateChip()
        if (Updater.shouldAutoCheck(this)) {
            // stamp first so an offline phone doesn't retry on every Home press
            prefs.edit().putLong("update_checked_at", System.currentTimeMillis()).apply()
            io.execute {
                try {
                    val rel = Updater.fetchLatest()
                    Updater.remember(this, rel)
                    handler.post { showUpdateChip() }
                } catch (e: Exception) {
                    // offline or rate-limited; try again next interval
                }
            }
        }
    }

    private fun showUpdateChip() {
        val tag = Updater.knownUpdate(this)
        if (tag == null) {
            updateChip.visibility = View.GONE
        } else {
            updateChip.text = "▲ UPDATE AVAILABLE  ·  $tag  ›"
            updateChip.setTextColor(accent)
            updateChip.visibility = View.VISIBLE
        }
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
        if (swipe.isOpen) swipe.close() else hideKeyboard()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (swipe.isOpen) swipe.close()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HOME && !isDefaultLauncher()) {
            startSafe(Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }

    // ---------------- window ----------------

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(insets: WindowInsets) {
        val l = insets.systemWindowInsetLeft
        val t = insets.systemWindowInsetTop
        val r = insets.systemWindowInsetRight
        val b = insets.systemWindowInsetBottom
        homeContent.setPadding(dp(20) + l, dp(18) + t, dp(20) + r, dp(12) + b)
        drawer.setPadding(dp(14) + l, dp(6) + t, dp(14) + r, b)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------- apps ----------------

    private fun loadApps() {
        io.execute {
            val pm = packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val list = pm.queryIntentActivities(query, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name, it.loadIcon(pm))
                }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
            handler.post {
                allApps = list
                byKey = list.associateBy { it.key }
                if (!prefs.contains("orbit")) initDefaults()
                refreshHome()
                filterDrawer()
            }
        }
    }

    private fun initDefaults() {
        val dockPkgs = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        ).mapNotNull { packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
        val dockKeys = dockPkgs.mapNotNull { p -> allApps.firstOrNull { it.pkg == p } }.map { it.key }.distinct()
        var orbitKeys = DEFAULT_ORBIT.mapNotNull { p -> allApps.firstOrNull { it.pkg == p } }
            .map { it.key }.distinct().filterNot { it in dockKeys }.take(OrbitView.MAX)
        if (orbitKeys.size < OrbitView.MAX) {
            orbitKeys = (orbitKeys + allApps.map { it.key }.filterNot { it in dockKeys || it in orbitKeys })
                .take(OrbitView.MAX)
        }
        setList("dock", dockKeys)
        setList("orbit", orbitKeys)
    }

    private fun getList(key: String): MutableList<String> =
        prefs.getString(key, null)?.split("|")?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()

    private fun setList(key: String, list: List<String>) =
        prefs.edit().putString(key, list.joinToString("|")).apply()

    private fun refreshHome() {
        orbit.setApps(getList("orbit").mapNotNull { byKey[it] }, ::launch, ::showAppMenu)

        dock.removeAllViews()
        val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true) }
        getList("dock").mapNotNull { byKey[it] }.forEach { app ->
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(8), dp(11), dp(8), dp(11))
                setBackgroundResource(ripple.resourceId)
                contentDescription = app.label
                setOnClickListener { launch(app) }
                setOnLongClickListener { showAppMenu(it, app); true }
            }
            dock.addView(iv)
        }
        dock.visibility = if (dock.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun filterDrawer() {
        val q = drawerSearch.text.toString().trim().lowercase(Locale.ROOT)
        drawerFiltered = if (q.isEmpty()) allApps else allApps.filter {
            it.label.lowercase(Locale.ROOT).contains(q) || it.pkg.lowercase(Locale.ROOT).contains(q)
        }
        drawerAdapter.submit(drawerFiltered)
        drawerCount.text = if (q.isEmpty()) "ALL APPS · ${allApps.size}" else "RESULTS · ${drawerFiltered.size}"
    }

    private fun launch(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.pkg, app.cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startSafe(intent)
        if (swipe.isOpen) handler.postDelayed({ swipe.close(false) }, 350)
    }

    private fun showAppMenu(anchor: View, app: AppEntry) {
        val orbitL = getList("orbit")
        val dockL = getList("dock")
        val inOrbit = app.key in orbitL
        val inDock = app.key in dockL
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, if (inOrbit) "Remove from orbit" else "Add to orbit")
        menu.menu.add(0, 2, 1, if (inDock) "Remove from dock" else "Add to dock")
        menu.menu.add(0, 3, 2, "App info")
        menu.menu.add(0, 4, 3, "Uninstall")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    if (inOrbit) orbitL.remove(app.key)
                    else if (orbitL.size >= OrbitView.MAX) toast("Orbit is full (${OrbitView.MAX} apps)")
                    else { orbitL.add(app.key); dockL.remove(app.key) }
                    setList("orbit", orbitL); setList("dock", dockL); refreshHome()
                }
                2 -> {
                    if (inDock) dockL.remove(app.key)
                    else if (dockL.size >= MAX_DOCK) toast("Dock is full ($MAX_DOCK apps)")
                    else { dockL.add(app.key); orbitL.remove(app.key) }
                    setList("orbit", orbitL); setList("dock", dockL); refreshHome()
                }
                3 -> startSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                4 -> startSafe(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
            }
            true
        }
        menu.show()
    }

    // ---------------- home menu / look ----------------

    private fun showHomeMenu(x: Float, y: Float) {
        menuAnchor.translationX = x
        menuAnchor.translationY = y
        val menu = PopupMenu(this, menuAnchor)
        val useWall = prefs.getBoolean("wallpaper", false)
        menu.menu.add(0, 1, 0, if (useWall) "Use ALFA carbon background" else "Use my wallpaper")
        menu.menu.add(0, 2, 1, "Change wallpaper")
        val sub = menu.menu.addSubMenu(0, 3, 2, "Accent colour")
        ACCENTS.forEachIndexed { i, (name, _) -> sub.add(0, 100 + i, i, name) }
        menu.menu.add(0, 4, 3, "IT tools")
        menu.menu.add(0, 7, 4, "Check for updates")
        menu.menu.add(0, 5, 5, "Default home app")
        menu.menu.add(0, 6, 6, "Android settings")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { prefs.edit().putBoolean("wallpaper", !useWall).apply(); applyBackground() }
                2 -> startSafe(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
                3 -> return@setOnMenuItemClickListener false
                4 -> openTool(null)
                5 -> startSafe(Intent(Settings.ACTION_HOME_SETTINGS))
                6 -> startSafe(Intent(Settings.ACTION_SETTINGS))
                7 -> openTool(ToolsActivity.TOOL_UPDATE)
                in 100 until 100 + ACCENTS.size -> {
                    accent = Color.parseColor(ACCENTS[item.itemId - 100].second)
                    prefs.edit().putInt("accent", accent).apply()
                    applyAccent()
                }
            }
            true
        }
        menu.show()
    }

    private fun applyBackground() {
        hud.visibility = if (prefs.getBoolean("wallpaper", false)) View.GONE else View.VISIBLE
    }

    private fun applyAccent() {
        hud.accent = accent
        orbit.accent = accent
        netDot.backgroundTintList = ColorStateList.valueOf(accent)
        findViewById<View>(R.id.bannerSet).backgroundTintList = ColorStateList.valueOf(accent)
        drawerSearch.highlightColor = (accent and 0x00FFFFFF) or 0x66000000
        for (i in 0 until toolsRow.childCount) {
            (toolsRow.getChildAt(i) as? TextView)?.setTextColor(accent)
        }
    }

    private fun buildToolsRow() {
        val items = listOf(
            "PING" to ToolsActivity.TOOL_PING,
            "NET" to ToolsActivity.TOOL_NETWORK,
            "PORTS" to ToolsActivity.TOOL_PORTS,
            "TOOLS ›" to null,
        )
        items.forEachIndexed { i, (label, tool) ->
            val tv = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.15f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setBackgroundResource(R.drawable.glass_pill)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = dp(8)
                }
                setOnClickListener { openTool(tool) }
            }
            toolsRow.addView(tv)
        }
    }

    private fun openTool(tool: String?) {
        val i = Intent(this, ToolsActivity::class.java)
        if (tool != null) i.putExtra(ToolsActivity.EXTRA_TOOL, tool)
        startSafe(i)
    }

    private fun openSearch() {
        swipe.open()
        drawerSearch.requestFocus()
        handler.postDelayed({
            getSystemService(InputMethodManager::class.java)?.showSoftInput(drawerSearch, InputMethodManager.SHOW_IMPLICIT)
        }, 180)
    }

    private fun hideKeyboard() {
        drawerSearch.clearFocus()
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(drawerSearch.windowToken, 0)
    }

    @SuppressLint("WrongConstant")
    private fun expandNotifications() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        } catch (e: Exception) {
            // not supported on this ROM
        }
    }

    // ---------------- default launcher ----------------

    private fun isDefaultLauncher(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val ri = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )
        return ri?.activityInfo?.packageName == packageName
    }

    @Suppress("DEPRECATION")
    private fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME)
                    return
                } catch (e: Exception) {
                    // fall through to settings
                }
            }
        }
        startSafe(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    // ---------------- HUD data ----------------

    private fun updateClock() {
        val now = Date()
        val is24 = android.text.format.DateFormat.is24HourFormat(this)
        clock.text = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
        date.text = SimpleDateFormat("EEE · dd MMM yyyy", Locale.getDefault()).format(now).uppercase(Locale.getDefault())
    }

    private fun updateStats() {
        val bm = getSystemService(BatteryManager::class.java)
        val bat = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        val charging = bm?.isCharging == true
        val batIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (batIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

        val am = getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mem)
        val ramFrac = if (mem.totalMem > 0) (mem.totalMem - mem.availMem).toFloat() / mem.totalMem else 0f

        val fs = StatFs(Environment.getDataDirectory().path)
        val stoFrac = if (fs.totalBytes > 0) (fs.totalBytes - fs.availableBytes).toFloat() / fs.totalBytes else 0f

        orbit.battery = bat / 100f
        orbit.ram = ramFrac
        orbit.storage = stoFrac
        orbit.hubValue.text = "$bat%"
        orbit.hubLabel.text = if (charging) "CHARGING" else "BATTERY"
        orbit.hubSub.text = String.format(Locale.US, "%.1f°C", temp)

        val up = SystemClock.elapsedRealtime() / 1000
        sysInfo.text = buildString {
            append(Build.MODEL.uppercase(Locale.US)).append('\n')
            append("ANDROID ").append(Build.VERSION.RELEASE).append(" · API ").append(Build.VERSION.SDK_INT).append('\n')
            append(String.format(Locale.US, "UP %dd %02dh %02dm", up / 86400, (up / 3600) % 24, (up / 60) % 60))
        }

        val net = netType()
        val ip = localIp()
        val online = net != "OFFLINE"
        netDot.backgroundTintList = ColorStateList.valueOf(if (online) accent else Color.GRAY)
        techLine.text = listOfNotNull(net, ip, "TAP FOR NETWORK INFO").joinToString("  ·  ")
    }

    private fun netType(): String {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return "N/A"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "OFFLINE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "LINK"
        }
    }

    private fun localIp(): String? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun startSafe(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Not available on this device")
        }
    }
}
