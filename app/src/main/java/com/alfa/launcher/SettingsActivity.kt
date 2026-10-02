package com.alfa.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * ALFA OS Settings: every launcher option in one organised place.
 * Changes are saved to prefs immediately; the home screen re-applies them on resume.
 */
class SettingsActivity : Activity() {

    companion object {
        private const val REQ_LOGO = 51
    }

    private val prefs by lazy { getSharedPreferences("alfa", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var accent = 0
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView

    private val white = Color.WHITE
    private val dim = Color.argb(150, 255, 255, 255)
    private val divider = Color.argb(20, 255, 255, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.addView(HudBackground(this, null).also { it.accent = MainActivity.accentOf(this) }, FrameLayout.LayoutParams(-1, -1))
        scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(36))
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        build()
    }

    override fun onResume() {
        super.onResume()
        build() // reflect changes made elsewhere (e.g. default launcher)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGO && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            io.execute {
                val ok = try { Profile.importLogo(this, uri); true } catch (e: Exception) { false }
                main.post {
                    if (!isDestroyed) {
                        if (!ok) toast("Couldn't load that image")
                        build()
                    }
                }
            }
        }
    }

    // ================= layout =================

    private fun build() {
        accent = MainActivity.accentOf(this)
        val y = scroll.scrollY
        content.removeAllViews()

        header()
        profileSection()
        appearanceSection()
        motionSection()
        homeSection()
        updatesSection()
        systemSection()
        aboutSection()

        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun header() {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(tv("‹", 28f, white).apply {
            setPadding(0, 0, dp(14), dp(4))
            setOnClickListener { finish() }
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("ALFA OS", 30f, white, font = "sans-serif-light"))
        col.addView(tv("SYSTEM SETTINGS  ·  v${Updater.currentName(this)}", 10f, dim, mono = true).apply { letterSpacing = 0.18f })
        row.addView(col)
        content.addView(row)
        content.addView(View(this).apply { setBackgroundColor(accent) },
            LinearLayout.LayoutParams(dp(40), dp(2)).apply { topMargin = dp(14); bottomMargin = dp(4) })
    }

    // ---------------- sections ----------------

    private fun profileSection() {
        val card = section("PROFILE", "◉")
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = getDrawable(R.drawable.ripple_item)
            setOnClickListener { editName() }
        }
        val avatar = ImageView(this).apply {
            setImageBitmap(Profile.avatar(this@SettingsActivity, accent, dp(96)))
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
        }
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(12, 12, 16))
                setStroke(dp(2), accent)
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(avatar, FrameLayout.LayoutParams(-1, -1))
        }
        row.addView(frame, LinearLayout.LayoutParams(dp(60), dp(60)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name = Profile.name(this)
        col.addView(tv(if (name.isEmpty()) "Set your name" else name, 18f, if (name.isEmpty()) dim else white, font = "sans-serif-medium"))
        col.addView(tv("Shown next to the clock  ·  tap to edit", 12f, dim), lp(3))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        row.addView(chevron())
        card.addView(row)
        line(card)
        actionRow(card, "Logo", if (Profile.hasLogo(this)) "Custom image" else "Initials", "Choose") {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Profile.pickIntent(), REQ_LOGO)
            } catch (e: Exception) {
                toast("No image picker available")
            }
        }
        if (Profile.hasLogo(this)) {
            line(card)
            actionRow(card, "Remove logo", "Go back to initials", null) {
                Profile.removeLogo(this); build()
            }
        }
    }

    private fun appearanceSection() {
        val card = section("APPEARANCE", "◐")

        // accent swatches
        val sw = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        sw.addView(tv("Accent colour", 15f, white))
        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        MainActivity.ACCENTS.forEach { (label, hex) ->
            val c = Color.parseColor(hex)
            val selected = c == accent
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    if (selected) setStroke(dp(3), white)
                }
                contentDescription = label
                setOnClickListener {
                    tick(it)
                    prefs.edit().putInt("accent", c).apply()
                    build()
                }
            }
            val size = if (selected) dp(34) else dp(28)
            dots.addView(dot, LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(12) })
        }
        sw.addView(dots, lp(12))
        val label = MainActivity.ACCENTS.firstOrNull { Color.parseColor(it.second) == accent }?.first ?: "Custom"
        sw.addView(tv(label, 12f, dim), lp(8))
        card.addView(sw)
        line(card)

        // icon style
        val current = prefs.getString("icon_style", IconStyler.NEON) ?: IconStyler.NEON
        choiceRow(card, "Icon style", IconStyler.STYLES.map { it.second.substringBefore(' ') to it.first }, current) {
            prefs.edit().putString("icon_style", it).apply(); build()
        }
        line(card)

        // background
        val wall = prefs.getBoolean("wallpaper", false)
        choiceRow(card, "Background", listOf("ALFA carbon" to "carbon", "My wallpaper" to "wall"), if (wall) "wall" else "carbon") {
            prefs.edit().putBoolean("wallpaper", it == "wall").apply(); build()
        }
        line(card)
        actionRow(card, "Change wallpaper", "Pick a system wallpaper", null) {
            startSafe(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
        }
    }

    private fun motionSection() {
        val card = section("ORBIT & MOTION", "◎")
        toggleRow(card, "Orbit spin", "Drag around the ring to spin the apps", "spin", true)
        line(card)
        toggleRow(card, "3D tilt in All Apps", "The orbit tilts in 3D while you drag", "galaxy_3d", true)
        line(card)
        toggleRow(card, "Idle drift", "Home orbit slowly rotates on its own (uses more battery)", "drift", false)
        line(card)
        toggleRow(card, "Vibration", "Haptic ticks while spinning, page changes and long-press", "haptics", true)
    }

    private fun homeSection() {
        val card = section("HOME SCREEN", "⌂")
        toggleRow(card, "IT Tools button", "One button above the search bar that holds every tool", "show_tools", true)
        line(card)
        toggleRow(card, "Network line", "Connection type and local IP under the clock", "show_net", true)
        line(card)
        toggleRow(card, "All apps in clone profile",
            "Off: only apps you cloned (WhatsApp, Messenger…). On: also Files, Play Store and other system apps in the dual-app profile",
            "clone_show_all", false)
        line(card)
        actionRow(card, "Reset orbit & dock", "Put the default apps back", null) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Reset home layout?")
                .setMessage("Your orbit and dock apps go back to the defaults.")
                .setPositiveButton("Reset") { _, _ ->
                    prefs.edit().remove("orbit").remove("dock").apply()
                    toast("Home layout reset")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updatesSection() {
        val card = section("UPDATES", "↻")
        val known = Updater.knownUpdate(this)
        actionRow(card, "Check for updates",
            if (known != null) "▲ $known available" else "Installed v${Updater.currentName(this)}",
            if (known != null) "Update" else null) {
            startActivity(Intent(this, ToolsActivity::class.java)
                .putExtra(ToolsActivity.EXTRA_TOOL, ToolsActivity.TOOL_UPDATE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        line(card)
        toggleRow(card, "Auto-check", "Look for new versions every 12 hours", "auto_update", true)
    }

    private fun systemSection() {
        val card = section("SYSTEM", "⚙")
        val isDefault = isDefaultLauncher()
        actionRow(card, "Default home app", if (isDefault) "ALFA is your home screen ✓" else "ALFA is not the default yet",
            if (isDefault) null else "Set") { requestDefault() }
        line(card)
        actionRow(card, "IT Tools", "Network, ping, ports, subnet, device info…", null) {
            startActivity(Intent(this, ToolsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        line(card)
        actionRow(card, "Android settings", "Open the phone's system settings", null) {
            startSafe(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun aboutSection() {
        val card = section("ABOUT", "ℹ")
        actionRow(card, "ALFA OS", "Version ${Updater.currentName(this)}  ·  build ${Updater.currentCode(this)}", null, showChevron = false) {}
        line(card)
        actionRow(card, "Source & releases", "github.com/${Updater.REPO}", null) {
            startSafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.REPO}")))
        }
    }

    // ================= building blocks =================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: CharSequence, size: Float, color: Int, mono: Boolean = false, font: String = "sans-serif") =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = if (mono) Typeface.MONOSPACE else Typeface.create(font, Typeface.NORMAL)
        }

    private fun lp(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun tick(v: View) {
        if (prefs.getBoolean("haptics", true)) v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** Section title + an empty glass card to fill with rows. */
    private fun section(title: String, glyph: String): LinearLayout {
        val t = tv("$glyph  $title", 11f, accent, mono = true).apply {
            letterSpacing = 0.2f
            setPadding(dp(6), 0, 0, 0)
        }
        content.addView(t, lp(24))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.glass_card)
        }
        content.addView(card, lp(8))
        return card
    }

    private fun line(card: LinearLayout) {
        card.addView(View(this).apply { setBackgroundColor(divider) },
            LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(16); marginEnd = dp(16) })
    }

    private fun chevron() = tv("›", 22f, dim).apply { setPadding(dp(10), 0, 0, dp(2)) }

    private fun actionRow(
        card: LinearLayout, title: String, sub: String?, badge: String?,
        showChevron: Boolean = true, onClick: () -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = getDrawable(R.drawable.ripple_item)
            setOnClickListener { tick(it); onClick() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 15f, white))
        if (sub != null) col.addView(tv(sub, 12f, dim), lp(3))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (badge != null) {
            row.addView(tv(badge, 12f, Color.BLACK, font = "sans-serif-medium").apply {
                setBackgroundResource(R.drawable.pill_accent)
                backgroundTintList = ColorStateList.valueOf(accent)
                setPadding(dp(12), dp(5), dp(12), dp(5))
            })
        }
        if (showChevron) row.addView(chevron())
        card.addView(row)
    }

    @Suppress("DEPRECATION")
    private fun toggleRow(card: LinearLayout, title: String, sub: String, key: String, def: Boolean) {
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(key, def)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(accent, Color.rgb(170, 170, 178)))
            trackTintList = ColorStateList(states, intArrayOf(
                Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)),
                Color.argb(60, 255, 255, 255)))
            setOnCheckedChangeListener { v, checked ->
                tick(v)
                prefs.edit().putBoolean(key, checked).apply()
            }
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = getDrawable(R.drawable.ripple_item)
            setOnClickListener { sw.toggle() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 15f, white))
        col.addView(tv(sub, 12f, dim), lp(3))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        row.addView(sw)
        card.addView(row)
    }

    /** Title + segmented pills. [options] = label to value. */
    private fun choiceRow(card: LinearLayout, title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        box.addView(tv(title, 15f, white))
        val seg = LinearLayout(this)
        options.forEachIndexed { i, (label, value) ->
            val on = value == selected
            seg.addView(tv(label, 13f, if (on) Color.BLACK else white, font = "sans-serif-medium").apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(9), dp(8), dp(9))
                setBackgroundResource(R.drawable.pill_accent)
                backgroundTintList = ColorStateList.valueOf(if (on) accent else Color.argb(30, 255, 255, 255))
                setOnClickListener { if (!on) { tick(it); onPick(value) } }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        box.addView(seg, lp(12))
        card.addView(box)
    }

    // ================= helpers =================

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun startSafe(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { toast("Not available on this device") }
    }

    private fun editName() {
        val field = EditText(this).apply {
            setText(Profile.name(this@SettingsActivity))
            hint = "Your name"
            setSingleLine()
            setTextColor(white)
            setHintTextColor(dim)
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(field)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Your name")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> Profile.setName(this, field.text.toString()); build() }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
    private fun requestDefault() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 52)
                    return
                } catch (e: Exception) { /* fall through */ }
            }
        }
        startSafe(Intent(Settings.ACTION_HOME_SETTINGS))
    }
}
