package ai.xyaa.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Delivers a user-requested reminder as a normal Android notification. */
class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    @SuppressLint("MissingPermission")
    override fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE)?.take(120)?.ifBlank { null } ?: "XYAA reminder"
        val detail = inputData.getString(KEY_DETAIL)?.take(500)?.ifBlank { null } ?: "You asked me to remind you."
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "XYAA reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val openApp = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notificationId = (inputData.getString(KEY_ID) ?: id.toString()).hashCode() and 0x7fffffff
        val contentIntent = PendingIntent.getActivity(
            applicationContext, notificationId, openApp,
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
        return try {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
            Result.success()
        } catch (_: SecurityException) {
            // Permission can be revoked after scheduling; do not retry a notification the user cannot receive.
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_DETAIL = "detail"
        const val KEY_ID = "id"
        const val CHANNEL_ID = "xyaa_reminders"
    }
}
