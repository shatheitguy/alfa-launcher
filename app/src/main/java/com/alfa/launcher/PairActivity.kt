package com.alfa.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator
import java.util.concurrent.Executors

/**
 * "Link your NoCap server": address + 6-digit code, a QR scanner, and nocap://pair deep links.
 * Flow: /pair/info (first contact, records the cert fingerprint) -> the user confirms the server
 * name, NoCap ID and fingerprint -> /pair/claim over a connection pinned to that fingerprint.
 */
class PairActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var accent = 0
    private val white = Color.WHITE
    private val dim = Color.argb(150, 255, 255, 255)

    private lateinit var serverField: EditText
    private lateinit var codeField: EditText
    private lateinit var status: TextView
    private lateinit var linkButton: TextView
    private var busy = false
    /** NoCap ID from a scanned QR / deep link; the server must report the same one. */
    private var expectedId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accent = MainActivity.accentOf(this)
        buildUi()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------------- deep link / QR ----------------

    private fun handle(i: Intent?) {
        val data = i?.data ?: return
        if (!applyPairUri(data)) say("That link's not a NoCap pairing link", error = true)
    }

    /** nocap://pair?server=<https://host:port>&code=482913&id=NC-XXXX-XXXX */
    private fun applyPairUri(u: Uri): Boolean {
        if (u.scheme?.lowercase() != "nocap" || u.host?.lowercase() != "pair") return false
        val server = u.getQueryParameter("server")?.let { NoCapApi.normalize(it) } ?: return false
        val code = u.getQueryParameter("code")?.filter { it.isDigit() }.orEmpty()
        expectedId = u.getQueryParameter("id")?.takeIf { it.isNotBlank() }
        serverField.setText(server)
        codeField.setText(code.take(6))
        if (code.length == 6) start() else codeField.requestFocus()
        return true
    }

    private fun scan() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Point it at the QR on your NoCap server")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (r == null) { super.onActivityResult(requestCode, resultCode, data); return }
        val text = r.contents ?: return // cancelled
        val ok = try { applyPairUri(Uri.parse(text.trim())) } catch (e: Exception) { false }
        if (!ok) say("That QR's not a NoCap pairing code", error = true)
    }

    // ---------------- linking ----------------

    private fun start() {
        if (busy) return
        val server = NoCapApi.normalize(serverField.text.toString())
        if (server == null) { say("Address needs to be https://… (like https://192.168.1.20:8443)", error = true); return }
        val code = codeField.text.toString().filter { it.isDigit() }
        if (code.length != 6) { say("Code is 6 digits — grab it from the server", error = true); codeField.requestFocus(); return }
        setBusy(true)
        say("Looking for your server…")
        io.execute {
            try {
                val info = NoCapApi.pairInfo(server)
                main.post { if (!isDestroyed) confirm(server, code, info) }
            } catch (e: Exception) {
                main.post { if (!isDestroyed) { setBusy(false); say(e.message ?: "Server's ghosting us — check Wi-Fi", error = true) } }
            }
        }
    }

    private fun confirm(server: String, code: String, info: NoCapApi.Info) {
        val want = expectedId
        if (want != null && info.nocapId.isNotEmpty() && !want.equals(info.nocapId, ignoreCase = true)) {
            setBusy(false)
            say("That server says it's ${info.nocapId}, but the QR said $want. Sus — not linking.", error = true)
            return
        }
        say("Found it. Double-check the details")
        val idPart = if (info.nocapId.isNotEmpty()) " (${info.nocapId})" else ""
        val msg = buildString {
            append(server).append('\n')
            if (info.version.isNotEmpty()) append("NoCapOS ").append(info.version).append('\n')
            append("\nCert fingerprint\n").append(NoCapApi.shortFingerprint(info.fingerprint))
            append("\n\nIf your server shows a fingerprint, it should match. ")
            append("After this, the phone only trusts this exact cert.")
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Link to ${info.name}$idPart?")
            .setMessage(msg)
            .setPositiveButton("Link it") { _, _ -> claim(server, code, info) }
            .setNegativeButton("Nah") { _, _ -> setBusy(false); say("No worries — nothing linked") }
            .setOnCancelListener { setBusy(false); say("No worries — nothing linked") }
            .show()
    }

    private fun claim(server: String, code: String, info: NoCapApi.Info) {
        say("Linking…")
        io.execute {
            try {
                val link = NoCapApi.claim(server, info.fingerprint, code, deviceName())
                val full = link.copy(
                    nocapId = link.nocapId.ifEmpty { info.nocapId },
                    serverName = link.serverName.ifEmpty { info.name },
                    version = info.version,
                )
                NoCapLink.save(this, full)
                NoCapApi.forgetIcons(this)
                HeartbeatWorker.schedule(this)
                main.post {
                    if (isDestroyed) return@post
                    Toast.makeText(this, "Bet — you're linked to ${full.serverName}", Toast.LENGTH_LONG).show()
                    finish()
                }
            } catch (e: Exception) {
                main.post { if (!isDestroyed) { setBusy(false); say(e.message ?: "That code's not it, try again", error = true) } }
            }
        }
    }

    private fun deviceName(): String {
        val named = try { Settings.Global.getString(contentResolver, "device_name") } catch (e: Exception) { null }
        if (!named.isNullOrBlank()) return named.take(60)
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return (if (Build.MODEL.startsWith(Build.MANUFACTURER, true)) Build.MODEL else "$maker ${Build.MODEL}").take(60)
    }

    // ---------------- UI ----------------

    private fun setBusy(b: Boolean) {
        busy = b
        linkButton.alpha = if (b) 0.5f else 1f
        linkButton.text = if (b) "Hold up…" else "Link it"
    }

    private fun say(msg: String, error: Boolean = false) {
        status.text = msg
        status.setTextColor(if (error) accent else dim)
        status.visibility = View.VISIBLE
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.addView(HudBackground(this, null).also { it.accent = accent; it.style = WallpaperSync.style(this) },
            FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(36), dp(22), dp(28))
        }
        scroll.addView(col)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        col.addView(tv("‹", 28f, white).apply { setOnClickListener { finish() }; setPadding(0, 0, dp(14), dp(4)) })
        col.addView(ImageView(this).apply {
            setImageResource(R.drawable.nocap_mark)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4) })
        col.addView(tv("Link your NoCap server", 28f, white, font = "sans-serif-light").apply { gravity = Gravity.CENTER },
            lp(14))
        col.addView(tv("NOCAP CLOUD  ·  PHONE ⇄ SERVER", 10f, accent, mono = true).apply {
            letterSpacing = 0.2f; gravity = Gravity.CENTER
        }, lp(6))
        col.addView(tv("On your server: Settings → Devices → Link a device. Scan the QR, or type the address and the 6-digit code.",
            13f, dim).apply { gravity = Gravity.CENTER }, lp(14))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.glass_card)
            setPadding(dp(16), dp(16), dp(16), dp(18))
        }
        col.addView(card, lp(20))

        card.addView(label("SERVER"))
        serverField = field().apply {
            setText(NoCapLink.get(this@PairActivity)?.server ?: NoCapApi.DEFAULT_SERVER)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        card.addView(serverField, lp(6))
        card.addView(tv("nocap.local not working? Use the IP the server shows, e.g. https://192.168.1.20:8443", 11f, dim), lp(6))

        card.addView(label("CODE"), lp(16))
        codeField = field().apply {
            hint = "000000"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            letterSpacing = 0.5f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { start(); true } else false }
        }
        card.addView(codeField, lp(6))

        linkButton = tv("Link it", 16f, Color.BLACK, font = "sans-serif-medium").apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.pill_accent)
            backgroundTintList = ColorStateList.valueOf(accent)
            setPadding(0, dp(13), 0, dp(13))
            setOnClickListener { start() }
        }
        card.addView(linkButton, lp(18))

        val scanBtn = tv("⌖  Scan QR", 15f, white, font = "sans-serif-medium").apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.glass_pill)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { scan() }
        }
        card.addView(scanBtn, lp(10))

        status = tv("", 13f, dim).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        col.addView(status, lp(16))

        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(tv("Skip for now", 14f, dim).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(6))
            setOnClickListener { finish() }
        }, lp(12))

        setContentView(root)
    }

    private fun label(s: String) = tv(s, 10f, accent, mono = true).apply { letterSpacing = 0.2f }

    private fun field() = EditText(this).apply {
        setBackgroundResource(R.drawable.field)
        setTextColor(white)
        setHintTextColor(Color.argb(80, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setSingleLine()
        setPadding(dp(14), dp(12), dp(14), dp(12))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun tv(s: CharSequence, size: Float, color: Int, mono: Boolean = false, font: String = "sans-serif") =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = if (mono) Typeface.MONOSPACE else Typeface.create(font, Typeface.NORMAL)
        }
}
