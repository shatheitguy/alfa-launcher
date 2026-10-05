package com.alfa.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Saved ALFA Assistant conversations: one JSON file per chat in the app's private storage,
 * so chats survive restarts and can be reopened from the history list.
 */
object ChatStore {

    class Chat(val id: String, var title: String, var updated: Long, val history: JSONArray)

    private const val MAX_CHATS = 100

    private fun prefs(c: Context) = c.getSharedPreferences("alfa", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "chats").apply { mkdirs() }
    private fun file(c: Context, id: String) = File(dir(c), "$id.json")

    fun newChat(): Chat = Chat(UUID.randomUUID().toString(), "", System.currentTimeMillis(), JSONArray())

    /** The chat that was open last time, or a fresh one. */
    fun lastOrNew(c: Context): Chat =
        prefs(c).getString("chat_last", null)?.let { load(c, it) } ?: newChat()

    fun load(c: Context, id: String): Chat? = try {
        val o = JSONObject(file(c, id).readText())
        Chat(o.getString("id"), o.optString("title"), o.optLong("updated"), o.optJSONArray("history") ?: JSONArray())
    } catch (e: Exception) { null }

    fun save(c: Context, chat: Chat) {
        if (chat.history.length() == 0) return
        val o = JSONObject().put("id", chat.id).put("title", chat.title).put("updated", chat.updated).put("history", chat.history)
        val f = file(c, chat.id)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
        prefs(c).edit().putString("chat_last", chat.id).apply()
        prune(c)
    }

    fun setOpen(c: Context, id: String?) = prefs(c).edit().putString("chat_last", id).apply()

    /** Newest first; only id / title / time are read for the list. */
    fun list(c: Context): List<Chat> =
        (dir(c).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> load(c, f.name.removeSuffix(".json")) }
            .sortedByDescending { it.updated }

    fun delete(c: Context, id: String) {
        file(c, id).delete()
        if (prefs(c).getString("chat_last", null) == id) setOpen(c, null)
    }

    fun deleteAll(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
        setOpen(c, null)
    }

    private fun prune(c: Context) {
        val files = dir(c).listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= MAX_CHATS) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_CHATS).forEach { it.delete() }
    }
}

/** Long-term memory: short facts the user asked ALFA to remember, included in every chat. */
object AssistantMemory {

    private const val KEY = "ai_memory"
    private const val MAX_ITEMS = 50

    private fun prefs(c: Context) = c.getSharedPreferences("alfa", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("ai_memory_on", true)

    fun items(c: Context): List<String> =
        prefs(c).getString(KEY, "")!!.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun setItems(c: Context, items: List<String>) =
        prefs(c).edit().putString(KEY, items.map { it.trim() }.filter { it.isNotEmpty() }.takeLast(MAX_ITEMS).joinToString("\n")).apply()

    fun add(c: Context, fact: String): Boolean {
        val f = fact.trim().replace('\n', ' ')
        if (f.isEmpty()) return false
        val cur = items(c)
        if (cur.any { it.equals(f, ignoreCase = true) }) return true
        setItems(c, cur + f)
        return true
    }

    /** Text for the system prompt, or "" when memory is off or empty. */
    fun promptBlock(c: Context, name: String): String {
        if (!enabled(c)) return ""
        val all = items(c)
        if (all.isEmpty()) return ""
        return "Things $name asked you to remember:\n" + all.joinToString("\n") { "- $it" }
    }
}
