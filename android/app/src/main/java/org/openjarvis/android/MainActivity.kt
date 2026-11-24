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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("openjarvis_android", MODE_PRIVATE)
        setContent {
            MaterialTheme {
                OpenJarvisScreen(
                    prefs.getString("url", "http://10.0.2.2:8000") ?: "",
                    prefs.getString("model", "qwen3:8b") ?: "qwen3:8b",
                    prefs.getString("key", "") ?: "",
                    onSave = { url, model, key ->
                        prefs.edit().putString("url", url).putString("model", model).putString("key", key).apply()
                    }
                )
            }
        }
    }
}

@Composable
private fun OpenJarvisScreen(
    initialUrl: String, initialModel: String, initialKey: String,
    onSave: (String, String, String) -> Unit
) {
    var serverUrl by rememberSaveable { mutableStateOf(initialUrl) }
    var model by rememberSaveable { mutableStateOf(initialModel) }
    var apiKey by rememberSaveable { mutableStateOf(initialKey) }
    var draft by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("Configure your OpenJarvis server to begin.") }
    var busy by remember { mutableStateOf(false) }
    val turns = remember { mutableStateListOf<ChatTurn>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("OpenJarvis", style = MaterialTheme.typography.headlineMedium)
            Text("Personal AI, on your devices", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("Server URL") },
                placeholder = { Text("http://192.168.1.10:8000") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key (optional)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    onSave(serverUrl, model, apiKey)
                    scope.launch {
                        status = "Checking server…"
                        status = runCatching { OpenJarvisApi.health(serverUrl, apiKey) }
                            .getOrElse { "Connection failed: " + (it.message ?: "unknown error") }
                    }
                }, enabled = !busy) { Text("Save & test") }
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(turns) { turn ->
                    val isUser = turn.role == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                        Card(Modifier.fillMaxWidth(0.92f), shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor =
                                if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(if (isUser) "You" else "OpenJarvis", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(turn.content)
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Message OpenJarvis") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (draft.isNotBlank() && !busy) {
                        val prompt = draft.trim(); draft = ""; turns.add(ChatTurn("user", prompt))
                        busy = true; onSave(serverUrl, model, apiKey)
                        scope.launch {
                            try { turns.add(ChatTurn("assistant", OpenJarvisApi.complete(serverUrl, apiKey, model, turns.toList()))); status = "Response received" }
                            catch (error: Exception) { status = "Request failed: " + (error.message ?: "unknown error") }
                            finally { busy = false }
                        }
                    }
                })
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    val prompt = draft.trim()
                    if (prompt.isNotEmpty() && !busy) {
                        draft = ""; turns.add(ChatTurn("user", prompt)); busy = true; onSave(serverUrl, model, apiKey)
                        scope.launch {
                            try { turns.add(ChatTurn("assistant", OpenJarvisApi.complete(serverUrl, apiKey, model, turns.toList()))); status = "Response received" }
                            catch (error: Exception) { status = "Request failed: " + (error.message ?: "unknown error") }
                            finally { busy = false }
                        }
                    }
                }, enabled = !busy && draft.isNotBlank()) { Text(if (busy) "Thinking…" else "Send") }
            }
        }
    }
}
