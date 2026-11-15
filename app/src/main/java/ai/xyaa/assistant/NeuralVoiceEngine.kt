package ai.xyaa.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Offline neural speech output for XYAA, powered by Sherpa-ONNX + Kokoro. */
class NeuralVoiceEngine(private val context: Context, private val onStatus: (String) -> Unit) {
    companion object {
        private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"
        private const val MODEL_DIR = "kokoro-multi-lang-v1_0"
        private const val VOICE_ID = 1 // Kokoro af_bella
    }
    private val modelRoot = File(context.filesDir, "neural-voice")
    private val modelDir = File(modelRoot, MODEL_DIR)
    private val executor = Executors.newSingleThreadExecutor()
    private val downloading = AtomicBoolean(false)
    @Volatile private var engine: OfflineTts? = null
    @Volatile private var closed = false

    fun initializeIfInstalled() {
        executor.execute {
            if (!closed && hasModelFiles()) runCatching { createEngine() }
                .onSuccess { engine = it; onStatus("Ready · Kokoro neural voice (af_bella), offline") }
                .onFailure { onStatus("Voice model found but could not load: ${it.message ?: "unknown error"}") }
        }
    }

    fun downloadAndInitialize() {
        if (closed || engine != null || !downloading.compareAndSet(false, true)) return
        executor.execute {
            try {
                onStatus("Downloading Kokoro neural voice… (about 400 MB)")
                modelRoot.mkdirs()
                val archive = File(modelRoot, "kokoro-model.tar.bz2.part")
                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "XYAA-Android")
                }
                try {
                    connection.connect()
                    if (connection.responseCode !in 200..299) error("Model download failed: HTTP ${connection.responseCode}")
                    connection.inputStream.use { input -> FileOutputStream(archive).use { output -> input.copyTo(output, 131072) } }
                } finally { connection.disconnect() }
                onStatus("Installing neural voice model…")
                extractSafely(archive, modelRoot)
                archive.delete()
                check(hasModelFiles()) { "Downloaded archive is missing required Kokoro files" }
                engine = createEngine()
                onStatus("Ready · Kokoro neural voice (af_bella), offline")
            } catch (error: Throwable) {
                onStatus("Neural voice setup failed: ${error.message ?: "unknown error"}. Existing system voice remains available.")
            } finally { downloading.set(false) }
        }
    }

    fun speak(text: String): Boolean {
        val active = engine ?: return false
        if (closed || text.isBlank()) return false
        executor.execute {
            runCatching {
                val spoken = text.replace(Regex("(?i)\\bX\\s*Y\\s*A\\s*A\\b"), "Zyaa")
                    .replace(Regex("(?i)\\bXYAA\\b"), "Zyaa")
                val audio = active.generate(spoken, sid = VOICE_ID, speed = 1.0f)
                play(audio.samples, audio.sampleRate)
            }.onFailure { onStatus("Neural voice playback failed; using device voice next time.") }
        }
        return true
    }

    private fun createEngine(): OfflineTts {
        val model = File(modelDir, "model.onnx")
        val voices = File(modelDir, "voices.bin")
        val tokens = File(modelDir, "tokens.txt")
        val espeak = File(modelDir, "espeak-ng-data")
        check(model.isFile && voices.isFile && tokens.isFile && espeak.isDirectory) { "Kokoro model files are incomplete" }
        val config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = model.absolutePath, voices = voices.absolutePath, tokens = tokens.absolutePath,
                dataDir = espeak.absolutePath,
                lexicon = "${File(modelDir, "lexicon-us-en.txt").absolutePath},${File(modelDir, "lexicon-zh.txt").absolutePath}",
                lang = "en"
            ),
            numThreads = 2, debug = false, provider = "cpu"
        ))
        return OfflineTts(config = config)
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty() || closed) return
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBuffer).setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            val playbackStarted = SystemClock.elapsedRealtime()
            track.play()
            var offset = 0
            while (offset < samples.size && !closed) {
                val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            val remainingMs = (samples.size * 1000L / sampleRate - (SystemClock.elapsedRealtime() - playbackStarted)).coerceAtLeast(0L)
            if (!closed && remainingMs > 0) Thread.sleep(minOf(remainingMs, 30000L))
        } finally { runCatching { track.stop() }; track.release() }
    }

    private fun hasModelFiles() = File(modelDir, "model.onnx").isFile && File(modelDir, "voices.bin").isFile &&
        File(modelDir, "tokens.txt").isFile && File(modelDir, "espeak-ng-data").isDirectory

    private fun extractSafely(archive: File, destination: File) {
        val root = destination.canonicalFile.toPath()
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val output = File(destination, entry.name).canonicalFile
                require(output.toPath().startsWith(root)) { "Unsafe path in voice model archive" }
                if (entry.isDirectory) output.mkdirs() else if (entry.isFile) {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { tar.copyTo(it, 131072) }
                }
            }
        }
    }

    fun close() {
        closed = true; executor.shutdownNow()
        runCatching { engine?.release() }; engine = null
    }
}
