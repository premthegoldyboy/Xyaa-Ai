package ai.xyaa.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Sequential OpenAI-compatible fallback for Groq and DeepSeek. Gemini remains the primary provider. */
class ProviderFallbackClient(
    private val groqKey: String?,
    private val deepSeekKey: String?
) {
    data class Provider(val name: String, val endpoint: String, val key: String, val model: String)

    fun complete(prompt: String, onProvider: (String) -> Unit = {}): String {
        val providers = buildList {
            if (!groqKey.isNullOrBlank()) add(
                Provider("Groq", "https://api.groq.com/openai/v1/chat/completions",
                    groqKey, "openai/gpt-oss-120b")
            )
            if (!deepSeekKey.isNullOrBlank()) add(
                Provider("DeepSeek", "https://api.deepseek.com/chat/completions",
                    deepSeekKey, "deepseek-chat")
            )
        }
        if (providers.isEmpty()) throw IllegalStateException("No backup AI provider is configured.")
        var last: Throwable? = null
        for (provider in providers) {
            onProvider(provider.name)
            try {
                return request(provider, prompt)
            } catch (error: Throwable) {
                last = error
            }
        }
        throw IllegalStateException("All configured backup AI providers failed.", last)
    }

    private fun request(provider: Provider, prompt: String): String {
        val connection = (URL(provider.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = 25000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + provider.key)
        }
        try {
            val body = JSONObject()
                .put("model", provider.model)
                .put("temperature", 0.35)
                .put("max_tokens", if (prompt.contains("complete document content", ignoreCase = true)) 4096 else 1024)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content",
                        "You are XYAA, pronounced Zyaa, an Android AI assistant. Follow the user's actual requested action, deliverable, format, scope, and constraints; do not substitute advice for an explicitly requested artifact. For PDF/document requests, write the complete subject-specific document for the app's PDF export workflow and never tell the user to copy text to create the file. Research factual/current product specifications, distinguish model and regional variants, cite sources when available, and never invent facts. Be honest about actions the app has not performed."))
                    .put(JSONObject().put("role", "user").put("content", prompt)))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("${provider.name} HTTP $status: ${detail.take(300)}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            val content = AssistantTextFormatter.forRichDisplay(message?.optString("content").orEmpty()).trim()
            if (content.isBlank()) throw IllegalStateException("${provider.name} returned an empty user-facing response.")
            return content
        } finally {
            connection.disconnect()
        }
    }
}
