package ai.xyaa.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

private val Bg = Color(0xFF080812)
private val Panel = Color(0xFF151126)
private val Panel2 = Color(0xFF211735)
private val Violet = Color(0xFF945BFF)
private val Cyan = Color(0xFF42DFFF)
private val White = Color(0xFFF5F3FF)
private val Muted = Color(0xFFA9A2C2)
private val Line = Color(0xFF34294F)

/**
 * Compact Compose version of the Uiverse rotating-words loader.
 * The repeated first word makes the vertical loop seamless.
 */
@Composable
private fun XyaaLoadingIndicator(modifier: Modifier = Modifier) {
    val words = remember { listOf("thinking", "searching", "reading", "writing", "checking", "speaking") }
    var wordIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1150)
            wordIndex = (wordIndex + 1) % words.size
        }
    }
    Row(
        modifier = modifier
            .background(Panel, RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("XYAA", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.height(22.dp).width(92.dp).clip(RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.CenterStart
        ) {
            Crossfade(targetState = words[wordIndex], animationSpec = tween(260), label = "xyaa-loading-word") { word ->
                Text(word, color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        Spacer(Modifier.width(5.dp))
        GooeyThinkingDots(Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun XyaaPremiumApp(
    onSend: (String, (String, Boolean) -> Unit) -> Unit,
    onVoice: () -> Unit,
    voiceInputStatus: String,
    onSaveKey: (String) -> Unit,
    onLoadKey: () -> String?,
    onSaveProviderKeys: (String, String, String) -> Unit,
    onLoadProviderKeys: () -> List<String>,
    neuralVoiceStatus: String,
    onDownloadNeuralVoice: () -> Unit,
    onClearMemory: () -> Unit,
    onShareReport: (String) -> Unit,
    recentMemory: List<String>,
    personalMemories: List<PersonalMemoryStore.Item>,
    personalMemoryPaused: Boolean,
    onPausePersonalMemory: (Boolean) -> Unit,
    onDeletePersonalMemory: (String) -> Unit,
    onAddPersonalMemory: (String) -> Unit,
    onEditPersonalMemory: (String, String) -> Unit,
    onExportPersonalMemory: () -> Unit,
    codeStudioTasks: List<CodeStudioTaskStore.Task>,
    onCreateCodeTask: (String) -> Unit,
    onDeleteCodeTask: (String) -> Unit,
    onEditCodeTask: (String, String) -> Unit,
    onShareCodeTask: (CodeStudioTaskStore.Task) -> Unit,
    liveVoiceActive: Boolean,
    liveVoiceStatus: String,
    liveTranscript: String,
    onStartLiveVoice: () -> Unit,
    onStopLiveVoice: () -> Unit,
    wakeWordEnabled: Boolean,
    onStartWakeWord: () -> Unit,
    onStopWakeWord: () -> Unit
) {
    var tab by remember { mutableStateOf("Orb") }
    var input by remember { mutableStateOf("") }
    var researchQuery by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) }
    var busy by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var groqKey by remember { mutableStateOf("") }
    var deepSeekKey by remember { mutableStateOf("") }
    var tavilyKey by remember { mutableStateOf("") }
    var memoryQuery by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    fun submit(raw: String) {
        val prompt = raw.trim()
        if (prompt.isEmpty() || busy) return
        if (tab != "Research") tab = "Chat"
        messages = messages + (prompt to true) + ("" to false)
        input = ""
        busy = true
        onSend(prompt) { response, finished ->
            // Keep the familiar Thinking… and Speaking… status visible while XYAA works.
            messages = messages.dropLast(1) + (response to false)
            if (finished) busy = false
        }
    }

    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Violet, secondary = Cyan, background = Bg, surface = Panel,
        onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Bg,
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("XYAA", color = White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp)
                        Text("PERSONAL AI", color = Muted, fontSize = 9.sp, letterSpacing = 2.5.sp)
                    }
                    Surface(color = Panel, shape = CircleShape, modifier = Modifier.size(40.dp).clickable { tab = "Settings" }) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Tune, "Settings", tint = Cyan) }
                    }
                }
            },
            bottomBar = {
                // The keyboard already covers the nav area; keeping the bar would stack it above the keyboard.
                if (!imeOpen) NavigationBar(containerColor = Color(0xFF100D1C)) {
                    listOf("Orb" to Icons.Default.BubbleChart, "Chat" to Icons.Default.Forum,
                        "Research" to Icons.Default.Search, "Skills" to Icons.Default.GridView,
                        "Code" to Icons.Default.Code, "Memory" to Icons.Default.Hub, "Settings" to Icons.Default.Settings).forEach { (name, icon) ->
                        NavigationBarItem(
                            selected = tab == name, onClick = { tab = name },
                            icon = { Icon(icon, name) }, label = { Text(name, fontSize = 9.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Cyan, selectedTextColor = Cyan,
                                indicatorColor = Violet.copy(alpha = .18f),
                                unselectedIconColor = Muted, unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            // Scaffold's padding already contains the system-bar insets; consume them so imePadding
            // adds only the part of the keyboard that is not already accounted for (no double inset).
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                when (tab) {
                    "Orb" -> HomePage(liveVoiceActive, liveVoiceStatus, wakeWordEnabled,
                        { tab = "Live Voice"; onStartLiveVoice() }, onStopLiveVoice, onStartWakeWord, onStopWakeWord, { tab = "Chat"; onVoice() },
                        { submit(it) }, { tab = "Research" }, { tab = "Chat" })
                    "Live Voice" -> LiveVoicePage(liveVoiceActive, liveVoiceStatus, liveTranscript,
                        onStartLiveVoice, onStopLiveVoice, { tab = "Chat"; onVoice() })
                    "Chat" -> ChatPage(messages, input, { input = it }, busy, { submit(it) }, onVoice, voiceInputStatus, { messages = emptyList() })
                    "Research" -> ResearchPage(researchQuery, { researchQuery = it }, busy,
                        { q -> researchQuery = q; tab = "Research"; submit("Research $q") }, messages, onShareReport)
                    "Skills" -> SkillsPage { command ->
                        if (command in setOf("Remind me to", "Save a note that ", "Search for", "Directions to", "Translate ")) {
                            input = command
                            tab = "Chat"
                        } else submit(command)
                    }
                    "Memory" -> MemoryPage(recentMemory, personalMemories, personalMemoryPaused, onPausePersonalMemory, onDeletePersonalMemory, onAddPersonalMemory, onEditPersonalMemory, onExportPersonalMemory, memoryQuery, { memoryQuery = it }) { confirmClear = true }
                    "Code" -> CodeStudioPage(codeStudioTasks, onCreateCodeTask, onDeleteCodeTask, onEditCodeTask, onShareCodeTask)
                    "Settings" -> {
                        LaunchedEffect(Unit) {
                            apiKey = onLoadKey().orEmpty()
                            val keys = onLoadProviderKeys()
                            groqKey = keys.getOrNull(0).orEmpty()
                            deepSeekKey = keys.getOrNull(1).orEmpty()
                            tavilyKey = keys.getOrNull(2).orEmpty()
                        }
                        SettingsPage(apiKey, { apiKey = it }, groqKey, { groqKey = it },
                            deepSeekKey, { deepSeekKey = it }, tavilyKey, { tavilyKey = it },
                            neuralVoiceStatus, onDownloadNeuralVoice, wakeWordEnabled,
                            onStartWakeWord, onStopWakeWord,
                            { onSaveKey(apiKey.trim()); onSaveProviderKeys(groqKey.trim(), deepSeekKey.trim(), tavilyKey.trim()) },
                            { confirmClear = true })
                    }
                }
            }
        }
        if (confirmClear) AlertDialog(
            onDismissRequest = { confirmClear = false }, containerColor = Panel2,
            title = { Text("Clear conversation memory?", color = White) },
            text = { Text("This removes XYAA's locally saved conversation context from this device.", color = Muted) },
            confirmButton = { TextButton(onClick = { onClearMemory(); confirmClear = false }) { Text("Clear memory", color = Color(0xFFFF8C9B)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = Muted) } }
        )
    }
}

@Composable
private fun HomePage(
    live: Boolean, liveStatus: String, wake: Boolean, startLive: () -> Unit, stopLive: () -> Unit,
    startWake: () -> Unit, stopWake: () -> Unit, voice: () -> Unit,
    action: (String) -> Unit, research: () -> Unit, chat: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Text("YOUR PERSONAL INTELLIGENCE", color = Cyan, fontSize = 9.sp, letterSpacing = 2.sp)
        Text("Hey, I'm XYAA.", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("What shall we explore today?", color = Muted, fontSize = 13.sp)
        EnergyOrb(Modifier.fillMaxWidth().height(250.dp), live)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MainButton("Live voice", Icons.Default.GraphicEq, Modifier.weight(1f)) { if (live) stopLive() else startLive() }
            MainButton("Speak", Icons.Default.Mic, Modifier.weight(1f), true, voice)
        }
        InfoCard("Live voice", if (live) liveStatus else "Start a real-time conversation with XYAA.", if (live) "ACTIVE" else "READY")
        InfoCard("Hey XYAA wake word", if (wake) "On-device wake-word service is running." else "On-device wake-word service is off.",
            if (wake) "ON" else "OFF", {
                TextButton(onClick = { if (wake) stopWake() else startWake() }) {
                    Text(if (wake) "Stop" else "Enable", color = Cyan)
                }
            })
        Label("QUICK ACCESS")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickCard("Deep research", Icons.Default.Search, Modifier.weight(1f), research)
            QuickCard("Open chat", Icons.Default.Forum, Modifier.weight(1f), chat)
            QuickCard("View notes", Icons.Default.EditNote, Modifier.weight(1f)) { action("Show my notes") }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun EnergyOrb(modifier: Modifier = Modifier, active: Boolean = false) {
    // Native port of the andrew-manzyk molten loader; see MoltenOrb.kt.
    MoltenOrb(modifier, active)
}

@Composable
private fun GooeyThinkingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "gooey-thinking")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Restart),
        label = "gooey-phase"
    )
    Canvas(modifier) {
        val center = Offset(size.width * .5f, size.height * .5f)
        val radius = size.minDimension * .17f
        val travel = size.minDimension * .30f
        val t = phase
        val lift = if (t < .5f) t * 2f else (1f - t) * 2f
        val spread = if (t < .5f) lift else 1f
        val points = listOf(
            Offset(center.x, center.y - travel * lift),
            Offset(center.x - travel * spread, center.y + travel * .72f * lift),
            Offset(center.x + travel * spread, center.y + travel * .72f * lift)
        )
        val colors = listOf(Cyan, Violet, Color(0xFFB99AFF))
        points.forEachIndexed { index, point ->
            val scale = if (t in .38f.. .82f) .72f else 1f
            drawCircle(colors[index].copy(alpha = .22f), radius * 2.1f * scale, point)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .95f), colors[index], colors[index].copy(alpha = .2f))),
                radius * scale, point)
        }
        // Soft central bridge creates a gooey, merging impression without a costly blur filter.
        drawCircle(Violet.copy(alpha = .38f), radius * .88f, center)
    }
}

@Composable
private fun LiveVoicePage(
    active: Boolean, status: String, transcript: String,
    start: () -> Unit, stop: () -> Unit, voiceInput: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF100B20), Bg)))
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Heading("Live voice", "Speak naturally with XYAA")
        Spacer(Modifier.height(12.dp))
        EnergyOrb(Modifier.fillMaxWidth().height(300.dp), active)
        Surface(color = Panel2, shape = RoundedCornerShape(30.dp)) {
            Text(if (active) "LIVE · ${status}" else status,
                Modifier.padding(horizontal = 15.dp, vertical = 8.dp), color = Cyan, fontSize = 10.sp)
        }
        Spacer(Modifier.height(18.dp))
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp)) {
                Label("LIVE TRANSCRIPTION")
                Text(transcript.ifBlank { "Your conversation transcript will appear here when speech is recognized." },
                    color = if (transcript.isBlank()) Muted else White, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = voiceInput, colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)) {
                Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp)); Text("Speech input")
            }
            Button(onClick = { if (active) stop() else start() },
                colors = ButtonDefaults.buttonColors(containerColor = if (active) Color(0xFF8C3657) else Violet)) {
                Icon(if (active) Icons.Default.CallEnd else Icons.Default.GraphicEq, null)
                Spacer(Modifier.width(7.dp)); Text(if (active) "End session" else "Start session")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ChatPage(
    messages: List<Pair<String, Boolean>>, input: String, onInput: (String) -> Unit,
    busy: Boolean, send: (String) -> Unit, voice: () -> Unit, voiceStatus: String,
    clearChat: () -> Unit
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val lastUserPrompt = messages.lastOrNull { it.second }?.first.orEmpty()

    // Follow streamed output only while the reader is already near the bottom.
    // Measuring the last visible row and applying its exact overflow avoids the
    // large, repeated Int.MAX_VALUE scroll offsets that caused streaming jumps.
    var followOutput by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            last != null &&
                last.index == layout.totalItemsCount - 1 &&
                last.offset + last.size <= layout.viewportEndOffset + 96
        }.distinctUntilChanged().collect { nearBottom ->
            followOutput = nearBottom
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.first, imeOpen) {
        if (messages.isNotEmpty() && followOutput) {
            withFrameNanos { }
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull { it.index == messages.lastIndex }
            if (last != null) {
                val overflow = last.offset + last.size - layout.viewportEndOffset
                if (overflow > 0) listState.scrollBy(overflow.toFloat())
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("YOUR CONVERSATION", color = Cyan, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Text("Chat with XYAA", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(if (busy) "Working on your request…" else "Ask anything. Build something useful.",
                    color = Muted, fontSize = 11.sp)
            }
            Surface(
                color = if (busy) Color(0xFF16313A) else Panel2,
                shape = RoundedCornerShape(30.dp),
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(if (busy) Cyan else Color(0xFF73D6A1), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "ACTIVE" else "READY", color = if (busy) Cyan else Muted,
                        fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
            if (messages.isNotEmpty()) {
                IconButton(onClick = clearChat, enabled = !busy, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.DeleteOutline, "Clear visible chat", tint = Muted, modifier = Modifier.size(19.dp))
                }
            }
        }

        if (messages.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF21163C), Color(0xFF101B2D))))
                        .border(1.dp, Line.copy(alpha = .8f), RoundedCornerShape(26.dp))
                        .padding(vertical = 18.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EnergyOrb(Modifier.size(142.dp))
                        Text("A clearer way to get things done.", color = White, fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(5.dp))
                        Text("Ask a question, create a document, research a topic or control your device.",
                            color = Muted, fontSize = 11.sp, lineHeight = 16.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("TRY ASKING", color = Cyan, fontSize = 9.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("Tap a prompt to start", color = Muted, fontSize = 9.sp)
                }
                Spacer(Modifier.height(9.dp))
                val suggestions = listOf(
                    Triple(Icons.Default.Lightbulb, "Explain a concept", "Explain quantum computing in simple terms"),
                    Triple(Icons.Default.Description, "Create a PDF", "Create a detailed PDF about renewable energy"),
                    Triple(Icons.Default.Search, "Research a topic", "Research the latest advances in space exploration"),
                    Triple(Icons.Default.Checklist, "Plan my day", "Help me make a realistic plan for today")
                )
                suggestions.forEach { (icon, title, prompt) ->
                    Surface(
                        color = Panel, shape = RoundedCornerShape(15.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            .border(1.dp, Line, RoundedCornerShape(15.dp))
                            .clickable(enabled = !busy) { send(prompt) }
                    ) {
                        Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).background(Panel2, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                                Icon(icon, null, tint = Cyan, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(11.dp))
                            Text(title, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowOutward, null, tint = Muted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(messages) { (text, fromUser) ->
                    if (fromUser) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                            Surface(
                                color = Color(0xFF342354),
                                shape = RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp),
                                modifier = Modifier.widthIn(max = 330.dp)
                                    .border(1.dp, Color(0xFF6546A0), RoundedCornerShape(20.dp,20.dp,5.dp,20.dp))
                            ) {
                                Text(text, Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                                    color = White, fontSize = 14.sp, lineHeight = 21.sp)
                            }
                            Text("YOU", color = Muted, fontSize = 8.sp, letterSpacing = 1.2.sp,
                                modifier = Modifier.padding(top = 5.dp, end = 5.dp))
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier.padding(top = 2.dp).size(30.dp)
                                    .background(Brush.linearGradient(listOf(Violet, Color(0xFF286E8C))), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("X", color = White, fontSize = 13.sp, fontWeight = FontWeight.Black)
                            }
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("XYAA", color = Cyan, fontSize = 9.sp, letterSpacing = 1.4.sp,
                                        fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(7.dp))
                                    Text("·", color = Muted, fontSize = 11.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("ASSISTANT", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
                                }
                                Spacer(Modifier.height(6.dp))
                                if (text.isBlank() || text == "Connecting…" || text == "Thinking…" ||
                                    text == "Speaking…" || text == "Preparing your PDF…") {
                                    XyaaLoadingIndicator()
                                } else {
                                    AnimatedVisibility(
                                        visible = true,
                                        enter = fadeIn(animationSpec = tween(220)) +
                                            slideInVertically(
                                                initialOffsetY = { it / 10 },
                                                animationSpec = tween(220)
                                            )
                                    ) {
                                        Surface(
                                            color = Panel,
                                            shape = RoundedCornerShape(5.dp, 18.dp, 18.dp, 18.dp),
                                            modifier = Modifier.fillMaxWidth()
                                                // Speech callbacks add words progressively; animate the
                                                // bubble's height so each new phrase settles smoothly.
                                                .animateContentSize(animationSpec = tween(110))
                                                .border(1.dp, Line, RoundedCornerShape(5.dp,18.dp,18.dp,18.dp))
                                        ) {
                                            val display = AssistantTextFormatter.forRichDisplay(text)
                                            MarkdownMessage(
                                                raw = display,
                                                modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp)
                                            )
                                        }
                                    }
                                    Row(
                                        Modifier.fillMaxWidth().padding(top = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = { clipboard.setText(AnnotatedString(AssistantTextFormatter.forDisplay(text))) },
                                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, null, tint = Muted, modifier = Modifier.size(13.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Copy", color = Muted, fontSize = 10.sp)
                                        }
                                        TextButton(
                                            onClick = {
                                                val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(android.content.Intent.EXTRA_TEXT, AssistantTextFormatter.forDisplay(text))
                                                }
                                                val chooser = android.content.Intent.createChooser(sendIntent, "Share XYAA response")
                                                context.startActivity(chooser)
                                            },
                                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 3.dp)
                                        ) {
                                            Icon(Icons.Default.Share, null, tint = Muted, modifier = Modifier.size(13.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Share", color = Muted, fontSize = 10.sp)
                                        }
                                        if (text == messages.lastOrNull { !it.second }?.first && lastUserPrompt.isNotBlank()) {
                                            TextButton(
                                                onClick = { send(lastUserPrompt) },
                                                enabled = !busy,
                                                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 3.dp)
                                            ) {
                                                Icon(Icons.Default.Replay, null, tint = Muted, modifier = Modifier.size(13.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Ask again", color = Muted, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (voiceStatus.isNotBlank()) {
            Text(voiceStatus, color = Cyan, fontSize = 10.sp, modifier = Modifier.padding(start = 7.dp, bottom = 5.dp))
        }
        MessageDock(input, onInput, {
            send(input)
            focusManager.clearFocus()
            keyboard?.hide()
        }, voice, !busy)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun MarkdownMessage(raw: String, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val lines = remember(raw) { raw.lines() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.trim().startsWith("```")) {
                val code = StringBuilder()
                index++
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[index])
                    index++
                }
                if (index < lines.size) index++
                Surface(
                    color = Color(0xFF0B0A14),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(12.dp))
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("CODE", color = Cyan, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { clipboard.setText(AnnotatedString(code.toString())) },
                                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                                Icon(Icons.Default.ContentCopy, null, tint = Muted, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy code", color = Muted, fontSize = 9.sp)
                            }
                        }
                        SelectionContainer {
                            Text(code.toString(), color = Color(0xFFB8F4E7), fontSize = 11.sp,
                                lineHeight = 17.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                }
                continue
            }
            if (line.trim().startsWith("|") && index + 1 < lines.size &&
                lines[index + 1].trim().matches(Regex("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?"))) {
                val headers = line.trim().trim('|').split('|').map { it.trim() }
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].trim().startsWith("|")) {
                    rows.add(lines[index].trim().trim('|').split('|').map { it.trim() })
                    index++
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (listOf(headers) + rows).forEachIndexed { rowIndex, cells ->
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            cells.forEachIndexed { cellIndex, cell ->
                                Surface(
                                    color = if (rowIndex == 0) Color(0xFF30234A) else Panel2,
                                    shape = RoundedCornerShape(7.dp),
                                    modifier = Modifier.widthIn(min = 88.dp).weight(1f, fill = false)
                                ) {
                                    Text(cell, Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                        color = if (rowIndex == 0) Cyan else White,
                                        fontSize = 10.sp, lineHeight = 14.sp,
                                        fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
                continue
            }
            when {
                line.isBlank() -> Spacer(Modifier.height(1.dp))
                line.trimStart().startsWith("#") -> {
                    val title = line.trimStart().dropWhile { it == '#' }.trim()
                    Text(title, color = White, fontSize = if (line.trimStart().startsWith("###")) 13.sp else 16.sp,
                        fontWeight = FontWeight.Bold, lineHeight = 21.sp)
                }
                line.trimStart().matches(Regex("[-*+]\\s+.*")) -> {
                    Row(verticalAlignment = Alignment.Top) {
                        Text("•", color = Cyan, fontSize = 15.sp, modifier = Modifier.padding(end = 7.dp))
                        MarkdownInlineText(line.trimStart().drop(2), uriHandler, Modifier.weight(1f))
                    }
                }
                line.trimStart().matches(Regex("\\d+[.)]\\s+.*")) -> {
                    val match = Regex("^(\\d+[.)])\\s+(.*)$").find(line.trimStart())
                    Row(verticalAlignment = Alignment.Top) {
                        Text(match?.groupValues?.get(1).orEmpty(), color = Cyan, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 7.dp))
                        MarkdownInlineText(match?.groupValues?.get(2).orEmpty(), uriHandler, Modifier.weight(1f))
                    }
                }
                else -> MarkdownInlineText(line, uriHandler)
            }
            index++
        }
    }
}

@Composable
private fun MarkdownInlineText(
    raw: String,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    modifier: Modifier = Modifier
) {
    val annotated = remember(raw) { buildMarkdownInline(raw) }
    ClickableText(
        text = annotated,
        modifier = modifier.fillMaxWidth(),
        style = TextStyle(color = White, fontSize = 13.sp, lineHeight = 20.sp),
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                runCatching { uriHandler.openUri(it.item) }
            }
        }
    )
}

private fun buildMarkdownInline(raw: String): AnnotatedString {
    val token = Regex("(\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|`[^`]+`|https?://[^\\s<>]+)", RegexOption.IGNORE_CASE)
    return buildAnnotatedString {
        var cursor = 0
        token.findAll(raw).forEach { match ->
            append(raw.substring(cursor, match.range.first))
            val value = match.value
            when {
                value.startsWith("**") -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = White))
                    append(value.removePrefix("**").removeSuffix("**"))
                    pop()
                }
                value.startsWith("*") -> {
                    pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                    append(value.removePrefix("*").removeSuffix("*"))
                    pop()
                }
                value.startsWith("`") -> {
                    pushStyle(SpanStyle(
                        color = Color(0xFFB8F4E7),
                        background = Color(0xFF29243A),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ))
                    append(value.removeSurrounding("`"))
                    pop()
                }
                else -> {
                    val url = value.trimEnd('.', ',', ')', ']', ';')
                    val trailing = value.removePrefix(url)
                    pushStringAnnotation("URL", url)
                    pushStyle(SpanStyle(color = Cyan, textDecoration = TextDecoration.Underline))
                    append(url)
                    pop()
                    pop()
                    append(trailing)
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) append(raw.substring(cursor))
    }
}

private fun buildLinkedText(raw: String): AnnotatedString {
    val urlPattern = Regex("https?://[^\\\\s<>]+", RegexOption.IGNORE_CASE)
    return buildAnnotatedString {
        var cursor = 0
        urlPattern.findAll(raw).forEach { match ->
            append(raw.substring(cursor, match.range.first))
            val url = match.value.trimEnd('.', ',', ')', ']', ';')
            val trailing = match.value.removePrefix(url)
            pushStringAnnotation("URL", url)
            pushStyle(SpanStyle(color = Cyan, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium))
            append(url)
            pop()
            pop()
            append(trailing)
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) append(raw.substring(cursor))
    }
}

@Composable
private fun ResearchPage(
    query: String, onQuery: (String) -> Unit, busy: Boolean, run: (String) -> Unit,
    messages: List<Pair<String, Boolean>>, onShare: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Heading("Deep research", "Explore a topic and build a cited report")
        Surface(color = Panel, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp)) {
                OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), placeholder = { Text("What should XYAA research?") },
                    minLines = 2, shape = RoundedCornerShape(16.dp), colors = fieldColors())
                Spacer(Modifier.height(10.dp))
                Button(onClick = { run(query) }, enabled = query.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Violet)) {
                    Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "Research in progress…" else "Start research")
                }
                Text("Uses configured Gemini Search grounding or Tavily search.", color = Muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(16.dp)); Label("LATEST RESEARCH OUTPUT")
        val report = messages.lastOrNull { !it.second && it.first.contains("##") }?.first
        if (report.isNullOrBlank()) InfoCard("No report yet", "Your completed report and returned sources will appear here.", "WAITING")
        else Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp)) {
                Text(AssistantTextFormatter.forDisplay(report), color = White, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { onShare(report) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Share / export report")
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SkillsPage(run: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Heading("Skill matrix", "Built-in Android actions and everyday tools")
        SkillSection("PRODUCTIVITY & TIME", listOf(
            Triple("Notes",Icons.Default.EditNote,"Save a note that "), Triple("Reminders",Icons.Default.Notifications,"Remind me to"),
            Triple("Timers & alarms",Icons.Default.Timer,"Set a timer for 5 minutes"), Triple("Calendar",Icons.Default.CalendarMonth,"Open calendar")
        ),run)
        SkillSection("DEVICE & UTILITIES", listOf(
            Triple("Calculator",Icons.Default.Calculate,"Calculate 25 * 4"), Triple("Camera",Icons.Default.PhotoCamera,"Open camera"),
            Triple("Maps",Icons.Default.Map,"Directions to"), Triple("App launching",Icons.Default.Apps,"Open YouTube")
        ),run)
        SkillSection("INFORMATION", listOf(
            Triple("Web search",Icons.Default.Language,"Search for"), Triple("Weather",Icons.Default.Cloud,"What's the weather"),
            Triple("News",Icons.Default.Newspaper,"News"), Triple("Translation",Icons.Default.Translate,"Translate ")
        ),run)
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SkillSection(title: String, skills: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, String>>, run: (String) -> Unit) {
    Label(title)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        skills.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (name,icon,command) ->
                    Surface(color = Panel, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).height(84.dp).clickable { run(command) }.border(1.dp,Line,RoundedCornerShape(16.dp))) {
                        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.Center) {
                            Icon(icon,null,tint=Cyan,modifier=Modifier.size(20.dp))
                            Spacer(Modifier.height(5.dp))
                            Text(name,color=White,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
                            Text("Open skill",color=Muted,fontSize=9.sp)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun CodeStudioPage(
    tasks: List<CodeStudioTaskStore.Task>,
    createTask: (String) -> Unit,
    deleteTask: (String) -> Unit,
    editTask: (String, String) -> Unit,
    shareTask: (CodeStudioTaskStore.Task) -> Unit
) {
    var request by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var editingTask by remember { mutableStateOf<CodeStudioTaskStore.Task?>(null) }
    var editRequest by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp)) {
        Heading("Code Studio", "Turn development ideas into tracked coding tasks")
        Surface(color=Panel,shape=RoundedCornerShape(17.dp),modifier=Modifier.fillMaxWidth().padding(bottom=12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal,null,tint=Cyan)
                    Spacer(Modifier.width(8.dp))
                    Text("NEW DEVELOPMENT TASK",color=Cyan,fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=1.2.sp)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value=request,onValueChange={request=it.take(4000)},modifier=Modifier.fillMaxWidth(),
                    placeholder={Text("Example: Add a favourites screen and tests…")},
                    minLines=3,maxLines=6,shape=RoundedCornerShape(13.dp),colors=fieldColors()
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick={val value=request.trim(); if(value.isNotEmpty()){createTask(value);request=""}},
                    enabled=request.isNotBlank(),modifier=Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add,null); Spacer(Modifier.width(7.dp)); Text("Add to Code Studio")
                }
            }
        }
        InfoCard("Execution environment",
            "Task capture is available. The isolated cloud coding/build runner is not connected yet; XYAA will not execute project code on this phone.",
            "SETUP REQUIRED")
        Label("TASK INBOX")
        if(tasks.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(130.dp),contentAlignment=Alignment.Center) {
                Text("No development tasks yet. Add a coding command above.",color=Muted,fontSize=12.sp)
            }
        } else tasks.forEach { task ->
            val expanded = expandedId == task.id
            Surface(color=Panel,shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)) {
                Column(Modifier.clickable { expandedId=if(expanded)null else task.id }.padding(13.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.Code,null,tint=Cyan,modifier=Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(task.status,color=Muted,fontSize=9.sp,modifier=Modifier.weight(1f))
                        IconButton(onClick={deleteTask(task.id)}) {
                            Icon(Icons.Default.DeleteOutline,"Delete task",tint=Color(0xFFFF8C9B))
                        }
                    }
                    Text(task.request,color=White,fontSize=12.sp,lineHeight=18.sp,maxLines=if(expanded) Int.MAX_VALUE else 3,
                        overflow=TextOverflow.Ellipsis)
                    if(expanded) {
                        Text("Task saved locally and encrypted. It has not been sent to a remote worker or executed.",
                            color=Muted,fontSize=10.sp,modifier=Modifier.padding(top=8.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(top=8.dp)) {
                            TextButton(onClick={editingTask=task;editRequest=task.request}) { Text("Edit task",color=Cyan) }
                            TextButton(onClick={shareTask(task)}) { Text("Export brief",color=Cyan) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    editingTask?.let { task ->
        AlertDialog(
            onDismissRequest={editingTask=null},containerColor=Panel2,
            title={Text("Edit development task",color=White)},
            text={OutlinedTextField(value=editRequest,onValueChange={editRequest=it.take(4000)},
                modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=6,colors=fieldColors())},
            confirmButton={TextButton(onClick={editTask(task.id,editRequest);editingTask=null}) { Text("Save changes",color=Cyan) }},
            dismissButton={TextButton(onClick={editingTask=null}) { Text("Cancel",color=Muted) }}
        )
    }
}

@Composable
private fun MemoryPage(
    memories: List<String>,
    personalMemories: List<PersonalMemoryStore.Item>,
    paused: Boolean,
    setPaused: (Boolean) -> Unit,
    deletePersonal: (String) -> Unit,
    addPersonal: (String) -> Unit,
    editPersonal: (String, String) -> Unit,
    exportPersonal: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    clear: () -> Unit
) {
    var newFact by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<PersonalMemoryStore.Item?>(null) }
    var editContent by remember { mutableStateOf("") }
    val filtered = memories.filter { it.contains(query, ignoreCase = true) }
    val saved = personalMemories.filter { it.content.contains(query, ignoreCase = true) || it.category.contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Heading("Memory core", "Your saved personal facts and recent conversation context")
        Surface(color=Panel,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(bottom=10.dp)) {
            Column(Modifier.padding(13.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PERSONAL MEMORY",color=Cyan,fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=1.4.sp)
                        Text(if(paused) "Saving is paused" else "Encrypted on this device",color=Muted,fontSize=10.sp)
                    }
                    Switch(checked=!paused,onCheckedChange={setPaused(!it)})
                }
                OutlinedTextField(
                    value=newFact,onValueChange={newFact=it.take(500)},modifier=Modifier.fillMaxWidth(),
                    placeholder={Text("Add a favourite, interest or plan…")},singleLine=false,maxLines=2,
                    shape=RoundedCornerShape(13.dp),colors=fieldColors()
                )
                Button(onClick={val fact=newFact.trim(); if(fact.isNotEmpty()){addPersonal(fact);newFact=""}},
                    enabled=newFact.isNotBlank() && !paused,modifier=Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add,null); Spacer(Modifier.width(6.dp)); Text("Save personal memory")
                }
                OutlinedButton(onClick=exportPersonal, enabled=personalMemories.isNotEmpty(), modifier=Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.IosShare,null); Spacer(Modifier.width(6.dp)); Text("Export personal memory")
                }
                if(saved.isEmpty()) Text("No personal facts saved yet. XYAA only saves facts when you ask it to remember them.",color=Muted,fontSize=10.sp)
                else {
                    Text("${saved.size} SAVED FACTS",color=Muted,fontSize=9.sp,letterSpacing=1.2.sp)
                    saved.take(8).forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(top=7.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.category.uppercase(),color=Cyan,fontSize=8.sp,fontWeight=FontWeight.Bold)
                                Text(item.content,color=White,fontSize=11.sp,lineHeight=15.sp)
                            }
                            IconButton(onClick={editingItem=item;editContent=item.content}) {
                                Icon(Icons.Default.Edit,"Edit saved memory",tint=Cyan)
                            }
                            IconButton(onClick={deletePersonal(item.id)}) {
                                Icon(Icons.Default.DeleteOutline,"Delete saved memory",tint=Color(0xFFFF8C9B))
                            }
                        }
                    }
                    if(saved.size>8) Text("Showing 8 of ${saved.size}; search to find others.",color=Muted,fontSize=9.sp)
                }
            }
        }
        OutlinedTextField(query,onQuery,Modifier.fillMaxWidth(),placeholder={Text("Search recent context and personal memory")},
            leadingIcon={Icon(Icons.Default.Search,null,tint=Muted)},singleLine=true,shape=RoundedCornerShape(15.dp),colors=fieldColors())
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment=Alignment.CenterVertically) {
            Label("RECENT CONVERSATION",Modifier.weight(1f))
            Text("${filtered.size} turns",color=Muted,fontSize=10.sp)
        }
        if (filtered.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            Text(if(memories.isEmpty()) "No recent conversation context yet." else "No matching context.",color=Muted)
        } else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(filtered.reversed()) { item ->
                var expanded by remember(item) { mutableStateOf(false) }
                val cleaned = AssistantTextFormatter.forDisplay(item)
                val preview = AssistantTextFormatter.memoryPreview(item)
                Surface(color=Panel,shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.clickable { expanded = !expanded }.padding(13.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(if (item.startsWith("You:")) "YOU" else "XYAA", color=Cyan, fontSize=9.sp, fontWeight=FontWeight.Bold, letterSpacing=1.2.sp)
                            Spacer(Modifier.weight(1f))
                            Text(if (expanded) "SHOW LESS" else "READ MORE", color=Muted, fontSize=9.sp)
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(if (expanded) cleaned else preview, color=White, fontSize=12.sp, lineHeight=18.sp,
                            maxLines=if (expanded) Int.MAX_VALUE else 4, overflow=TextOverflow.Ellipsis)
                    }
                }
            }
        }
        OutlinedButton(onClick=clear,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.outlinedButtonColors(contentColor=Color(0xFFFF8C9B))) {
            Icon(Icons.Default.DeleteOutline,null); Spacer(Modifier.width(8.dp)); Text("Clear conversation memory")
        }
        Spacer(Modifier.height(10.dp))
    }
    editingItem?.let { item ->
        AlertDialog(
            onDismissRequest={editingItem=null}, containerColor=Panel2,
            title={Text("Edit saved memory",color=White)},
            text={OutlinedTextField(value=editContent,onValueChange={editContent=it.take(500)},
                modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=4,colors=fieldColors())},
            confirmButton={TextButton(onClick={editPersonal(item.id,editContent);editingItem=null}) { Text("Save changes",color=Cyan) }},
            dismissButton={TextButton(onClick={editingItem=null}) { Text("Cancel",color=Muted) }}
        )
    }
}

@Composable
private fun SettingsPage(
    gemini: String, setGemini: (String)->Unit, groq: String, setGroq: (String)->Unit,
    deep: String, setDeep: (String)->Unit, tavily: String, setTavily: (String)->Unit,
    voiceStatus: String, downloadVoice: ()->Unit, wake: Boolean, startWake: ()->Unit, stopWake: ()->Unit,
    save: ()->Unit, clear: ()->Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp)) {
        Heading("Settings","Configure XYAA's connected services and voice")
        Label("AI PROVIDERS")
        KeyField("Gemini · Primary AI",gemini,setGemini,"Required for Gemini chat and Live voice")
        KeyField("Groq · Backup AI",groq,setGroq,"Optional fallback provider")
        KeyField("DeepSeek · Backup AI",deep,setDeep,"Optional fallback provider")
        KeyField("Tavily · Web research",tavily,setTavily,"Optional research search provider")
        Button(onClick=save,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Violet)) {
            Icon(Icons.Default.Lock,null); Spacer(Modifier.width(8.dp)); Text("Save encrypted provider keys")
        }
        Text("Keys are stored using Android Keystore-backed encryption.",color=Muted,fontSize=10.sp)
        Spacer(Modifier.height(18.dp)); Label("VOICE")
        InfoCard("XYAA voice · Fiona-inspired",voiceStatus,"VOICE")
        OutlinedButton(onClick=downloadVoice,modifier=Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Download,null); Spacer(Modifier.width(8.dp)); Text("Download / initialize XYAA voice")
        }
        InfoCard("Hey XYAA wake word",if(wake) "On-device wake-word service is enabled." else "On-device wake-word service is disabled.",if(wake) "ON" else "OFF",{
            TextButton(onClick={if(wake)stopWake() else startWake()}) { Text(if(wake)"Turn off" else "Turn on",color=Cyan) }
        })
        Spacer(Modifier.height(18.dp)); Label("PRIVACY & MEMORY")
        InfoCard("Conversation memory","Recent turns are stored locally to provide context in future conversations.","LOCAL")
        OutlinedButton(onClick=clear,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.outlinedButtonColors(contentColor=Color(0xFFFF8C9B))) {
            Text("Clear saved conversation memory")
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun KeyField(title:String,value:String,onValue:(String)->Unit,hint:String) {
    Column(Modifier.padding(bottom=10.dp)) {
        Text(title,color=White,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
        OutlinedTextField(value,onValue,Modifier.fillMaxWidth(),placeholder={Text("Paste API key")},
            singleLine=true,visualTransformation=PasswordVisualTransformation(),
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
            shape=RoundedCornerShape(14.dp),colors=fieldColors())
        Text(hint,color=Muted,fontSize=10.sp)
    }
}

@Composable
private fun MessageDock(
    value: String,
    onValue: (String) -> Unit,
    send: () -> Unit,
    voice: () -> Unit,
    enabled: Boolean = true
) {
    val canSend = enabled && value.isNotBlank()
    Surface(
        color = Color(0xFF171329),
        shape = RoundedCornerShape(23.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF51406F), RoundedCornerShape(23.dp)),
        shadowElevation = 12.dp
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MESSAGE XYAA", color = Muted, fontSize = 8.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (enabled) "ENTER TO SEND" else "XYAA IS WORKING",
                    color = if (enabled) Muted.copy(alpha = .72f) else Cyan,
                    fontSize = 8.sp,
                    letterSpacing = .8.sp
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValue,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask anything or give XYAA a task…", color = Muted, fontSize = 13.sp) },
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(17.dp),
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Send
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { if (canSend) send() }
                    )
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = voice,
                    enabled = enabled,
                    modifier = Modifier.padding(bottom = 3.dp).size(40.dp)
                ) {
                    Icon(Icons.Default.Mic, "Voice input", tint = if (enabled) Cyan else Muted)
                }
                IconButton(
                    onClick = send,
                    enabled = canSend,
                    modifier = Modifier.padding(bottom = 3.dp).size(42.dp)
                        .background(if (canSend) Violet else Panel2, CircleShape)
                ) {
                    Icon(Icons.Default.ArrowUpward, "Send", tint = White)
                }
            }
        }
    }
}
@Composable
private fun Heading(title:String,subtitle:String) {
    Column(Modifier.fillMaxWidth().padding(top=6.dp,bottom=16.dp)) {
        Text(title,color=White,fontSize=23.sp,fontWeight=FontWeight.Bold)
        Text(subtitle,color=Muted,fontSize=11.sp)
    }
}

@Composable
private fun Label(text:String,modifier:Modifier=Modifier) {
    Text(text,color=Cyan,fontSize=9.sp,letterSpacing=1.8.sp,fontWeight=FontWeight.Bold,
        modifier=modifier.fillMaxWidth().padding(bottom=9.dp))
}

@Composable
private fun InfoCard(title:String,subtitle:String,badge:String,trailing:(@Composable ()->Unit)?=null) {
    Surface(color=Panel,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(bottom=8.dp).border(1.dp,Line,RoundedCornerShape(16.dp))) {
        Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title,color=White,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(subtitle,color=Muted,fontSize=10.sp,lineHeight=14.sp)
            }
            if(trailing!=null) trailing() else Text(badge,color=Cyan,fontSize=8.sp,letterSpacing=1.sp)
        }
    }
}

@Composable
private fun MainButton(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier=Modifier,secondary:Boolean=false,onClick:()->Unit) {
    Button(onClick=onClick,modifier=modifier.height(48.dp),shape=RoundedCornerShape(14.dp),
        colors=ButtonDefaults.buttonColors(containerColor=if(secondary) Panel2 else Violet)) {
        Icon(icon,null,tint=if(secondary)Cyan else White,modifier=Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp)); Text(label,color=White,fontSize=11.sp)
    }
}

@Composable
private fun QuickCard(title:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier=Modifier,onClick:()->Unit) {
    Surface(color=Panel,shape=RoundedCornerShape(15.dp),modifier=modifier.height(80.dp).clickable(onClick=onClick).border(1.dp,Line,RoundedCornerShape(15.dp))) {
        Column(Modifier.padding(9.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(icon,null,tint=Cyan,modifier=Modifier.size(20.dp)); Spacer(Modifier.height(5.dp))
            Text(title,color=White,fontSize=9.sp,fontWeight=FontWeight.Medium)
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor=White,unfocusedTextColor=White,focusedBorderColor=Violet,unfocusedBorderColor=Line,
    cursorColor=Cyan,focusedPlaceholderColor=Muted,unfocusedPlaceholderColor=Muted
)
