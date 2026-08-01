package com.avishait.shabbat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Date

object NotificationScheduler {

    const val CHANNEL_ID = "shabbat_channel"
    const val TYPE_EREV = "erev"
    const val TYPE_MOTZAEI = "motzaei"

    fun rescheduleAll(ctx: Context) {
        if (!ShabbatCore.notifEnabled(ctx)) {
            cancelAll(ctx)
            return
        }
        val city = ShabbatCore.cityOrDefault(ctx)
        val now = Date()
        scheduleType(ctx, city, now, TYPE_EREV)
        scheduleType(ctx, city, now, TYPE_MOTZAEI)
    }

    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, TYPE_EREV, ""))
        am.cancel(pending(ctx, TYPE_MOTZAEI, ""))
    }

    private fun scheduleType(ctx: Context, city: City, now: Date, type: String) {
        // Look up to a month ahead for the first alarm moment that is still in the future
        for (week in 0..4) {
            val ref = Date(now.time + week * 7L * 86400000L)
            val st = ShabbatCore.nextShabbat(city, ref)
            val target: Date
            val label: String
            if (type == TYPE_EREV) {
                val c = st.candle ?: continue
                target = Date(c.time - 3L * 3600000L)
                label = ShabbatCore.fmt(c, city.tz)
            } else {
                val h = st.havdalah ?: continue
                target = Date(h.time + 10L * 60000L)   // 10 minutes after Shabbat exit
                label = ""
            }
            if (target.time > now.time + 60000L) {
                scheduleAt(ctx, type, target.time, label)
                return
            }
        }
    }

    private fun scheduleAt(ctx: Context, type: String, at: Long, timeLabel: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, type, timeLabel)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60000L, pi)
    }

    private fun pending(ctx: Context, type: String, timeLabel: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction("com.avishait.shabbat.NOTIFY_$type")
            .putExtra("type", type)
            .putExtra("time", timeLabel)
        val req = if (type == TYPE_EREV) 1001 else 1002
        return PendingIntent.getBroadcast(
            ctx, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
