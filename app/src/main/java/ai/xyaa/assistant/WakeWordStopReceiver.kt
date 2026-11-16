package ai.xyaa.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WakeWordStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        context.stopService(Intent(context, WakeWordService::class.java))
    }
}
