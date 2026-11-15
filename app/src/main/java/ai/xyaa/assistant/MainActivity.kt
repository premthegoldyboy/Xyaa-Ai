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
import androidx.compose.foundation.layout.*
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
        setContent {
            XyaaScreen(::handleCommand, ::startVoiceInput, ::openVoiceSettings)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
    }

    private fun say(text: String) {
        val spoken = text.replace(Regex("(?i)\\bXYAA\\b"), "Zyaa")
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "xyaa-response")
    }

    private fun openVoiceSettings() {
        val intent = Intent("com.android.settings.TTS_SETTINGS")
        if (intent.resolveActivity(packageManager) == null) intent.action = Settings.ACTION_SETTINGS
        runCatching { startActivity(intent) }.onFailure {
            say("I couldn't open voice settings on this device.")
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
        runCatching { startActivityForResult(intent, 42) }.onFailure {
            say("Voice input is unavailable on this device.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 43 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceInput()
        else if (requestCode == 43) say("Microphone permission is needed for voice input.")
    }

    @Deprecated("Deprecated by Android; retained for compatibility with this starter.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == Activity.RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) handleCommand(spoken)
        }
    }

    private fun handleCommand(raw: String): String {
        val match = Regex("^(?:please\\s+)?(?:open|launch|start)\\s+(.+?)[.!?]?$", RegexOption.IGNORE_CASE)
            .find(raw.trim())
        if (match != null) {
            val requested = match.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
            return openInstalledApp(requested).also(::say)
        }
        return "I can open installed apps. Try saying, open YouTube.".also(::say)
    }

    private fun openInstalledApp(requested: String): String {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = packageManager.queryIntentActivities(launcher, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
        val exact = candidates.filter { it.first.equals(requested, ignoreCase = true) }
        val matches = if (exact.isNotEmpty()) exact else candidates.filter { it.first.contains(requested, ignoreCase = true) }
        val unique = matches.distinctBy { it.second }
        if (unique.size == 1) {
            val (label, pkg) = unique.first()
            val launch = packageManager.getLaunchIntentForPackage(pkg)
                ?: return "I found $label, but Android did not provide a launch action."
            return try { startActivity(launch); "Opening $label." }
            catch (_: Exception) { "Android could not open $label." }
        }
        if (unique.size > 1) return "I found multiple matches: ${unique.take(4).joinToString(", ") { it.first }}. Which one?"
        return "I couldn't find an installed app named $requested."
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun XyaaScreen(onCommand: (String) -> String, onVoice: () -> Unit, onVoiceSettings: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("Hi, I'm Zyaa. Say “open YouTube” to launch an installed app.") }
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("XYAA", style = MaterialTheme.typography.headlineLarge)
                Text("Your Android assistant", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) { Text(reply, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
                OutlinedButton(onClick = onVoiceSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose XYAA's voice")
                }
                Text("Install a compatible neural TTS engine such as HayaiTTS, then select its voice in Android settings.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Tell XYAA what to do") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { if (input.isNotBlank()) reply = onCommand(input) }, modifier = Modifier.weight(1f)) { Text("Send") }
                    OutlinedButton(onClick = onVoice, modifier = Modifier.weight(1f)) { Text("Speak") }
                }
            }
        }
    }
}
