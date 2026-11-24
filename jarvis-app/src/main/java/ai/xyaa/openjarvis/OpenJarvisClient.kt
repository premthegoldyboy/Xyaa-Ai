package ai.xyaa.openjarvis

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class OpenJarvisClient(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun chat(history: List<Pair<String, String>>): String {
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "Server URL must start with http:// or https://"
        }
        require(model.isNotBlank()) { "Enter a model ID in Setup." }
        val messages = JSONArray()
        history.forEach { (role, content) ->
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", 1024)
            .put("stream", false)
        val requestBuilder = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/chat/completions")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) requestBuilder.header("Authorization", "Bearer $apiKey")
        http.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(300)}")
            val root = JSONObject(body)
            val choices = root.optJSONArray("choices") ?: error("Server response has no choices.")
            val message = choices.optJSONObject(0)?.optJSONObject("message")
                ?: error("Server response has no assistant message.")
            return message.optString("content").takeIf { it.isNotBlank() }
                ?: "The server returned an empty response."
        }
    }
}
