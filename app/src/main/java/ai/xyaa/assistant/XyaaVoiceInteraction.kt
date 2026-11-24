package ai.xyaa.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import androidx.core.content.ContextCompat

/**
 * Android keeps the selected VoiceInteractionService alive for assistant invocation.
 * XYAA uses that system-owned lifetime to keep its explicitly armed local hotword detector
 * available even after MainActivity is no longer visible.
 */
class XyaaVoiceInteractionService : VoiceInteractionService() {
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WakeWordService.ACTION_WAKE_DETECTED) return
            val preferences = getSharedPreferences("xyaa_voice", MODE_PRIVATE)
            if (!preferences.getBoolean("wake_word_armed", false)) return

            // Ask Android to show the selected assistant session. This is the supported
            // background-safe route; MainActivity itself is not launched from the detector.
            runCatching {
                showSession(
                    Bundle().apply {
                        putBoolean(WakeWordService.EXTRA_START_LIVE_VOICE, true)
                    },
                    0
                )
            }
        }
    }

    private var receiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            wakeReceiver,
            IntentFilter(WakeWordService.ACTION_WAKE_DETECTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    override fun onReady() {
        super.onReady()
        // Do not collect the foreground app's screen or hierarchy by default.
        setDisabledShowContext(
            VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
        )
        startArmedWakeWordIfPermitted()
    }

    private fun startArmedWakeWordIfPermitted() {
        if (!getSharedPreferences("xyaa_voice", MODE_PRIVATE)
                .getBoolean("wake_word_armed", false)
        ) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        // A selected VoiceInteractionService is an Android-recognized exemption for
        // microphone foreground-service startup while the app UI is in the background.
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, WakeWordService::class.java)
            )
        }.onFailure { error ->
            sendBroadcast(
                Intent(WakeWordService.ACTION_STATE).setPackage(packageName)
                    .putExtra(WakeWordService.EXTRA_ACTIVE, false)
                    .putExtra(
                        WakeWordService.EXTRA_STATUS,
                        "Android couldn't start the background microphone: ${error.message ?: "permission or system restriction"}"
                    )
            )
        }
    }

    override fun onShowSessionFailed(args: Bundle) {
        super.onShowSessionFailed(args)
        sendBroadcast(
            Intent(WakeWordService.ACTION_STATE).setPackage(packageName)
                .putExtra(WakeWordService.EXTRA_ACTIVE, false)
                .putExtra(WakeWordService.EXTRA_STATUS, "Android could not open XYAA's assistant session.")
        )
    }

    override fun onShutdown() {
        // If the user switches away from XYAA as the system assistant, stop the detector.
        stopService(Intent(this, WakeWordService::class.java))
        super.onShutdown()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(wakeReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
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
        // Use Android's assistant-activity launch path for both the system gesture and
        // locally detected hotword. Android authorizes this handoff from the session.
        val launch = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            putExtra(WakeWordService.EXTRA_START_LIVE_VOICE, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startAssistantActivity(launch)
    }
}
