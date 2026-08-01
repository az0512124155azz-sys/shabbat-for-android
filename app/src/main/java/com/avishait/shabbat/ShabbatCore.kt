package com.avishait.shabbat

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

data class City(val name: String, val lat: Double, val lon: Double, val tz: String)

/**
 * Native port of the time calculations in assets/shabbat.html.
 * The math must stay identical to the JS version so the app and the
 * widgets/notifications always show the same times.
 */
object ShabbatCore {

    private const val PREFS = "shabbat_prefs"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── city ──────────────────────────────────────────────────────────────
    fun saveCity(ctx: Context, json: String) {
        prefs(ctx).edit().putString("city", json).apply()
    }

    fun loadCity(ctx: Context): City? {
        val s = prefs(ctx).getString("city", null) ?: return null
        return try {
            val o = JSONObject(s)
            City(
                o.optString("n", "ירושלים"),
                o.getDouble("la"),
                o.getDouble("lo"),
                o.optString("tz", "Asia/Jerusalem")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun cityOrDefault(ctx: Context): City =
        loadCity(ctx) ?: City("ירושלים", 31.7683, 35.2137, "Asia/Jerusalem")

    // ── tefillin (date keys are UTC, matching tk() in the HTML) ───────────
    fun todayKey(): String {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    fun tefillinMap(ctx: Context): JSONObject = try {
        JSONObject(prefs(ctx).getString("tef", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    fun isTefillinToday(ctx: Context): Boolean = tefillinMap(ctx).optBoolean(todayKey(), false)

    fun setTefillin(ctx: Context, key: String, v: Boolean) {
        val m = tefillinMap(ctx)
        m.put(key, v)
        prefs(ctx).edit().putString("tef", m.toString()).apply()
    }

    fun toggleTefillinToday(ctx: Context) = setTefillin(ctx, todayKey(), !isTefillinToday(ctx))

    // ── notifications flag ────────────────────────────────────────────────
    fun notifEnabled(ctx: Context) = prefs(ctx).getBoolean("notif", false)
    fun setNotifEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("notif", v).apply()
    }

    // ── sun math ──────────────────────────────────────────────────────────
    private fun jd(y0: Int, m0: Int, d: Int): Double {
        var y = y0
        var m = m0
        if (m <= 2) { y--; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    private fun sol(lat: Double, lng: Double, day: Calendar, rising: Boolean, zenith: Double): Date? {
        val y = day.get(Calendar.YEAR)
        val mo = day.get(Calendar.MONTH) + 1
        val d = day.get(Calendar.DAY_OF_MONTH)
        val n = jd(y, mo, d) - 2451545.0 + 0.5
        val bigL = (280.46 + 0.9856474 * n) % 360.0
        val g = ((357.528 + 0.9856003 * n) % 360.0) * PI / 180
        val lam = (bigL + 1.915 * sin(g) + 0.02 * sin(2 * g)) * PI / 180
        val sD = sin(23.439 * PI / 180) * sin(lam)
        val cD = cos(asin(sD))
        val lR = lat * PI / 180
        val cosH = (cos(zenith * PI / 180) - sin(lR) * sD) / (cos(lR) * cD)
        if (cosH < -1 || cosH > 1) return null
        var h = acos(cosH) * 180 / PI
        if (rising) h = -h
        val ra = atan2(cos(23.439 * PI / 180) * sin(lam), cos(lam)) * 180 / PI / 15
        val sv = (((12 - (bigL / 15 - ((ra + 360) % 24.0)) - lng / 15 + h / 15) % 24.0) + 24) % 24.0
        val hh = floor(sv).toInt()
        val mm = floor((sv - hh) * 60).toInt()
        val ss = floor(((sv - hh) * 60 - mm) * 60).toInt()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(y, mo - 1, d, hh, mm, ss)
        return cal.time
    }

    fun sunrise(c: City, day: Calendar): Date? = sol(c.lat, c.lon, day, true, 90.833)
    fun sunset(c: City, day: Calendar): Date? = sol(c.lat, c.lon, day, false, 90.833)
    fun tzeit(c: City, day: Calendar): Date? = sol(c.lat, c.lon, day, false, 96.0)
    private fun candleOffsetMinutes(c: City): Long = when (c.name) {
        "ירושלים" -> 40L
        "חיפה" -> 30L
        else -> 18L
    }
    fun candle(c: City, friday: Calendar): Date? =
        sunset(c, friday)?.let { Date(it.time - candleOffsetMinutes(c) * 60000L) }
    // Havdalah = sun 8.5° below horizon ("3 small stars" – matches Hebcal's default motzaei-Shabbat calculation)
    fun havdalah(c: City, saturday: Calendar): Date? = sol(c.lat, c.lon, saturday, false, 98.5)

    fun todayNoon(now: Date = Date()): Calendar {
        val c = Calendar.getInstance()
        c.time = now
        c.set(Calendar.HOUR_OF_DAY, 12)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c
    }

    private fun nextDow(from: Calendar, dow: Int): Calendar {
        val c = from.clone() as Calendar
        var diff = (dow - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
        if (diff == 0) diff = 7
        c.add(Calendar.DAY_OF_MONTH, diff)
        return c
    }

    data class ShabbatTimes(
        val friday: Calendar,
        val saturday: Calendar,
        val candle: Date?,
        val havdalah: Date?
    )

    /** Mirrors the friday/saturday selection logic in render() of the HTML. */
    fun nextShabbat(city: City, now: Date = Date()): ShabbatTimes {
        val cal = Calendar.getInstance()
        cal.time = now
        val today = todayNoon(now)
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val fri: Calendar
        val sat: Calendar
        when (dow) {
            Calendar.SATURDAY -> {
                val h = havdalah(city, today)
                if (h != null && now.before(h)) {
                    sat = today.clone() as Calendar
                    fri = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
                } else {
                    fri = nextDow(today, Calendar.FRIDAY)
                    sat = (fri.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
                }
            }
            Calendar.FRIDAY -> {
                fri = today.clone() as Calendar
                sat = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
            }
            else -> {
                fri = nextDow(today, Calendar.FRIDAY)
                sat = (fri.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
            }
        }
        return ShabbatTimes(fri, sat, candle(city, fri), havdalah(city, sat))
    }

    fun fmt(d: Date?, tz: String): String {
        if (d == null) return "--:--"
        val f = SimpleDateFormat("HH:mm", Locale.US)
        f.timeZone = TimeZone.getTimeZone(tz)
        return f.format(d)
    }

    // ── parasha (port of the PM table in the HTML) ────────────────────────
    private val PARASHOT: List<Pair<Int, String>> = listOf(
        20260103 to "ויחי", 20260110 to "שמות", 20260117 to "וארא", 20260124 to "בא", 20260131 to "בשלח",
        20260207 to "יתרו", 20260214 to "משפטים", 20260221 to "תרומה", 20260228 to "תצוה",
        20260307 to "כי תשא", 20260314 to "ויקהל-פקודי", 20260321 to "ויקרא", 20260328 to "צו",
        20260411 to "שמיני", 20260418 to "תזריע-מצורע", 20260425 to "אחרי מות-קדושים",
        20260502 to "אמור", 20260509 to "בהר-בחוקותי", 20260516 to "במדבר", 20260523 to "נשא", 20260530 to "בהעלותך",
        20260606 to "שלח", 20260613 to "קרח", 20260620 to "חוקת", 20260627 to "בלק",
        20260704 to "פינחס", 20260711 to "מטות-מסעי", 20260718 to "דברים", 20260725 to "ואתחנן",
        20260801 to "עקב", 20260808 to "ראה", 20260815 to "שופטים", 20260822 to "כי תצא", 20260829 to "כי תבוא",
        20260905 to "נצבים-וילך", 20260919 to "האזינו",
        20261010 to "בראשית", 20261017 to "נח", 20261024 to "לך לך", 20261031 to "וירא",
        20261107 to "חיי שרה", 20261114 to "תולדות", 20261121 to "ויצא", 20261128 to "וישלח",
        20261205 to "וישב", 20261212 to "מקץ", 20261219 to "ויגש", 20261226 to "ויחי",
        20270102 to "שמות", 20270109 to "וארא", 20270116 to "בא", 20270123 to "בשלח", 20270130 to "יתרו",
        20270206 to "משפטים", 20270213 to "תרומה", 20270220 to "תצוה", 20270227 to "כי תשא",
        20270306 to "ויקהל", 20270313 to "פקודי", 20270320 to "ויקרא", 20270327 to "צו",
        20270403 to "שמיני", 20270410 to "תזריע", 20270417 to "מצורע",
        20270501 to "אחרי מות", 20270508 to "קדושים", 20270515 to "אמור", 20270522 to "בהר", 20270529 to "בחוקותי",
        20270605 to "במדבר", 20270612 to "נשא", 20270619 to "בהעלותך", 20270626 to "שלח",
        20270703 to "קרח", 20270710 to "חוקת", 20270717 to "בלק", 20270724 to "פינחס", 20270731 to "מטות",
        20270807 to "מסעי", 20270814 to "דברים", 20270821 to "ואתחנן", 20270828 to "עקב",
        20270904 to "ראה", 20270911 to "שופטים", 20270918 to "כי תצא", 20270925 to "כי תבוא",
        20271009 to "נצבים-וילך", 20271016 to "האזינו", 20271030 to "בראשית",
        20271106 to "נח", 20271113 to "לך לך", 20271120 to "וירא", 20271127 to "חיי שרה",
        20271204 to "תולדות", 20271211 to "ויצא", 20271218 to "וישלח", 20271225 to "וישב",
        20280101 to "מקץ", 20280108 to "ויגש", 20280115 to "ויחי", 20280122 to "שמות", 20280129 to "וארא",
        20280205 to "בא", 20280212 to "בשלח", 20280219 to "יתרו", 20280226 to "משפטים",
        20280304 to "תרומה", 20280311 to "תצוה", 20280318 to "כי תשא", 20280325 to "ויקהל",
        20280401 to "פקודי", 20280408 to "ויקרא", 20280429 to "צו",
        20280506 to "שמיני", 20280513 to "תזריע-מצורע", 20280520 to "אחרי מות-קדושים", 20280527 to "אמור",
        20280603 to "בהר-בחוקותי", 20280610 to "במדבר", 20280617 to "נשא", 20280624 to "בהעלותך",
        20280701 to "שלח", 20280708 to "קרח", 20280715 to "חוקת", 20280722 to "בלק", 20280729 to "פינחס",
        20280805 to "מטות-מסעי", 20280812 to "דברים", 20280819 to "ואתחנן", 20280826 to "עקב",
        20280902 to "ראה", 20280909 to "שופטים", 20280916 to "כי תצא", 20280930 to "כי תבוא",
        20281007 to "נצבים", 20281014 to "האזינו", 20281028 to "בראשית",
        20281104 to "נח", 20281111 to "לך לך", 20281118 to "וירא", 20281125 to "חיי שרה",
        20281202 to "תולדות", 20281209 to "ויצא", 20281216 to "וישלח", 20281223 to "וישב", 20281230 to "מקץ"
    )

    fun parasha(saturday: Calendar): String {
        val key = saturday.get(Calendar.YEAR) * 10000 +
                (saturday.get(Calendar.MONTH) + 1) * 100 +
                saturday.get(Calendar.DAY_OF_MONTH)
        var best = ""
        for ((k, name) in PARASHOT) {
            if (k <= key) best = name else break
        }
        return best
    }
}
