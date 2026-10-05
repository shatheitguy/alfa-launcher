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

    /** The open conversation; saved after every reply so it survives restarts. */
    var chat: ChatStore.Chat = ChatStore.lastOrNew(ctx)
        private set
    private val history: JSONArray get() = chat.history
    private val recent = ArrayDeque<String>()
    var lastUsed = 0L
        private set

    /** Tokens the last request filled (prompt + reply), as reported by the server; 0 = unknown. */
    @Volatile var contextUsed = 0
        private set

    /** The model's context window in tokens. */
    val contextSize: Int get() =
        if (engine == "builtin") LocalLlm.contextSize(ctx) else prefs.getInt("ai_server_ctx", 8192)

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
            When a request maps to a tool, call the tool rather than describing how to do it. Only call a tool that clearly matches what was asked; never substitute an unrelated one. If the user is chatting or asking a question, just answer it yourself in conversation. Never invent details the user did not give (names, numbers, Wi-Fi names, passwords, links, message text): ask for them instead; only search the web when they explicitly ask you to search. Do several steps in one go when asked. If no tool can do what was asked, say so plainly in one sentence and suggest the closest option.
            Reply briefly, one to three short sentences, in plain text without markdown, because replies are shown in a small panel and read aloud. After acting, confirm what you did.
        """.trimIndent().let { base ->
            val mem = AssistantMemory.promptBlock(ctx, name)
            if (mem.isEmpty()) base else "$base\n\n$mem"
        }
    }

    // ---------------- context window ----------------

    /** Tokens kept free for the model's answer. */
    private val replyReserve = 1024
    /** Older turns beyond this are dropped from the saved chat file too. */
    private val maxSavedTurns = 60

    /** Rough token count (~3.5 characters per token); only used to decide what fits. */
    private fun est(s: String) = (s.length * 2 + 6) / 7

    /** History split into turns that each start at a user message, so tool calls stay with their results. */
    private fun turns(): List<IntRange> {
        val starts = (0 until history.length()).filter { history.optJSONObject(it)?.optString("role") == "user" }
        return starts.mapIndexed { i, s -> s until (starts.getOrNull(i + 1) ?: history.length()) }
    }

    /**
     * The newest turns that fit in [budget] tokens. The current turn is always kept. Sending the whole
     * history used to overflow the model's context after a few exchanges, which llama-server rejects
     * (the "HTTP 500" error).
     */
    private fun fitHistory(budget: Int): JSONArray {
        val kept = ArrayDeque<IntRange>()
        var used = 0
        for (t in turns().asReversed()) {
            val cost = t.sumOf { est(history.get(it).toString()) }
            if (kept.isNotEmpty() && used + cost > budget) break
            kept.addFirst(t)
            used += cost
        }
        val out = JSONArray()
        for (t in kept) for (i in t) out.put(history.get(i))
        return out
    }

    private fun dropOldTurns() {
        val t = turns()
        if (t.size <= maxSavedTurns) return
        repeat(t[t.size - maxSavedTurns].first) { history.remove(0) }
    }

    private fun isContextError(code: Int, body: String): Boolean {
        val b = body.lowercase(Locale.ROOT)
        return (code == 400 || code == 500 || code == 413) &&
            (b.contains("context") || b.contains("too long") || b.contains("exceed") || b.contains("n_ctx") || b.contains("tokens"))
    }

    // ---------------- conversations ----------------

    /** Starts a new, empty chat (the previous one stays in the history list). */
    fun newChat() {
        recent.clear()
        contextUsed = 0
        chat = ChatStore.newChat()
        ChatStore.setOpen(ctx, null)
    }

    /** Reopens a saved chat. */
    fun open(id: String): Boolean {
        val c = ChatStore.load(ctx, id) ?: return false
        recent.clear()
        contextUsed = 0
        chat = c
        ChatStore.setOpen(ctx, id)
        return true
    }

    private fun persist(firstUserText: String) {
        if (chat.title.isBlank()) chat.title = firstUserText.lineSequence().first().take(60)
        chat.updated = System.currentTimeMillis()
        dropOldTurns()
        try { ChatStore.save(ctx, chat) } catch (e: Exception) { /* storage full: keep chatting */ }
    }

    private fun timeNote(): String =
        "Current local time: " + SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US).format(Date()) + "."

    private fun tools(offered: List<ToolSpec>): JSONArray {
        val arr = JSONArray()
        for (spec in offered) {
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
        // only offer the actions this request can need (small models pick far better from a short list)
        // follow-up answers ("HomeNet, password abc") keep the intent of the previous request
        recent.addLast(text); while (recent.size > 2) recent.removeFirst()
        val intent = recent.joinToString(" ")
        val offered = AssistantTools.relevant(intent)
        history.put(JSONObject().put("role", "user").put("content", "$text\n\n(${timeNote()})"))
        val system = systemPrompt()
        val toolJson = if (offered.isNotEmpty()) tools(offered) else null
        val fixed = est(system) + (toolJson?.let { est(it.toString()) } ?: 0)
        var shrink = 1.0
        var steps = 0
        while (steps < 8) {
            val budget = ((contextSize - fixed - replyReserve) * shrink).toInt().coerceAtLeast(256)
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            val fitted = fitHistory(budget)
            for (i in 0 until fitted.length()) msgs.put(fitted.get(i))
            val body = JSONObject().put("model", modelName).put("messages", msgs).put("stream", false)
            if (toolJson != null) body.put("tools", toolJson)   // no tools = just talk
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
                // The conversation no longer fits: send less history and try again.
                if (isContextError(code, txt) && shrink > 0.15 && fitted.length() > 1) {
                    shrink /= 2
                    continue
                }
                val hint = when {
                    isContextError(code, txt) ->
                        " (the message is too long for this model's context window; start a new chat or pick a larger context in ALFA OS Settings)"
                    txt.contains("does not support tools", true) || txt.contains("tool", true) && code == 400 ->
                        " (this model may not support tool calling; try llama3.1, qwen2.5 or mistral-nemo)"
                    else -> ""
                }
                val log = if (builtin) LocalLlm.logText().lines().filter { it.isNotBlank() }.takeLast(2).joinToString(" ") else ""
                throw RuntimeException("Server returned HTTP $code$hint: ${txt.take(160)}" + if (log.isNotEmpty()) "\nEngine: ${log.take(200)}" else "")
            }
            steps++
            if (builtin) LocalLlm.touch()
            val resp = JSONObject(txt)
            resp.optJSONObject("usage")?.let { u ->
                val total = u.optInt("total_tokens", u.optInt("prompt_tokens") + u.optInt("completion_tokens"))
                if (total > 0) contextUsed = total
            }
            val msg = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            // Some servers send content: null alongside tool calls; chat templates choke on null later.
            if (msg.isNull("content")) msg.put("content", "")
            history.put(msg)
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                persist(text)
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
                val out = runTool(fn.getString("name"), args, listener, intent)
                if (out.startsWith("ASK:")) {
                    // the action needs details the user hasn't given: ask them directly, don't let the model guess
                    val q = out.removePrefix("ASK:").trim()
                    history.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id"))
                        .put("name", fn.getString("name")).put("content", "Not done yet. Asked the user: $q"))
                    history.put(JSONObject().put("role", "assistant").put("content", q))
                    persist(text)
                    return q
                }
                history.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id"))
                    .put("name", fn.getString("name")).put("content", out))
            }
        }
        persist(text)
        return "I stopped after several steps. Tell me if you want me to continue."
    }

    private fun runTool(name: String, input: JSONObject, listener: Listener, request: String): String {
        val spec = AssistantTools.byName(name) ?: return "Unknown tool $name."
        if (!AssistantTools.matches(name, request))
            return "Not done: \"$name\" does not match what the user asked. Use a different tool that fits the request, or reply without a tool."
        for (r in spec.required) if (!input.has(r)) return "Missing required field '$r'."
        AssistantTools.currentRequest = request
        return try {
            val r = spec.run(ctx, input)
            if (!r.startsWith("ASK:")) listener.onAction(label(name, input))
            r
        } catch (e: Exception) { "Failed: ${e.message ?: e.javaClass.simpleName}" }
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
        "remember" -> "Remembered"
        else -> name.replace('_', ' ')
    }

    fun reset() = newChat()
}
