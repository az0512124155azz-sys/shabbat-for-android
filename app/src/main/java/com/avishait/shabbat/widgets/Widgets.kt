package com.avishait.shabbat.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.widget.RemoteViews
import com.avishait.shabbat.MainActivity
import com.avishait.shabbat.R
import com.avishait.shabbat.ShabbatCore
import java.util.Locale

fun getHebrewContext(ctx: Context): Context {
    val hebrewLocale = Locale("he", "IL")
    val config = Configuration(ctx.resources.configuration)
    config.setLocale(hebrewLocale)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
        ctx.createConfigurationContext(config)
    } else {
        ctx.resources.configuration.setLocale(hebrewLocale)
        ctx
    }
}

object WidgetUpdater {
    private val ALL = listOf(
        ShabbatTimesWidget::class.java,
        NetzWidget::class.java,
        TzeitWidget::class.java,
        SunTimesWidget::class.java,
        ParashaWidget::class.java,
        TefillinWidget::class.java
    )

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (cls in ALL) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            if (ids.isEmpty()) continue
            val i = Intent(ctx, cls)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            ctx.sendBroadcast(i)
        }
    }
}

abstract class BaseShabbatWidget : AppWidgetProvider() {

    abstract fun views(ctx: Context): RemoteViews

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val rv = views(ctx)
        rv.setOnClickPendingIntent(
            R.id.widgetRoot,
            PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        for (id in ids) mgr.updateAppWidget(id, rv)
    }
}

/** Shabbat entry and exit times */
class ShabbatTimesWidget : BaseShabbatWidget() {
    override fun views(ctx: Context): RemoteViews {
        val hctx = getHebrewContext(ctx)
        val city = ShabbatCore.cityOrDefault(ctx)
        val t = ShabbatCore.nextShabbat(city)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_two)
        rv.setTextViewText(R.id.wTitle, hctx.getString(R.string.widget_label_shabbat) + " · " + city.name)
        rv.setTextViewText(R.id.wLbl1, hctx.getString(R.string.widget_label_candle))
        rv.setTextViewText(R.id.wVal1, ShabbatCore.fmt(t.candle, city.tz))
        rv.setTextViewText(R.id.wLbl2, hctx.getString(R.string.widget_label_havdalah))
        rv.setTextViewText(R.id.wVal2, ShabbatCore.fmt(t.havdalah, city.tz))
        return rv
    }
}

/** Sunrise time */
class NetzWidget : BaseShabbatWidget() {
    override fun views(ctx: Context): RemoteViews {
        val hctx = getHebrewContext(ctx)
        val city = ShabbatCore.cityOrDefault(ctx)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_one)
        rv.setTextViewText(R.id.wTitle, hctx.getString(R.string.widget_label_sunrise))
        rv.setTextViewText(R.id.wVal, ShabbatCore.fmt(ShabbatCore.sunrise(city, ShabbatCore.todayNoon()), city.tz))
        rv.setTextColor(R.id.wVal, 0xFFF5A623.toInt())
        rv.setTextViewText(R.id.wSub, city.name)
        return rv
    }
}

/** Nightfall time */
class TzeitWidget : BaseShabbatWidget() {
    override fun views(ctx: Context): RemoteViews {
        val hctx = getHebrewContext(ctx)
        val city = ShabbatCore.cityOrDefault(ctx)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_one)
        rv.setTextViewText(R.id.wTitle, hctx.getString(R.string.widget_label_nightfall))
        rv.setTextViewText(R.id.wVal, ShabbatCore.fmt(ShabbatCore.tzeit(city, ShabbatCore.todayNoon()), city.tz))
        rv.setTextColor(R.id.wVal, 0xFFC4B5FD.toInt())
        rv.setTextViewText(R.id.wSub, city.name)
        return rv
    }
}

/** Sunrise and nightfall times */
class SunTimesWidget : BaseShabbatWidget() {
    override fun views(ctx: Context): RemoteViews {
        val hctx = getHebrewContext(ctx)
        val city = ShabbatCore.cityOrDefault(ctx)
        val today = ShabbatCore.todayNoon()
        val rv = RemoteViews(ctx.packageName, R.layout.widget_two)
        rv.setTextViewText(R.id.wTitle, hctx.getString(R.string.widget_label_sun_times) + " · " + city.name)
        rv.setTextViewText(R.id.wLbl1, hctx.getString(R.string.widget_label_sunrise))
        rv.setTextViewText(R.id.wVal1, ShabbatCore.fmt(ShabbatCore.sunrise(city, today), city.tz))
        rv.setTextViewText(R.id.wLbl2, hctx.getString(R.string.widget_label_nightfall))
        rv.setTextViewText(R.id.wVal2, ShabbatCore.fmt(ShabbatCore.tzeit(city, today), city.tz))
        return rv
    }
}

/** Weekly Torah portion */
class ParashaWidget : BaseShabbatWidget() {
    override fun views(ctx: Context): RemoteViews {
        val hctx = getHebrewContext(ctx)
        val city = ShabbatCore.cityOrDefault(ctx)
        val t = ShabbatCore.nextShabbat(city)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_parasha)
        rv.setTextViewText(R.id.wTitle, hctx.getString(R.string.widget_title_parasha))
        val p = ShabbatCore.parasha(t.saturday)
        val label = hctx.getString(R.string.widget_label_parasha)
        rv.setTextViewText(R.id.wParasha, if (p.isEmpty()) "—" else "$label $p")
        return rv
    }
}

/** הנחת תפילין */
class TefillinWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val rv = build(ctx)
        for (id in ids) mgr.updateAppWidget(id, rv)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            ShabbatCore.toggleTefillinToday(ctx)
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TefillinWidget::class.java))
            val rv = build(ctx)
            for (id in ids) mgr.updateAppWidget(id, rv)
        }
        super.onReceive(ctx, intent)
    }

    companion object {
        const val ACTION_TOGGLE = "com.avishait.shabbat.TOGGLE_TEF"

        fun build(ctx: Context): RemoteViews {
            val hctx = getHebrewContext(ctx)
            val on = ShabbatCore.isTefillinToday(ctx)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_tefillin)
            rv.setTextViewText(R.id.wTitle, hctx.getString(R.string.widget_title_tefillin))
            val btnText = if (on) hctx.getString(R.string.tefillin_on) else hctx.getString(R.string.tefillin_off)
            rv.setTextViewText(R.id.wTefBtn, btnText)
            rv.setInt(
                R.id.wTefBtn, "setBackgroundResource",
                if (on) R.drawable.widget_btn_on else R.drawable.widget_btn_off
            )
            val i = Intent(ctx, TefillinWidget::class.java).setAction(ACTION_TOGGLE)
            rv.setOnClickPendingIntent(
                R.id.wTefBtn,
                PendingIntent.getBroadcast(
                    ctx, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            return rv
        }
    }
}
