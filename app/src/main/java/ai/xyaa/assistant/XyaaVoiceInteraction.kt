package ai.xyaa.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Lightweight system-owned entry point. Keep this service small; the actual assistant UI
 * and microphone session are started only when Android invokes the assistant.
 */
class XyaaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Do not collect the foreground app's screen or hierarchy by default.
        setDisabledShowContext(
            VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
        )
    }
}

class XyaaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        XyaaVoiceInteractionSession(this)
}

private class XyaaVoiceInteractionSession(
    context: android.content.Context
) : VoiceInteractionSession(context) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // XYAA renders its own assistant Activity; do not create a competing session window.
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Use Android's assistant-activity launch path so XYAA appears above the current app.
        // Keep the session alive while the assistant Activity is presented; hiding the session
        // immediately here can dismiss the system-owned assistant surface during handoff.
        val launch = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            putExtra(WakeWordService.EXTRA_START_LIVE_VOICE, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startAssistantActivity(launch)
    }
}
