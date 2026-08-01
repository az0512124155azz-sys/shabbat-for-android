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

data class City(val name: String, val nameEn: String, val lat: Double, val lon: Double, val tz: String) {
    fun localizedName(ctx: Context): String {
        val lang = ctx.resources.configuration.locales[0].language
        return when (lang) {
            "he", "iw" -> name
            "fr" -> {
                val resId = ctx.resources.getIdentifier("city_${nameEn.lowercase().replace(" ", "_").replace("'", "")}", "string", ctx.packageName)
                if (resId != 0) ctx.getString(resId) else nameEn
            }
            else -> if (nameEn.isNotEmpty()) nameEn else name
        }
    }
}

object ShabbatCore {

    private const val PREFS = "shabbat_prefs"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveCity(ctx: Context, json: String) {
        prefs(ctx).edit().putString("city", json).apply()
    }

    fun loadCity(ctx: Context): City? {
        val s = prefs(ctx).getString("city", null) ?: return null
        return try {
            val o = JSONObject(s)
            City(
                o.optString("n", "ירושלים"),
                o.optString("e", "Jerusalem"),
                o.getDouble("la"),
                o.getDouble("lo"),
                o.optString("tz", "Asia/Jerusalem")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun cityOrDefault(ctx: Context): City =
        loadCity(ctx) ?: City("ירושלים", "Jerusalem", 31.7683, 35.2137, "Asia/Jerusalem")

    fun todayKey(): String {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
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

    fun notifEnabled(ctx: Context) = prefs(ctx).getBoolean("notif", false)
    fun setNotifEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("notif", v).apply()
    }

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

    fun fmt(ctx: Context, d: Date?, tz: String): String {
        if (d == null) return "--:--"
        val lang = ctx.resources.configuration.locales[0].language
        val isHeb = lang == "he" || lang == "iw"
        val pattern = if (isHeb) "HH:mm" else "h:mm a"
        val locale = if (isHeb) Locale.ROOT else Locale.US
        val f = SimpleDateFormat(pattern, locale)
        f.timeZone = TimeZone.getTimeZone(tz)
        return f.format(d)
    }

    private val PARASHOT: List<Pair<Int, String>> = listOf(
        20260103 to "para_vayehi", 20260110 to "para_chemot", 20260117 to "para_vaera", 20260124 to "para_bo", 20260131 to "para_bechalah",
        20260207 to "para_yitro", 20260214 to "para_michpatim", 20260221 to "para_terouma", 20260228 to "para_tetsave",
        20260307 to "para_ki_tissa", 20260314 to "para_vayakhel", 20260321 to "para_vayikra", 20260328 to "para_tsav",
        20260411 to "para_chemini", 20260418 to "para_tazria", 20260425 to "para_ahare_mot",
        20260502 to "para_emor", 20260509 to "para_behar", 20260516 to "para_bamidbar", 20260523 to "para_nasso", 20260530 to "para_behaalotkha",
        20260606 to "para_chelah_lekha", 20260613 to "para_korah", 20260620 to "para_houkat", 20260627 to "para_balak",
        20260704 to "para_pinhas", 20260711 to "para_matot", 20260718 to "para_devarim", 20260725 to "para_vaethanan",
        20260801 to "para_ekev", 20260808 to "para_reeh", 20260815 to "para_choftim", 20260822 to "para_ki_tetse", 20260829 to "para_ki_tavo",
        20260905 to "para_nitsavim", 20260919 to "para_haazinou",
        20261010 to "para_bereshit", 20261017 to "para_noach", 20261024 to "para_lekh_lekha", 20261031 to "para_vayera",
        20261107 to "para_hayei_sarah", 20261114 to "para_toladot", 20261121 to "para_vayetse", 20261128 to "para_vayichlah",
        20261205 to "para_vayechev", 20261212 to "para_miketz", 20261219 to "para_vayigach", 20261226 to "para_vayehi",
        20270102 to "para_chemot", 20270109 to "para_vaera", 20270116 to "para_bo", 20270123 to "para_bechalah", 20270130 to "para_yitro",
        20270206 to "para_michpatim", 20270213 to "para_terouma", 20270220 to "para_tetsave", 20270227 to "para_ki_tissa",
        20270306 to "para_vayakhel", 20270313 to "para_pekoude", 20270320 to "para_vayikra", 20270327 to "para_tsav",
        20270403 to "para_chemini", 20270410 to "para_tazria", 20270417 to "para_metsora",
        20270501 to "para_ahare_mot", 20270508 to "para_kedochim", 20270515 to "para_emor", 20270522 to "para_behar", 20270529 to "para_behoukotai",
        20270605 to "para_bamidbar", 20270612 to "para_nasso", 20270619 to "para_behaalotkha", 20270626 to "para_chelah_lekha",
        20270703 to "para_korah", 20270710 to "para_houkat", 20270717 to "para_balak", 20270724 to "para_pinhas", 20270731 to "para_matot",
        20270807 to "para_massei", 20270814 to "para_devarim", 20270821 to "para_vaethanan", 20270828 to "para_ekev",
        20270904 to "para_reeh", 20270911 to "para_choftim", 20270918 to "para_ki_tetse", 20270925 to "para_ki_tavo",
        20271009 to "para_nitsavim", 20271016 to "para_haazinou", 20271030 to "para_bereshit",
        20271106 to "para_noach", 20271113 to "para_lekh_lekha", 20271120 to "para_vayera", 20271127 to "para_hayei_sarah",
        20271204 to "para_toladot", 20271211 to "para_vayetse", 20271218 to "para_vayichlah", 20271225 to "para_vayechev",
        20280101 to "para_miketz", 20280108 to "para_vayigach", 20280115 to "para_vayehi", 20280122 to "para_chemot", 20280129 to "para_vaera",
        20280205 to "para_bo", 20280212 to "para_bechalah", 20280219 to "para_yitro", 20280226 to "para_michpatim",
        20280304 to "para_terouma", 20280311 to "para_tetsave", 20280318 to "para_ki_tissa", 20280325 to "para_vayakhel",
        20280401 to "para_pekoude", 20280408 to "para_vayikra", 20280429 to "para_tsav",
        20280506 to "para_chemini", 20280513 to "para_tazria", 20280520 to "para_ahare_mot", 20280527 to "para_emor",
        20280603 to "para_behar", 20280610 to "para_bamidbar", 20280617 to "para_nasso", 20280624 to "para_behaalotkha",
        20280701 to "para_chelah_lekha", 20280708 to "para_korah", 20280715 to "para_houkat", 20280722 to "para_balak", 20280729 to "para_pinhas",
        20280805 to "para_matot", 20280812 to "para_devarim", 20280819 to "para_vaethanan", 20280826 to "para_ekev",
        20280902 to "para_reeh", 20280909 to "para_choftim", 20280916 to "para_ki_tetse", 20280930 to "para_ki_tavo",
        20281007 to "para_nitsavim", 20281014 to "para_haazinou", 20281028 to "para_bereshit",
        20281104 to "para_noach", 20281111 to "para_lekh_lekha", 20281118 to "para_vayera", 20281125 to "para_hayei_sarah",
        20281202 to "para_toladot", 20281209 to "para_vayetse", 20281216 to "para_vayichlah", 20281223 to "para_vayechev", 20281230 to "para_miketz"
    )

    fun parasha(ctx: Context, saturday: Calendar): String {
        val key = saturday.get(Calendar.YEAR) * 10000 +
                (saturday.get(Calendar.MONTH) + 1) * 100 +
                saturday.get(Calendar.DAY_OF_MONTH)
        var stringKey = ""
        for ((k, sk) in PARASHOT) {
            if (k <= key) stringKey = sk else break
        }
        if (stringKey.isEmpty()) return ""
        val resId = ctx.resources.getIdentifier(stringKey, "string", ctx.packageName)
        return if (resId != 0) ctx.getString(resId) else ""
    }
}
