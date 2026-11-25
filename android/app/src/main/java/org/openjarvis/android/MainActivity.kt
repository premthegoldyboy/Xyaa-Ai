package org.openjarvis.android

import android.os.Bundle
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private object ApiKeyVault {
    private const val alias = "openjarvis_android_api_key"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    fun encrypt(value: String): Pair<String, String> {
        if (value.isBlank()) return "" to ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) to
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decrypt(iv: String, encrypted: String): String {
        if (iv.isBlank() || encrypted.isBlank()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        return String(Base64.decode(encrypted, Base64.NO_WRAP), Charsets.UTF_8)
    }
}

class MainActivity : ComponentActivity() {
    private var speechEngine: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speechEngine = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                speechEngine?.language = Locale.getDefault()
            }
        }
        val prefs = getSharedPreferences("openjarvis_android", MODE_PRIVATE)
        val savedKey = runCatching {
            val encrypted = prefs.getString("key_enc", "").orEmpty()
            val iv = prefs.getString("key_iv", "").orEmpty()
            if (encrypted.isNotBlank()) ApiKeyVault.decrypt(iv, encrypted)
            else prefs.getString("key", "").orEmpty()
        }.getOrDefault("")
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
                    initialKey = savedKey,
                    initialTurns = restoredTurns,
                    onSpeak = { text ->
                        speechEngine?.speak(text.take(4000), TextToSpeech.QUEUE_FLUSH, null, "openjarvis-response")
                    },
                    onSave = { url, model, key ->
                        val (iv, encryptedKey) = runCatching { ApiKeyVault.encrypt(key) }.getOrDefault("" to "")
                        prefs.edit()
                            .putString("url", url)
                            .putString("model", model)
                            .putString("key_iv", iv)
                            .putString("key_enc", encryptedKey)
                            .remove("key")
                            .apply()
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

    override fun onDestroy() {
        speechEngine?.stop()
        speechEngine?.shutdown()
        speechEngine = null
        super.onDestroy()
    }
}

@Composable
private fun OpenJarvisScreen(
    initialUrl: String,
    initialModel: String,
    initialKey: String,
    initialTurns: List<ChatTurn>,
    onSpeak: (String) -> Unit,
    onSave: (String, String, String) -> Unit,
    onTurnsChanged: (List<ChatTurn>) -> Unit
) {
    var serverUrl by rememberSaveable { mutableStateOf(initialUrl) }
    var model by rememberSaveable { mutableStateOf(initialModel) }
    var apiKey by rememberSaveable { mutableStateOf(initialKey) }
    var draft by rememberSaveable { mutableStateOf("") }
    var streamingEnabled by rememberSaveable { mutableStateOf(true) }
    var status by remember { mutableStateOf("Connect to your OpenJarvis server to begin.") }
    var busy by remember { mutableStateOf(false) }
    var modelsLoading by remember { mutableStateOf(false) }
    var modelsExpanded by remember { mutableStateOf(false) }
    val availableModels = remember { mutableStateListOf<String>() }
    var activePanel by rememberSaveable { mutableStateOf("") }
    var panelLoading by remember { mutableStateOf(false) }
    var memoryQuery by rememberSaveable { mutableStateOf("") }
    var newAgentName by rememberSaveable { mutableStateOf("") }
    var selectedAgentId by rememberSaveable { mutableStateOf("") }
    var agentTasks by remember { mutableStateOf<List<JarvisTask>>(emptyList()) }
    var taskDraft by rememberSaveable { mutableStateOf("") }
    var memoryResults by remember { mutableStateOf<List<String>>(emptyList()) }
    var memorySummary by remember { mutableStateOf("Memory has not been checked yet.") }
    val managedAgents = remember { mutableStateListOf<JarvisAgent>() }
    val availableTools = remember { mutableStateListOf<JarvisTool>() }
    var panelError by remember { mutableStateOf("") }
    val turns = remember { mutableStateListOf<ChatTurn>().apply { addAll(initialTurns) } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val voiceInput = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) draft = listOf(draft, spoken).filter { it.isNotBlank() }.joinToString(" ")
        }
    }

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
                if (streamingEnabled) {
                    val assistantIndex = turns.size
                    turns.add(ChatTurn("assistant", ""))
                    val answer = OpenJarvisApi.completeStreaming(
                        normalizedUrl, apiKey.trim(), model.trim(), turns.dropLast(1)
                    ) { delta ->
                        withContext(Dispatchers.Main) {
                            if (assistantIndex < turns.size) {
                                turns[assistantIndex] = ChatTurn("assistant", turns[assistantIndex].content + delta)
                            }
                        }
                    }
                    turns[assistantIndex] = ChatTurn("assistant", answer)
                } else {
                    val answer = OpenJarvisApi.complete(normalizedUrl, apiKey.trim(), model.trim(), turns.toList())
                    turns.add(ChatTurn("assistant", answer))
                }
                status = "Response received"
            } catch (error: Exception) {
                if (turns.lastOrNull()?.role == "assistant" && turns.lastOrNull()?.content.isNullOrBlank()) {
                    turns.removeAt(turns.lastIndex)
                }
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Stream replies", style = MaterialTheme.typography.bodySmall)
                Switch(checked = streamingEnabled, onCheckedChange = { streamingEnabled = it })
                Spacer(Modifier.weight(1f))
                Text("Voice input uses your device speech service", style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("Agents", "Tools", "Memory").forEach { label ->
                    FilterChip(
                        selected = activePanel == label,
                        onClick = {
                            activePanel = if (activePanel == label) "" else label
                            panelError = ""
                            if (activePanel == "Agents" || activePanel == "Tools" || activePanel == "Memory") {
                                panelLoading = true
                                scope.launch {
                                    try {
                                        when (activePanel) {
                                            "Agents" -> {
                                                managedAgents.clear()
                                                managedAgents.addAll(OpenJarvisApi.listAgents(serverUrl, apiKey.trim()))
                                            }
                                            "Tools" -> {
                                                availableTools.clear()
                                                availableTools.addAll(OpenJarvisApi.listTools(serverUrl, apiKey.trim()))
                                            }
                                            "Memory" -> {
                                                val stats = OpenJarvisApi.memoryStats(serverUrl, apiKey.trim())
                                                memorySummary = "${stats.entries} entries · ${stats.backend} · ${stats.status}"
                                            }
                                        }
                                    } catch (error: Exception) {
                                        panelError = error.message ?: "Unable to load this OpenJarvis feature"
                                    } finally {
                                        panelLoading = false
                                    }
                                }
                            }
                        },
                        label = { Text(label) }
                    )
                }
            }
            if (activePanel.isNotBlank()) {
                Card(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(activePanel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                panelLoading = true
                                scope.launch {
                                    try {
                                        when (activePanel) {
                                            "Agents" -> {
                                                managedAgents.clear()
                                                managedAgents.addAll(OpenJarvisApi.listAgents(serverUrl, apiKey.trim()))
                                            }
                                            "Tools" -> {
                                                availableTools.clear()
                                                availableTools.addAll(OpenJarvisApi.listTools(serverUrl, apiKey.trim()))
                                            }
                                            "Memory" -> {
                                                val stats = OpenJarvisApi.memoryStats(serverUrl, apiKey.trim())
                                                memorySummary = "${stats.entries} entries · ${stats.backend} · ${stats.status}"
                                            }
                                        }
                                        panelError = ""
                                    } catch (error: Exception) {
                                        panelError = error.message ?: "Refresh failed"
                                    } finally { panelLoading = false }
                                }
                            }, enabled = !panelLoading) { Text("Refresh") }
                        }
                        if (panelLoading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else if (panelError.isNotBlank()) {
                            Text(panelError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        } else when (activePanel) {
                            "Agents" -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = newAgentName,
                                        onValueChange = { newAgentName = it },
                                        label = { Text("New agent name") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            val name = newAgentName.trim()
                                            if (name.isNotBlank()) {
                                                panelLoading = true
                                                scope.launch {
                                                    try {
                                                        OpenJarvisApi.createAgent(serverUrl, apiKey.trim(), name, "monitor_operative")
                                                        newAgentName = ""
                                                        managedAgents.clear()
                                                        managedAgents.addAll(OpenJarvisApi.listAgents(serverUrl, apiKey.trim()))
                                                        panelError = ""
                                                    } catch (error: Exception) {
                                                        panelError = error.message ?: "Agent creation failed"
                                                    } finally { panelLoading = false }
                                                }
                                            }
                                        },
                                        enabled = !panelLoading && newAgentName.isNotBlank()
                                    ) { Text("Create") }
                                }
                                if (managedAgents.isEmpty()) {
                                    Text("No managed agents found. Create one above, if Agent Manager is enabled.")
                                } else LazyColumn(Modifier.heightIn(max = 110.dp)) {
                                    items(managedAgents, key = { it.id }) { agent ->
                                        Column(Modifier.fillMaxWidth()) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(agent.name, style = MaterialTheme.typography.bodyMedium)
                                                    Text("${agent.type} · ${agent.status}", style = MaterialTheme.typography.bodySmall)
                                                }
                                                TextButton(onClick = {
                                                    panelLoading = true
                                                    scope.launch {
                                                        try {
                                                            OpenJarvisApi.setAgentPaused(serverUrl, apiKey.trim(), agent, agent.status != "paused")
                                                            managedAgents.clear()
                                                            managedAgents.addAll(OpenJarvisApi.listAgents(serverUrl, apiKey.trim()))
                                                        } catch (error: Exception) {
                                                            panelError = error.message ?: "Agent action failed"
                                                        } finally { panelLoading = false }
                                                    }
                                                }, enabled = !panelLoading) {
                                                    Text(if (agent.status == "paused") "Resume" else "Pause")
                                                }
                                                TextButton(onClick = {
                                                    panelLoading = true
                                                    scope.launch {
                                                        try {
                                                            status = "Agent run: " + OpenJarvisApi.runAgent(serverUrl, apiKey.trim(), agent)
                                                            managedAgents.clear()
                                                            managedAgents.addAll(OpenJarvisApi.listAgents(serverUrl, apiKey.trim()))
                                                        } catch (error: Exception) {
                                                            panelError = error.message ?: "Unable to start agent"
                                                        } finally { panelLoading = false }
                                                    }
                                                }, enabled = !panelLoading && agent.status != "running" && agent.status != "paused") {
                                                    Text("Run")
                                                }
                                                TextButton(onClick = {
                                                    selectedAgentId = agent.id
                                                    panelLoading = true
                                                    scope.launch {
                                                        try {
                                                            agentTasks = OpenJarvisApi.listAgentTasks(serverUrl, apiKey.trim(), agent)
                                                            panelError = ""
                                                        } catch (error: Exception) {
                                                            panelError = error.message ?: "Unable to load agent tasks"
                                                        } finally { panelLoading = false }
                                                    }
                                                }, enabled = !panelLoading) { Text("Tasks") }
                                            }
                                            if (selectedAgentId == agent.id) {
                                                agentTasks.forEach { task ->
                                                    Text("${task.status}: ${task.description}", style = MaterialTheme.typography.labelSmall)
                                                }
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    OutlinedTextField(
                                                        value = taskDraft,
                                                        onValueChange = { taskDraft = it },
                                                        label = { Text("Add task") },
                                                        singleLine = true,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    TextButton(onClick = {
                                                        val description = taskDraft.trim()
                                                        if (description.isNotBlank()) {
                                                            panelLoading = true
                                                            scope.launch {
                                                                try {
                                                                    OpenJarvisApi.createAgentTask(serverUrl, apiKey.trim(), agent, description)
                                                                    taskDraft = ""
                                                                    agentTasks = OpenJarvisApi.listAgentTasks(serverUrl, apiKey.trim(), agent)
                                                                    panelError = ""
                                                                } catch (error: Exception) {
                                                                    panelError = error.message ?: "Unable to create task"
                                                                } finally { panelLoading = false }
                                                            }
                                                        }
                                                    }, enabled = !panelLoading && taskDraft.isNotBlank()) { Text("Add") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            "Tools" -> {
                                if (availableTools.isEmpty()) Text("No tools returned by the server.")
                                else LazyColumn(Modifier.heightIn(max = 145.dp)) {
                                    items(availableTools, key = { it.name }) { tool ->
                                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                            Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                                            Text("${tool.category} · ${tool.description}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                        }
                                    }
                                }
                            }
                            "Memory" -> {
                                Text(memorySummary, style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = memoryQuery,
                                    onValueChange = { memoryQuery = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("Search long-term memory") },
                                    trailingIcon = {
                                        TextButton(onClick = {
                                            if (memoryQuery.isNotBlank()) {
                                                panelLoading = true
                                                scope.launch {
                                                    try {
                                                        memoryResults = OpenJarvisApi.searchMemory(serverUrl, apiKey.trim(), memoryQuery.trim())
                                                        panelError = ""
                                                    } catch (error: Exception) {
                                                        panelError = error.message ?: "Memory search failed"
                                                    } finally { panelLoading = false }
                                                }
                                            }
                                        }, enabled = !panelLoading && memoryQuery.isNotBlank()) { Text("Search") }
                                    }
                                )
                                if (memoryResults.isNotEmpty()) {
                                    LazyColumn(Modifier.heightIn(max = 80.dp)) {
                                        items(memoryResults) { result ->
                                            Text(result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
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
                                if (!isUser && turn.content.isNotBlank()) {
                                    TextButton(onClick = { onSpeak(turn.content) }) { Text("Read aloud") }
                                }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to OpenJarvis")
                        }
                        runCatching { voiceInput.launch(intent) }
                            .onFailure { status = "Voice input is unavailable on this device" }
                    },
                    enabled = !busy
                ) { Text("Speak") }
                Button(onClick = { sendMessage() }, enabled = !busy && draft.isNotBlank()) {
                    Text(if (busy) "Thinking…" else "Send")
                }
            }
        }
    }
}
