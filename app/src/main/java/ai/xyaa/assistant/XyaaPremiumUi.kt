package ai.xyaa.assistant

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF080812)
private val Panel = Color(0xFF151126)
private val Panel2 = Color(0xFF211735)
private val Violet = Color(0xFF945BFF)
private val Cyan = Color(0xFF42DFFF)
private val White = Color(0xFFF5F3FF)
private val Muted = Color(0xFFA9A2C2)
private val Line = Color(0xFF34294F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun XyaaPremiumApp(
    onSend: (String, (String, Boolean) -> Unit) -> Unit,
    onVoice: () -> Unit,
    onSaveKey: (String) -> Unit,
    onLoadKey: () -> String?,
    onSaveProviderKeys: (String, String, String) -> Unit,
    onLoadProviderKeys: () -> List<String>,
    neuralVoiceStatus: String,
    onDownloadNeuralVoice: () -> Unit,
    onClearMemory: () -> Unit,
    onShareReport: (String) -> Unit,
    recentMemory: List<String>,
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
        messages = messages + (prompt to true) + ("Thinking…" to false)
        input = ""
        busy = true
        onSend(prompt) { response, finished ->
            messages = messages.dropLast(1) + (response to false)
            if (finished) busy = false
        }
    }

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
                NavigationBar(containerColor = Color(0xFF100D1C)) {
                    listOf("Orb" to Icons.Default.BubbleChart, "Chat" to Icons.Default.Forum,
                        "Research" to Icons.Default.Search, "Skills" to Icons.Default.GridView,
                        "Memory" to Icons.Default.Hub, "Settings" to Icons.Default.Settings).forEach { (name, icon) ->
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
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    "Orb" -> HomePage(liveVoiceActive, liveVoiceStatus, wakeWordEnabled,
                        { tab = "Live Voice"; onStartLiveVoice() }, onStopLiveVoice, onStartWakeWord, onStopWakeWord, onVoice,
                        { submit(it) }, { tab = "Research" }, { tab = "Chat" })
                    "Live Voice" -> LiveVoicePage(liveVoiceActive, liveVoiceStatus, liveTranscript,
                        onStartLiveVoice, onStopLiveVoice, onVoice)
                    "Chat" -> ChatPage(messages, input, { input = it }, busy, { submit(it) }, onVoice)
                    "Research" -> ResearchPage(researchQuery, { researchQuery = it }, busy,
                        { q -> researchQuery = q; tab = "Research"; submit("Research $q") }, messages, onShareReport)
                    "Skills" -> SkillsPage { command ->
                        if (command in setOf("Remind me to", "Save a note that ", "Search for", "Directions to", "Translate ")) {
                            input = command
                            tab = "Chat"
                        } else submit(command)
                    }
                    "Memory" -> MemoryPage(recentMemory, memoryQuery, { memoryQuery = it }) { confirmClear = true }
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
    val motion = rememberInfiniteTransition(label = "xyaa-orb-motion")
    val rotation by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(if (active) 2600 else 6200), RepeatMode.Restart), label = "orb-rotation")
    val pulse by motion.animateFloat(.94f, 1.06f, infiniteRepeatable(tween(if (active) 900 else 1800), RepeatMode.Reverse), label = "orb-pulse")
    val morph by motion.animateFloat(-.12f, .12f, infiniteRepeatable(tween(2300), RepeatMode.Reverse), label = "orb-morph")
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension * .285f * pulse
        drawCircle(Brush.radialGradient(listOf(Cyan.copy(alpha = .30f), Violet.copy(alpha = .22f), Color.Transparent), c, r * 2.25f), r * 2.25f, c)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFF7F0FF), Cyan.copy(alpha = .92f), Violet, Color(0xFF28134D), Bg), Offset(c.x-r*.25f,c.y-r*.28f), r*1.45f), r, c)
        rotate(rotation, c) {
            drawCircle(Brush.radialGradient(listOf(Cyan.copy(alpha = .85f), Violet.copy(alpha = .05f))), r*.34f, Offset(c.x+r*.57f,c.y+r*(morph+.12f)))
            drawCircle(Brush.radialGradient(listOf(Violet.copy(alpha = .9f), Cyan.copy(alpha = .04f))), r*.30f, Offset(c.x-r*.48f,c.y-r*(morph+.18f)))
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .75f), Cyan.copy(alpha = .02f))), r*.19f, Offset(c.x+r*.10f,c.y-r*.62f))
        }
        drawCircle(Color.White.copy(alpha = .62f), r*.05f, Offset(c.x-r*.27f,c.y-r*.3f))
        drawCircle(Cyan.copy(alpha = .6f), r*1.34f, c, style = Stroke(1.dp.toPx()))
        drawCircle(Violet.copy(alpha = .7f), r*1.58f, c, style = Stroke(1.dp.toPx()))
        rotate(rotation, c) {
            drawArc(Brush.sweepGradient(listOf(Color.Transparent,Cyan,Violet,Color.Transparent)), 20f, 245f, false,
                Offset(c.x-r*1.72f,c.y-r*1.72f), androidx.compose.ui.geometry.Size(r*3.44f,r*3.44f),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(Cyan, 3.dp.toPx(), Offset(c.x+r*1.58f,c.y))
        }
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
    busy: Boolean, send: (String) -> Unit, voice: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Heading("Conversation", "A clear space for ideas and answers")
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EnergyOrb(Modifier.size(190.dp))
                    Text("Start a conversation", color = White, fontWeight = FontWeight.SemiBold)
                    Text("Ask a question or give XYAA a task.", color = Muted, fontSize = 12.sp)
                }
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages) { (text, fromUser) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                    if (!fromUser && text == "Thinking…") {
                        EnergyOrb(Modifier.size(58.dp), active = true)
                        Text("XYAA is thinking", Modifier.padding(start = 8.dp), color = Cyan, fontSize = 12.sp)
                    } else {
                        Surface(color = if (fromUser) Color(0xFF39245D) else Panel,
                            shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 330.dp).border(1.dp, Line, RoundedCornerShape(18.dp))) {
                            Text(text, Modifier.padding(14.dp), color = White, fontSize = 13.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }
        }
        MessageDock(input, onInput, { send(input) }, voice, !busy)
        Spacer(Modifier.height(8.dp))
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
                Text(report, color = White, fontSize = 12.sp, lineHeight = 18.sp)
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
private fun MemoryPage(memories: List<String>, query: String, onQuery: (String) -> Unit, clear: () -> Unit) {
    val filtered = memories.filter { it.contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Heading("Memory core", "Recent conversation context stored on this device")
        OutlinedTextField(query,onQuery,Modifier.fillMaxWidth(),placeholder={Text("Search recent context")},
            leadingIcon={Icon(Icons.Default.Search,null,tint=Muted)},singleLine=true,shape=RoundedCornerShape(15.dp),colors=fieldColors())
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment=Alignment.CenterVertically) {
            Label("RECENT CONTEXT",Modifier.weight(1f))
            Text("${filtered.size} recent turns",color=Muted,fontSize=10.sp)
        }
        if (filtered.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            Text(if(memories.isEmpty()) "No saved conversation context yet." else "No matching memories.",color=Muted)
        } else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(filtered.reversed()) { item ->
                Surface(color=Panel,shape=RoundedCornerShape(15.dp),modifier=Modifier.fillMaxWidth()) {
                    Text(item,Modifier.padding(13.dp),color=White,fontSize=12.sp,lineHeight=18.sp)
                }
            }
        }
        OutlinedButton(onClick=clear,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.outlinedButtonColors(contentColor=Color(0xFFFF8C9B))) {
            Icon(Icons.Default.DeleteOutline,null); Spacer(Modifier.width(8.dp)); Text("Clear conversation memory")
        }
        Spacer(Modifier.height(10.dp))
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
        InfoCard("XYAA neural voice",voiceStatus,"VOICE")
        OutlinedButton(onClick=downloadVoice,modifier=Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Download,null); Spacer(Modifier.width(8.dp)); Text("Download / initialize neural voice")
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
private fun MessageDock(value:String,onValue:(String)->Unit,send:()->Unit,voice:()->Unit,enabled:Boolean=true) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).border(1.dp,Line,RoundedCornerShape(20.dp)).padding(5.dp),
        verticalAlignment=Alignment.CenterVertically) {
        OutlinedTextField(value,onValue,Modifier.weight(1f),placeholder={Text("Whisper or type a thought…",color=Muted,fontSize=12.sp)},
            maxLines=3,shape=RoundedCornerShape(16.dp),colors=fieldColors())
        IconButton(onClick=voice) { Icon(Icons.Default.Mic,"Voice input",tint=Cyan) }
        IconButton(onClick=send,enabled=enabled && value.isNotBlank()) { Icon(Icons.Default.ArrowUpward,"Send",tint=White) }
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
