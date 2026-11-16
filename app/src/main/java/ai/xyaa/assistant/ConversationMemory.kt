package ai.xyaa.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small, on-device conversation memory. It stores only recent user/assistant turns,
 * never API keys, and keeps a strict size bound to avoid unbounded preference growth.
 */
class ConversationMemory(context: Context) {
    private val prefs = context.getSharedPreferences("xyaa_conversation_memory", Context.MODE_PRIVATE)
    private val lock = Any()
    private val maxTurns = 24
    private val maxCharsPerMessage = 1800

    data class Turn(val role: String, val text: String, val timestamp: Long)

    fun remember(role: String, text: String) = synchronized(lock) {
        if (role !in setOf("user", "assistant") || text.isBlank()) return@synchronized
        val turns = readUnsafe().toMutableList()
        turns.add(Turn(role, text.take(maxCharsPerMessage), System.currentTimeMillis()))
        while (turns.size > maxTurns) turns.removeAt(0)
        val json = JSONArray()
        turns.forEach { turn ->
            json.put(JSONObject()
                .put("role", turn.role)
                .put("text", turn.text)
                .put("timestamp", turn.timestamp))
        }
        prefs.edit().putString("turns", json.toString()).apply()
    }

    /** Returns compact context for the next request; callers should treat it as untrusted user data. */
    fun contextForPrompt(): String = synchronized(lock) {
        val turns = readUnsafe().takeLast(12)
        if (turns.isEmpty()) return@synchronized ""
        turns.joinToString("\n") { "${it.role}: ${it.text}" }
            .take(9000)
    }

    fun clear() = synchronized(lock) { prefs.edit().remove("turns").apply() }

    fun recentTurns(): List<Turn> = synchronized(lock) { readUnsafe().toList() }

    private fun readUnsafe(): List<Turn> {
        val raw = prefs.getString("turns", null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index ->
                val item = json.optJSONObject(index) ?: return@mapNotNull null
                val role = item.optString("role")
                val text = item.optString("text")
                if (role !in setOf("user", "assistant") || text.isBlank()) null
                else Turn(role, text.take(maxCharsPerMessage), item.optLong("timestamp"))
            }.takeLast(maxTurns)
        }.getOrDefault(emptyList())
    }
}
