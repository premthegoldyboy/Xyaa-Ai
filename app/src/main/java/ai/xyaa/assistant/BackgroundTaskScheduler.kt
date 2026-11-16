package ai.xyaa.assistant

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Persistent OS-managed reminders; WorkManager reschedules work across process death and reboot. */
class BackgroundTaskScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    fun scheduleReminder(delayMinutes: Long, detail: String): String {
        require(delayMinutes in 1L..10080L) { "Choose a reminder between 1 minute and 7 days." }
        val id = UUID.randomUUID().toString()
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(
                Data.Builder()
                    .putString(ReminderWorker.KEY_TITLE, "XYAA reminder")
                    .putString(ReminderWorker.KEY_DETAIL, detail.take(500))
                    .putString(ReminderWorker.KEY_ID, id)
                    .build()
            )
            .build()
        workManager.enqueueUniqueWork("xyaa-reminder-$id", ExistingWorkPolicy.KEEP, request)
        return id
    }

    fun notificationsEnabled(): Boolean = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
}
