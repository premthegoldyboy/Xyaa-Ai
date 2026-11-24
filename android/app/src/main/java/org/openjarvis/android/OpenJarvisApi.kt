package org.openjarvis.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatTurn(val role: String, val content: String)

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

    suspend fun complete(baseUrl: String, apiKey: String, model: String, turns: List<ChatTurn>): String =
        withContext(Dispatchers.IO) {
            val connection = connection(baseUrl, "/v1/chat/completions", apiKey).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                val messages = JSONArray().apply {
                    turns.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
                }
                val body = JSONObject().put("model", model.ifBlank { "qwen3:8b" })
                    .put("messages", messages).put("temperature", 0.7)
                    .put("max_tokens", 1024).put("stream", false)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                JSONObject(readResponse(connection)).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")
                    .ifBlank { "The server returned an empty response." }
            } finally { connection.disconnect() }
        }

    suspend fun listModels(baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val connection = connection(baseUrl, "/v1/models", apiKey).apply { requestMethod = "GET" }
            try {
                val data = JSONObject(readResponse(connection)).optJSONArray("data") ?: JSONArray()
                buildList {
                    for (index in 0 until data.length()) {
                        val id = data.optJSONObject(index)?.optString("id").orEmpty()
                        if (id.isNotBlank()) add(id)
                    }
                }
            } finally { connection.disconnect() }
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
        } finally { connection.disconnect() }
    }
}
