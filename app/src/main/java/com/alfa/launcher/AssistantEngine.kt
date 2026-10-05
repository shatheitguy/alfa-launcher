package com.alfa.launcher

import android.content.Context
import android.os.Build
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ALFA Assistant's brain. Two providers:
 *  - Claude, through the official Anthropic SDK (manual tool-use loop)
 *  - the user's own OpenAI-compatible server (Ollama, LM Studio, vLLM, ...) over plain HTTP
 * Both run the same [AssistantTools]. Call [send] from a background thread.
 */
class AssistantEngine(private val ctx: Context) {

    /** Progress callbacks (called on the worker thread). */
    interface Listener {
        fun onAction(label: String)
    }

    private val prefs = ctx.getSharedPreferences("alfa", Context.MODE_PRIVATE)

    val provider: String get() = prefs.getString("ai_provider", "claude") ?: "claude"

    fun configured(): Boolean = when (provider) {
        "claude" -> !prefs.getString("ai_claude_key", "").isNullOrBlank()
        else -> !prefs.getString("ai_server_url", "").isNullOrBlank()
    }

    private fun systemPrompt(): String {
        val name = Profile.name(ctx).ifEmpty { "the user" }
        return """
            You are ALFA, the assistant built into the ALFA launcher on $name's Android phone (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}).
            You help by acting through your tools: opening apps, changing ALFA's look and settings, setting timers and alarms, opening system settings, and running quick IT checks (ping, DNS, SSL, network, device status, Wake-on-LAN). $name is an IT professional, so technical answers are welcome.
            When a request maps to a tool, use it rather than describing how to do it. Do several steps in one go when asked. If no tool can do what was asked, say so plainly in one sentence and suggest the closest option.
            Reply briefly, one to three short sentences, in plain text without markdown, because replies are shown in a small chat panel and may be read aloud. After acting, confirm what you did.
        """.trimIndent()
    }

    private fun timeNote(): String =
        "Current local time: " + SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US).format(Date()) + "."

    // ======================= Claude (Anthropic SDK) =======================

    private var claude: AnthropicClient? = null
    private var claudeKey: String? = null
    private val claudeHistory = ArrayList<MessageParam>()

    private fun claudeClient(): AnthropicClient {
        val key = prefs.getString("ai_claude_key", "")!!.trim()
        if (claude == null || key != claudeKey) {
            claude = AnthropicOkHttpClient.builder().apiKey(key).build()
            claudeKey = key
        }
        return claude!!
    }

    private fun jsonToMap(o: JSONObject): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        for (k in o.keys()) m[k] = unwrap(o.get(k))
        return m
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        is JSONObject -> jsonToMap(v)
        is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    private val claudeTools: List<Tool> by lazy {
        AssistantTools.all.map { spec ->
            val props = Tool.InputSchema.Properties.builder()
            for (k in spec.params.keys()) props.putAdditionalProperty(k, JsonValue.from(jsonToMap(spec.params.getJSONObject(k))))
            val schema = Tool.InputSchema.builder().properties(props.build())
            if (spec.required.isNotEmpty()) schema.required(spec.required)
            Tool.builder().name(spec.name).description(spec.description).inputSchema(schema.build()).build()
        }
    }

    private fun sendClaude(text: String, listener: Listener): String {
        val client = claudeClient()
        val model = prefs.getString("ai_claude_model", "")?.trim().orEmpty().ifEmpty { "claude-opus-5-5" }
        claudeHistory.add(MessageParam.builder().role(MessageParam.Role.USER).content("$text\n\n(${timeNote()})").build())
        repeat(8) {
            val builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(4096L)
                .system(systemPrompt())
                .messages(claudeHistory)
                // short, snappy assistant turns
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            claudeTools.forEach { builder.addTool(it) }
            if (model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5") || model.startsWith("claude-sonnet-5-5")) {
                // server-side refusal fallback: a declined request is retried on a suitable model
                builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            val resp: Message = client.messages().create(builder.build())
            // keep the full assistant turn (incl. thinking blocks) unchanged in history
            claudeHistory.add(MessageParam.builder().role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(resp.content().map { it.toParam() }).build())

            val toolUses = resp.content().mapNotNull { it.toolUse().orElse(null) }
            val reply = resp.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()
            if (toolUses.isEmpty()) {
                val stop = resp.stopReason().orElse(null)?.toString()?.lowercase(Locale.ROOT) ?: ""
                return when {
                    reply.isNotEmpty() -> reply
                    stop.contains("refusal") -> "I can't help with that one."
                    else -> "Done."
                }
            }
            val results = toolUses.map { tu ->
                val input = try {
                    @Suppress("UNCHECKED_CAST")
                    JSONObject(tu._input().convert(Map::class.java) as Map<String, Any?>)
                } catch (e: Exception) { JSONObject() }
                val out = runTool(tu.name(), input, listener)
                ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(tu.id()).content(out.first).isError(!out.second).build())
            }
            // all results of one turn go back in a single user message
            claudeHistory.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build())
        }
        return "I stopped after several steps. Tell me if you want me to continue."
    }

    // ======================= Own server (OpenAI-compatible) =======================

    private val serverHistory = JSONArray()

    private fun serverTools(): JSONArray {
        val arr = JSONArray()
        for (spec in AssistantTools.all) {
            val params = JSONObject().put("type", "object").put("properties", spec.params)
            if (spec.required.isNotEmpty()) params.put("required", JSONArray(spec.required))
            arr.put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", spec.name).put("description", spec.description).put("parameters", params)))
        }
        return arr
    }

    private fun sendServer(text: String, listener: Listener): String {
        var base = prefs.getString("ai_server_url", "")!!.trim().trimEnd('/')
        if (!base.endsWith("/v1")) base = "$base/v1"
        val model = prefs.getString("ai_server_model", "")?.trim().orEmpty().ifEmpty { "llama3.1" }
        val key = prefs.getString("ai_server_key", "")?.trim().orEmpty()
        serverHistory.put(JSONObject().put("role", "user").put("content", "$text\n\n(${timeNote()})"))
        repeat(8) {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
            for (i in 0 until serverHistory.length()) msgs.put(serverHistory.get(i))
            val body = JSONObject().put("model", model).put("messages", msgs).put("tools", serverTools()).put("stream", false)
            val c = URL("$base/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 120000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (key.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $key")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code !in 200..299) throw RuntimeException("Server returned HTTP $code: ${txt.take(200)}")
            val msg = JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            serverHistory.put(msg)
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                return msg.optString("content", "").trim().ifEmpty { "Done." }
            }
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val argsRaw = fn.opt("arguments")
                val args = when (argsRaw) {
                    is JSONObject -> argsRaw
                    is String -> try { JSONObject(argsRaw) } catch (e: Exception) { JSONObject() }
                    else -> JSONObject()
                }
                val out = runTool(fn.getString("name"), args, listener)
                serverHistory.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id"))
                    .put("name", fn.getString("name")).put("content", out.first))
            }
        }
        return "I stopped after several steps. Tell me if you want me to continue."
    }

    // ======================= shared =======================

    /** Runs a tool; returns (result text, success). */
    private fun runTool(name: String, input: JSONObject, listener: Listener): Pair<String, Boolean> {
        val spec = AssistantTools.byName(name) ?: return Pair("Unknown tool $name.", false)
        for (r in spec.required) if (!input.has(r)) return Pair("Missing required field '$r'.", false)
        listener.onAction(label(name, input))
        return try {
            Pair(spec.run(ctx, input), true)
        } catch (e: Exception) {
            Pair("Failed: ${e.message ?: e.javaClass.simpleName}", false)
        }
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

    /** Blocking: one user turn, possibly several tool steps. */
    fun send(text: String, listener: Listener): String =
        if (provider == "claude") sendClaude(text, listener) else sendServer(text, listener)

    fun reset() {
        claudeHistory.clear()
        while (serverHistory.length() > 0) serverHistory.remove(0)
    }
}
