package org.openjarvis.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatTurn(val role: String, val content: String)

object OpenJarvisApi {
    suspend fun complete(baseUrl: String, apiKey: String, model: String, turns: List<ChatTurn>): String =
        withContext(Dispatchers.IO) {
            val connection = (URL(baseUrl.trim().trimEnd('/') + "/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
            try {
                val messages = JSONArray().apply {
                    turns.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
                }
                val body = JSONObject().put("model", model.ifBlank { "qwen3:8b" })
                    .put("messages", messages).put("temperature", 0.7)
                    .put("max_tokens", 1024).put("stream", false)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val detail = runCatching { JSONObject(response).optString("detail") }.getOrNull()
                    throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "OpenJarvis returned HTTP " + status)
                }
                JSONObject(response).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")
                    .ifBlank { "The server returned an empty response." }
            } finally { connection.disconnect() }
        }

    suspend fun health(baseUrl: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl.trim().trimEnd('/') + "/health").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer " + apiKey)
        }
        try {
            val status = connection.responseCode
            if (status in 200..299) "Connected to OpenJarvis" else "Server returned HTTP " + status
        } finally { connection.disconnect() }
    }
}
