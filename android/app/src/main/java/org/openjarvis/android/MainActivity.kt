package org.openjarvis.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("openjarvis_android", MODE_PRIVATE)
        val restoredTurns = runCatching {
            val array = JSONArray(prefs.getString("turns", "[]"))
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val role = item.optString("role")
                    val text = item.optString("content")
                    if ((role == "user" || role == "assistant") && text.isNotBlank()) {
                        add(ChatTurn(role, text))
                    }
                }
            }
        }.getOrDefault(emptyList())

        setContent {
            MaterialTheme {
                OpenJarvisScreen(
                    initialUrl = prefs.getString("url", "http://10.0.2.2:8000") ?: "",
                    initialModel = prefs.getString("model", "") ?: "",
                    initialKey = prefs.getString("key", "") ?: "",
                    initialTurns = restoredTurns,
                    onSave = { url, model, key ->
                        prefs.edit().putString("url", url).putString("model", model).putString("key", key).apply()
                    },
                    onTurnsChanged = { turns ->
                        val json = JSONArray().apply {
                            turns.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
                        }
                        prefs.edit().putString("turns", json.toString()).apply()
                    }
                )
            }
        }
    }
}

@Composable
private fun OpenJarvisScreen(
    initialUrl: String,
    initialModel: String,
    initialKey: String,
    initialTurns: List<ChatTurn>,
    onSave: (String, String, String) -> Unit,
    onTurnsChanged: (List<ChatTurn>) -> Unit
) {
    var serverUrl by rememberSaveable { mutableStateOf(initialUrl) }
    var model by rememberSaveable { mutableStateOf(initialModel) }
    var apiKey by rememberSaveable { mutableStateOf(initialKey) }
    var draft by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("Connect to your OpenJarvis server to begin.") }
    var busy by remember { mutableStateOf(false) }
    var modelsLoading by remember { mutableStateOf(false) }
    var modelsExpanded by remember { mutableStateOf(false) }
    val availableModels = remember { mutableStateListOf<String>() }
    val turns = remember { mutableStateListOf<ChatTurn>().apply { addAll(initialTurns) } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(turns.size) {
        onTurnsChanged(turns.toList())
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }

    fun sendMessage() {
        val prompt = draft.trim()
        if (prompt.isEmpty() || busy) return
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            status = "Enter a valid server URL beginning with http:// or https://"
            return
        }
        if (model.isBlank()) {
            status = "Choose a model. Use Load models to discover available models."
            return
        }
        draft = ""
        turns.add(ChatTurn("user", prompt))
        busy = true
        onSave(normalizedUrl, model.trim(), apiKey)
        scope.launch {
            try {
                val answer = OpenJarvisApi.complete(normalizedUrl, apiKey.trim(), model.trim(), turns.toList())
                turns.add(ChatTurn("assistant", answer))
                status = "Response received"
            } catch (error: Exception) {
                status = "Request failed: " + (error.message ?: "unknown error")
            } finally {
                busy = false
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("OpenJarvis", style = MaterialTheme.typography.headlineMedium)
                    Text("Native Android client", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = {
                    if (!busy) {
                        turns.clear()
                        status = "Conversation cleared"
                    }
                }, enabled = !busy && turns.isNotEmpty()) { Text("Clear chat") }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it },
                label = { Text("OpenJarvis server URL") },
                placeholder = { Text("http://192.168.1.10:8000") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("Model") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API key (optional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = {
                    val url = serverUrl.trim().trimEnd('/')
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        status = "Enter a valid server URL"
                    } else {
                        onSave(url, model, apiKey)
                        scope.launch {
                            status = "Checking server…"
                            status = runCatching { OpenJarvisApi.health(url, apiKey.trim()) }
                                .getOrElse { "Connection failed: " + (it.message ?: "unknown error") }
                        }
                    }
                }, enabled = !busy && !modelsLoading) { Text("Test") }
                Box {
                    TextButton(onClick = {
                        val url = serverUrl.trim().trimEnd('/')
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            status = "Enter a valid server URL"
                        } else {
                            modelsLoading = true
                            scope.launch {
                                try {
                                    val found = OpenJarvisApi.listModels(url, apiKey.trim())
                                    availableModels.clear()
                                    availableModels.addAll(found)
                                    if (found.isNotEmpty()) {
                                        if (model !in found) model = found.first()
                                        modelsExpanded = true
                                        status = "Loaded " + found.size + " model(s)"
                                        onSave(url, model, apiKey)
                                    } else {
                                        status = "No chat models were returned by the server"
                                    }
                                } catch (error: Exception) {
                                    status = "Model lookup failed: " + (error.message ?: "unknown error")
                                } finally {
                                    modelsLoading = false
                                }
                            }
                        }
                    }, enabled = !busy && !modelsLoading) {
                        Text(if (modelsLoading) "Loading…" else "Load models")
                    }
                    DropdownMenu(expanded = modelsExpanded, onDismissRequest = { modelsExpanded = false }) {
                        availableModels.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item) },
                                onClick = {
                                    model = item
                                    modelsExpanded = false
                                    onSave(serverUrl.trim().trimEnd('/'), model, apiKey)
                                }
                            )
                        }
                    }
                }
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(turns) { turn ->
                    val isUser = turn.role == "user"
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            Modifier.fillMaxWidth(0.92f),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(if (isUser) "You" else "OpenJarvis", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(turn.content)
                            }
                        }
                    }
                }
                if (busy) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("OpenJarvis is thinking…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Message OpenJarvis") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendMessage() })
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { sendMessage() }, enabled = !busy && draft.isNotBlank()) {
                    Text(if (busy) "Thinking…" else "Send")
                }
            }
        }
    }
}
