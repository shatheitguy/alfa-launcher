package com.alfa.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

/** ALFA Assistant chat: clean, quiet layout; replies glide in; QR codes show inline. */
class AssistantActivity : Activity() {

    companion object {
        private const val REQ_VOICE = 81
        private const val REQ_CONTACTS = 82
        private const val REQ_SMS = 83
        const val EXTRA_VOICE = "voice"
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var engine: AssistantEngine
    private var accent = 0
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var status: TextView
    private var empty: View? = null
    private var busy = false

    private val white = Color.WHITE
    private val dim = Color.argb(140, 255, 255, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accent = MainActivity.accentOf(this)
        engine = AssistantEngine(this)

        val root = FrameLayout(this)
        root.addView(HudBackground(this, null).also { it.accent = accent; it.style = WallpaperSync.style(this) }, FrameLayout.LayoutParams(-1, -1))
        // darken the wallpaper so text stays crisp
        root.addView(View(this).apply { setBackgroundColor(Color.argb(150, 4, 4, 7)) }, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(12)) }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // ---- header ----
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("‹", 28f, white).apply { setPadding(dp(2), 0, dp(14), dp(4)); setOnClickListener { finish() } })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(tv("ALFA", 20f, white, font = "sans-serif-medium").apply { letterSpacing = 0.12f })
        status = tv("", 10f, dim, mono = true).apply { letterSpacing = 0.12f }
        titles.addView(status)
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(tv("☰", 19f, dim).apply {
            setPadding(dp(14), dp(8), dp(6), dp(8))
            contentDescription = "Chat history"
            setOnClickListener { if (!busy) showHistory() }
        })
        head.addView(tv("⟲", 20f, dim).apply {
            setPadding(dp(10), dp(8), dp(4), dp(8))
            contentDescription = "New chat"
            setOnClickListener { if (!busy) { engine.newChat(); list.removeAllViews(); empty = null; showEmpty(); refreshStatus() } }
        })
        col.addView(head)
        col.addView(View(this).apply { setBackgroundColor(Color.argb(22, 255, 255, 255)) },
            LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(10) })

        // ---- messages ----
        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(16)) }
        scroll.addView(list)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- input ----
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat(); setColor(Color.argb(225, 18, 18, 23)); setStroke(dp(1), Color.argb(38, 255, 255, 255))
            }
            setPadding(dp(18), dp(5), dp(6), dp(5))
        }
        input = EditText(this).apply {
            hint = "Message ALFA"
            setHintTextColor(Color.argb(100, 255, 255, 255))
            setTextColor(white)
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(micButton { startVoice() })
        sendBtn = round("↑", true) { submit() }
        bar.addView(sendBtn)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { sendBtn.animate().alpha(if (s.isNullOrBlank() || busy) 0.35f else 1f).setDuration(120).start() }
        })
        sendBtn.alpha = 0.35f
        col.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        setContentView(root)
        refreshStatus()
        if (!renderChat()) showEmpty()
        if (intent.getBooleanExtra(EXTRA_VOICE, false)) main.postDelayed({ startVoice() }, 250)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_VOICE, false) == true) startVoice()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val ready = engine.configured()
        val where = if (engine.engine == "builtin") "ON-DEVICE" else "LOCAL SERVER"
        val used = engine.contextUsed
        val ctxNote = if (ready && used > 0) " · ${k(used)}/${k(engine.contextSize)} CTX" else ""
        status.text = if (ready) "● $where · ${engine.modelName.uppercase(Locale.US)}$ctxNote" else "○ NOT SET UP"
        status.setTextColor(if (ready) accent else dim)
    }

    private fun k(tokens: Int) = if (tokens >= 1000) String.format(Locale.US, "%.1fK", tokens / 1000f).replace(".0K", "K") else tokens.toString()

    // ---------------- saved chats ----------------

    /** Shows the open chat's messages; false when there is nothing to show. */
    private fun renderChat(): Boolean {
        val h = engine.chat.history
        var shown = 0
        for (i in 0 until h.length()) {
            val m = h.optJSONObject(i) ?: continue
            val content = m.optString("content", "").let { if (it == "null") "" else it }
            when (m.optString("role")) {
                "user" -> {
                    // drop the "(Current local time: …)" note added for the model
                    val text = content.replace(Regex("\\n\\n\\(Current local time:[^)]*\\)\\s*$"), "").trim()
                    if (text.isNotEmpty()) { userBubble(text); shown++ }
                }
                "assistant" -> {
                    val calls = m.optJSONArray("tool_calls")
                    if (content.isNotBlank() && (calls == null || calls.length() == 0)) {
                        val turn = assistantRow()
                        turn.text.text = content.trim()
                        turn.text.visibility = View.VISIBLE
                        shown++
                    }
                }
            }
        }
        if (shown > 0) scrollDown()
        return shown > 0
    }

    private fun showHistory() {
        val chats = ChatStore.list(this)
        if (chats.isEmpty()) { toast("No saved chats yet"); return }
        val fmt = java.text.SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
        val labels = chats.map { (it.title.ifBlank { "Untitled chat" }) + "\n" + fmt.format(java.util.Date(it.updated)) }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Chat history")
            .setItems(labels.toTypedArray()) { _, i ->
                if (engine.open(chats[i].id)) {
                    list.removeAllViews(); empty = null
                    if (!renderChat()) showEmpty()
                    refreshStatus()
                }
            }
            .setNeutralButton("Delete all") { _, _ ->
                android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setMessage("Delete all ${chats.size} saved chats? This can't be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        ChatStore.deleteAll(this)
                        engine.newChat(); list.removeAllViews(); empty = null; showEmpty(); refreshStatus()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------- empty state ----------------

    private fun showEmpty() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(VoiceOrbView(this).apply { accent = this@AssistantActivity.accent; state = VoiceOrbView.State.IDLE },
            LinearLayout.LayoutParams(dp(150), dp(150)).apply { topMargin = dp(36) })
        if (!engine.configured()) {
            box.addView(tv("Let's set me up", 22f, white, font = "sans-serif-light"), lpc(10))
            box.addView(tv("Download a model to run me offline on this phone, or connect your own server.", 14f, dim).apply {
                gravity = Gravity.CENTER; setPadding(dp(20), 0, dp(20), 0)
            }, lpc(6))
            box.addView(pill("Open AI settings", true) {
                startActivity(Intent(this, if (engine.engine == "builtin") ModelsActivity::class.java else SettingsActivity::class.java))
            }, lpc(18))
        } else {
            box.addView(tv("How can I help?", 22f, white, font = "sans-serif-light"), lpc(10))
            box.addView(tv("Ask me to open apps, change ALFA, or run IT checks.", 13f, dim), lpc(4))
            val ideas = listOf(
                "Open WhatsApp", "Set a 10 minute timer", "Make a Wi-Fi QR code",
                "Is google.com's SSL OK?", "How's my battery and storage?", "Use the Aurora wallpaper",
            )
            val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            ideas.chunked(2).forEach { pair ->
                val r = LinearLayout(this)
                pair.forEachIndexed { i, s ->
                    r.addView(suggestion(s), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
                }
                grid.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            box.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        }
        empty = box
        list.addView(box, LinearLayout.LayoutParams(-1, -2))
        glide(box)
    }

    private fun suggestion(s: String) = tv(s, 13f, white).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(12), dp(10), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat(); setColor(Color.argb(150, 20, 20, 26)); setStroke(dp(1), Color.argb(34, 255, 255, 255))
        }
        setOnClickListener { input.setText(s); submit() }
    }

    // ---------------- sending ----------------

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        if (!engine.configured()) { toast("Set up an AI model first"); return }
        empty?.let { list.removeView(it); empty = null }
        input.setText("")
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        userBubble(text)
        val turn = assistantRow()
        val dots = typingDots()
        turn.body.addView(dots)
        busy = true
        sendBtn.alpha = 0.35f
        worker.execute {
            val reply = try {
                engine.send(text, object : AssistantEngine.Listener {
                    override fun onAction(label: String) { main.post { addChip(turn, label) } }
                })
            } catch (e: java.net.ConnectException) {
                "I can't reach the AI server. Check that it's running and reachable from the phone."
            } catch (e: java.net.SocketTimeoutException) {
                "The AI took too long to answer. A model may still be loading; try again."
            } catch (e: Exception) {
                e.message ?: "Something went wrong (${e.javaClass.simpleName})."
            }
            val qr = AssistantTools.takeQr()
            val sms = AssistantTools.takeSms()
            main.post {
                if (isDestroyed) return@post
                turn.body.removeView(dots)
                (dots.tag as? ValueAnimator)?.cancel()
                turn.text.text = reply
                turn.text.visibility = View.VISIBLE
                glide(turn.text)
                if (qr != null) addQr(turn, qr)
                if (sms != null) addSms(turn, sms)
                askContactsIfNeeded()
                busy = false
                sendBtn.alpha = if (input.text.isNullOrBlank()) 0.35f else 1f
                refreshStatus()
                scrollDown()
            }
        }
    }

    /**
     * An action needed contacts to find someone: ask for the permission (or, if Android no longer
     * shows the prompt, open ALFA's permission page), then the user can repeat the request.
     */
    private fun askContactsIfNeeded() {
        if (!AssistantTools.needsContacts) return
        AssistantTools.needsContacts = false
        Perms.request(this, android.Manifest.permission.READ_CONTACTS, REQ_CONTACTS, "Contacts")
    }

    /** A text waiting for the SMS permission before it can be sent. */
    private var smsWaiting: Pair<AssistantTools.Sms, TextView>? = null

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQ_CONTACTS -> if (ok) toast("Contacts allowed — ask again and I'll find them")
            REQ_SMS -> {
                val w = smsWaiting
                smsWaiting = null
                if (ok && w != null) doSendSms(w.first, w.second)
                else if (!ok) toast("ALFA needs the SMS permission to send texts — or use Open in Messages")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask ALFA…")
        try { startActivityForResult(i, REQ_VOICE) } catch (e: Exception) { toast("No speech recognition on this phone") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            input.setText(said)
            submit()
        }
    }

    // ---------------- message views ----------------

    private class Turn(val row: LinearLayout, val body: LinearLayout, val chips: LinearLayout, val text: TextView)

    private fun userBubble(text: String) {
        val b = tv(text, 15.5f, white).apply {
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(6).toFloat(), dp(6).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat())
                setColor(Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        }
        list.addView(b, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18); gravity = Gravity.END })
        glide(b)
        scrollDown()
    }

    private fun assistantRow(): Turn {
        val row = LinearLayout(this).apply { gravity = Gravity.TOP }
        val av = tv("α", 14f, accent, font = "sans-serif-medium").apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(160, 14, 14, 18)); setStroke(dp(1), accent) }
        }
        row.addView(av, LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(2) })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val text = tv("", 16f, Color.argb(235, 255, 255, 255)).apply {
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
            visibility = View.GONE
        }
        body.addView(chips)
        body.addView(text, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        row.addView(body, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        glide(row)
        scrollDown()
        return Turn(row, body, chips, text)
    }

    private fun typingDots(): LinearLayout {
        val box = LinearLayout(this).apply { setPadding(0, dp(8), 0, dp(4)) }
        val dots = (0 until 3).map {
            View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) } }
        }
        dots.forEach { box.addView(it, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) }) }
        val anim = ValueAnimator.ofFloat(0f, 3f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                dots.forEachIndexed { i, d ->
                    val p = ((t - i * 0.35f) % 3f + 3f) % 3f
                    val k = if (p < 1f) kotlin.math.sin(p * Math.PI).toFloat() else 0f
                    d.alpha = 0.3f + 0.7f * k
                    d.translationY = -dp(4) * k
                }
            }
            start()
        }
        box.tag = anim
        return box
    }

    private fun addChip(turn: Turn, label: String) {
        val c = tv("▸  $label", 12f, accent, mono = true).apply { setPadding(0, dp(3), 0, dp(3)) }
        turn.chips.addView(c)
        glide(c)
        scrollDown()
    }

    private fun addQr(turn: Turn, qr: AssistantTools.Qr) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.argb(200, 20, 20, 26)); setStroke(dp(1), Color.argb(40, 255, 255, 255)) }
        }
        val img = ImageView(this).apply {
            setImageBitmap(qr.bitmap)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.WHITE) }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToOutline = true
        }
        card.addView(img, LinearLayout.LayoutParams(dp(220), dp(220)))
        card.addView(tv(qr.caption, 13f, dim).apply { gravity = Gravity.CENTER }, lpc(10))
        val btns = LinearLayout(this)
        btns.addView(pill("Save PNG", true) {
            val uri = try { QrUtil.save(this, qr.bitmap, qr.name) } catch (e: Exception) { null }
            toast(if (uri != null) "Saved to Pictures/ALFA" else "Open IT Tools → QR generator to save on this Android version")
        })
        btns.addView(pill("Share", false) {
            val uri = try { QrUtil.save(this, qr.bitmap, qr.name) } catch (e: Exception) { null }
            if (uri == null) { toast("Couldn't prepare the image"); return@pill }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share QR code"))
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        card.addView(btns, lpc(12))
        turn.body.addView(card, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        glide(card)
        scrollDown()
    }

    /** The prepared text, shown for the user to check; nothing is sent until they tap Send. */
    private fun addSms(turn: Turn, sms: AssistantTools.Sms) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.argb(200, 20, 20, 26)); setStroke(dp(1), Color.argb(40, 255, 255, 255)) }
        }
        card.addView(tv("SMS TO ${sms.name.uppercase(Locale.getDefault())}", 11f, accent, mono = true).apply { letterSpacing = 0.12f })
        card.addView(tv(sms.number, 12f, dim, mono = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        card.addView(tv(sms.text, 15f, white).apply { setTextIsSelectable(true); setLineSpacing(dp(2).toFloat(), 1f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val state = tv("", 12f, dim)
        val btns = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val sendPill = pill("Send", true) {
            if (Perms.granted(this, android.Manifest.permission.SEND_SMS)) doSendSms(sms, state)
            else {
                smsWaiting = sms to state
                Perms.request(this, android.Manifest.permission.SEND_SMS, REQ_SMS, "SMS")
            }
        }
        btns.addView(sendPill)
        btns.addView(pill("Open in Messages", false) {
            if (AssistantTools.openSmsApp(this, sms) != "ok") toast("No messaging app found")
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        card.addView(btns, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        card.addView(state, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        state.tag = sendPill
        turn.body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        glide(card)
        scrollDown()
    }

    private fun doSendSms(sms: AssistantTools.Sms, state: TextView) {
        val btn = state.tag as? TextView
        try {
            AssistantTools.sendSms(this, sms)
            state.text = "✓ Sent to ${sms.name}"
            state.setTextColor(accent)
            btn?.isEnabled = false
            btn?.alpha = 0.4f
        } catch (e: Exception) {
            state.text = "Couldn't send: ${e.message ?: e.javaClass.simpleName}. Try Open in Messages."
            state.setTextColor(Color.rgb(255, 120, 120))
        }
    }

    // ---------------- helpers ----------------

    private fun glide(v: View) {
        v.alpha = 0f
        v.translationY = dp(12).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lpc(top: Int) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(top); gravity = Gravity.CENTER_HORIZONTAL }

    private fun tv(s: CharSequence, size: Float, color: Int, mono: Boolean = false, font: String = "sans-serif") =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = if (mono) Typeface.MONOSPACE else Typeface.create(font, Typeface.NORMAL)
        }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) =
        tv(label, 13f, if (primary) Color.BLACK else white, font = "sans-serif-medium").apply {
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat(); setColor(if (primary) accent else Color.argb(40, 255, 255, 255))
            }
            setOnClickListener { onClick() }
        }

    /** Mic in the chat's own style: line icon in the accent colour on a soft accent ring (the emoji looked out of place). */
    private fun micButton(onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(R.drawable.ic_mic)
        imageTintList = android.content.res.ColorStateList.valueOf(accent)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)))
            setStroke(dp(1), Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        contentDescription = "Speak to ALFA"
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun round(glyph: String, primary: Boolean, onClick: () -> Unit) =
        tv(glyph, 18f, if (primary) Color.BLACK else white).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (primary) accent else Color.argb(30, 255, 255, 255)) }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun scrollDown() = scroll.post { scroll.smoothScrollTo(0, list.height) }
}
