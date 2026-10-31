package com.alfa.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import java.util.Locale
import java.util.concurrent.Executors

/**
 * "NoCap Cloud" on the home screen: the linked server's name, an online dot, the user, and the
 * server's apps as tiles. Shows the cached state instantly, then refreshes in the background.
 */
class CloudPanel(private val act: Activity, private val root: LinearLayout) {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs = act.getSharedPreferences("alfa", Context.MODE_PRIVATE)
    private var generation = 0
    private var online: Boolean? = null
    private var lastFetch = 0L
    private var lastKey = ""

    var accent: Int = Color.RED
        set(v) { field = v; render() }

    fun destroy() { io.shutdownNow() }

    /** Called from onResume: draw what we know, then ask the server (at most every 20 s). */
    fun refresh(force: Boolean = false) {
        if (!prefs.getBoolean("show_cloud", true)) { root.visibility = View.GONE; return }
        root.visibility = View.VISIBLE
        render()
        val link = NoCapLink.get(act) ?: return
        val now = System.currentTimeMillis()
        val key = link.server + "#" + link.deviceId
        if (!force && key == lastKey && now - lastFetch < 20_000) return
        if (key != lastKey) online = null
        lastKey = key
        lastFetch = now
        val gen = ++generation
        io.execute {
            try {
                val me = NoCapApi.me(link)
                NoCapLink.updateInfo(act, me.serverName, me.nocapId, me.username, me.version)
                val apps = NoCapApi.appsJson(link)
                NoCapLink.cacheApps(act, apps)
                main.post { if (gen == generation) { online = true; render() } }
            } catch (e: NoCapApi.Unlinked) {
                NoCapLink.clear(act)
                main.post {
                    if (gen != generation) return@post
                    online = null
                    Toast.makeText(act, "Your server unlinked this phone. Relink anytime.", Toast.LENGTH_LONG).show()
                    render()
                }
            } catch (e: Exception) {
                main.post { if (gen == generation) { online = false; offlineReason = e.message; render() } }
            }
        }
    }

    private var offlineReason: String? = null

    private fun render() {
        if (root.visibility != View.VISIBLE) return
        root.removeAllViews()
        val link = NoCapLink.get(act)

        // header: dot · NOCAP CLOUD · server / user
        val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        val dotColor = when {
            link == null -> Color.argb(90, 255, 255, 255)
            online == true -> Color.rgb(61, 220, 151)
            online == false -> Color.GRAY
            else -> Color.argb(160, 255, 255, 255)
        }
        head.addView(View(act).apply {
            setBackgroundResource(R.drawable.dot)
            backgroundTintList = ColorStateList.valueOf(dotColor)
        }, LinearLayout.LayoutParams(dp(7), dp(7)))
        head.addView(tv("NOCAP CLOUD", 10f, accent, mono = true).apply { letterSpacing = 0.2f },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        val right = when {
            link == null -> "NOT LINKED"
            online == false -> "OFFLINE"
            else -> listOf(link.serverName.ifEmpty { "server" }, link.username.takeIf { it.isNotEmpty() }?.let { "@$it" })
                .filterNotNull().joinToString("  ·  ").uppercase(Locale.getDefault())
        }
        head.addView(tv(right, 10f, Color.argb(170, 255, 255, 255), mono = true).apply {
            gravity = Gravity.END; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        root.addView(head)

        if (link == null) {
            root.addView(tv("Link your NoCap server  ›", 15f, Color.WHITE).apply {
                setPadding(0, dp(10), 0, dp(2))
            })
            root.addView(tv("Your server's apps, one tap from home", 12f, Color.argb(150, 255, 255, 255)))
            root.setOnClickListener { openPair() }
            return
        }
        root.setOnClickListener(null)
        root.isClickable = false

        val apps = NoCapLink.cachedApps(act)?.let { try { NoCapApi.parseApps(it) } catch (e: Exception) { null } }.orEmpty()
        if (apps.isEmpty()) {
            root.addView(tv(
                if (online == false) (offlineReason ?: "Server's ghosting us — check Wi-Fi") else "Loading your apps…",
                12f, Color.argb(150, 255, 255, 255)).apply { setPadding(0, dp(10), 0, 0) })
            return
        }
        val strip = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        apps.forEach { strip.addView(tile(link, it)) }
        val scroller = HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(strip)
        }
        root.addView(scroller, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun tile(link: NoCapLink.Link, app: NoCapApi.AppTile): View {
        val size = dp(42)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.ripple_item)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            contentDescription = app.name
            setOnClickListener {
                if (prefs.getBoolean("haptics", true)) it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                open(NoCapApi.resolve(link.server, app.url))
            }
        }
        val frame = FrameLayout(act)
        val letter = tv(app.name.take(1).uppercase(Locale.getDefault()), 17f, Color.WHITE, font = "sans-serif-medium").apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(40, 255, 255, 255))
                setStroke(dp(1), (accent and 0x00FFFFFF) or 0x88000000.toInt())
            }
        }
        frame.addView(letter, FrameLayout.LayoutParams(size, size))
        val img = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.28f)
            }
            clipToOutline = true
            visibility = View.INVISIBLE
        }
        frame.addView(img, FrameLayout.LayoutParams(size, size))
        box.addView(frame, LinearLayout.LayoutParams(size, size))
        box.addView(tv(app.name, 10f, Color.argb(210, 255, 255, 255)).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(dp(62), -2).apply { topMargin = dp(4) })

        io.execute {
            val bmp = NoCapApi.icon(act, link, app.iconUrl, size)
            if (bmp != null) main.post {
                img.setImageBitmap(bmp)
                img.visibility = View.VISIBLE
                letter.visibility = View.INVISIBLE
            }
        }
        return box
    }

    private fun open(url: String) {
        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(Color.rgb(10, 10, 12)).build())
                .setShowTitle(true)
                .build()
                .launchUrl(act, uri)
        } catch (e: ActivityNotFoundException) {
            try {
                act.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                Toast.makeText(act, "No browser on this phone to open that", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openPair() {
        try { act.startActivity(Intent(act, PairActivity::class.java)) } catch (e: Exception) { /* ignore */ }
    }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    private fun tv(s: CharSequence, size: Float, color: Int, mono: Boolean = false, font: String = "sans-serif") =
        TextView(act).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = if (mono) Typeface.MONOSPACE else Typeface.create(font, Typeface.NORMAL)
        }
}
