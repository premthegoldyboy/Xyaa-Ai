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
data class JarvisTask(val id: String, val description: String, val status: String)
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
                        onDelta(delta)
                    }
                }
            }
            if (answer.isEmpty()) throw IllegalStateException("OpenJarvis stream ended without text")
            answer.toString()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Client-side OpenAI function-calling loop for the Android interaction bridge.
     * The model proposes structured actions; the Android app executes them locally
     * through the user-enabled Accessibility service and returns each result.
     */
    suspend fun completeWithDeviceTools(
        baseUrl: String,
        apiKey: String,
        model: String,
        turns: List<ChatTurn>,
        onProgress: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put(
                "content",
                "You are OpenJarvis running on Android. You may use the supplied android_* functions to help with the user's requested phone task. Inspect the screen only as needed, act only on the user's intent, and verify results by reading the screen. Never seek, read, enter, transmit, or reveal passwords, one-time codes, payment credentials, or other authentication secrets. Do not send messages, publish posts, make calls, purchase, pay, transfer money, delete data, change security/privacy settings, or submit consequential forms. If such an action is requested, prepare it if possible and tell the user to complete the final action themselves."
            ))
            turns.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        }
        val tools = JSONArray().apply {
            fun addTool(name: String, description: String, properties: JSONObject = JSONObject(), required: JSONArray = JSONArray()) {
                put(JSONObject().put("type", "function").put("function",
                    JSONObject().put("name", name).put("description", description)
                        .put("parameters", JSONObject().put("type", "object")
                            .put("properties", properties).put("required", required))
                ))
            }
            addTool("android_read_screen", "Read visible text from the currently active app.")
            addTool("android_tap_text", "Tap a visible UI element by its displayed text label.", JSONObject().put("text", JSONObject().put("type", "string")), JSONArray().put("text"))
            addTool("android_enter_text", "Enter text into a visible editable field. Provide label when known; empty label uses the first visible editable field.", JSONObject().put("label", JSONObject().put("type", "string")).put("text", JSONObject().put("type", "string")), JSONArray().put("text"))
            addTool("android_scroll", "Scroll the active app view up or down.", JSONObject().put("direction", JSONObject().put("type", "string").put("enum", JSONArray().put("up").put("down"))), JSONArray().put("direction"))
            addTool("android_swipe", "Swipe the screen in a direction.", JSONObject().put("direction", JSONObject().put("type", "string").put("enum", JSONArray().put("up").put("down").put("left").put("right"))), JSONArray().put("direction"))
            addTool("android_list_apps", "List launchable apps installed on this Android device, including their display names and package IDs.")
            addTool("android_open_app", "Open an installed Android app by display name or package ID.", JSONObject().put("app_name_or_package", JSONObject().put("type", "string")), JSONArray().put("app_name_or_package"))
            addTool("android_back", "Navigate back.")
            addTool("android_home", "Go to the Android home screen.")
            addTool("android_notifications", "Open the notification shade.")
            addTool("android_quick_settings", "Open Android Quick Settings.")
        }

        var rounds = 0
        while (rounds < 6) {
            val body = JSONObject().put("model", model).put("messages", messages)
                .put("temperature", 0.2).put("max_tokens", 1200)
                .put("stream", false).put("tools", tools)
            val response = JSONObject(request(baseUrl, "/v1/chat/completions", apiKey, "POST", body))
            val choices = response.optJSONArray("choices") ?: JSONArray()
            val message = choices.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalStateException("OpenJarvis returned no assistant message")
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                return@withContext message.optString("content").ifBlank { "OpenJarvis completed the request without a text response." }
            }

            messages.put(JSONObject().put("role", "assistant")
                .put("content", message.optString("content"))
                .put("tool_calls", calls))
            for (index in 0 until calls.length()) {
                val call = calls.optJSONObject(index) ?: continue
                val callId = call.optString("id")
                val function = call.optJSONObject("function") ?: JSONObject()
                val name = function.optString("name")
                val args = runCatching { JSONObject(function.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                val result = when (name) {
                    "android_read_screen" -> DeviceControlAccessibilityService.execute("read_screen")
                    "android_tap_text" -> DeviceControlAccessibilityService.execute("click_text", args.optString("text"))
                    "android_enter_text" -> DeviceControlAccessibilityService.execute("set_text", args.optString("label") + "|" + args.optString("text"))
                    "android_scroll" -> DeviceControlAccessibilityService.execute(if (args.optString("direction") == "up") "scroll_up" else "scroll_down")
                    "android_swipe" -> DeviceControlAccessibilityService.execute("swipe", args.optString("direction"))
                    "android_list_apps" -> DeviceControlAccessibilityService.execute("list_apps")
                    "android_open_app" -> DeviceControlAccessibilityService.execute("open_app", args.optString("app_name_or_package"))
                    "android_back" -> DeviceControlAccessibilityService.execute("back")
                    "android_home" -> DeviceControlAccessibilityService.execute("home")
                    "android_notifications" -> DeviceControlAccessibilityService.execute("notifications")
                    "android_quick_settings" -> DeviceControlAccessibilityService.execute("quick_settings")
                    else -> "Unsupported Android action: $name"
                }
                onProgress("$name: $result")
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", callId).put("content", result))
            }
            rounds++
        }
        "I reached the action limit for this request. Tell me what happened on the phone and I can continue."
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

    suspend fun runAgent(baseUrl: String, apiKey: String, agent: JarvisAgent): String =
        withContext(Dispatchers.IO) {
            val response = JSONObject(
                request(baseUrl, "/v1/managed-agents/${agent.id}/run", apiKey, "POST")
            )
            response.optString("status", "running")
        }

    suspend fun createAgent(baseUrl: String, apiKey: String, name: String, agentType: String): JarvisAgent =
        withContext(Dispatchers.IO) {
            val json = JSONObject(request(
                baseUrl, "/v1/managed-agents", apiKey, "POST",
                JSONObject().put("name", name).put("agent_type", agentType)
            ))
            JarvisAgent(
                id = json.optString("id"),
                name = json.optString("name", name),
                type = json.optString("agent_type", agentType),
                status = json.optString("status", "idle")
            ).also {
                if (it.id.isBlank()) throw IllegalStateException("Server created an agent but returned no ID")
            }
        }

    suspend fun listAgentTasks(baseUrl: String, apiKey: String, agent: JarvisAgent): List<JarvisTask> =
        withContext(Dispatchers.IO) {
            val data = JSONObject(request(baseUrl, "/v1/managed-agents/${agent.id}/tasks", apiKey))
                .optJSONArray("tasks") ?: JSONArray()
            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optString("id", item.optString("task_id"))
                    if (id.isNotBlank()) add(
                        JarvisTask(
                            id = id,
                            description = item.optString("description", "Task"),
                            status = item.optString("status", "unknown")
                        )
                    )
                }
            }
        }

    suspend fun createAgentTask(baseUrl: String, apiKey: String, agent: JarvisAgent, description: String) =
        withContext(Dispatchers.IO) {
            request(
                baseUrl, "/v1/managed-agents/${agent.id}/tasks", apiKey, "POST",
                JSONObject().put("description", description)
            )
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
