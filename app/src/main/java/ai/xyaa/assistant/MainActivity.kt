package ai.xyaa.assistant

import android.app.Activity
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    private lateinit var managementAgent: ManagementAgent
    private lateinit var neuralVoice: NeuralVoiceEngine
    private lateinit var conversationMemory: ConversationMemory
    private val recentMemory = mutableStateOf<List<String>>(emptyList())
    private var liveVoiceSession: GeminiLiveVoiceSession? = null
    private var pdfDraft = ""
    private var speechRecognizer: SpeechRecognizer? = null
    private val voiceInputStatus = mutableStateOf("")
    private val liveVoiceActive = mutableStateOf(false)
    private val liveVoiceStatus = mutableStateOf("Live voice is off")
    private val liveTranscript = mutableStateOf("")
    private val wakeWordEnabled = mutableStateOf(false)
    private var wakeReceiverRegistered = false
    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                WakeWordService.ACTION_STATE -> wakeWordEnabled.value = intent.getBooleanExtra(WakeWordService.EXTRA_ACTIVE, false)
                WakeWordService.ACTION_WAKE_DETECTED -> {
                    wakeWordEnabled.value = false
                    liveVoiceStatus.value = "Wake word detected · opening live voice…"
                    stopWakeWord()
                    window.decorView.postDelayed({ startLiveVoice() }, 600)
                }
            }
        }
    }
    private val neuralVoiceStatus = mutableStateOf("Neural voice not downloaded (about 400 MB)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        skills = SkillEngine(this)
        managementAgent = ManagementAgent(this)
        conversationMemory = ConversationMemory(this)
        recentMemory.value = conversationMemory.recentTurns().map { "${if (it.role == "user") "You" else "XYAA"}: ${it.text}" }
        neuralVoice = NeuralVoiceEngine(this) { status -> runOnUiThread { neuralVoiceStatus.value = status } }
        neuralVoice.initializeIfInstalled()
        tts = TextToSpeech(this, this)
        // Edge-to-edge on every API level so Compose receives real system-bar and IME insets (one source of truth).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            XyaaPremiumApp(
                onSend = { prompt, answer -> process(prompt, answer) },
                onVoice = ::startVoiceInput,
                voiceInputStatus = voiceInputStatus.value,
                onSaveKey = { key -> SecureKeyStore(this).save(key) },
                onLoadKey = { SecureKeyStore(this).read() },
                onSaveProviderKeys = { groq, deepSeek, tavily ->
                    ProviderKeyStore(this).apply { save("groq", groq); save("deepseek", deepSeek); save("tavily", tavily) }
                },
                onLoadProviderKeys = { ProviderKeyStore(this).let { listOf(it.read("groq").orEmpty(), it.read("deepseek").orEmpty(), it.read("tavily").orEmpty()) } },
                neuralVoiceStatus = neuralVoiceStatus.value,
                onDownloadNeuralVoice = { neuralVoice.downloadAndInitialize() },
                onClearMemory = { conversationMemory.clear(); recentMemory.value = emptyList() },
                onShareReport = ::shareResearchReport,
                recentMemory = recentMemory.value,
                liveVoiceActive = liveVoiceActive.value,
                liveVoiceStatus = liveVoiceStatus.value,
                liveTranscript = liveTranscript.value,
                onStartLiveVoice = ::startLiveVoice,
                onStopLiveVoice = ::stopLiveVoice,
                wakeWordEnabled = wakeWordEnabled.value,
                onStartWakeWord = ::startWakeWord,
                onStopWakeWord = ::stopWakeWord
            )
        }
        if (intent?.getBooleanExtra(WakeWordService.EXTRA_START_LIVE_VOICE, false) == true) {
            window.decorView.postDelayed({ stopWakeWord(); startLiveVoice() }, 700)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!wakeReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WakeWordService.ACTION_STATE)
                addAction(WakeWordService.ACTION_WAKE_DETECTED)
            }
            ContextCompat.registerReceiver(this, wakeWordReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            wakeReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (wakeReceiverRegistered) {
            unregisterReceiver(wakeWordReceiver)
            wakeReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(WakeWordService.EXTRA_START_LIVE_VOICE, false)) {
            window.decorView.postDelayed({ stopWakeWord(); startLiveVoice() }, 600)
        }
    }

    private fun startLiveVoice() {
        stopSpeech() // never let the speaker talk into the live session's microphone
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 47)
            liveVoiceStatus.value = "Allow microphone access to start live voice."
            return
        }
        val key = SecureKeyStore(this).read()
        if (key.isNullOrBlank()) {
            liveVoiceStatus.value = "Add your Gemini API key in Settings first."
            return
        }
        if (liveVoiceActive.value) return
        liveTranscript.value = ""
        liveVoiceActive.value = true
        liveVoiceSession = GeminiLiveVoiceSession(
            context = this,
            apiKey = key,
            onStatus = { status -> runOnUiThread { liveVoiceStatus.value = status } },
            onTranscript = { text, fromUser ->
                runOnUiThread {
                    val line = (if (fromUser) "You: " else "XYAA: ") + text.trim()
                    liveTranscript.value = (liveTranscript.value + "\n" + line).trim().takeLast(2400)
                }
            },
            onClosed = {
                runOnUiThread {
                    liveVoiceActive.value = false
                    liveVoiceSession = null
                }
            }
        ).also { it.start() }
    }

    private fun stopLiveVoice() {
        liveVoiceSession?.stop()
        liveVoiceSession = null
        liveVoiceActive.value = false
        liveVoiceStatus.value = "Live voice is off"
    }

    private fun startWakeWord() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            val permissions = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissions(permissions.toTypedArray(), 46)
            return
        }
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
            wakeWordEnabled.value = true
        }.onFailure {
            liveVoiceStatus.value = "Wake word couldn't start: ${it.message ?: "Android blocked the microphone service"}"
            wakeWordEnabled.value = false
        }
    }

    private fun stopWakeWord() {
        stopService(Intent(this, WakeWordService::class.java))
        wakeWordEnabled.value = false
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
        engine.setOnUtteranceProgressListener(deviceVoiceListener)
        deviceVoiceReady = true
    }

    // ---- Speech -------------------------------------------------------------------------------
    // One path for everything XYAA says. The text is cleaned once (AssistantTextFormatter.forSpeech) and the
    // same transcript is handed to Kokoro, or to Android TTS if Kokoro is missing or fails. Exactly one
    // engine speaks at a time, a newer request supersedes the older one, and every session completes once.

    private val speechHandler = Handler(Looper.getMainLooper())
    private var speechSession: SpeechSession? = null // main thread only
    @Volatile private var deviceVoiceReady = false

    private inner class SpeechSession(
        val plan: AssistantTextFormatter.SpeechPlan,
        val onProgress: (String) -> Unit,
        val onComplete: () -> Unit
    ) {
        val idPrefix = "xyaa-" + System.nanoTime() + "-"
        var done = false
        var lastPartId = ""
        var sawRange = false
        val partOffsets = HashMap<String, Int>()
        var ticker: Runnable? = null
        var tickWord = 0

        fun owns(id: String?) = id != null && id.startsWith(idPrefix)
        fun cancelTicker() { ticker?.let(speechHandler::removeCallbacks); ticker = null }
        fun progress(visible: String) {
            if (!done && speechSession === this && visible.isNotBlank()) onProgress(visible)
        }
        fun finish() {
            if (done) return
            done = true
            cancelTicker()
            if (speechSession === this) speechSession = null
            onProgress(plan.display)
            onComplete()
        }
    }

    /** Stops whatever is being spoken. The interrupted session still completes once, with its full text. */
    private fun stopSpeech(completeInterrupted: Boolean = true) {
        val session = speechSession
        speechSession = null
        runCatching { neuralVoice.stop() }
        runCatching { tts?.stop() }
        session?.cancelTicker()
        if (session != null && !session.done) {
            if (completeInterrupted) { speechSession = session; session.finish() } else session.done = true
        }
    }

    private fun say(text: String) = speak(text)

    private fun speakChatAnswer(text: String, onProgress: (String) -> Unit, onComplete: () -> Unit) =
        speak(text, onProgress, onComplete)

    private fun speak(text: String, onProgress: (String) -> Unit = {}, onComplete: () -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            speechHandler.post { speak(text, onProgress, onComplete) }
            return
        }
        val plan = AssistantTextFormatter.forSpeech(text)
        stopSpeech()
        if (plan.isSilent) {
            onProgress(plan.display)
            onComplete()
            return
        }
        val session = SpeechSession(plan, onProgress, onComplete)
        speechSession = session
        val neuralQueued = neuralVoice.speak(
            plan.spoken,
            // Transcript is published as each chunk starts playing, so text follows the audio.
            onChunk = { spokenPrefix -> speechHandler.post { session.progress(plan.displayPrefix(spokenPrefix.length)) } },
            onComplete = { speechHandler.post { session.finish() } },
            // Kokoro failed part-way: continue on the device voice after what was already heard.
            fallback = { playedChars -> speechHandler.post { speakOnDevice(session, playedChars) } }
        )
        if (!neuralQueued) speakOnDevice(session, 0)
    }

    private fun speakOnDevice(session: SpeechSession, fromChar: Int) {
        if (session.done || speechSession !== session) return
        val engine = tts
        if (engine == null || !deviceVoiceReady) { session.finish(); return }
        val spoken = session.plan.spoken
        val start = fromChar.coerceIn(0, spoken.length)
        val skipped = spoken.substring(start).length - spoken.substring(start).trimStart().length
        val body = spoken.substring(start + skipped)
        if (body.isEmpty()) { session.finish(); return }
        val base = start + skipped
        val parts = splitForDeviceVoice(body)
        session.sawRange = false
        session.tickWord = session.plan.wordsThrough(start)
        parts.forEachIndexed { index, (offset, partText) ->
            val id = session.idPrefix + index
            session.partOffsets[id] = base + offset
            if (index == parts.lastIndex) session.lastPartId = id
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engine.speak(partText, mode, null, id) == TextToSpeech.ERROR) {
                engine.stop()
                session.finish()
                return
            }
        }
    }

    /** Android TTS rejects input over getMaxSpeechInputLength(); split at sentence boundaries. */
    private fun splitForDeviceVoice(body: String, limit: Int = 3000): List<Pair<Int, String>> {
        val parts = ArrayList<Pair<Int, String>>()
        var pos = 0
        while (pos < body.length) {
            var end = minOf(body.length, pos + limit)
            if (end < body.length) {
                val window = body.substring(pos, end)
                val sentence = maxOf(window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? "))
                val cut = if (sentence > limit / 3) sentence else window.lastIndexOf(' ')
                if (cut > 0) end = pos + cut + 1
            }
            parts.add(pos to body.substring(pos, end))
            pos = end
            while (pos < body.length && body[pos] == ' ') pos++
        }
        return parts
    }

    private val deviceVoiceListener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {
            speechHandler.post {
                val s = speechSession?.takeIf { it.owns(id) && !it.done } ?: return@post
                // Engines that never report word ranges still get a paced reveal.
                if (s.ticker != null) return@post
                val tick = object : Runnable {
                    override fun run() {
                        if (s.done || speechSession !== s || s.sawRange) return
                        if (s.tickWord < s.plan.wordCount) {
                            s.tickWord++
                            s.progress(s.plan.displayAfterWords(s.tickWord))
                            speechHandler.postDelayed(this, 300L)
                        }
                    }
                }
                s.ticker = tick
                speechHandler.postDelayed(tick, 350L)
            }
        }
        override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
            speechHandler.post {
                val s = speechSession?.takeIf { it.owns(id) && !it.done } ?: return@post
                val offset = id?.let { s.partOffsets[it] } ?: return@post
                s.sawRange = true
                s.cancelTicker()
                s.progress(s.plan.displayPrefix(offset + end))
            }
        }
        override fun onDone(id: String?) {
            speechHandler.post {
                val s = speechSession?.takeIf { it.owns(id) } ?: return@post
                if (id == s.lastPartId) s.finish()
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) {
            speechHandler.post {
                val s = speechSession?.takeIf { it.owns(id) } ?: return@post
                runCatching { tts?.stop() }
                s.finish()
            }
        }
        override fun onError(id: String?, errorCode: Int) = onError(id)
        override fun onStop(id: String?, interrupted: Boolean) {
            // Interruptions we cause go through stopSpeech(); this only handles the engine stopping on its own.
            speechHandler.post {
                val s = speechSession?.takeIf { it.owns(id) } ?: return@post
                if (interrupted) s.finish()
            }
        }
    }

    private fun shareResearchReport(report: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "XYAA Research Report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        runCatching { startActivity(Intent.createChooser(sendIntent, "Share research report")) }
    }

    private fun rememberTurn(role: String, text: String) {
        conversationMemory.remember(role, if (role == "assistant") AssistantTextFormatter.forMemory(text) else text)
        recentMemory.value = conversationMemory.recentTurns().map { "${if (it.role == "user") "You" else "XYAA"}: ${it.text}" }
    }


    private fun launchPdfFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, 72) }
            .onFailure { say("I couldn't open the folder picker: ${it.message.orEmpty().take(100)}") }
    }

    private fun process(prompt: String, answer: (String, Boolean) -> Unit) {
        rememberTurn("user", prompt)
        // A single routing decision prevents generic browser skills from stealing research requests.
        val agentPlan = XYAAAgentOrchestrator.plan(prompt, managementAgent)
        if (Regex("(?i)\\b(scan|import|index)\\b").containsMatchIn(prompt) &&
            Regex("(?i)\\b(phone|device|storage|folder|all|my)\\b").containsMatchIn(prompt) &&
            Regex("(?i)\\b(pdf|pdfs|documents)\\b").containsMatchIn(prompt)) {
            answer("Choose the folder that contains your PDFs. XYAA will scan that folder and its subfolders.", true)
            launchPdfFolderPicker()
            return
        }
        val pdfRequest = agentPlan.createPdf
        val managed = if (pdfRequest || agentPlan.kind != XYAAAgentKind.MANAGEMENT) null else managementAgent.execute(prompt)
        if (managed != null) {
            rememberTurn("assistant", managed)
            answer(managed, true)
            say(managed)
            return
        }
        if (!pdfRequest && agentPlan.kind == XYAAAgentKind.MANAGEMENT && managementAgent.isManagementRequest(prompt)) {
            val key = SecureKeyStore(this).read()
            val providers = ProviderKeyStore(this)
            val groq = providers.read("groq")
            val deepSeek = providers.read("deepseek")
            if (key.isNullOrBlank() && groq.isNullOrBlank() && deepSeek.isNullOrBlank()) {
                val message = "The management agent needs an AI provider key to interpret that request. Simple note and PDF commands still work offline."
                rememberTurn("assistant", message)
                answer(message, true)
                say(message)
                return
            }
            answer("XYAA management agent is planning…", false)
            Thread {
                val plan = runCatching {
                    if (!key.isNullOrBlank()) askGemini(key, managementAgent.plannerPrompt(prompt), {}, {})
                    else ProviderFallbackClient(groq, deepSeek).complete(managementAgent.plannerPrompt(prompt)) {}
                }.getOrElse { "Could not create a safe management plan: ${it.message.orEmpty().take(120)}" }
                val result = managementAgent.executePlan(plan, prompt)
                    ?: "I couldn't turn that request into a safe file operation. Please specify the note or PDF and the action."
                rememberTurn("assistant", result)
                runOnUiThread {
                    answer(result, true)
                    speakChatAnswer(result, onProgress = { visible -> answer(visible, false) }, onComplete = { answer(result, true) })
                }
            }.start()
            return
        }
        if (!pdfRequest && agentPlan.kind == XYAAAgentKind.LINKS) {
            val result = LinkAgent.generate(prompt)
            rememberTurn("assistant", result)
            answer(result, true)
            say("Links prepared.")
            return
        }
        if (!pdfRequest && agentPlan.kind == XYAAAgentKind.MEMORY) {
            val recalled = ConversationMemoryAgent.execute(prompt, conversationMemory)
            if (recalled != null) {
                rememberTurn("assistant", recalled)
                answer(recalled, true)
                say(recalled)
                return
            }
        }
        val local = if (pdfRequest || agentPlan.needsResearch) null else skills.execute(prompt)
        if (local != null) {
            rememberTurn("assistant", local)
            answer(local, true)
            say(local)
            return
        }
        val key = SecureKeyStore(this).read()
        val providerKeys = ProviderKeyStore(this)
        val groqKey = providerKeys.read("groq")
        val deepSeekKey = providerKeys.read("deepseek")
        val tavilyKey = providerKeys.read("tavily")
        if (key.isNullOrBlank() && groqKey.isNullOrBlank() && deepSeekKey.isNullOrBlank()) {
            val message = "I can handle built-in skills offline. Add a Gemini, Groq, or DeepSeek API key in Settings to enable AI conversations."
            rememberTurn("assistant", message)
            answer(message, true)
            say(message)
            return
        }

        // Keep the animated orb visible for the entire generation phase.
        answer("Thinking…", false)
        Thread {
            val memoryContext = conversationMemory.contextForPrompt()
            val contextualPrompt = "You are XYAA, a helpful, precise personal AI with a playful, upbeat, warmly feminine conversational style. Sound naturally witty, curious, encouraging, and expressive; use light humor and occasional fitting emojis when they add warmth, but never force jokes, overuse emojis, or act childish. Be friendly and conversational rather than stiff or corporate. Keep replies concise for simple chats and clear and well-structured for serious, technical, or sensitive topics. Match the user's energy without mocking them, flirting, or pretending to be human. Do not introduce yourself, repeat your name, or begin routine replies with phrases like \"I'm XYAA\" or \"As XYAA\"; the app already identifies you. Only explain your identity when the user asks. Answer directly, explain uncertainty, and never invent sources or claim actions you did not perform.\\n\\n" +
                (if (memoryContext.isBlank()) "" else "Recent context:\\n$memoryContext\\n\\n") + "Current request: $prompt"
            val aiPrompt = if (pdfRequest) PdfAgent.contentPrompt(prompt) else contextualPrompt

            val researchRequest = agentPlan.needsResearch
            if (researchRequest) {
                val topic = extractResearchTopic(prompt, pdfRequest)
                val report = runCatching {
                    ResearchEngine(key, tavilyKey, groqKey, deepSeekKey).research(topic) { _ ->
                        runOnUiThread { answer("Thinking…", false) }
                    }
                }
                report.onSuccess { result ->
                    val rendered = result.asText()
                    val spokenSummary = result.spoken.ifBlank { "Research report completed." }
                    if (pdfRequest) {
                        pdfDraft = rendered
                        rememberTurn("assistant", "Created a research-backed PDF about ${result.topic}.")
                        runOnUiThread {
                            answer("Preparing your PDF…", false)
                            // The complete document belongs in the PDF, not duplicated in the chat bubble.
                            openPdfPicker()
                            speakChatAnswer(
                                spokenSummary,
                                onProgress = { visible -> answer(visible, false) },
                                onComplete = {
                                    answer("$spokenSummary\n\nPDF ready. Choose a location to save it.", true)
                                }
                            )
                        }
                    } else {
                        rememberTurn("assistant", spokenSummary)
                        runOnUiThread {
                            answer("Research complete. Speaking the summary…", false)
                            speakChatAnswer(
                                spokenSummary,
                                onProgress = { visible -> answer(visible, false) },
                                onComplete = { answer(rendered, true) }
                            )
                        }
                    }
                }.onFailure { error ->
                    val message = "Research failed: ${error.message.orEmpty().take(180)}"
                    rememberTurn("assistant", message)
                    runOnUiThread { answer(message, false); speakChatAnswer(message, { answer(it, false) }) { answer(message, true) } }
                }
                return@Thread
            }

            val response = runCatching {
                if (key.isNullOrBlank()) throw IllegalStateException("Gemini key not configured.")
                askGemini(
                    key,
                    aiPrompt,
                    onStatus = { _ -> runOnUiThread { answer("Thinking…", false) } },
                    onText = { _ -> /* Keep generated tokens hidden until Gemini completes. */ }
                )
            }.recoverCatching { geminiError ->
                if (groqKey.isNullOrBlank() && deepSeekKey.isNullOrBlank()) throw geminiError
                runOnUiThread { answer("Thinking…", false) }
                ProviderFallbackClient(groqKey, deepSeekKey).complete(aiPrompt) { _ ->
                    runOnUiThread { answer("Thinking…", false) }
                }
            }.getOrElse { formatAiError(it) }

            if (pdfRequest) rememberTurn("assistant", "Created the requested PDF document.")
            else rememberTurn("assistant", response)
            runOnUiThread {
                if (pdfRequest && response.isNotBlank()) {
                    pdfDraft = response
                    answer("Your PDF is ready. Choose a location to save it.", true)
                    openPdfPicker()
                    say("Your PDF is ready. Choose a location to save it.")
                } else {
                    // Reveal the same transcript progressively as the speech engine advances.
                    answer("Speaking…", false)
                    speakChatAnswer(response,
                        onProgress = { visible -> answer(visible, false) },
                        onComplete = { answer(response, true) })
                }
            }
        }.start()
    }


    private fun openPdfPicker() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "XYAA-Document.pdf")
        }
        startActivityForResult(intent, 71)
    }

    private fun writePdf(uri: android.net.Uri) {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 48
        val contentWidth = pageWidth - margin * 2
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(43, 42, 53)
            textSize = 10.8f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            // Disable ligatures: some Android PDF font maps otherwise lose combined glyphs on extraction.
            fontFeatureSettings = "'liga' 0, 'clig' 0"
        }
        val h1Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(45, 32, 78)
            textSize = 21f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            fontFeatureSettings = "'liga' 0, 'clig' 0"
        }
        val h2Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(68, 48, 112)
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            fontFeatureSettings = "'liga' 0, 'clig' 0"
        }
        val h3Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(76, 72, 94)
            textSize = 12f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            fontFeatureSettings = "'liga' 0, 'clig' 0"
        }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(119, 112, 137)
            textSize = 8.5f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            fontFeatureSettings = "'liga' 0, 'clig' 0"
        }
        var pageNumber = 0
        fun beginPage(): PdfDocument.Page {
            pageNumber++
            val next = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            next.canvas.drawText("XYAA  /  RESEARCH DOCUMENT", margin.toFloat(), 31f, metaPaint)
            val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(224, 220, 234)
                strokeWidth = 0.8f
            }
            next.canvas.drawLine(margin.toFloat(), 42f, (pageWidth - margin).toFloat(), 42f, rule)
            return next
        }
        var page = beginPage()
        var y = 62f
        fun finishPage() {
            val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(224, 220, 234)
                strokeWidth = 0.8f
            }
            page.canvas.drawLine(margin.toFloat(), (pageHeight - 38).toFloat(),
                (pageWidth - margin).toFloat(), (pageHeight - 38).toFloat(), rule)
            page.canvas.drawText("Prepared with XYAA", margin.toFloat(), (pageHeight - 22).toFloat(), metaPaint)
            val pageLabel = "Page $pageNumber"
            page.canvas.drawText(pageLabel, pageWidth - margin - metaPaint.measureText(pageLabel),
                (pageHeight - 22).toFloat(), metaPaint)
            document.finishPage(page)
        }
        fun nextPage() {
            finishPage()
            page = beginPage()
            y = 62f
        }
        fun drawBlock(text: String, paint: TextPaint, before: Float = 0f, after: Float = 6f) {
            if (text.isBlank()) { y += 5f; return }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(3f, 1.0f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build()
            y += before
            if (y + layout.height + after > pageHeight - 58) nextPage()
            page.canvas.save()
            page.canvas.translate(margin.toFloat(), y)
            layout.draw(page.canvas)
            page.canvas.restore()
            y += layout.height + after
        }
        try {
            val lines = pdfDraft.replace("\r", "").lines()
            val tableRow = Regex("^\\s*\\|.*\\|\\s*$")
            var index = 0
            while (index < lines.size) {
                val raw = lines[index].trim()
                if (raw.isBlank()) {
                    y += 5f
                    index++
                    continue
                }
                if (tableRow.matches(raw)) {
                    val cells = raw.trim('|').split('|').map { it.trim() }
                    val separator = cells.all { it.matches(Regex(":?-{3,}:?")) }
                    if (!separator) {
                        val label = cells.firstOrNull().orEmpty()
                        val detail = cells.drop(1).filter { it.isNotBlank() }.joinToString("  ·  ")
                        drawBlock(if (detail.isBlank()) label else "$label:  $detail",
                            bodyPaint, before = 1f, after = 5f)
                    }
                    index++
                    continue
                }
                val heading = Regex("^(#{1,6})\\s*(.*)$").matchEntire(raw)
                if (heading != null) {
                    val level = heading.groupValues[1].length
                    val title = heading.groupValues[2].trim()
                    when (level) {
                        1 -> drawBlock(title, h1Paint, before = 14f, after = 11f)
                        2 -> drawBlock(title, h2Paint, before = 13f, after = 7f)
                        else -> drawBlock(title, h3Paint, before = 10f, after = 5f)
                    }
                } else {
                    val bullet = Regex("^([-*+]\\s+|\\d+[.)]\\s+)").containsMatchIn(raw)
                    val clean = raw.replace(Regex("^[-*+]\\s+"), "• ")
                        .replace(Regex("^(\\d+[.)])\\s+"), "$1  ")
                        .replace(Regex("\\*{1,3}([^*]+)\\*{1,3}"), "$1")
                        .replace(Regex("\\u0060([^\\u0060]+)\\u0060"), "$1")
                    drawBlock(clean, bodyPaint, before = if (bullet) 1f else 2f, after = if (bullet) 5f else 7f)
                }
                index++
            }
            finishPage()
            contentResolver.openOutputStream(uri)?.use { output -> document.writeTo(output) }
                ?: error("Could not open the selected save location")
        } finally {
            document.close()
        }
    }

    private fun isResearchRequest(prompt: String): Boolean = Regex(
        "^\\s*(research|investigate|compare|make (?:me )?a report(?: on)?|write (?:me )?a report(?: on)?|find out about|summari[sz]e (?:the )?(?:latest )?(?:news|information) about)\\b",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(prompt)

    private fun extractResearchTopic(prompt: String, forPdf: Boolean = false): String {
        if (forPdf) {
            val documentSubject = prompt
                .replace(Regex("(?i)\\s+(?:and\\s+)?(?:please\\s+)?(?:create|make|generate|export|save)\\s+(?:me\\s+)?(?:a\\s+)?pdf.*$"), "")
                .replace(Regex("(?i)^\\s*(?:please\\s+)?(?:search(?:\\s+the)?\\s+web|web\\s+search|search\\s+for|google|look\\s+up|research|investigate)\\s+(?:for\\s+|about\\s+|on\\s+)?"), "")
                .replace(Regex("(?i)^\\s*(?:please\\s+)?(?:i\\s+)?(?:want|need|would like)\\s+(?:you\\s+to\\s+)?"), "")
                .replace(Regex("(?i)^\\s*(?:create|make|generate|prepare|export)\\s+(?:me\\s+)?"), "")
                .replace(Regex("(?i)^\\s*(?:a\\s+)?pdf\\s+(?:of|about|on)\\s+"), "")
                .replace(Regex("(?i)^\\s*(?:complete|full|detailed|comprehensive)\\s+(?:technical\\s+)?spe.{1,4}fic\\w*\\s+(?:(?:of|for)\\s+)?"), "")
                .replace(Regex("(?i)^\\s*spe.{1,4}fic\\w*\\s+(?:of|for)\\s+"), "")
                .replace(Regex("(?i)\\s+(?:and\\s+)?(?:make|create|generate|export|save)\\s+(?:me\\s+)?(?:a\\s+)?pdf.*$"), "")
                .trim().trimEnd('.', '?')
            if (documentSubject.isNotBlank()) return documentSubject
        }
        val researchStart = Regex(
            "(?i)\\b(?:research|investigate|find out about|look up information about)\\b\\s*(?:about\\s+|on\\s+)?"
        ).find(prompt)
        var topic = if (researchStart != null) prompt.substring(researchStart.range.last + 1).trim()
        else prompt.trim()
        topic = topic.replaceFirst(
            Regex("(?i)^(?:please\\s+)?(?:can you\\s+|could you\\s+)?(?:compare|make (?:me )?a report(?: on)?|write (?:me )?a report(?: on)?|summari[sz]e)\\s*"),
            ""
        ).trim()
        if (forPdf) {
            topic = topic.replace(
                Regex("(?i)\\s+(?:and\\s+)?(?:make|create|generate|export|save)\\s+(?:me\\s+)?(?:a\\s+)?pdf(?:\\s+of\\s+(?:it|this|the report))?.*$"),
                ""
            ).trim()
        }
        return topic.trim().trimEnd(',', '.', '?').ifBlank { prompt.trim() }
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
            "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.7-flash",
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

    private fun askGemini(
        apiKey: String,
        prompt: String,
        onStatus: (String) -> Unit,
        onText: (String) -> Unit
    ): String {
        // Keep model discovery off the critical path: first-token latency matters most.
        val fastFallback = listOf(
            "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.7-flash",
            "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.1-flash-lite"
        )
        val now = System.currentTimeMillis()
        val cached = cachedGeminiModels.takeIf {
            it.isNotEmpty() && now - modelCacheTimeMs < 6 * 60 * 60 * 1000L
        }
        val models = (cached ?: fastFallback).take(3)
        if (cached == null) Thread { runCatching { discoverGeminiModels(apiKey) } }.start()

        var lastError: Throwable? = null
        models.forEachIndexed { index, model ->
            onStatus("Connecting to Gemini…")
            try {
                return requestGemini(apiKey, prompt, model, onText)
            } catch (error: Throwable) {
                lastError = error
                if (!isTemporaryAiFailure(error) && !isUnavailableModel(error)) throw error
                if (index < models.lastIndex) {
                    onText("")
                    onStatus("Switching to another fast Gemini model…")
                }
            }
        }
        throw IllegalStateException("Fast Gemini models were busy or unavailable. Please retry in a moment.", lastError)
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

    private fun requestGemini(
        apiKey: String,
        prompt: String,
        model: String,
        onText: (String) -> Unit
    ): String {
        val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey.trim())
        }
        try {
            val body = JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                    "You are XYAA (pronounced Zyaa), an Android AI assistant. Do not introduce yourself, repeat your name, or begin routine replies with phrases like \"I'm XYAA\" or \"As XYAA\"; the app already identifies you. Only explain your identity when the user asks. Follow the user's actual request, not merely its topic. First identify the requested action, subject, deliverable or file format, completeness level, and constraints; satisfy every explicit requirement. If the user asks you to create a document or PDF, produce complete subject-specific content for the app's PDF export workflow—never tell the user to copy text and make the file themselves. For factual or current product specifications, research and cite reliable sources, distinguish model/region variants, and never invent missing values. Do not replace requested actions with advice or a generic explanation. If an action cannot be completed by the app, state the limitation honestly and do not claim it was done. Be concise when the user asks for a short answer, but do not omit requested detail. Spoken audio is provided by the Android app; never claim you are text-only."))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", 0.35)
                    .put("maxOutputTokens", if (prompt.contains("complete document content", ignoreCase = true)) 4096 else 1024))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val payload = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val apiMessage = runCatching {
                    JSONObject(payload).getJSONObject("error").optString("message")
                }.getOrDefault("")
                throw IllegalStateException("AI service error: HTTP $status. " + apiMessage.ifBlank { "Google did not provide an explanation." })
            }

            // Consume Server-Sent Events and render each generated text chunk immediately.
            val accumulated = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substringAfter("data:").trim()
                    if (data.isBlank() || data == "[DONE]") continue
                    val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    val candidates = chunk.optJSONArray("candidates") ?: continue
                    if (candidates.length() == 0) continue
                    val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: continue
                    val parts = content.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val piece = parts.optJSONObject(i)?.optString("text").orEmpty()
                        if (piece.isNotBlank()) {
                            accumulated.append(piece)
                            onText(accumulated.toString())
                        }
                    }
                }
            }
            val responseText = accumulated.toString().trim()
            if (responseText.isBlank()) throw IllegalStateException("AI service error: Google returned no streamed text.")
            return responseText
        } finally {
            connection.disconnect()
        }
    }

    private fun startVoiceInput() {
        stopSpeech() // the recognizer must not hear XYAA's own voice
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceInputStatus.value = "Microphone permission is needed for voice input."
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 43)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceInputStatus.value = "Speech recognition isn't available. Install or enable a speech recognition service in Android settings."
            return
        }
        speechRecognizer?.destroy()
        voiceInputStatus.value = "Starting microphone…"
        runCatching {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    runOnUiThread { voiceInputStatus.value = "Listening… speak now." }
                }
                override fun onBeginningOfSpeech() {
                    runOnUiThread { voiceInputStatus.value = "Hearing you…" }
                }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    runOnUiThread { voiceInputStatus.value = "Recognizing your words…" }
                }
                override fun onError(error: Int) {
                    runOnUiThread {
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                        voiceInputStatus.value = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error. Check microphone access and try again."
                            SpeechRecognizer.ERROR_CLIENT -> "Voice input was interrupted. Tap the mic and try again."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is blocked. Allow it in Android app settings."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service couldn't connect. Check your internet and try again."
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap the mic and speak a little closer."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy. Wait a moment and try again."
                            SpeechRecognizer.ERROR_SERVER -> "Speech service had a problem. Try again in a moment."
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The selected speech language isn't available on this device."
                            else -> "Voice input failed (error $error). Tap the mic to retry."
                        }
                    }
                }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    runOnUiThread {
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                        if (spoken.isNullOrBlank()) {
                            voiceInputStatus.value = "I didn't receive any words. Tap the mic and try again."
                        } else {
                            voiceInputStatus.value = "Heard: $spoken"
                            process(spoken) { _, _ -> }
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (voiceInputStatus.value == "Heard: $spoken") voiceInputStatus.value = ""
                            }, 5000L)
                        }
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrBlank()) runOnUiThread {
                        voiceInputStatus.value = "Hearing: $partial"
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val speechIntent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag())
                putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to XYAA")
            }
            recognizer.startListening(speechIntent)
        }.onFailure { error ->
            speechRecognizer?.destroy()
            speechRecognizer = null
            voiceInputStatus.value = "Couldn't start voice input: ${error.message ?: "speech service unavailable"}"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 43) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceInput()
            else voiceInputStatus.value = "Microphone permission was denied. Allow it in Android app settings to use voice input."
        }
        if (requestCode == 47 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startLiveVoice()
        if (requestCode == 46) {
            val audioGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val notificationsGranted = android.os.Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (audioGranted && notificationsGranted) startWakeWord()
            else liveVoiceStatus.value = "Wake word needs microphone and notification permissions."
        }
    }

    @Deprecated("Deprecated by Android; retained for compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 71 && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                runCatching { writePdf(uri) }
                    .onSuccess {
                        managementAgent.registerPdf(uri)
                        say("Your PDF has been saved and added to your XYAA library.")
                    }
                    .onFailure { error -> say("PDF export failed: " + error.message.orEmpty().take(100)) }
            }
        }
        if (requestCode == 72 && resultCode == Activity.RESULT_OK) {
            val treeUri = data?.data
            if (treeUri != null) {
                say("Scanning the selected folder for PDFs…")
                Thread {
                    val result = runCatching { managementAgent.importPdfFolder(treeUri) }
                    runOnUiThread {
                        result.onSuccess { count -> say("Folder scan complete. I added $count PDF files to your XYAA library.") }
                            .onFailure { error -> say("I couldn't scan that folder: " + error.message.orEmpty().take(120)) }
                    }
                }.start()
            } else say("No folder was selected.")
        }
    }

    override fun onDestroy() {
        liveVoiceSession?.stop()
        liveVoiceSession = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        stopSpeech(completeInterrupted = false)
        speechHandler.removeCallbacksAndMessages(null)
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
    onSaveProviderKeys: (String, String, String) -> Unit,
    onLoadProviderKeys: () -> List<String>,
    neuralVoiceStatus: String,
    onDownloadNeuralVoice: () -> Unit,
    onClearMemory: () -> Unit,
    liveVoiceActive: Boolean,
    liveVoiceStatus: String,
    liveTranscript: String,
    onStartLiveVoice: () -> Unit,
    onStopLiveVoice: () -> Unit,
    wakeWordEnabled: Boolean,
    onStartWakeWord: () -> Unit,
    onStopWakeWord: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf(ChatMessage("Hey! I'm Zyaa. I can launch apps, search, set timers, calculate, save notes and much more. Ask me anything.", false))) }
    var busy by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var groqKey by remember { mutableStateOf("") }
    var deepSeekKey by remember { mutableStateOf("") }
    var tavilyKey by remember { mutableStateOf("") }
    val quick = listOf("Open YouTube", "What's the time?", "Set a timer for 5 minutes", "Research latest AI news")

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
                            val backupKeys = onLoadProviderKeys()
                            groqKey = backupKeys.getOrNull(0).orEmpty()
                            deepSeekKey = backupKeys.getOrNull(1).orEmpty()
                            tavilyKey = backupKeys.getOrNull(2).orEmpty()
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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { if (liveVoiceActive) onStopLiveVoice() else onStartLiveVoice() },
                        modifier = Modifier.weight(1f)) {
                        Text(if (liveVoiceActive) "End live voice" else "Start live voice")
                    }
                    OutlinedButton(onClick = { if (wakeWordEnabled) onStopWakeWord() else onStartWakeWord() },
                        modifier = Modifier.weight(1f)) {
                        Text(if (wakeWordEnabled) "Wake word: ON" else "Enable wake word")
                    }
                }
                Text(liveVoiceStatus, Modifier.padding(horizontal = 18.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF9B9BB0))
                if (liveTranscript.isNotBlank()) {
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                        color = Color(0xFF171C2A), shape = RoundedCornerShape(14.dp)) {
                        Text(liveTranscript, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE4E2F0), maxLines = 5)
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
                        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it },
                            label = { Text("Gemini API key") }, singleLine = true)
                        Text("The key is encrypted with Android Keystore on this device.", style = MaterialTheme.typography.bodySmall)
                        Text("Backup AI providers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(value = groqKey, onValueChange = { groqKey = it }, label = { Text("Groq API key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                        OutlinedTextField(value = deepSeekKey, onValueChange = { deepSeekKey = it }, label = { Text("DeepSeek API key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                        OutlinedTextField(value = tavilyKey, onValueChange = { tavilyKey = it }, label = { Text("Tavily search key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                        Text("Optional keys are encrypted with Android Keystore. Gemini is tried first; Groq and DeepSeek are automatic chat fallbacks. Tavily backs up web research.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9B9BB0))
                        HorizontalDivider()
                        Text("XYAA Neural Voice", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(neuralVoiceStatus, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onDownloadNeuralVoice, modifier = Modifier.fillMaxWidth()) { Text("Download neural voice (~400 MB)") }
                        Text("Kokoro runs on this phone after download. Speech generation is then offline.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onClearMemory, modifier = Modifier.fillMaxWidth()) { Text("Clear saved conversation memory") }
                        Text("Recent chat context is stored on this device to help future replies. Clear it here at any time.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9B9BB0))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onSaveKey(apiKey.trim())
                        onSaveProviderKeys(groqKey.trim(), deepSeekKey.trim(), tavilyKey.trim())
                        settingsOpen = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { settingsOpen = false }) { Text("Cancel") } }
            )
        }
    }
}
