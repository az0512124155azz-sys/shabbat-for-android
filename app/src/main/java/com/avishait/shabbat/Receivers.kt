package com.avishait.shabbat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val type = intent.getStringExtra("type") ?: return
        val time = intent.getStringExtra("time") ?: ""

        val title: String
        val body: String
        if (type == NotificationScheduler.TYPE_EREV) {
            title = "🕯️ שבת שלום!"
            body = "השבת נכנסת היום בשעה $time. נותרו כשלוש שעות — זמן להתארגן ולקבל את השבת ✨"
        } else {
            title = "⭐ שבוע טוב!"
            body = "השבת יצאה. שיהיה לך שבוע נפלא ומבורך!"
        }

        ensureChannel(ctx)
        val tap = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, NotificationScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(ctx)
                .notify(if (type == NotificationScheduler.TYPE_EREV) 2001 else 2002, n)
        } catch (e: SecurityException) {
            // notification permission was revoked after scheduling
        }

        // schedule next week's alarm
        NotificationScheduler.rescheduleAll(ctx)
    }

    companion object {
        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NotificationScheduler.CHANNEL_ID,
                    "שבת שלום",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "התראות שבת" }
                ctx.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            NotificationScheduler.rescheduleAll(ctx)
        }
    }
}
