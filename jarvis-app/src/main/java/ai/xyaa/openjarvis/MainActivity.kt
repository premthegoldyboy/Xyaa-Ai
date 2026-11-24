package ai.xyaa.openjarvis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                JarvisScreen(
                    initialUrl = prefs.getString("base_url", "http://10.0.2.2:8000") ?: "",
                    initialModel = prefs.getString("model", "qwen3:8b") ?: "qwen3:8b",
                    initialKey = prefs.getString("api_key", "") ?: "",
                    onSave = { url, model, key ->
                        prefs.edit().putString("base_url", url.trim().trimEnd('/'))
                            .putString("model", model.trim()).putString("api_key", key.trim()).apply()
                    }
                )
            }
        }
    }
}

private data class ChatLine(val text: String, val user: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JarvisScreen(
    initialUrl: String, initialModel: String, initialKey: String,
    onSave: (String, String, String) -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var model by remember { mutableStateOf(initialModel) }
    var apiKey by remember { mutableStateOf(initialKey) }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connect to your OpenJarvis server to begin.") }
    var settings by remember { mutableStateOf(true) }
    var messages by remember { mutableStateOf(listOf(ChatLine("Hello. I'm Jarvis Mobile, connected to the OpenJarvis engine.", false))) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(title = { Column {
                Text("JARVIS MOBILE")
                Text("Powered by OpenJarvis", style = MaterialTheme.typography.labelSmall)
            } }, actions = { TextButton(onClick = { settings = !settings }) { Text("SETUP") } })
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().imePadding().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.weight(1f), placeholder = { Text("Ask Jarvis…") }, maxLines = 4)
                Button(enabled = prompt.isNotBlank() && !busy, onClick = {
                    val q = prompt.trim()
                    prompt = ""
                    messages = messages + ChatLine(q, true)
                    busy = true
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                OpenJarvisClient(url, model, apiKey).chat(messages.filter { it.text != q }.takeLast(20).map {
                                    (if (it.user) "user" else "assistant") to it.text
                                } + listOf("user" to q))
                            }
                        }
                        result.onSuccess { answer ->
                            messages = messages + ChatLine(answer, false)
                            status = "Response received from OpenJarvis."
                        }.onFailure {
                            status = "Connection failed: ${it.message ?: "unknown error"}"
                            messages = messages + ChatLine(status, false)
                        }
                        busy = false
                    }
                }) { Text(if (busy) "…" else "Send") }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            if (settings) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Server connection", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("OpenJarvis URL") }, singleLine = true)
                        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model ID") }, singleLine = true)
                        OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API key (optional)") }, singleLine = true)
                        Button(onClick = { onSave(url, model, apiKey); settings = false; status = "Connection settings saved on this device." }) { Text("Save settings") }
                        Text("Use your computer's LAN IP and port 8000 on a real phone. The emulator default is 10.0.2.2:8000.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(status, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { line ->
                    Surface(
                        color = if (line.user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(line.text, Modifier.padding(12.dp), color = if (line.user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
