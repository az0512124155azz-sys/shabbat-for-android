package com.avishait.shabbat

import android.content.Context
import androidx.work.*
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import java.util.concurrent.TimeUnit

class ShabbatNotificationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val city = ShabbatCore.loadCity()
            val t = ShabbatCore.nextShabbat(city)
            val notificationManager = NotificationManagerCompat.from(applicationContext)

            val notificationType = inputData.getString("type") ?: return Result.retry()

            val (title, body) = when (notificationType) {
                "candle" -> Pair(
                    "כניסת שבת",
                    "עוד ${calculateMinutesUntil(t.candle)} דקות"
                )
                "havdalah" -> Pair(
                    "סיום שבת",
                    "עוד ${calculateMinutesUntil(t.havdalah)} דקות"
                )
                else -> return Result.failure()
            }

            val notification = NotificationCompat.Builder(
                applicationContext,
                NOTIFICATION_CHANNEL_ID
            )
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            notificationManager.notify(notificationType.hashCode(), notification)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    private fun calculateMinutesUntil(targetTime: java.util.Date): Long {
        val now = System.currentTimeMillis()
        val diff = targetTime.time - now
        return if (diff > 0) diff / 60000 else 0
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "shabbat_times"
        const val CANDLE_WORK_TAG = "shabbat_candle"
        const val HAVDALAH_WORK_TAG = "shabbat_havdalah"

        fun scheduleNotifications(context: Context) {
            val city = ShabbatCore.loadCity()
            val t = ShabbatCore.nextShabbat(city)

            val workManager = WorkManager.getInstance(context)
            workManager.cancelAllWorkByTag(CANDLE_WORK_TAG)
            workManager.cancelAllWorkByTag(HAVDALAH_WORK_TAG)

            val candleTime = t.candle.time - (15 * 60 * 1000)
            val now = System.currentTimeMillis()
            val candleDelay = maxOf(0, candleTime - now)

            val candleWork = OneTimeWorkRequestBuilder<ShabbatNotificationWorker>()
                .setInitialDelay(candleDelay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("type" to "candle"))
                .addTag(CANDLE_WORK_TAG)
                .build()

            workManager.enqueueUniqueWork(
                CANDLE_WORK_TAG,
                ExistingWorkPolicy.REPLACE,
                candleWork
            )

            val havdalahTime = t.havdalah.time - (20 * 60 * 1000)
            val havdalahDelay = maxOf(0, havdalahTime - now)

            val havdalahWork = OneTimeWorkRequestBuilder<ShabbatNotificationWorker>()
                .setInitialDelay(havdalahDelay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("type" to "havdalah"))
                .addTag(HAVDALAH_WORK_TAG)
                .build()

            workManager.enqueueUniqueWork(
                HAVDALAH_WORK_TAG,
                ExistingWorkPolicy.REPLACE,
                havdalahWork
            )
        }
    }
}
