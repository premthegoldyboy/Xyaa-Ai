package ai.xyaa.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Compatibility endpoint required by VoiceInteractionService metadata on Android 11 and
 * earlier. Android 12+ does not use this metadata entry for voice interaction.
 *
 * XYAA currently owns its live audio pipeline through GeminiLiveVoiceSession; this service
 * intentionally reports that standalone platform recognition is not implemented yet.
 */
class XyaaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback) = Unit
}
