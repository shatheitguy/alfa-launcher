package com.alfa.launcher

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Built-in on-device AI. ALFA ships llama.cpp's `llama-server` (compiled for arm64 and packaged
 * as libllamaserver.so so Android extracts it to an executable location). This object downloads
 * GGUF models, starts the server on 127.0.0.1:[PORT] with the chosen model, and stops it after a
 * period of inactivity to give the RAM back. ALFA Assistant then talks to it like any
 * OpenAI-compatible server, completely offline.
 */
object LocalLlm {

    const val PORT = 8765
    val BASE = "http://127.0.0.1:$PORT"

    /** How long the model stays in RAM after the last message, in minutes; -1 = until stopped. */
    val KEEP_OPTIONS = listOf(30, 120, 240, -1)
    const val DEFAULT_KEEP_MIN = 240

    /** Context window sizes offered (tokens). Bigger remembers more of the chat but uses more RAM. */
    val CONTEXT_OPTIONS = listOf(4096, 8192, 16384, 32768)
    const val DEFAULT_CONTEXT = 8192

    fun keepMinutes(c: Context) = prefs(c).getInt("llm_keep_min", DEFAULT_KEEP_MIN)
    fun setKeepMinutes(c: Context, min: Int) {
        prefs(c).edit().putInt("llm_keep_min", min).apply()
        idleStopMs = if (min < 0) -1L else min * 60_000L
    }

    fun contextSize(c: Context) = prefs(c).getInt("llm_ctx", DEFAULT_CONTEXT)
    fun setContextSize(c: Context, tokens: Int) {
        prefs(c).edit().putInt("llm_ctx", tokens).apply()
        if (isRunning() && runningContext != tokens) stop()   // restarts with the new size on next use
    }

    fun keepLabel(min: Int) = when {
        min < 0 -> "until you stop it"
        min >= 60 -> "${min / 60} h"
        else -> "$min min"
    }

    @Volatile private var idleStopMs = DEFAULT_KEEP_MIN * 60_000L
    @Volatile var runningContext = 0
        private set

    class Model(val id: String, val name: String, val sizeMb: Int, val url: String, val note: String) {
        val file get() = "$id.gguf"
    }

    /** Small instruct models with good tool calling that run on a phone CPU. */
    val CATALOG = listOf(
        Model("qwen2.5-1.5b-instruct-q4_k_m", "Qwen2.5 1.5B Instruct", 1120,
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "Recommended — fast, follows tools well"),
        Model("qwen2.5-3b-instruct-q4_k_m", "Qwen2.5 3B Instruct", 2100,
            "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            "Smarter, slower — needs ~4 GB free RAM"),
        Model("llama-3.2-3b-instruct-q4_k_m", "Llama 3.2 3B Instruct", 2020,
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            "Meta's small model, good all-rounder"),
        Model("qwen2.5-0.5b-instruct-q4_k_m", "Qwen2.5 0.5B Instruct", 400,
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "Tiny and quick, basic commands only"),
    )

    private fun prefs(c: Context) = c.getSharedPreferences("alfa", Context.MODE_PRIVATE)

    fun dir(c: Context): File = (c.getExternalFilesDir("models") ?: File(c.filesDir, "models")).apply { mkdirs() }

    fun binary(c: Context) = File(c.applicationInfo.nativeLibraryDir, "libllamaserver.so")

    /** False on phones without the bundled 64-bit ARM server. */
    fun supported(c: Context) = binary(c).exists()

    // ---------------- models on disk ----------------

    fun selected(c: Context): String? = prefs(c).getString("llm_model", null)

    fun select(c: Context, file: String) {
        prefs(c).edit().putString("llm_model", file).apply()
        if (runningModel != null && runningModel != file) stop()
    }

    /** Finished .gguf files (anything still downloading is excluded). */
    fun installed(c: Context): List<File> =
        dir(c).listFiles { f -> f.isFile && f.name.endsWith(".gguf") && downloadId(c, f.name) < 0 }
            ?.sortedBy { it.name } ?: emptyList()

    fun isInstalled(c: Context, file: String) = installed(c).any { it.name == file }

    fun delete(c: Context, file: String) {
        if (runningModel == file) stop()
        File(dir(c), file).delete()
        if (selected(c) == file) prefs(c).edit().remove("llm_model").apply()
    }

    // ---------------- downloads ----------------

    private fun downloadId(c: Context, file: String): Long {
        val id = prefs(c).getLong("llm_dl_$file", -1L)
        if (id < 0) return -1L
        val dm = c.getSystemService(DownloadManager::class.java) ?: return -1L
        dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
            if (cur.moveToFirst()) {
                val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (st != DownloadManager.STATUS_SUCCESSFUL && st != DownloadManager.STATUS_FAILED) return id
                if (st == DownloadManager.STATUS_FAILED) File(dir(c), file).delete()
            }
        }
        prefs(c).edit().remove("llm_dl_$file").apply()
        return -1L
    }

    /** 0..100 while downloading, -1 when not downloading. */
    fun progress(c: Context, file: String): Int {
        val id = downloadId(c, file)
        if (id < 0) return -1
        val dm = c.getSystemService(DownloadManager::class.java) ?: return -1
        dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
            if (cur.moveToFirst()) {
                val done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                return if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 99) else 0
            }
        }
        return 0
    }

    fun anyDownloading(c: Context) = CATALOG.any { progress(c, it.file) >= 0 } ||
        (prefs(c).getString("llm_custom_file", null)?.let { progress(c, it) >= 0 } ?: false)

    fun download(c: Context, url: String, file: String): Long {
        val dm = c.getSystemService(DownloadManager::class.java) ?: throw IllegalStateException("no download manager")
        File(dir(c), file).delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("ALFA AI model")
            .setDescription(file)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setDestinationInExternalFilesDir(c, "models", file)
        val id = dm.enqueue(req)
        prefs(c).edit().putLong("llm_dl_$file", id).apply()
        return id
    }

    fun cancelDownload(c: Context, file: String) {
        val id = prefs(c).getLong("llm_dl_$file", -1L)
        if (id >= 0) c.getSystemService(DownloadManager::class.java)?.remove(id)
        prefs(c).edit().remove("llm_dl_$file").apply()
        File(dir(c), file).delete()
    }

    // ---------------- the server process ----------------

    @Volatile private var process: Process? = null
    @Volatile var runningModel: String? = null
        private set
    private val log = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastUse = 0L

    fun isRunning() = process?.isAlive == true

    fun logText(): String = synchronized(log) { log.joinToString("\n") }

    private fun addLog(s: String) = synchronized(log) {
        log.addLast(s)
        while (log.size > 200) log.removeFirst()
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            if (!isRunning()) return
            val limit = idleStopMs
            if (limit > 0 && System.currentTimeMillis() - lastUse > limit) { addLog("[alfa] idle, stopping to free memory"); stop(); return }
            main.postDelayed(this, 60_000L)
        }
    }

    @Synchronized
    fun start(c: Context, file: String) {
        val ctxSize = contextSize(c)
        if (isRunning() && runningModel == file && runningContext == ctxSize) return
        stop()
        val keep = keepMinutes(c)
        idleStopMs = if (keep < 0) -1L else keep * 60_000L
        val bin = binary(c)
        if (!bin.exists()) throw IllegalStateException("The on-device AI engine isn't available for this phone (needs 64-bit ARM).")
        val model = File(dir(c), file)
        if (!model.exists()) throw IllegalStateException("Model $file isn't downloaded.")
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
        val cmd = listOf(bin.absolutePath, "-m", model.absolutePath, "--host", "127.0.0.1", "--port", PORT.toString(),
            "-c", ctxSize.toString(), "-t", threads.toString(), "-np", "1", "--jinja")
        addLog("[alfa] starting: ${cmd.drop(1).joinToString(" ")}")
        val p = ProcessBuilder(cmd).redirectErrorStream(true).directory(c.filesDir).start()
        process = p
        runningModel = file
        runningContext = ctxSize
        lastUse = System.currentTimeMillis()
        Thread {
            try { p.inputStream.bufferedReader().forEachLine { addLog(it) } } catch (e: Exception) {}
            addLog("[alfa] server exited (${try { p.exitValue() } catch (e: Exception) { "?" }})")
            if (process === p) { process = null; runningModel = null; runningContext = 0 }
        }.apply { isDaemon = true }.start()
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, 60_000L)
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
        runningModel = null
        runningContext = 0
        main.removeCallbacks(idleCheck)
    }

    fun healthy(): Boolean = try {
        val h = URL("$BASE/health").openConnection() as HttpURLConnection
        h.connectTimeout = 1500; h.readTimeout = 2000
        val ok = h.responseCode == 200
        h.disconnect(); ok
    } catch (e: Exception) { false }

    /**
     * Blocking: makes sure the server is up with the selected model, starting it and waiting for
     * the model to load if needed. [onStarting] is called once if a cold start happens.
     */
    fun ensureRunning(c: Context, onStarting: () -> Unit = {}) {
        lastUse = System.currentTimeMillis()
        val file = selected(c) ?: installed(c).firstOrNull()?.name
            ?: throw IllegalStateException("No on-device model yet. Download one in ALFA OS Settings → ALFA Assistant.")
        if (isRunning() && runningModel == file && runningContext == contextSize(c) && healthy()) return
        onStarting()
        start(c, file)
        val deadline = System.currentTimeMillis() + 180_000L
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) throw IllegalStateException("The on-device AI stopped while loading. " +
                logText().lines().takeLast(3).joinToString(" "))
            if (healthy()) return
            Thread.sleep(600)
        }
        throw IllegalStateException("The model took too long to load.")
    }

    fun touch() { lastUse = System.currentTimeMillis() }
}
