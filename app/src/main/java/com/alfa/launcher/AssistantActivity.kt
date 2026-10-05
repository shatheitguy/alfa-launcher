package com.alfa.launcher

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

/** ALFA Assistant: chat with an AI that acts through ALFA's tools. Text + voice. */
class AssistantActivity : Activity() {

    companion object {
        private const val REQ_VOICE = 81
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
    private var busy = false

    private val white = Color.WHITE
    private val dim = Color.argb(150, 255, 255, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accent = MainActivity.accentOf(this)
        engine = AssistantEngine(this)

        val root = FrameLayout(this)
        root.addView(HudBackground(this, null).also { it.accent = accent; it.style = WallpaperSync.style(this) }, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(14))
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // header
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("‹", 28f, white).apply { setPadding(0, 0, dp(12), dp(4)); setOnClickListener { finish() } })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(tv("ALFA Assistant", 24f, white, font = "sans-serif-light"))
        titles.addView(tv(providerLine(), 10f, accent, mono = true).apply { letterSpacing = 0.15f })
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(pill("New chat", false) { engine.reset(); list.removeAllViews(); greet() })
        col.addView(head)

        scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        scroll.addView(list)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // input bar
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat(); setColor(Color.argb(200, 16, 16, 21)); setStroke(dp(1), Color.argb(45, 255, 255, 255))
            }
            setPadding(dp(16), dp(4), dp(6), dp(4))
        }
        input = EditText(this).apply {
            hint = "Ask ALFA to do something…"
            setHintTextColor(Color.argb(110, 255, 255, 255))
            setTextColor(white)
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(round("🎙", false) { startVoice() })
        sendBtn = round("↑", true) { submit() }
        bar.addView(sendBtn)
        col.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        setContentView(root)
        greet()
        if (intent.getBooleanExtra(EXTRA_VOICE, false)) main.postDelayed({ startVoice() }, 250)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_VOICE, false) == true) startVoice()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun providerLine(): String {
        val p = getSharedPreferences("alfa", MODE_PRIVATE)
        return if (engine.provider == "claude")
            "CLAUDE · " + (p.getString("ai_claude_model", "")?.ifBlank { null } ?: "claude-opus-5-5").uppercase(Locale.US)
        else "OWN SERVER · " + (p.getString("ai_server_model", "")?.ifBlank { null } ?: "llama3.1").uppercase(Locale.US)
    }

    private fun greet() {
        if (!engine.configured()) {
            bubble("Hi! Before I can help, connect me to an AI in ⚙ ALFA OS Settings → ALFA Assistant: " +
                "paste a Claude API key, or point me at your own server (Ollama or any OpenAI-compatible URL).", false)
            list.addView(pill("Open settings", true) {
                startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            return
        }
        val name = Profile.name(this).substringBefore(' ').ifEmpty { "there" }
        bubble("Hi $name, what should I do? Try “open WhatsApp”, “use the Aurora wallpaper in ice blue”, " +
            "“is google.com's certificate OK?” or “set a 10 minute timer”.", false)
    }

    // ---------------- sending ----------------

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        if (!engine.configured()) { greet(); return }
        input.setText("")
        bubble(text, true)
        val thinking = tv("…", 14f, dim, mono = true)
        list.addView(thinking, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        scrollDown()
        setBusy(true)
        worker.execute {
            val reply = try {
                engine.send(text, object : AssistantEngine.Listener {
                    override fun onAction(label: String) { main.post { chip(label) } }
                })
            } catch (e: com.anthropic.errors.UnauthorizedException) {
                "That Claude API key was rejected. Check it in ALFA OS Settings → ALFA Assistant."
            } catch (e: com.anthropic.errors.RateLimitException) {
                "Claude is rate-limiting this key right now. Try again in a moment."
            } catch (e: com.anthropic.errors.AnthropicServiceException) {
                "Claude returned an error (${e.statusCode()}): ${e.message}"
            } catch (e: com.anthropic.errors.AnthropicIoException) {
                "Couldn't reach Claude. Check the internet connection."
            } catch (e: java.net.ConnectException) {
                "Couldn't reach your AI server. Check the URL and that it's running and reachable from the phone."
            } catch (e: Exception) {
                "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
            }
            main.post {
                if (isDestroyed) return@post
                list.removeView(thinking)
                bubble(reply, false)
                setBusy(false)
            }
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        sendBtn.alpha = if (b) 0.35f else 1f
    }

    @Suppress("DEPRECATION")
    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask ALFA…")
        try { startActivityForResult(i, REQ_VOICE) } catch (e: Exception) {
            Toast.makeText(this, "No speech recognition on this phone", Toast.LENGTH_SHORT).show()
        }
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

    // ---------------- views ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: CharSequence, size: Float, color: Int, mono: Boolean = false, font: String = "sans-serif") =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = if (mono) Typeface.MONOSPACE else Typeface.create(font, Typeface.NORMAL)
        }

    private fun bubble(text: String, mine: Boolean) {
        val b = tv(text, 15f, if (mine) Color.BLACK else white).apply {
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                if (mine) setColor(accent) else { setColor(Color.argb(190, 22, 22, 28)); setStroke(dp(1), Color.argb(40, 255, 255, 255)) }
            }
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        }
        list.addView(b, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8)
            gravity = if (mine) Gravity.END else Gravity.START
        })
        scrollDown()
    }

    private fun chip(label: String) {
        val c = tv("▸ $label", 11f, accent, mono = true).apply {
            letterSpacing = 0.05f
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }
        list.addView(c, list.childCount.coerceAtLeast(1) - 1, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        scrollDown()
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) =
        tv(label, 13f, if (primary) Color.BLACK else white, font = "sans-serif-medium").apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundResource(R.drawable.pill_accent)
            backgroundTintList = ColorStateList.valueOf(if (primary) accent else Color.argb(38, 255, 255, 255))
            setOnClickListener { onClick() }
        }

    private fun round(glyph: String, primary: Boolean, onClick: () -> Unit) =
        tv(glyph, 18f, if (primary) Color.BLACK else white).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (primary) accent else Color.argb(38, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
}
