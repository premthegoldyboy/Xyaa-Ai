package ai.xyaa.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Explicitly user-started, visible microphone foreground service.
 * The on-device sherpa-onnx model detects "Hey Zyaa" locally; raw microphone audio
 * is not uploaded or stored by this service.
 */
class WakeWordService : Service() {
    companion object {
        const val ACTION_STOP = "ai.xyaa.assistant.STOP_WAKE_WORD"
        const val ACTION_WAKE_DETECTED = "ai.xyaa.assistant.WAKE_DETECTED"
        const val ACTION_STATE = "ai.xyaa.assistant.WAKE_WORD_STATE"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_START_LIVE_VOICE = "start_live_voice"
        private const val CHANNEL = "xyaa_wake_word"
        private const val ALERT_CHANNEL = "xyaa_wake_word_alerts"
        private const val ALERT_NOTIFICATION_ID = 7304
        private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
        private const val MAX_EXTRACTED_BYTES = 512L * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 10000
        private const val NOTIFICATION_ID = 7301
        private const val MODEL_NAME = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"
        private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$MODEL_NAME.tar.bz2"
    }

    private val stopping = AtomicBoolean(false)
    @Volatile private var record: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var spotter: KeywordSpotter? = null
    @Volatile private var lastAlertAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing offline wake-word model…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker == null) {
            worker = Thread({ runDetector() }, "XYAA-WakeWord")
                .apply { start() }
        }
        // A microphone service must only run after an explicit foreground user action.
        // Do not let Android restart it in the background after process death.
        return START_NOT_STICKY
    }

    private fun runDetector() {
        try {
            val dir = ensureModel()
            if (stopping.get()) return
            val encoder = File(dir, "encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx")
            val decoder = File(dir, "decoder-epoch-13-avg-2-chunk-8-left-64.onnx")
            val joiner = File(dir, "joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx")
            val tokens = File(dir, "tokens.txt")
            val keywords = File(dir, "xyaa-keywords.txt").apply {
                writeText("HH EY1 Z AY1 AH0 @XYAA\n")
            }
            check(encoder.isFile && decoder.isFile && joiner.isFile && tokens.isFile) {
                "Wake-word model archive is missing required files."
            }
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = 1,
                    provider = "cpu",
                    modelType = "zipformer2",
                    modelingUnit = "phone+ppinyin"
                ),
                maxActivePaths = 4,
                keywordsFile = keywords.absolutePath,
                keywordsScore = 2.5f,
                keywordsThreshold = 0.25f,
                numTrailingBlanks = 1
            )
            val kws = KeywordSpotter(config = config)
            spotter = kws
            val stream = kws.createStream()
            updateNotification("Wake word active · microphone is on")
            sendState(true)
            val minBytes = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBytes > 0) { "Microphone is unavailable." }
            check(androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                "Microphone permission is required to run the wake word service."
            }
            val audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes * 2, 6400)
            )
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed." }
            record = audio
            audio.startRecording()
            val buffer = ShortArray(1600)
            while (!stopping.get()) {
                val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) continue
                val samples = FloatArray(count) { buffer[it] / 32768.0f }
                stream.acceptWaveform(samples, sampleRate = 16000)
                while (kws.isReady(stream)) {
                    kws.decode(stream)
                    val result = kws.getResult(stream).keyword
                    if (result.isNotBlank()) {
                        kws.reset(stream)
                        val now = System.currentTimeMillis()
                        if (now - lastAlertAt > 30_000L) {
                            lastAlertAt = now
                            sendBroadcast(Intent(ACTION_WAKE_DETECTED).setPackage(packageName))
                            showWakeAlert()
                            updateNotification("I heard Hey Zyaa · tap the alert to talk")
                        }
                    }
                }
            }
            stream.release()
            kws.release()
        } catch (error: Throwable) {
            if (!stopping.get()) updateNotification("Wake word unavailable: ${error.message ?: "model or microphone error"}")
            sendState(false)
        } finally {
            stopping.set(true)
            sendState(false)
            runCatching { record?.stop(); record?.release() }
            record = null
            runCatching { spotter?.release() }
            spotter = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun ensureModel(): File {
        val root = File(filesDir, "wake-word")
        val modelDir = File(root, MODEL_NAME)
        val needed = listOf(
            "encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx",
            "decoder-epoch-13-avg-2-chunk-8-left-64.onnx",
            "joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx",
            "tokens.txt"
        )
        if (needed.all { File(modelDir, it).isFile }) return modelDir
        root.mkdirs()
        updateNotification("Downloading offline wake-word model…")
        val archive = File(root, "wake-word.tar.bz2.part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "XYAA-Android")
        }
        try {
            if (connection.responseCode !in 200..299) error("Model download HTTP ${connection.responseCode}")
            require(connection.contentLengthLong <= MAX_ARCHIVE_BYTES || connection.contentLengthLong < 0L) {
                "Wake-word model archive exceeds the 256 MB download limit."
            }
            connection.inputStream.use { input ->
                FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(131072)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        require(downloaded <= MAX_ARCHIVE_BYTES) { "Wake-word model archive exceeded the 256 MB download limit." }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        } finally { connection.disconnect() }
        check(archive.length() > 1024 * 1024) { "Wake-word model download was incomplete." }
        val staging = File(root, "$MODEL_NAME.installing")
        staging.deleteRecursively()
        staging.mkdirs()
        val canonicalRoot = staging.canonicalFile.toPath()
        var extracted = 0L
        var entries = 0
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                entries++
                require(entries <= MAX_ARCHIVE_ENTRIES) { "Wake-word archive contains too many entries." }
                require(!entry.isSymbolicLink && !entry.isLink) { "Links are not allowed in the wake-word archive." }
                val target = File(staging, entry.name).canonicalFile
                require(target.toPath().startsWith(canonicalRoot)) { "Unsafe path in wake-word model archive." }
                if (entry.isDirectory) target.mkdirs()
                else if (entry.isFile) {
                    require(entry.size in 0..MAX_EXTRACTED_BYTES) { "Wake-word archive entry is too large." }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(131072)
                        while (true) {
                            val count = tar.read(buffer)
                            if (count < 0) break
                            extracted += count
                            require(extracted <= MAX_EXTRACTED_BYTES) { "Wake-word model expands beyond the 512 MB installation limit." }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
        val stagedModel = File(staging, MODEL_NAME)
        check(needed.all { File(stagedModel, it).isFile }) { "Wake-word model installation is incomplete." }
        installStagedModel(stagedModel, modelDir)
        staging.deleteRecursively()
        archive.delete()
        check(needed.all { File(modelDir, it).isFile }) { "Wake-word model installation is incomplete." }
        return modelDir
    }

    /** Keep the last working wake-word model if promotion of a validated update fails. */
    private fun installStagedModel(stagedModel: File, target: File) {
        val backup = File(target.parentFile, "${target.name}.previous")
        if (backup.exists() && !target.exists()) {
            check(backup.renameTo(target)) { "Could not restore the previous wake-word model." }
        } else if (backup.exists()) {
            backup.deleteRecursively()
        }
        val hadPrevious = target.exists()
        if (hadPrevious) check(target.renameTo(backup)) { "Could not preserve the previous wake-word model." }
        try {
            check(stagedModel.renameTo(target)) { "Could not finalize the wake-word model installation." }
        } catch (error: Throwable) {
            if (hadPrevious && backup.exists()) {
                check(backup.renameTo(target)) { "Wake-word installation failed and the previous model could not be restored." }
            }
            throw error
        }
        if (backup.exists()) backup.deleteRecursively()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "XYAA wake word", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "XYAA wake word alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun notification(message: String): Notification {
        val stop = PendingIntent.getBroadcast(
            this, 7302, Intent(this, WakeWordStopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 7303, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("XYAA wake word")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }

    private fun showWakeAlert() {
        val open = PendingIntent.getActivity(
            this, 7305,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_START_LIVE_VOICE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alert = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("XYAA heard you")
            .setContentText("Tap to start talking with XYAA.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_btn_speak_now, "Talk now", open)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, alert)
    }

    private fun sendState(active: Boolean) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_ACTIVE, active))
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    override fun onDestroy() {
        stopping.set(true)
        sendState(false)
        runCatching { record?.stop() }
        worker?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
