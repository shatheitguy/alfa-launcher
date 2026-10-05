package com.alfa.launcher

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Speech for ALFA Assistant.
 *  - [startHotword] keeps listening (on-device recognizer when the phone has one) for the word
 *    "ALFA" and calls [Callbacks.onWake]; anything said after it in the same breath is passed on.
 *  - [listenCommand] captures one command with live partial text.
 * Must be used from the main thread.
 */
class VoiceListener(private val ctx: Context, private val cb: Callbacks) {

    interface Callbacks {
        fun onWake()
        fun onPartial(text: String)
        fun onLevel(rmsDb: Float)
        fun onCommand(text: String)
        fun onNoCommand()
    }

    private enum class Mode { OFF, HOTWORD, COMMAND }

    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var mode = Mode.OFF
    private var woke = false
    private val restart = Runnable { if (mode == Mode.HOTWORD) beginHotword() }

    companion object {
        private val WAKE = Regex("\\b(hey |ok |okay )?(alfa|alpha|alpa|alfah|elfa|alva)\\b[,.!?\\s]*", RegexOption.IGNORE_CASE)

        fun available(ctx: Context) = SpeechRecognizer.isRecognitionAvailable(ctx)
    }

    /** True when an on-device (offline, silent) recognizer is used for the always-listening loop. */
    var onDevice = false
        private set

    private fun recognizer(): SpeechRecognizer {
        sr?.let { return it }
        val r = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            onDevice = true
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        } else {
            onDevice = false
            SpeechRecognizer.createSpeechRecognizer(ctx)
        }
        r.setRecognitionListener(listener)
        sr = r
        return r
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        // finish quickly once you stop talking (default waits ~2 s of silence)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)

    fun startHotword() {
        if (mode == Mode.HOTWORD) return
        mode = Mode.HOTWORD
        main.removeCallbacks(restart)
        main.postDelayed(restart, 120)
    }

    private fun beginHotword() {
        woke = false
        try { recognizer().startListening(intent().putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)) } catch (e: Exception) {
            main.postDelayed(restart, 3000)
        }
    }

    /** [fresh] = the previous session already ended, so start right away without cancelling. */
    fun listenCommand(fresh: Boolean = false) {
        main.removeCallbacks(restart)
        mode = Mode.COMMAND
        woke = true
        if (!fresh) try { recognizer().cancel() } catch (e: Exception) {}
        main.postDelayed({
            if (mode == Mode.COMMAND) try { recognizer().startListening(intent()) } catch (e: Exception) { cb.onNoCommand() }
        }, if (fresh) 0L else 80L)
    }

    fun stop() {
        mode = Mode.OFF
        main.removeCallbacks(restart)
        try { sr?.cancel() } catch (e: Exception) {}
    }

    fun destroy() {
        stop()
        try { sr?.destroy() } catch (e: Exception) {}
        sr = null
    }

    private fun afterWake(text: String): String? {
        val m = WAKE.find(text) ?: return null
        return text.substring(m.range.last + 1).trim()
    }

    private fun texts(b: Bundle?): List<String> =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filterNotNull() ?: emptyList()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { if (woke) cb.onLevel(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = texts(partialResults).firstOrNull() ?: return
            when (mode) {
                Mode.HOTWORD -> {
                    val rest = afterWake(t) ?: return
                    if (!woke) { woke = true; cb.onWake() }
                    if (rest.isNotEmpty()) cb.onPartial(rest)
                }
                Mode.COMMAND -> cb.onPartial(t)
                Mode.OFF -> {}
            }
        }

        override fun onResults(results: Bundle?) {
            val all = texts(results)
            when (mode) {
                Mode.HOTWORD -> {
                    val hit = all.firstNotNullOfOrNull { afterWake(it) }
                    if (hit == null) { main.postDelayed(restart, 40); return }
                    if (!woke) { woke = true; cb.onWake() }
                    if (hit.length >= 2) { mode = Mode.COMMAND; cb.onCommand(hit) }
                    else listenCommand(fresh = true)   // just "ALFA" -> listen for the command straight away
                }
                Mode.COMMAND -> {
                    val t = all.firstOrNull()?.trim().orEmpty()
                    val cleaned = afterWake(t) ?: t
                    if (cleaned.isEmpty()) cb.onNoCommand() else cb.onCommand(cleaned)
                }
                Mode.OFF -> {}
            }
        }

        override fun onError(error: Int) {
            when (mode) {
                Mode.HOTWORD -> {
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { mode = Mode.OFF; return }
                    val delay = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 60L
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1200L
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 4000L
                        else -> 800L
                    }
                    main.postDelayed(restart, delay)
                }
                Mode.COMMAND -> cb.onNoCommand()
                Mode.OFF -> {}
            }
        }
    }
}
