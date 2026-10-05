package com.alfa.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * ALFA's voice orb. A glowing core with ripple rings and a living wave ring that react to the
 * microphone level, orbiting arcs that speed up while thinking, and a soft pulse while speaking.
 */
class VoiceOrbView(context: Context) : View(context) {

    enum class State { LISTENING, THINKING, SPEAKING, IDLE }

    var state = State.LISTENING
        set(v) { field = v; invalidate() }
    var accent: Int = Color.rgb(255, 45, 61)

    private val d = resources.displayMetrics.density
    private var level = 0f          // smoothed 0..1
    private var target = 0f
    private var t = 0f              // seconds
    private var spin = 0f
    private var spinSpeed = 40f     // eased toward the state's target speed
    private var think = 0f          // 0..1, eased presence of the "thinking" dots
    private var lastMs = 0L
    private var running = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val oval = RectF()

    /** Feed SpeechRecognizer.onRmsChanged values (about -2..10 dB). */
    fun setRms(rmsDb: Float) {
        target = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime() / 1_000_000L
            val dt = ((now - lastMs).coerceIn(1L, 50L)) / 1000f
            lastMs = now
            t += dt
            level += (target - level) * (1f - kotlin.math.exp(-dt / 0.08f))
            target *= 0.92f
            // Ease the spin speed and the thinking-dots toward the state's target,
            // so switching states ramps smoothly instead of snapping.
            val targetSpeed = when (state) { State.THINKING -> 260f; State.SPEAKING -> 70f; else -> 40f }
            spinSpeed += (targetSpeed - spinSpeed) * (1f - kotlin.math.exp(-dt / 0.35f))
            val targetThink = if (state == State.THINKING) 1f else 0f
            think += (targetThink - think) * (1f - kotlin.math.exp(-dt / 0.25f))
            spin = (spin + spinSpeed * dt) % 360f
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastMs = System.nanoTime() / 1_000_000L
        postOnAnimation(frame)
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    private fun a(c: Int, alpha: Float) = Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.2f
        if (r <= 0f) return

        val speakPulse = if (state == State.SPEAKING) (0.5f + 0.5f * sin(t * 9f)) * 0.6f else 0f
        val energy = (level + speakPulse).coerceIn(0f, 1f)

        // 1) ambient glow
        fill.shader = RadialGradient(cx, cy, r * 3.2f, a(accent, 0.28f + 0.3f * energy), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 3.2f, fill)
        fill.shader = null

        // 2) ripple rings expanding outward
        for (k in 0 until 3) {
            val p = ((t * 0.65f + k / 3f) % 1f)
            val rr = r * (1.1f + p * (1.3f + 0.8f * energy))
            stroke.strokeWidth = (2.2f - 1.6f * p) * d
            stroke.color = a(accent, (1f - p) * (0.35f + 0.45f * energy))
            canvas.drawCircle(cx, cy, rr, stroke)
        }

        // 3) living wave ring (the "voice")
        path.reset()
        val base = r * 1.22f
        val amp = r * (0.035f + 0.16f * energy)
        val n = 120
        for (i in 0..n) {
            val ang = (i / n.toFloat()) * (2 * Math.PI).toFloat()
            val wobble = sin(ang * 6f + t * 4.2f) * 0.6f + sin(ang * 11f - t * 6.1f) * 0.4f
            val rad = base + amp * wobble
            val x = cx + rad * cos(ang); val y = cy + rad * sin(ang)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        stroke.strokeWidth = 2.6f * d
        stroke.color = a(accent, 0.95f)
        canvas.drawPath(path, stroke)
        stroke.strokeWidth = 7f * d
        stroke.color = a(accent, 0.18f)
        canvas.drawPath(path, stroke)

        // 4) orbiting arcs
        val ar = r * 1.55f
        oval.set(cx - ar, cy - ar, cx + ar, cy + ar)
        stroke.strokeWidth = 2f * d
        stroke.color = a(Color.WHITE, 0.55f)
        canvas.drawArc(oval, spin, 70f, false, stroke)
        stroke.color = a(accent, 0.8f)
        canvas.drawArc(oval, spin + 180f, 110f, false, stroke)
        val ar2 = r * 1.75f
        oval.set(cx - ar2, cy - ar2, cx + ar2, cy + ar2)
        stroke.strokeWidth = 1.2f * d
        stroke.color = a(Color.WHITE, 0.25f)
        canvas.drawArc(oval, -spin * 0.6f, 200f, false, stroke)

        // 5) thinking: dots chasing around the ring (fade in/out with `think`)
        if (think > 0.02f) {
            for (k in 0 until 6) {
                val ang = Math.toRadians((spin * 1.4 + k * 18).toDouble())
                fill.color = a(accent, (1f - k * 0.14f) * think)
                canvas.drawCircle(cx + (r * 1.38f * cos(ang)).toFloat(), cy + (r * 1.38f * sin(ang)).toFloat(), (3.4f - k * 0.4f) * d, fill)
            }
        }

        // 6) glowing core
        val cr = r * (0.9f + 0.12f * energy)
        fill.shader = RadialGradient(cx - cr * 0.25f, cy - cr * 0.3f, cr * 1.2f,
            intArrayOf(Color.WHITE, a(accent, 1f), Color.rgb(12, 10, 16)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, cr, fill)
        fill.shader = null
        stroke.strokeWidth = 1.5f * d
        stroke.color = a(Color.WHITE, 0.35f)
        canvas.drawCircle(cx, cy, cr, stroke)
    }
}
