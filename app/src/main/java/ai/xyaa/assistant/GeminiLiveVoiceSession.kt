package ai.xyaa.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Base64
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Full-duplex Gemini Live session. Mic PCM is sent while native 24 kHz response audio
 * is played as it arrives; no text-to-speech synthesis is placed between the model and speaker.
 */
class GeminiLiveVoiceSession(
    private val context: Context,
    private val apiKey: String,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String, Boolean) -> Unit,
    private val onClosed: () -> Unit
) {
    companion object {
        // Native-audio Live model supported by the Gemini Developer API.
        private const val MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        private const val INPUT_RATE = 16000
        private const val OUTPUT_RATE = 24000
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val stopped = AtomicBoolean(false)
    private val playbackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "XYAA-Live-Audio-Playback").apply { priority = Thread.MAX_PRIORITY }
    }
    private val ready = AtomicBoolean(false)
    private val audioStarted = AtomicBoolean(false)
    private val playbackGeneration = AtomicInteger(0)
    private val setupTimeout = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var setupTimeoutTask: ScheduledFuture<*>? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var micThread: Thread? = null
    @Volatile private var transcriptText = StringBuilder()

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Microphone permission is required for live voice.")
            onClosed()
            return
        }
        onStatus("Connecting to Gemini Live…")
        setupTimeoutTask = setupTimeout.schedule({
            if (!ready.get() && !stopped.get()) {
                onStatus("Gemini Live did not finish connecting. Check the API key, model access and connection, then retry.")
                socket?.close(1001, "Live setup timeout")
                finish()
            }
        }, 20, TimeUnit.SECONDS)
        val encodedKey = URLEncoder.encode(apiKey.trim(), "UTF-8")
        val request = Request.Builder()
            .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$encodedKey")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject()
                    .put("setup", JSONObject()
                        .put("model", MODEL)
                        .put("generationConfig", JSONObject()
                            .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                            .put("maxOutputTokens", 256)
                            .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                            .put("speechConfig", JSONObject().put("voiceConfig",
                                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore")))))
                        .put("systemInstruction", JSONObject().put("parts",
                            org.json.JSONArray().put(JSONObject().put("text",
                                "You are XYAA, pronounced Zyaa, a helpful, precise Android voice assistant with a playful, upbeat, warmly feminine conversational style. Sound naturally witty, curious, encouraging, and expressive; use light humor only when it fits, and never force jokes or act childish. Speak with a bright, friendly, youthful energy, natural pacing, clear pronunciation, and varied but comfortable intonation. Keep simple replies concise and serious or sensitive replies calm, respectful, and clear. Match the user's energy without mocking, flirting, or pretending to be human. Do not routinely introduce yourself or repeat your name. Answer directly, be honest about uncertainty, actions, and capabilities, and never claim to have done something you did not do."))))
                        .put("inputAudioTranscription", JSONObject())
                        .put("outputAudioTranscription", JSONObject()))
                if (!webSocket.send(setup.toString())) {
                    onStatus("Gemini Live could not send its session setup. Please retry.")
                    finish()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("Live voice connection failed: ${t.message ?: "network error"}")
                finish()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped.get()) onStatus("Live voice session closed.")
                finish()
            }
        })
    }

    private fun handleServerMessage(raw: String) {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        root.optJSONObject("error")?.let { error ->
            onStatus("Gemini Live error: ${error.optString("message", "request rejected")}")
            finish()
            return
        }
        if (root.has("setupComplete")) {
            if (ready.compareAndSet(false, true)) {
                setupTimeoutTask?.cancel(false)
                onStatus("Live voice connected · listening")
                startMicrophone()
            }
            return
        }
        val serverContent = root.optJSONObject("serverContent") ?: return
        if (serverContent.optBoolean("interrupted", false)) {
            // Discard queued audio from the interrupted turn so old speech cannot play later.
            playbackGeneration.incrementAndGet()
            runCatching { track?.pause(); track?.flush(); track?.play() }
        }
        serverContent.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
            onTranscript(it, true)
        }
        serverContent.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
            onTranscript(it, false)
        }
        val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data") ?: continue
            val encoded = inline.optString("data")
            if (encoded.isNotBlank()) {
                val pcm = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: continue
                playPcm(pcm)
            }
        }
        if (serverContent.optBoolean("turnComplete", false)) onStatus("Live voice connected · listening")
    }

    private fun startMicrophone() {
        if (stopped.get() || !audioStarted.compareAndSet(false, true)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Microphone permission was revoked. Allow it again to use live voice.")
            stop()
            return
        }
        val minBytes = AudioRecord.getMinBufferSize(INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) {
            onStatus("This device cannot initialize 16 kHz microphone capture.")
            stop()
            return
        }
        val input = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes * 2, INPUT_RATE * 2)
            )
        } catch (error: Throwable) {
            onStatus("Microphone could not be initialized: ${error.message ?: "device audio error"}")
            stop()
            return
        }
        if (input.state != AudioRecord.STATE_INITIALIZED) {
            input.release()
            onStatus("Microphone could not be started.")
            stop()
            return
        }
        recorder = input
        try {
            input.startRecording()
        } catch (error: Throwable) {
            onStatus("Microphone could not start recording: ${error.message ?: "permission or device error"}")
            stop()
            return
        }
        onStatus("Listening · speak naturally")
        micThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buffer = ShortArray(1600) // 100 ms of mono 16 kHz PCM
            try {
                while (!stopped.get()) {
                    val count = input.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) continue
                    val bytes = ByteArray(count * 2)
                    for (i in 0 until count) {
                        val sample = buffer[i].toInt()
                        bytes[i * 2] = (sample and 0xff).toByte()
                        bytes[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
                    }
                    val audio = JSONObject().put("realtimeInput", JSONObject().put("audio",
                        JSONObject().put("mimeType", "audio/pcm;rate=16000")
                            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
                    if (socket?.send(audio.toString()) != true) break
                }
            } catch (_: Throwable) {
                if (!stopped.get()) onStatus("Microphone stream stopped unexpectedly.")
            } finally {
                runCatching { socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()) }
            }
        }.apply { name = "XYAA-Live-Microphone"; start() }
    }

    private fun playPcm(bytes: ByteArray) {
        if (stopped.get() || bytes.isEmpty()) return
        val generation = playbackGeneration.get()
        playbackExecutor.execute {
            if (!stopped.get() && generation == playbackGeneration.get()) writePcm(bytes, generation)
        }
    }

    @Synchronized
    private fun writePcm(bytes: ByteArray, generation: Int) {
        if (stopped.get() || generation != playbackGeneration.get() || bytes.isEmpty()) return
        var output = track
        if (output == null || output.state != AudioTrack.STATE_INITIALIZED) {
            val minBytes = AudioTrack.getMinBufferSize(OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBytes <= 0) return
            output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBytes * 4, 24_000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (output.state != AudioTrack.STATE_INITIALIZED) {
                output.release()
                return
            }
            track = output
            output.play()
        }
        if (output.playState != AudioTrack.PLAYSTATE_PLAYING) output.play()
        var offset = 0
        while (offset < bytes.size && !stopped.get() && generation == playbackGeneration.get()) {
            val written = output.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        playbackGeneration.incrementAndGet()
        setupTimeoutTask?.cancel(false)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { track?.pause(); track?.flush(); track?.stop(); track?.release() }
        track = null
        socket?.close(1000, "User ended live voice")
        socket = null
        playbackExecutor.shutdownNow()
        setupTimeout.shutdownNow()
        finish()
    }

    private val finished = AtomicBoolean(false)
    private fun finish() {
        if (!finished.compareAndSet(false, true)) return
        stopped.set(true)
        setupTimeoutTask?.cancel(false)
        setupTimeout.shutdownNow()
        playbackGeneration.incrementAndGet()
        runCatching { recorder?.stop(); recorder?.release() }
        recorder = null
        runCatching { track?.pause(); track?.flush(); track?.stop(); track?.release() }
        track = null
        micThread = null
        playbackExecutor.shutdownNow()
        onClosed()
    }
}
