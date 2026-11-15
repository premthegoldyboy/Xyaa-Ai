package ai.xyaa.assistant

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent { XyaaScreen(::handleCommand) { startVoiceInput() } }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
    }

    private fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "xyaa-response")
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to XYAA")
        }
        runCatching { startActivityForResult(intent, 42) }.onFailure {
            say("Voice input is unavailable on this device.")
        }
    }

    @Deprecated("Deprecated by Android; kept for broad device compatibility in this starter.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == Activity.RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) handleCommand(spoken)
        }
    }

    private fun handleCommand(raw: String): String {
        val command = raw.trim()
        val match = Regex("^(?:please\\s+)?(?:open|launch|start)\\s+(.+?)[.!?]?$", RegexOption.IGNORE_CASE)
            .find(command)
        if (match != null) {
            val requested = match.groupValues[1].trim().trim('"', '\'')
            val result = openInstalledApp(requested)
            say(result)
            return result
        }
        val response = "I can open installed apps. Try saying, open YouTube."
        say(response)
        return response
    }

    private fun openInstalledApp(requested: String): String {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, PackageManager.MATCH_DEFAULT_ONLY)
        val candidates = apps.mapNotNull { info ->
            val label = info.loadLabel(packageManager).toString()
            Triple(label, info.activityInfo.packageName, info.activityInfo.name)
        }
        val exact = candidates.filter { it.first.equals(requested, ignoreCase = true) }
        val partial = candidates.filter { it.first.contains(requested, ignoreCase = true) }
        val matches = if (exact.isNotEmpty()) exact else partial
        val uniquePackages = matches.distinctBy { it.second }
        if (uniquePackages.size == 1) {
            val packageName = uniquePackages.first().second
            val launch = packageManager.getLaunchIntentForPackage(packageName)
                ?: return "I found $requested, but Android did not provide a launch action."
            return try {
                startActivity(launch)
                "Opening ${uniquePackages.first().first}."
            } catch (_: Exception) {
                "Android could not open ${uniquePackages.first().first}."
            }
        }
        if (uniquePackages.size > 1) {
            val names = uniquePackages.take(4).joinToString(", ") { it.first }
            return "I found more than one match: $names. Which one do you mean?"
        }
        return "I couldn't find an installed app named $requested."
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun XyaaScreen(onCommand: (String) -> String, onVoice: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("Hi, I'm XYAA. Say or type “open YouTube” to launch an installed app.") }
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("XYAA", style = MaterialTheme.typography.headlineLarge)
                Text("Your Android assistant", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    Text(reply, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.weight(1f))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tell XYAA what to do") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { if (input.isNotBlank()) reply = onCommand(input) }, modifier = Modifier.weight(1f)) {
                        Text("Send")
                    }
                    OutlinedButton(onClick = onVoice, modifier = Modifier.weight(1f)) {
                        Text("Speak")
                    }
                }
            }
        }
    }
}
