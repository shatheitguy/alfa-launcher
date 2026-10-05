package com.alfa.launcher

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ALFA Assistant's brain: talks to the user's own local AI server through the
 * OpenAI-compatible chat API (Ollama, LM Studio, llama.cpp server, vLLM, LocalAI, ...)
 * and runs [AssistantTools] when the model asks for them. Call [send] from a background thread.
 */
class AssistantEngine(private val ctx: Context) {

    /** Progress callbacks (called on the worker thread). */
    interface Listener {
        fun onAction(label: String)
    }

    private val prefs = ctx.getSharedPreferences("alfa", Context.MODE_PRIVATE)
    private val history = JSONArray()
    var lastUsed = 0L
        private set

    /** "builtin" = ALFA's on-device llama-server, "server" = the user's own server on the network. */
    val engine: String get() = prefs.getString("ai_engine", "builtin") ?: "builtin"

    fun configured(): Boolean =
        if (engine == "builtin") LocalLlm.supported(ctx) && LocalLlm.installed(ctx).isNotEmpty()
        else !prefs.getString("ai_server_url", "").isNullOrBlank()

    val modelName: String get() =
        if (engine == "builtin") (LocalLlm.selected(ctx) ?: LocalLlm.installed(ctx).firstOrNull()?.name ?: "no model").removeSuffix(".gguf")
        else prefs.getString("ai_server_model", "")?.trim().orEmpty().ifEmpty { "llama3.1" }

    companion object {
        /** "http://host:port" or ".../v1" -> ".../v1" */
        fun baseUrl(raw: String): String {
            var b = raw.trim().trimEnd('/')
            if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://$b"
            if (!b.endsWith("/v1")) b = "$b/v1"
            return b
        }

        /** Blocking: lists model ids from GET {base}/models. Throws on failure. */
        fun listModels(url: String, key: String): List<String> {
            val c = URL(baseUrl(url) + "/models").openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 10000
            if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${key.trim()}")
            try {
                val code = c.responseCode
                val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) throw RuntimeException("HTTP $code ${txt.take(120)}")
                val data = JSONObject(txt).optJSONArray("data") ?: JSONArray()
                return (0 until data.length()).map { data.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }
            } finally {
                c.disconnect()
            }
        }
    }

    private fun systemPrompt(): String {
        val name = Profile.name(ctx).ifEmpty { "the user" }
        return """
            You are ALFA, the assistant built into the ALFA launcher on $name's Android phone (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}).
            You help by acting through your tools: opening apps, changing ALFA's look and settings, setting timers and alarms, opening system settings, and running quick IT checks (ping, DNS, SSL, network, device status, Wake-on-LAN). $name is an IT professional, so technical answers are welcome.
            When a request maps to a tool, call the tool rather than describing how to do it. Do several steps in one go when asked. If no tool can do what was asked, say so plainly in one sentence and suggest the closest option.
            Reply briefly, one to three short sentences, in plain text without markdown, because replies are shown in a small panel and read aloud. After acting, confirm what you did.
        """.trimIndent()
    }

    private fun timeNote(): String =
        "Current local time: " + SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US).format(Date()) + "."

    private fun tools(): JSONArray {
        val arr = JSONArray()
        for (spec in AssistantTools.all) {
            val params = JSONObject().put("type", "object").put("properties", spec.params)
            if (spec.required.isNotEmpty()) params.put("required", JSONArray(spec.required))
            arr.put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", spec.name).put("description", spec.description).put("parameters", params)))
        }
        return arr
    }

    /** Blocking: one user turn, possibly several tool steps. Returns the reply text. */
    fun send(text: String, listener: Listener): String {
        lastUsed = System.currentTimeMillis()
        val builtin = engine == "builtin"
        if (builtin) LocalLlm.ensureRunning(ctx) { listener.onAction("Starting on-device AI (first reply takes longer)") }
        val base = if (builtin) baseUrl(LocalLlm.BASE) else baseUrl(prefs.getString("ai_server_url", "")!!)
        val key = if (builtin) "" else prefs.getString("ai_server_key", "")?.trim().orEmpty()
        history.put(JSONObject().put("role", "user").put("content", "$text\n\n(${timeNote()})"))
        repeat(8) {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
            for (i in 0 until history.length()) msgs.put(history.get(i))
            val body = JSONObject().put("model", modelName).put("messages", msgs).put("tools", tools()).put("stream", false)
            val c = URL("$base/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 180000          // local models can be slow on first load
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (key.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $key")
            val txt: String
            val code: Int
            try {
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                code = c.responseCode
                txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            } finally {
                c.disconnect()
            }
            if (code !in 200..299) {
                val hint = if (txt.contains("does not support tools", true) || txt.contains("tool", true) && code == 400)
                    " (this model may not support tool calling; try llama3.1, qwen2.5 or mistral-nemo)" else ""
                throw RuntimeException("Server returned HTTP $code$hint: ${txt.take(160)}")
            }
            if (builtin) LocalLlm.touch()
            val msg = JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            // some servers send content: null alongside tool calls; keep the turn as-is
            history.put(msg)
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                return msg.optString("content", "").let { if (it == "null") "" else it }.trim().ifEmpty { "Done." }
            }
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val args = when (val raw = fn.opt("arguments")) {
                    is JSONObject -> raw
                    is String -> try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
                    else -> JSONObject()
                }
                val out = runTool(fn.getString("name"), args, listener)
                history.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id"))
                    .put("name", fn.getString("name")).put("content", out))
            }
        }
        return "I stopped after several steps. Tell me if you want me to continue."
    }

    private fun runTool(name: String, input: JSONObject, listener: Listener): String {
        val spec = AssistantTools.byName(name) ?: return "Unknown tool $name."
        for (r in spec.required) if (!input.has(r)) return "Missing required field '$r'."
        listener.onAction(label(name, input))
        return try { spec.run(ctx, input) } catch (e: Exception) { "Failed: ${e.message ?: e.javaClass.simpleName}" }
    }

    private fun label(name: String, a: JSONObject): String = when (name) {
        "open_app" -> "Opening ${a.optString("name")}"
        "find_apps" -> "Looking up apps"
        "set_accent" -> "Accent → ${a.optString("color")}"
        "set_wallpaper" -> "Wallpaper → ${a.optString("style")}"
        "set_icon_style" -> "Icons → ${a.optString("style")}"
        "set_setting" -> "${a.optString("setting")} → ${if (a.optBoolean("on")) "on" else "off"}"
        "edit_home" -> "${a.optString("action")} ${a.optString("app")} (${a.optString("place")})"
        "open_it_tool" -> "Opening ${a.optString("tool")} tool"
        "open_settings_panel" -> "Opening ${a.optString("panel")} settings"
        "set_timer" -> "Timer ${a.optInt("minutes")} min"
        "set_alarm" -> String.format(Locale.US, "Alarm %02d:%02d", a.optInt("hour"), a.optInt("minute"))
        "ping" -> "Pinging ${a.optString("host")}"
        "dns_lookup" -> "Resolving ${a.optString("host")}"
        "ssl_check" -> "Checking SSL for ${a.optString("host")}"
        "wake_device" -> "Waking ${a.optString("name")}"
        "web_search" -> "Searching “${a.optString("query")}”"
        else -> name.replace('_', ' ')
    }

    fun reset() {
        while (history.length() > 0) history.remove(0)
    }
}
