package com.alfa.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Full-screen voice mode on top of the home screen: the animated [VoiceOrbView], live transcript,
 * action chips and the reply (optionally spoken). Drives [VoiceListener] for the command and
 * [AssistantEngine] for the answer. [onClosed] lets the home screen resume wake-word listening.
 */
class VoiceOverlay(private val act: Activity, private val onClosed: () -> Unit) : FrameLayout(act) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs = act.getSharedPreferences("alfa", Activity.MODE_PRIVATE)
    private val d = resources.displayMetrics.density
    private val engine = AssistantEngine(act)
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val orb = VoiceOrbView(act)
    private val status = text(11f, 0.7f, mono = true).apply { letterSpacing = 0.25f }
    private val transcript = text(22f, 1f, font = "sans-serif-light")
    private val chips = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val reply = text(16f, 0.9f)
    private val qrImg = android.widget.ImageView(act).apply {
        visibility = GONE
        background = GradientDrawable().apply { cornerRadius = 14 * resources.displayMetrics.density; setColor(Color.WHITE) }
        val p = (8 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
    }
    private val hint = text(10f, 0.45f, mono = true).apply { letterSpacing = 0.18f }

    var isOpen = false
        private set
    private var busy = false

    val voice = VoiceListener(act, object : VoiceListener.Callbacks {
        override fun onWake() { show(); listening() }
        override fun onPartial(text: String) { transcript.text = text }
        override fun onLevel(rmsDb: Float) { orb.setRms(rmsDb) }
        override fun onCommand(text: String) { transcript.text = text; run(text) }
        override fun onNoCommand() {
            if (busy) return
            status.text = "I DIDN’T CATCH THAT"
            main.postDelayed({ if (!busy) close() }, 1400)
        }
    })

    init {
        visibility = GONE
        isClickable = true            // eat touches behind the overlay
        // dark scrim with a soft accent fade toward the bottom
        background = object : ShapeDrawable(RectShape()) {}.apply {
            shaderFactory = object : ShapeDrawable.ShaderFactory() {
                override fun resize(width: Int, height: Int) = LinearGradient(0f, 0f, 0f, height.toFloat(),
                    intArrayOf(Color.argb(235, 4, 4, 7), Color.argb(225, 6, 6, 10), Color.argb(240, 10, 6, 10)),
                    null, Shader.TileMode.CLAMP)
            }
        }
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(40), dp(24), dp(28))
        }
        col.addView(View(act), LinearLayout.LayoutParams(1, 0, 0.6f))
        col.addView(orb, LinearLayout.LayoutParams(dp(300), dp(300)))
        col.addView(status, lp(4))
        col.addView(transcript, lp(14))
        col.addView(chips, lp(14))
        col.addView(reply, lp(12))
        col.addView(qrImg, LinearLayout.LayoutParams(dp(200), dp(200)).apply { topMargin = dp(14); gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(View(act), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(hint, lp(0))
        addView(col, LayoutParams(-1, -1))
        transcript.gravity = Gravity.CENTER
        reply.gravity = Gravity.CENTER
        status.gravity = Gravity.CENTER
        hint.gravity = Gravity.CENTER
        hint.text = "TAP ANYWHERE TO CLOSE"

        addView(text(20f, 0.8f).apply {
            text = "✕"
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setOnClickListener { close() }
        }, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(24) })
        setOnClickListener { if (!busy) close() }
    }

    private fun dp(v: Int) = (v * d).toInt()
    private fun lp(top: Int) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(top); gravity = Gravity.CENTER_HORIZONTAL }

    private fun text(size: Float, alpha: Float, mono: Boolean = false, font: String = "sans-serif") = TextView(act).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(Color.argb((alpha * 255).toInt(), 255, 255, 255))
        typeface = if (mono) Typeface.MONOSPACE else Typeface.create(font, Typeface.NORMAL)
    }

    // ---------------- open / close ----------------

    /** Wake word or long-press: show and start listening for a command. */
    fun openAndListen() {
        show()
        listening()
        voice.listenCommand()
    }

    private fun show() {
        if (isOpen) return
        isOpen = true
        val accent = MainActivity.accentOf(act)
        orb.accent = accent
        status.setTextColor(accent)
        transcript.text = ""
        reply.text = ""
        qrImg.visibility = GONE
        chips.removeAllViews()
        visibility = VISIBLE
        alpha = 0f
        orb.scaleX = 0.4f; orb.scaleY = 0.4f
        animate().alpha(1f).setDuration(220).start()
        orb.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(DecelerateInterpolator(2.4f)).start()
        if (prefs.getBoolean("haptics", true)) performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        if (System.currentTimeMillis() - engine.lastUsed > 5 * 60_000L) engine.reset()   // fresh context after a while
        warmTts()   // so the reply can be spoken without a start-up pause
    }

    private fun listening() {
        orb.state = VoiceOrbView.State.LISTENING
        status.text = "LISTENING"
        reply.text = ""
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        busy = false
        voice.stop()
        tts?.stop()
        orb.animate().scaleX(0.5f).scaleY(0.5f).setDuration(220).start()
        animate().alpha(0f).setDuration(220).withEndAction { visibility = GONE; onClosed() }.start()
    }

    fun destroy() {
        voice.destroy()
        tts?.shutdown()
        worker.shutdownNow()
    }

    // ---------------- run a command ----------------

    private fun run(text: String) {
        if (busy) return
        busy = true
        voice.stop()
        orb.state = VoiceOrbView.State.THINKING
        status.text = "THINKING"
        if (!engine.configured()) {
            finish("Connect your local AI server first: ⚙ ALFA OS Settings → ALFA Assistant.")
            return
        }
        worker.execute {
            val answer = try {
                engine.send(text, object : AssistantEngine.Listener {
                    override fun onAction(label: String) { main.post { chip(label) } }
                })
            } catch (e: java.net.ConnectException) {
                "I can't reach your AI server. Check that it's running and on the same network."
            } catch (e: java.net.SocketTimeoutException) {
                "Your AI server took too long. Try again."
            } catch (e: Exception) {
                "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
            }
            val qr = AssistantTools.takeQr()
            main.post {
                if (!isOpen) return@post
                if (AssistantTools.needsContacts) {
                    AssistantTools.needsContacts = false
                    @Suppress("DEPRECATION")
                    act.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 83)
                }
                if (qr != null) {
                    qrImg.setImageBitmap(qr.bitmap); qrImg.visibility = VISIBLE
                    qrImg.alpha = 0f; qrImg.animate().alpha(1f).setDuration(300).start()
                }
                finish(answer, if (qr != null) 9000L else null)
            }
        }
    }

    private fun finish(answer: String, hold: Long? = null) {
        holdMs = hold
        reply.text = answer
        reply.alpha = 0f
        reply.animate().alpha(1f).setDuration(260).start()
        if (prefs.getBoolean("ai_speak", true)) speak(answer) else done(holdMs ?: 2600)
    }

    private var holdMs: Long? = null

    private fun done(delay: Long) {
        orb.state = VoiceOrbView.State.IDLE
        status.text = "DONE"
        busy = false
        // keep a QR code on screen long enough to scan it
        main.postDelayed({ if (isOpen && !busy) close() }, holdMs?.coerceAtLeast(delay) ?: delay)
    }

    private fun chip(label: String) {
        val accent = MainActivity.accentOf(act)
        val c = text(12f, 1f, mono = true).apply {
            text = "▸ $label"
            setTextColor(accent)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(36, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
            alpha = 0f
            translationY = dp(8).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(240).start()
        }
        chips.addView(c, lp(6))
    }

    // ---------------- text to speech ----------------

    private fun warmTts() {
        if (tts == null && prefs.getBoolean("ai_speak", true)) {
            tts = TextToSpeech(act) { st -> ttsReady = st == TextToSpeech.SUCCESS; if (ttsReady) tts?.language = Locale.getDefault() }
        }
    }

    private fun speak(textToSay: String) {
        orb.state = VoiceOrbView.State.SPEAKING
        status.text = "SPEAKING"
        val say = {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { main.post { done(1200) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { main.post { done(1800) } }
            })
            tts?.speak(textToSay, TextToSpeech.QUEUE_FLUSH, null, "alfa")
        }
        if (tts != null && ttsReady) { say(); return }
        tts = TextToSpeech(act) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            if (ttsReady) { tts?.language = Locale.getDefault(); say() } else main.post { done(2600) }
        }
    }

    @Suppress("unused")
    private val keepImport = PaintDrawable::class
}
