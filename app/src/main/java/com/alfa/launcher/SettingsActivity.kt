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
        private const val REQ_PHOTOS = 53
    }

    private val prefs by lazy { getSharedPreferences("alfa", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var accent = 0
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var bgView: HudBackground
    // rendered style previews, cached per accent so rebuilding the screen stays instant
    private val thumbs = HashMap<String, android.graphics.Bitmap>()
    private var thumbsAccent = 0

    private val white = Color.WHITE
    private val dim = Color.argb(150, 255, 255, 255)
    private val divider = Color.argb(20, 255, 255, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        bgView = HudBackground(this, null).also { it.accent = MainActivity.accentOf(this); it.style = WallpaperSync.style(this) }
        root.addView(bgView, FrameLayout.LayoutParams(-1, -1))
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
        if (requestCode == REQ_PHOTOS && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<android.net.Uri>()
            data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            if (uris.isEmpty()) return
            toast("Adding ${uris.size} photo${if (uris.size > 1) "s" else ""}…")
            io.execute {
                var first: String? = null
                var failed = 0
                for (u in uris) {
                    try { val id = WallpaperSync.importPhoto(this, u); if (first == null) first = id } catch (e: Exception) { failed++ }
                }
                main.post {
                    if (isDestroyed) return@post
                    if (failed > 0) toast("$failed image(s) couldn't be read")
                    val pick = first
                    if (pick != null) {
                        // use the first new photo straight away
                        WallpaperSync.setStyle(this, pick)
                        bgView.style = pick
                        applyCarbonWallpaper(accent)
                    } else build()
                }
            }
            return
        }
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
        updatesSection()       // always first
        aboutSection()
        profileSection()
        appearanceSection()
        motionSection()
        homeSection()
        systemSection()

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
                    // carbon wallpaper follows the accent colour
                    if (!prefs.getBoolean("wallpaper", false)) applyCarbonWallpaper(c) else build()
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
        choiceRow(card, "Wallpaper", listOf("ALFA wallpaper" to "carbon", "My wallpaper" to "wall"), if (wall) "wall" else "carbon") {
            if (it == "carbon") {
                prefs.edit().putBoolean("wallpaper", false).apply()
                applyCarbonWallpaper(accent)
            } else {
                prefs.edit().putBoolean("wallpaper", true).apply()
                WallpaperSync.markCustom(this)
                build()
                startSafe(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose your wallpaper"))
            }
        }
        if (!wall) {
            line(card)
            styleRow(card)
            val applied = WallpaperSync.isApplied(this, accent)
            line(card)
            actionRow(card, if (applied) "Set as your system wallpaper ✓" else "Apply as system wallpaper",
                "One wallpaper for home, Recents and app switching, in your accent colour",
                if (applied) null else "Apply") { applyCarbonWallpaper(accent) }
            line(card)
            toggleRow(card, "Also on lock screen", "Use the ALFA wallpaper on the lock screen too", "wall_lock", false)
        } else {
            line(card)
            actionRow(card, "Change wallpaper", "Pick a system wallpaper — ALFA shows the same one", null) {
                startSafe(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
            }
        }
    }

    /** Horizontal strip of wallpaper-style previews; tap one to apply it. */
    private fun styleRow(card: LinearLayout) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), 0, dp(14))
        }
        box.addView(tv("Wallpaper style", 15f, white))
        val strip = LinearLayout(this).apply { setPadding(0, 0, dp(16), 0) }
        val hs = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        if (thumbsAccent != accent) { thumbs.clear(); thumbsAccent = accent }
        val current = WallpaperSync.style(this)
        val (sw, sh) = WallpaperSync.screenSize(this)
        val tw = dp(78)
        val th = (tw * sh / sw.toFloat()).toInt()
        val scale = tw / sw.toFloat()
        HudBackground.STYLES.forEach { (id, label) ->
            val on = id == current
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
                clipToOutline = true
                thumbs[id]?.let { setImageBitmap(it) }
            }
            val frame = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(15).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(if (on) 3 else 1), if (on) accent else Color.argb(40, 255, 255, 255))
                }
                setPadding(dp(3), dp(3), dp(3), dp(3))
                addView(img, FrameLayout.LayoutParams(tw, th))
                setOnClickListener {
                    if (on) return@setOnClickListener
                    tick(it)
                    WallpaperSync.setStyle(this@SettingsActivity, id)
                    bgView.style = id
                    applyCarbonWallpaper(accent)
                }
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(frame)
                addView(tv(label, 11f, if (on) accent else dim, mono = true).apply { gravity = Gravity.CENTER }, lp(6))
            }
            strip.addView(col, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
            // render missing previews off the main thread
            if (thumbs[id] == null) {
                val color = accent
                io.execute {
                    val bmp = try { WallpaperSync.render(this, color, id, scale) } catch (e: Exception) { null }
                    main.post {
                        if (bmp != null && !isDestroyed && color == accent) { thumbs[id] = bmp; img.setImageBitmap(bmp) }
                    }
                }
            }
        }
        box.addView(hs, lp(12))
        card.addView(box)

        // ---- your photos ----
        line(card)
        val pbox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), 0, dp(14))
        }
        pbox.addView(tv("Your photos", 15f, white))
        pbox.addView(tv("Add your own images. Tap to use, long-press to remove.", 12f, dim), lp(3))
        val pstrip = LinearLayout(this).apply { setPadding(0, 0, dp(16), 0) }
        val phs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pstrip) }
        // "+" tile
        val add = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(15).toFloat(); setColor(Color.argb(18, 255, 255, 255))
                setStroke(dp(1), Color.argb(60, 255, 255, 255))
            }
            addView(tv("+", 30f, accent, font = "sans-serif-light").apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(tw + dp(6), th + dp(6)))
            setOnClickListener { tick(it); pickPhotos() }
        }
        pstrip.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(add)
            addView(tv("Add", 11f, dim, mono = true).apply { gravity = Gravity.CENTER }, lp(6))
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        WallpaperSync.photos(this).forEach { name ->
            val id = HudBackground.PHOTO_PREFIX + name
            val on = id == current
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
                clipToOutline = true
            }
            val frame = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(15).toFloat(); setColor(Color.TRANSPARENT)
                    setStroke(dp(if (on) 3 else 1), if (on) accent else Color.argb(40, 255, 255, 255))
                }
                setPadding(dp(3), dp(3), dp(3), dp(3))
                addView(img, FrameLayout.LayoutParams(tw, th))
                setOnClickListener {
                    if (on) return@setOnClickListener
                    tick(it)
                    WallpaperSync.setStyle(this@SettingsActivity, id)
                    bgView.style = id
                    applyCarbonWallpaper(accent)
                }
                setOnLongClickListener {
                    android.app.AlertDialog.Builder(this@SettingsActivity, android.R.style.Theme_Material_Dialog_Alert)
                        .setMessage("Remove this photo from ALFA?")
                        .setPositiveButton("Remove") { _, _ ->
                            WallpaperSync.deletePhoto(this@SettingsActivity, name)
                            thumbs.remove(id)
                            if (on) { WallpaperSync.setStyle(this@SettingsActivity, HudBackground.DEFAULT); bgView.style = HudBackground.DEFAULT; applyCarbonWallpaper(accent) }
                            else build()
                        }
                        .setNegativeButton("Cancel", null).show()
                    true
                }
            }
            pstrip.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(frame)
                addView(tv(if (on) "In use" else "Photo", 11f, if (on) accent else dim, mono = true).apply { gravity = Gravity.CENTER }, lp(6))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
            val cached = thumbs[id]
            if (cached != null) img.setImageBitmap(cached) else {
                val color = accent
                io.execute {
                    val bmp = try { WallpaperSync.render(this, color, id, scale) } catch (e: Exception) { null }
                    main.post { if (bmp != null && !isDestroyed) { thumbs[id] = bmp; img.setImageBitmap(bmp) } }
                }
            }
        }
        pbox.addView(phs, lp(12))
        card.addView(pbox)
        if (current.startsWith(HudBackground.PHOTO_PREFIX)) {
            line(card)
            toggleRow(card, "Darken photo", "Keeps the clock and icons readable on bright photos", "photo_dim", true) {
                thumbs.remove(current); applyCarbonWallpaper(accent)
            }
            line(card)
            toggleRow(card, "Tint with accent", "Washes the photo in your accent colour", "photo_tint", false) {
                thumbs.remove(current); applyCarbonWallpaper(accent)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pickPhotos() {
        val i = if (Build.VERSION.SDK_INT >= 33) {
            Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                .putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { startActivityForResult(i, REQ_PHOTOS) } catch (e: Exception) { toast("No image picker available") }
    }

    /** Renders ALFA carbon in [color] and sets it as the real system wallpaper. */
    private fun applyCarbonWallpaper(color: Int) {
        toast("Setting wallpaper…")
        val lock = prefs.getBoolean("wall_lock", false)
        io.execute {
            val ok = try { WallpaperSync.applyCarbon(this, color, lock); true } catch (e: Exception) { false }
            main.post {
                if (isDestroyed) return@post
                toast(if (ok) "Wallpaper set" else "Couldn't set the wallpaper")
                build()
            }
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

    /** Updates: first section in Settings. */
    private fun updatesSection() {
        val card = section("UPDATES", "↻")
        val known = Updater.knownUpdate(this)
        actionRow(card, "Check for updates",
            if (known != null) "▲ $known available" else "Installed v${Updater.currentName(this)}",
            if (known != null) "Update" else null) { openUpdates() }
        line(card)
        toggleRow(card, "Auto-check", "Look for new versions every 12 hours", "auto_update", true)
    }

    /** About: ALFA OS (opens the ALFA Launcher website) and the source code. */
    private fun aboutSection() {
        val card = section("ABOUT", "ℹ")
        actionRow(card, "ALFA OS", "Version ${Updater.currentName(this)}  ·  build ${Updater.currentCode(this)}  ·  open website", null) {
            startSafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://shatheitguy.github.io/alfa-launcher/")))
        }
        line(card)
        actionRow(card, "Source & releases", "github.com/${Updater.REPO}", null) {
            startSafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.REPO}")))
        }
    }

    private fun openUpdates() {
        startActivity(Intent(this, ToolsActivity::class.java)
            .putExtra(ToolsActivity.EXTRA_TOOL, ToolsActivity.TOOL_UPDATE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    private fun toggleRow(card: LinearLayout, title: String, sub: String, key: String, def: Boolean, onChange: ((Boolean) -> Unit)? = null) {
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
                onChange?.invoke(checked)
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
