package ai.xyaa.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Delivers a user-requested reminder as a normal, dismissible Android notification. */
class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE)?.take(120)?.ifBlank { null } ?: "XYAA reminder"
        val detail = inputData.getString(KEY_DETAIL)?.take(500)?.ifBlank { null } ?: "You asked me to remind you."
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "XYAA reminders", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            return Result.success()
        }
        val openApp = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            idFrom(inputData.getString(KEY_ID)),
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return runCatching {
            NotificationManagerCompat.from(applicationContext).notify(idFrom(inputData.getString(KEY_ID)), notification)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun idFrom(value: String?): Int = (value ?: "xyaa").hashCode() and 0x7fffffff

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_DETAIL = "detail"
        const val KEY_ID = "id"
        const val CHANNEL_ID = "xyaa_reminders"
    }
}
