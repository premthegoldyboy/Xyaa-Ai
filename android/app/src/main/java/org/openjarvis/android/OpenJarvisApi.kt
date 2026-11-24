package org.openjarvis.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatTurn(val role: String, val content: String)
data class JarvisAgent(val id: String, val name: String, val type: String, val status: String)
data class JarvisTool(val name: String, val description: String, val category: String, val configured: Boolean)
data class MemoryStats(val entries: Int, val backend: String, val status: String)

object OpenJarvisApi {
    private fun connection(baseUrl: String, path: String, apiKey: String): HttpURLConnection =
        (URL(baseUrl.trim().trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 120_000
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer " + apiKey)
        }

    private fun readResponse(connection: HttpURLConnection): String {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val detail = runCatching { JSONObject(response).optString("detail") }.getOrNull()
            throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "OpenJarvis returned HTTP " + status)
        }
        return response
    }

    private fun request(
        baseUrl: String,
        path: String,
        apiKey: String,
        method: String = "GET",
        body: JSONObject? = null
    ): String {
        val connection = connection(baseUrl, path, apiKey).apply {
            requestMethod = method
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun complete(baseUrl: String, apiKey: String, model: String, turns: List<ChatTurn>): String =
        withContext(Dispatchers.IO) {
            val messages = JSONArray().apply {
                turns.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            }
            val body = JSONObject().put("model", model)
                .put("messages", messages).put("temperature", 0.7)
                .put("max_tokens", 1024).put("stream", false)
            val response = JSONObject(request(baseUrl, "/v1/chat/completions", apiKey, "POST", body))
            val choices = response.optJSONArray("choices") ?: JSONArray()
            if (choices.length() == 0) throw IllegalStateException("OpenJarvis returned no completion choices")
            choices.getJSONObject(0).getJSONObject("message").optString("content")
                .ifBlank { "The server returned an empty response." }
        }

    suspend fun completeStreaming(
        baseUrl: String,
        apiKey: String,
        model: String,
        turns: List<ChatTurn>,
        onDelta: suspend (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            turns.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        }
        val body = JSONObject().put("model", model)
            .put("messages", messages).put("temperature", 0.7)
            .put("max_tokens", 1024).put("stream", true)
        val conn = connection(baseUrl, "/v1/chat/completions", apiKey).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        val answer = StringBuilder()
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status !in 200..299) {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val detail = runCatching { JSONObject(error).optString("detail") }.getOrNull()
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "OpenJarvis returned HTTP " + status)
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (!line.startsWith("data:")) return@forEach
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") return@forEach
                    val delta = runCatching {
                        val choices = JSONObject(payload).optJSONArray("choices") ?: JSONArray()
                        val choice = choices.optJSONObject(0) ?: return@runCatching ""
                        choice.optJSONObject("delta")?.opt("content") as? String ?: ""
                    }.getOrDefault("")
                    if (delta.isNotEmpty()) {
                        answer.append(delta)
                        kotlinx.coroutines.runBlocking { onDelta(delta) }
                    }
                }
            }
            if (answer.isEmpty()) throw IllegalStateException("OpenJarvis stream ended without text")
            answer.toString()
        } finally {
            conn.disconnect()
        }
    }

    suspend fun speechHealth(baseUrl: String, apiKey: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(request(baseUrl, "/v1/speech/health", apiKey))
    }

    suspend fun textToSpeech(baseUrl: String, apiKey: String, text: String): ByteArray =
        withContext(Dispatchers.IO) {
            val conn = connection(baseUrl, "/v1/speech/synthesize", apiKey).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "audio/wav")
            }
            try {
                conn.outputStream.use {
                    it.write(JSONObject().put("text", text.take(5000)).toString().toByteArray(Charsets.UTF_8))
                }
                val status = conn.responseCode
                if (status !in 200..299) {
                    val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val detail = runCatching { JSONObject(error).optString("detail") }.getOrNull()
                    throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "Speech synthesis returned HTTP " + status)
                }
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }

    suspend fun listModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val data = JSONObject(request(baseUrl, "/v1/models", apiKey)).optJSONArray("data") ?: JSONArray()
        buildList {
            for (index in 0 until data.length()) {
                val id = data.optJSONObject(index)?.optString("id").orEmpty()
                if (id.isNotBlank()) add(id)
            }
        }
    }

    suspend fun listAgents(baseUrl: String, apiKey: String): List<JarvisAgent> = withContext(Dispatchers.IO) {
        val data = JSONObject(request(baseUrl, "/v1/managed-agents", apiKey))
            .optJSONArray("agents") ?: JSONArray()
        buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val id = item.optString("id", item.optString("agent_id"))
                if (id.isNotBlank()) add(
                    JarvisAgent(
                        id = id,
                        name = item.optString("name", "Unnamed agent"),
                        type = item.optString("agent_type", item.optString("type", "agent")),
                        status = item.optString("status", "unknown")
                    )
                )
            }
        }
    }

    suspend fun setAgentPaused(baseUrl: String, apiKey: String, agent: JarvisAgent, pause: Boolean) =
        withContext(Dispatchers.IO) {
            request(baseUrl, "/v1/managed-agents/${agent.id}/${if (pause) "pause" else "resume"}", apiKey, "POST")
        }

    suspend fun listTools(baseUrl: String, apiKey: String): List<JarvisTool> = withContext(Dispatchers.IO) {
        val data = JSONObject(request(baseUrl, "/v1/tools", apiKey))
            .optJSONArray("tools") ?: JSONArray()
        buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val name = item.optString("name")
                if (name.isNotBlank()) add(
                    JarvisTool(
                        name = name,
                        description = item.optString("description"),
                        category = item.optString("category", "general"),
                        configured = item.optBoolean("configured", true)
                    )
                )
            }
        }
    }

    suspend fun memoryStats(baseUrl: String, apiKey: String): MemoryStats = withContext(Dispatchers.IO) {
        val json = JSONObject(request(baseUrl, "/v1/memory/stats", apiKey))
        MemoryStats(
            entries = json.optInt("entries", json.optInt("count", 0)),
            backend = json.optString("backend", "unknown"),
            status = json.optString("status", "available")
        )
    }

    suspend fun searchMemory(baseUrl: String, apiKey: String, query: String): List<String> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("query", query).put("top_k", 5)
            val results = JSONObject(request(baseUrl, "/v1/memory/search", apiKey, "POST", body))
                .optJSONArray("results") ?: JSONArray()
            buildList {
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val content = item.optString("content")
                    if (content.isNotBlank()) add(content)
                }
            }
        }

    suspend fun health(baseUrl: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val connection = connection(baseUrl, "/health", apiKey).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        try {
            val status = connection.responseCode
            if (status in 200..299) "Connected to OpenJarvis" else "Server returned HTTP " + status
        } finally {
            connection.disconnect()
        }
    }
}
