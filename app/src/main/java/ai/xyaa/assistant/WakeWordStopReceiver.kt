package ai.xyaa.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WakeWordStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // The notification's Stop action is an explicit user opt-out, not just a temporary pause.
        context.getSharedPreferences("xyaa_voice", Context.MODE_PRIVATE)
            .edit().putBoolean("wake_word_armed", false).apply()
        context.sendBroadcast(Intent(WakeWordService.ACTION_USER_STOP).setPackage(context.packageName))
        context.stopService(Intent(context, WakeWordService::class.java))
    }
}
