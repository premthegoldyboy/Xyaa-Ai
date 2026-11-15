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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private lateinit var skills: SkillEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        skills = SkillEngine(this)
        tts = TextToSpeech(this, this)
        setContent {
            XyaaApp(
                onSend = { prompt, answer -> process(prompt, answer) },
                onVoice = ::startVoiceInput,
                onSaveKey = { key -> SecureKeyStore(this).save(key) },
                onLoadKey = { SecureKeyStore(this).read() }
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            tts?.setSpeechRate(0.94f)
        }
    }

    private fun say(text: String) {
        val spoken = text.replace(Regex("(?i)\\bXYAA\\b"), "Zyaa")
            .replace(Regex("https?://\\S+".toRegex()), "link")
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "xyaa-response")
    }

    private fun process(prompt: String, answer: (String) -> Unit) {
        val local = skills.execute(prompt)
        if (local != null) {
            answer(local)
            say(local)
            return
        }
        val key = SecureKeyStore(this).read()
        if (key.isNullOrBlank()) {
            val message = "I can handle built-in skills offline. Add your Gemini API key in Settings to enable open-ended AI conversations."
            answer(message)
            say(message)
            return
        }
        answer("Thinking…")
        Thread {
            val response = runCatching { askGemini(key, prompt) }
                .getOrElse { "I couldn't reach the AI service. Check your connection and API key, then try again." }
            runOnUiThread {
                answer(response)
                say(response)
            }
        }.start()
    }

    private fun askGemini(apiKey: String, prompt: String): String {
        val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + URLEncoder.encode(apiKey, "UTF-8"))
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val body = JSONObject().put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                "You are XYAA, a helpful, warm, concise Android assistant. Your name is pronounced Zyaa, never spell it letter by letter. Be honest about actions you cannot perform. Never claim an action succeeded unless the app confirms it."))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", 700))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val payload = stream.bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) throw IllegalStateException("AI request failed: HTTP ${connection.responseCode}")
            val json = JSONObject(payload)
            return json.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
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
                process(spoken) { /* Voice result is spoken by process(). */ }
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

private data class ChatMessage(val text: String, val fromUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XyaaApp(
    onSend: (String, (String) -> Unit) -> Unit,
    onVoice: () -> Unit,
    onSaveKey: (String) -> Unit,
    onLoadKey: () -> String?
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
                            Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFFB9A5FF), modifier = Modifier.padding(11.dp))
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
                            Icon(Icons.Default.Mic, "Speak", tint = Color(0xFFCCBFFF))
                        }
                        IconButton(
                            onClick = {
                                val prompt = input.trim()
                                if (prompt.isNotEmpty() && !busy) {
                                    messages = messages + ChatMessage(prompt, true)
                                    input = ""
                                    busy = true
                                    messages = messages + ChatMessage("Thinking…", false)
                                    onSend(prompt) { result ->
                                        messages = messages.dropLast(1) + ChatMessage(result, false)
                                        busy = false
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
                            Text("ONLINE", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8CE3BB))
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
