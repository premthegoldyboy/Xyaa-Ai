package ai.xyaa.assistant

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private lateinit var skills: SkillEngine
    private lateinit var neuralVoice: NeuralVoiceEngine
    private val neuralVoiceStatus = mutableStateOf("Neural voice not downloaded (about 400 MB)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        skills = SkillEngine(this)
        neuralVoice = NeuralVoiceEngine(this) { status -> runOnUiThread { neuralVoiceStatus.value = status } }
        neuralVoice.initializeIfInstalled()
        tts = TextToSpeech(this, this)
        setContent {
            XyaaApp(
                onSend = { prompt, answer -> process(prompt, answer) },
                onVoice = ::startVoiceInput,
                onSaveKey = { key -> SecureKeyStore(this).save(key) },
                onLoadKey = { SecureKeyStore(this).read() },
                neuralVoiceStatus = neuralVoiceStatus.value,
                onDownloadNeuralVoice = { neuralVoice.downloadAndInitialize() }
            )
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val engine = tts ?: return
        // Prefer a high-quality Indian-English voice when the device has one installed.
        val voices = engine.voices.orEmpty()
        val preferred = voices
            .filter { it.locale.language == "en" }
            .sortedWith(
                compareByDescending<android.speech.tts.Voice> {
                    it.locale.country.equals("IN", ignoreCase = true)
                }.thenByDescending { it.quality }
                    .thenBy { it.isNetworkConnectionRequired }
            )
            .firstOrNull()
        if (preferred != null) engine.voice = preferred
        else engine.language = Locale("en", "IN")
        engine.setSpeechRate(0.91f)
        engine.setPitch(1.02f)
    }

    private fun say(text: String) {
        val spoken = text
            .replace(Regex("(?i)\\bX\\s*Y\\s*A\\s*A\\b"), "Zyaa")
            .replace(Regex("(?i)\\bXYAA\\b"), "Zyaa")
        val neuralQueued = neuralVoice.speak(spoken) {
            runOnUiThread { tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "xyaa-response") }
        }
        if (!neuralQueued) {
            tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "xyaa-response")
        }
    }

    private fun process(prompt: String, answer: (String, Boolean) -> Unit) {
        val local = skills.execute(prompt)
        if (local != null) {
            answer(local, true)
            say(local)
            return
        }
        val key = SecureKeyStore(this).read()
        if (key.isNullOrBlank()) {
            val message = "I can handle built-in skills offline. Add your Gemini API key in Settings to enable open-ended AI conversations."
            answer(message, true)
            say(message)
            return
        }
        answer("Connecting to Gemini…", false)
        Thread {
            val response = runCatching { askGemini(key, prompt) { status ->
                runOnUiThread { answer(status, false) }
            } }.getOrElse { formatAiError(it) }
            runOnUiThread {
                answer(response, true)
                say(response)
            }
        }.start()
    }


    private fun formatAiError(error: Throwable): String {
        val detail = error.message.orEmpty()
        return when {
            error is java.net.UnknownHostException || error is java.net.ConnectException ->
                "I can't connect to Google's AI service. Check that mobile data or Wi-Fi is working, then try again."
            error is java.net.SocketTimeoutException ->
                "Google's AI service did not respond in time. XYAA has a shorter timeout now; please retry."
            detail.contains("HTTP 429") ->
                "Google AI rate limit reached. XYAA will retry briefly; wait a little before sending another request."
            detail.contains("HTTP 503") || detail.contains("HTTP 500") || detail.contains("HTTP 502") || detail.contains("HTTP 504") ->
                "Google AI is temporarily overloaded. XYAA tried its recovery path; please try again shortly."
            detail.contains("HTTP 400") ->
                "Google rejected this request (400). Check that your Gemini API key is valid and that the selected model is available."
            detail.contains("HTTP 401") || detail.contains("HTTP 403") ->
                "Google rejected the API key (401/403). Recheck the key in XYAA Settings and confirm Gemini API access is enabled for its Google AI Studio project."
            detail.contains("HTTP 404") ->
                "The selected Gemini model wasn't found (404). XYAA needs an app update to use an available model."
            detail.startsWith("AI service error:") ->
                detail.removePrefix("AI service error:").trim().take(240)
            else ->
                "XYAA couldn't complete the AI request. ${detail.take(180).ifBlank { "Check your connection and API key, then try again." }}"
        }
    }

    @Volatile private var cachedGeminiModels: List<String> = emptyList()
    @Volatile private var modelCacheTimeMs: Long = 0L

    // Discover models enabled for this API key, then prioritize fast, stable text models.
    private fun discoverGeminiModels(apiKey: String): List<String> {
        val now = System.currentTimeMillis()
        if (cachedGeminiModels.isNotEmpty() && now - modelCacheTimeMs < 6 * 60 * 60 * 1000L) {
            return cachedGeminiModels
        }
        val preferred = listOf(
            "gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.7-flash",
            "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.1-flash-lite",
            "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash",
            "gemini-2.0-flash-lite"
        )
        val discovered = runCatching {
            val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("x-goog-api-key", apiKey.trim())
            }
            try {
                val status = connection.responseCode
                if (status !in 200..299) error("Model discovery HTTP " + status)
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val models = json.optJSONArray("models") ?: JSONArray()
                (0 until models.length()).mapNotNull { i ->
                    val item = models.optJSONObject(i) ?: return@mapNotNull null
                    val name = item.optString("name").removePrefix("models/")
                    val methods = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    val supportsText = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                    val lower = name.lowercase(Locale.ROOT)
                    if (supportsText && lower.startsWith("gemini-") &&
                        listOf("image", "audio", "tts", "transcrib", "embedding", "live", "robotics", "deep-research")
                            .none { lower.contains(it) }) name else null
                }.distinct()
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(emptyList())
        val eligible = if (discovered.isNotEmpty()) discovered.toSet() else preferred.toSet()
        val ordered = preferred.filter { it in eligible } +
            discovered.filter { it !in preferred && it in eligible }.sorted()
        val result = ordered.distinct().take(8).ifEmpty { preferred.take(5) }
        cachedGeminiModels = result
        modelCacheTimeMs = now
        return result
    }

    private fun askGemini(apiKey: String, prompt: String, onStatus: (String) -> Unit): String {
        val models = discoverGeminiModels(apiKey)
        var lastError: Throwable? = null
        models.forEachIndexed { index, model ->
            onStatus("Trying Gemini " + (index + 1) + "/" + models.size + ": " + model + "…")
            try {
                return requestGemini(apiKey, prompt, model)
            } catch (error: Throwable) {
                lastError = error
                if (!isTemporaryAiFailure(error) && !isUnavailableModel(error)) throw error
                if (index < models.lastIndex) {
                    onStatus(model + " is slow or unavailable. Switching to " + models[index + 1] + "…")
                }
            }
        }
        throw IllegalStateException(
            "Every available Gemini text model was busy or unavailable. XYAA tried " + models.size + " models. Please retry in a moment.",
            lastError
        )
    }

    private fun isUnavailableModel(error: Throwable): Boolean {
        val detail = error.message.orEmpty().lowercase(Locale.ROOT)
        return detail.contains("http 404") ||
            (detail.contains("http 400") &&
                (detail.contains("model") || detail.contains("not supported") || detail.contains("not found")))
    }

    private fun isTemporaryAiFailure(error: Throwable): Boolean {
        if (error is java.net.SocketTimeoutException ||
            error is java.net.ConnectException ||
            error is java.net.UnknownHostException) return true
        return Regex("HTTP (408|429|500|502|503|504)\\b").containsMatchIn(error.message.orEmpty())
    }

    private fun requestGemini(apiKey: String, prompt: String, model: String): String {
        val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = 9000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey.trim())
        }
        try {
            val body = JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                    "You are XYAA, a helpful, warm, concise Android assistant. Your name is pronounced Zyaa, never spell it letter by letter. Be honest about actions you cannot perform. Never claim an action succeeded unless the app confirms it."))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", 0.6).put("maxOutputTokens", 450))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val apiMessage = runCatching {
                    JSONObject(payload).getJSONObject("error").optString("message")
                }.getOrDefault("")
                throw IllegalStateException("AI service error: HTTP $status. " + apiMessage.ifBlank { "Google did not provide an explanation." })
            }
            val json = JSONObject(payload)
            val candidates = json.optJSONArray("candidates")
                ?: throw IllegalStateException("AI service error: Google returned no response candidates.")
            if (candidates.length() == 0) {
                throw IllegalStateException("AI service error: Google returned an empty response. The request may have been blocked by a safety filter.")
            }
            val parts = candidates.getJSONObject(0).getJSONObject("content").optJSONArray("parts")
                ?: throw IllegalStateException("AI service error: Google returned no text.")
            val responseText = (0 until parts.length()).mapNotNull { index ->
                parts.optJSONObject(index)?.optString("text")?.takeIf { it.isNotBlank() }
            }.joinToString("\n").trim()
            if (responseText.isBlank()) throw IllegalStateException("AI service error: Google returned an empty text response.")
            return responseText
        } finally {
            connection.disconnect()
        }
    }

    private fun startVoiceInput() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 43)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to XYAA")
        }
        runCatching { startActivityForResult(intent, 42) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 43 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceInput()
    }

    @Deprecated("Deprecated by Android; retained for compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == Activity.RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spoken ->
                process(spoken) { _, _ -> /* Voice result is spoken by process(). */ }
            }
        }
    }

    override fun onDestroy() {
        neuralVoice.close()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

private data class ChatMessage(val text: String, val fromUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XyaaApp(
    onSend: (String, (String, Boolean) -> Unit) -> Unit,
    onVoice: () -> Unit,
    onSaveKey: (String) -> Unit,
    onLoadKey: () -> String?,
    neuralVoiceStatus: String,
    onDownloadNeuralVoice: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf(ChatMessage("Hey! I'm Zyaa. I can launch apps, search, set timers, calculate, save notes and much more. Ask me anything.", false))) }
    var busy by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    val quick = listOf("Open YouTube", "What's the time?", "Set a timer for 5 minutes", "Search the web")

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB9A5FF),
            secondary = Color(0xFF83D8D0),
            background = Color(0xFF0A0B13),
            surface = Color(0xFF151827),
            onBackground = Color(0xFFF2F1FA),
            onSurface = Color(0xFFF2F1FA)
        )
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("XYAA", fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp)
                            Text("PERSONAL AI", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9B9BB0), letterSpacing = 2.sp)
                        }
                    },
                    navigationIcon = {
                        Surface(shape = CircleShape, color = Color(0xFF24233A), modifier = Modifier.padding(start = 12.dp)) {
                            Icon(Icons.Default.Star, null, tint = Color(0xFFB9A5FF), modifier = Modifier.padding(11.dp))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            apiKey = onLoadKey().orEmpty()
                            settingsOpen = true
                        }) { Icon(Icons.Default.Settings, "Settings") }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color(0xFF0A0B13))
                )
            },
            bottomBar = {
                Surface(color = Color(0xFF111421), tonalElevation = 8.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message XYAA…") },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 4
                        )
                        IconButton(onClick = onVoice, modifier = Modifier.size(48.dp).background(Color(0xFF28233F), CircleShape)) {
                            Icon(Icons.Default.KeyboardVoice, "Speak", tint = Color(0xFFCCBFFF))
                        }
                        IconButton(
                            onClick = {
                                val prompt = input.trim()
                                if (prompt.isNotEmpty() && !busy) {
                                    messages = messages + ChatMessage(prompt, true)
                                    input = ""
                                    busy = true
                                    messages = messages + ChatMessage("Thinking…", false)
                                    onSend(prompt) { result, finished ->
                                        messages = messages.dropLast(1) + ChatMessage(result, false)
                                        if (finished) busy = false
                                    }
                                }
                            },
                            enabled = input.isNotBlank() && !busy,
                            modifier = Modifier.size(48.dp).background(Color(0xFF8C75E8), CircleShape)
                        ) { Icon(Icons.Default.Send, "Send", tint = Color.White) }
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding)
                    .background(Brush.verticalGradient(listOf(Color(0xFF111426), Color(0xFF0A0B13))))
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Your assistant", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Ready when you are", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9B9BB0))
                    }
                    Surface(color = Color(0xFF17372F), shape = RoundedCornerShape(30.dp)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(Color(0xFF62D6A5), CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text("READY", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8CE3BB))
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages) { message ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
                            Surface(
                                color = if (message.fromUser) Color(0xFF51427F) else Color(0xFF1B2030),
                                shape = RoundedCornerShape(18.dp, 18.dp, if (message.fromUser) 5.dp else 18.dp, if (message.fromUser) 18.dp else 5.dp),
                                modifier = Modifier.widthIn(max = 310.dp)
                            ) {
                                Text(message.text, Modifier.padding(horizontal = 15.dp, vertical = 12.dp), color = Color(0xFFF3F1FC))
                            }
                        }
                    }
                }
                Text("QUICK ACTIONS", Modifier.padding(start = 18.dp, top = 4.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF9293AA), letterSpacing = 1.5.sp)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quick.forEach { action ->
                        SuggestionChip(onClick = { input = action }, label = { Text(action, maxLines = 1) })
                    }
                }
            }
        }
        if (settingsOpen) {
            AlertDialog(
                onDismissRequest = { settingsOpen = false },
                title = { Text("XYAA settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Add a Gemini API key to enable open-ended AI chat. Built-in device skills work without it.")
                        OutlinedTextField(
                            value = apiKey, onValueChange = { apiKey = it },
                            label = { Text("Gemini API key") }, singleLine = true
                        )
                        Text("The key is encrypted with Android Keystore on this device.", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("XYAA Neural Voice", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(neuralVoiceStatus, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onDownloadNeuralVoice, modifier = Modifier.fillMaxWidth()) { Text("Download neural voice (~400 MB)") }
                        Text("Kokoro runs on this phone after download. Speech generation is then offline.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onSaveKey(apiKey.trim()); settingsOpen = false }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { settingsOpen = false }) { Text("Cancel") } }
            )
        }
    }
}
