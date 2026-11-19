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
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Offline neural speech output for XYAA, powered by Sherpa-ONNX + Kokoro. */
class NeuralVoiceEngine(private val context: Context, private val onStatus: (String) -> Unit) {
    companion object {
        private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"
        private const val MODEL_DIR = "kokoro-multi-lang-v1_0"
    }
    private val modelRoot = File(context.filesDir, "neural-voice")
    private val modelDir = File(modelRoot, MODEL_DIR)
    private val executor = Executors.newSingleThreadExecutor() // Serialize model inference
    private val playbackExecutor = Executors.newSingleThreadExecutor() // Play while the next chunk is synthesized
    private val revealExecutor = Executors.newSingleThreadExecutor()   // Waits for each chunk to drain, in order
    private val downloading = AtomicBoolean(false)
    @Volatile private var engine: OfflineTts? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var closed = false
    private val generation = AtomicInteger(0)

    fun initializeIfInstalled() {
        executor.execute {
            if (!closed && hasModelFiles()) runCatching { createEngine() }
                .onSuccess { engine = it; onStatus("${XyaaVoiceProfile.READY_STATUS}") }
                .onFailure { onStatus("Voice model found but could not load: ${it.message ?: "unknown error"}") }
        }
    }

    fun downloadAndInitialize() {
        if (closed || engine != null || !downloading.compareAndSet(false, true)) return
        executor.execute {
            try {
                onStatus("Downloading XYAA's Fiona-inspired offline voice… (about 400 MB)")
                modelRoot.mkdirs()
                val archive = File(modelRoot, "kokoro-model.tar.bz2.part")
                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "XYAA-Android")
                }
                try {
                    connection.connect()
                    if (connection.responseCode !in 200..299) error("Model download failed: HTTP ${connection.responseCode}")
                    val total = connection.contentLengthLong
                    connection.inputStream.use { input ->
                        FileOutputStream(archive).use { output ->
                            val buffer = ByteArray(131072)
                            var downloaded = 0L
                            var lastUpdate = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                if (downloaded - lastUpdate >= 5L * 1024 * 1024) {
                                    val percent = if (total > 0) " (${downloaded * 100 / total}%)" else ""
                                    onStatus("Downloading neural voice: ${downloaded / (1024 * 1024)} MB${if (total > 0) " / ${total / (1024 * 1024)} MB" else ""}$percent")
                                    lastUpdate = downloaded
                                }
                            }
                            output.fd.sync()
                        }
                    }
                } finally { connection.disconnect() }
                check(archive.isFile && archive.length() > 1024 * 1024) { "Voice model download was incomplete. Please retry." }
                onStatus("Installing neural voice model… preparing archive")
                extractSafely(archive, modelRoot) { extracted, fileName ->
                    onStatus("Installing neural voice: ${extracted / (1024 * 1024)} MB extracted · ${fileName.takeLast(42)}")
                }
                archive.delete()
                check(hasModelFiles()) { "Downloaded archive is missing required Kokoro files" }
                engine = createEngine()
                onStatus("${XyaaVoiceProfile.READY_STATUS}")
            } catch (error: Throwable) {
                onStatus("Neural voice setup failed: ${error.message ?: "unknown error"}. Existing system voice remains available.")
            } finally { downloading.set(false) }
        }
    }

    /**
     * Speaks [text] (already cleaned by AssistantTextFormatter.forSpeech().spoken).
     *
     * - A new call, or [stop], supersedes any speech still running; stale work exits and never calls back.
     * - [onChunk] receives the spoken transcript up to and including a chunk only after that chunk has
     *   actually finished playing (AudioTrack head position reached the chunk's last frame), so the
     *   transcript can never run ahead of the audio. Playback of the next chunk is not delayed by this.
     * - [onComplete] fires exactly once, after the last sample has actually played.
     * - If synthesis or playback fails, [fallback] receives the number of spoken characters already played
     *   in full, so the device voice continues from there instead of repeating the whole answer.
     */
    fun speak(
        text: String,
        onComplete: () -> Unit = {},
        onChunk: (String) -> Unit = {},
        fallback: (Int) -> Unit = {}
    ): Boolean {
        val active = engine ?: return false
        if (closed || text.isBlank()) return false
        val gen = generation.incrementAndGet()   // supersedes anything already speaking
        haltPlayback()
        executor.execute {
            fun stale() = closed || gen != generation.get()
            val playedChars = AtomicInteger(0)
            val playbackError = AtomicReference<Throwable?>(null)
            val slots = Semaphore(2)               // synthesize at most two chunks ahead of playback
            val framesWritten = AtomicLong(0)
            val lastPlayback = AtomicReference<Future<*>?>(null)
            val lastReveal = AtomicReference<Future<*>?>(null)
            try {
                val chunks = speechChunks(text)
                var consumedChars = 0
                chunks.forEachIndexed { index, chunk ->
                    if (stale()) return@execute
                    val audio = active.generate(chunk, sid = XyaaVoiceProfile.KOKORO_VOICE_ID, speed = XyaaVoiceProfile.KOKORO_SPEED)
                    if (stale()) return@execute
                    if (playbackError.get() != null) throw playbackError.get()!!
                    val chunkStart = consumedChars
                    consumedChars += chunk.length + 1
                    val transcript = text.substring(0, minOf(text.length, chunkStart + chunk.length))
                    val first = index == 0
                    slots.acquire()
                    lastPlayback.set(playbackExecutor.submit(Runnable {
                        try {
                            if (stale()) return@Runnable
                            // Capture the frame range in playback order, then start transcript
                            // tracking BEFORE the blocking AudioTrack write can fill its buffer.
                            val startFrame = framesWritten.get()
                            val targetFrame = startFrame + audio.samples.size
                            lastReveal.set(revealExecutor.submit(Runnable {
                                try {
                                    val revealed = revealDuringPlayback(startFrame, targetFrame, chunkStart, chunk, text, ::stale, onChunk)
                                    if (!stale() && revealed) playedChars.set(chunkStart + chunk.length)
                                } catch (t: Throwable) {
                                    playbackError.compareAndSet(null, t)
                                }
                            }))
                            play(audio.samples, audio.sampleRate, first, framesWritten, ::stale)
                        } catch (t: Throwable) {
                            playbackError.compareAndSet(null, t)
                        } finally { slots.release() }
                    }))
                }
                lastPlayback.get()?.get()
                lastReveal.get()?.get()   // the final chunk has drained and its transcript has been published
                playbackError.get()?.let { throw it }
                if (stale()) return@execute
                if (completed(gen)) onComplete()
            } catch (error: Throwable) {
                if (stale()) return@execute
                onStatus("Neural voice playback failed; switching to device voice for this reply.")
                haltPlayback()
                // Claim the generation so nothing from this attempt can speak or complete afterwards.
                if (generation.compareAndSet(gen, gen + 1)) fallback(playedChars.get())
            }
        }
        return true
    }

    private val completedGeneration = AtomicInteger(-1)
    /** True only the first time it is called for [gen]; guards against duplicate completion callbacks. */
    private fun completed(gen: Int): Boolean = completedGeneration.getAndSet(gen) != gen

    /** Stops neural speech immediately. Safe to call from any thread, and when nothing is playing. */
    fun stop() {
        generation.incrementAndGet()
        haltPlayback()
    }

    private fun haltPlayback() {
        runCatching { audioTrack?.let { it.pause(); it.flush() } }
    }

    private fun playbackHead(track: AudioTrack): Long = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    private fun revealDuringPlayback(
        startFrame: Long,
        endFrame: Long,
        chunkStart: Int,
        chunk: String,
        fullText: String,
        stale: () -> Boolean,
        onChunk: (String) -> Unit
    ): Boolean {
        // Wait until AudioTrack has actually begun advancing before starting the
        // elapsed-time fallback; otherwise synthesis/queue delay could reveal text early.
        var output: AudioTrack? = null
        var initialHead = 0L
        val startDeadline = SystemClock.elapsedRealtime() + 5000L
        while (!stale() && SystemClock.elapsedRealtime() < startDeadline) {
            output = audioTrack
            if (output != null && output.state == AudioTrack.STATE_INITIALIZED) {
                initialHead = (playbackHead(output) - startFrame).coerceAtLeast(0L)
                if (initialHead > 0L) break
            }
            Thread.sleep(10)
        }
        if (stale() || output == null || initialHead <= 0L) return false

        val durationFrames = (endFrame - startFrame).coerceAtLeast(1L)
        val revealStartedAt = SystemClock.elapsedRealtime()
        val deadline = revealStartedAt + (durationFrames * 1000L / output.sampleRate) + 2200L
        var lastVisible = 0
        while (!stale() && SystemClock.elapsedRealtime() < deadline) {
            val actualFrames = (playbackHead(output) - startFrame).coerceIn(0L, durationFrames)
            // Prefer the real playback head; elapsed audio time is a fallback for
            // devices whose streamed AudioTrack playback-head counter freezes.
            val elapsedFrames = initialHead +
                ((SystemClock.elapsedRealtime() - revealStartedAt) * output.sampleRate / 1000L)
            val playedFrames = maxOf(actualFrames, elapsedFrames).coerceIn(0L, durationFrames)
            val estimated = (chunk.length * playedFrames.toFloat() / durationFrames.toFloat())
                .toInt().coerceIn(0, chunk.length)
            var visible = estimated
            if (visible < chunk.length) {
                while (visible > 0 && !chunk[visible - 1].isWhitespace()) visible--
            }
            if (visible > lastVisible) {
                lastVisible = visible
                onChunk(fullText.substring(0, minOf(fullText.length, chunkStart + visible)).trimEnd())
            }
            if (playedFrames >= durationFrames) break
            Thread.sleep(30)
        }
        if (stale()) return false
        onChunk(fullText.substring(0, minOf(fullText.length, chunkStart + chunk.length)))
        return true
    }

    /** True when the track has really played [frames] frames (false if it was flushed or timed out). */
    private fun drained(frames: Long): Boolean {
        val track = audioTrack ?: return false
        return playbackHead(track) >= frames
    }

    private fun speechChunks(text: String, maxChars: Int = XyaaVoiceProfile.MAX_CHUNK_CHARS): List<String> {
        val sentences = text.replace("\n", " ").split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        sentences.forEach { sentence ->
            if (current.isNotEmpty() && current.length + sentence.length + 1 > maxChars) {
                chunks.add(current.toString())
                current = StringBuilder()
            }
            if (sentence.length <= maxChars) {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            } else {
                sentence.split(Regex("\\s+")).forEach { word ->
                    if (current.isNotEmpty() && current.length + word.length + 1 > maxChars) {
                        chunks.add(current.toString())
                        current = StringBuilder()
                    }
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(word)
                }
            }
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
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

    private fun play(
        samples: FloatArray, sampleRate: Int, firstChunk: Boolean,
        framesWritten: AtomicLong, stale: () -> Boolean
    ) {
        if (samples.isEmpty() || stale()) return
        // Keep one AudioTrack alive across all synthesized phrases. Recreating it for
        // every phrase adds audible startup gaps even when the next phrase is ready.
        var track = audioTrack
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED || track.sampleRate != sampleRate) {
            runCatching { track?.release() }
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(4096)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 4, 32768))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Could not initialize audio output" }
            audioTrack = track
        } else if (firstChunk) {
            // New utterance: drop anything left over from an interrupted one.
            track.pause(); track.flush()
        }
        if (firstChunk) framesWritten.set(0)
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        var offset = 0
        while (offset < samples.size && !stale()) {
            val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (stale()) return
            check(written > 0) { "Audio output stopped unexpectedly" }
            offset += written
            framesWritten.addAndGet(written.toLong())
        }
    }

    private fun hasModelFiles() = File(modelDir, "model.onnx").isFile && File(modelDir, "voices.bin").isFile &&
        File(modelDir, "tokens.txt").isFile && File(modelDir, "espeak-ng-data").isDirectory

    private fun extractSafely(
        archive: File,
        destination: File,
        onProgress: (Long, String) -> Unit
    ) {
        val root = destination.canonicalFile.toPath()
        var extracted = 0L
        var lastUpdate = 0L
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val output = File(destination, entry.name).canonicalFile
                require(output.toPath().startsWith(root)) { "Unsafe path in voice model archive" }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else if (entry.isFile) {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { sink ->
                        val buffer = ByteArray(131072)
                        while (true) {
                            val count = tar.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                            extracted += count
                            if (extracted - lastUpdate >= 8L * 1024 * 1024) {
                                onProgress(extracted, entry.name)
                                lastUpdate = extracted
                            }
                        }
                    }
                }
            }
        }
        onProgress(extracted, "finalizing")
    }

    fun close() {
        closed = true; executor.shutdownNow(); playbackExecutor.shutdownNow(); revealExecutor.shutdownNow()
        runCatching { audioTrack?.pause(); audioTrack?.flush(); audioTrack?.stop(); audioTrack?.release() }; audioTrack = null
        runCatching { engine?.release() }; engine = null
    }
}
