package app.featherlauncher

import android.icu.util.HebrewCalendar
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** One zman: its name and when it is (epoch millis). */
data class Zman(val name: String, val time: Long)

/**
 * The day's zmanim for a place, worked out from the sun's position (the standard sunrise
 * equation, accurate to about a minute). Shaos zmaniyos follow the Gra (sunrise to sunset)
 * or, for Shema and Tefila, optionally the Magen Avraham (72 minutes before and after).
 */
object Zmanim {
    /** Sun this many degrees below the horizon, counted as a zenith angle. */
    private const val SUNRISE = 90.833
    private const val ALOS = 106.1
    private const val MISHEYAKIR = 101.5
    private const val TZEIS = 98.5

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** Everything for the day containing [day], in order. Friday adds candle lighting, Shabbos its end. */
    fun forDay(day: Long, lat: Double, lon: Double, magenAvraham: Boolean, candleMinutes: Int): List<Zman> {
        val sunrise = sun(day, lat, lon, SUNRISE, true) ?: return emptyList()
        val sunset = sun(day, lat, lon, SUNRISE, false) ?: return emptyList()
        val hour = (sunset - sunrise) / 12
        // Magen Avraham's day runs from 72 minutes before sunrise to 72 after sunset.
        val maStart = sunrise - 72 * MINUTE
        val maHour = (sunset + 72 * MINUTE - maStart) / 12
        val shemaStart = if (magenAvraham) maStart else sunrise
        val shemaHour = if (magenAvraham) maHour else hour
        val weekday = Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.DAY_OF_WEEK)

        val list = ArrayList<Zman>()
        sun(day, lat, lon, ALOS, true)?.let { list += Zman("Alos hashachar", it) }
        sun(day, lat, lon, MISHEYAKIR, true)?.let { list += Zman("Misheyakir", it) }
        list += Zman("Netz", sunrise)
        list += Zman("Sof zman Shema", shemaStart + 3 * shemaHour)
        list += Zman("Sof zman Tefila", shemaStart + 4 * shemaHour)
        list += Zman("Chatzos", sunrise + 6 * hour)
        list += Zman("Mincha gedola", sunrise + 6 * hour + hour / 2)
        list += Zman("Mincha ketana", sunrise + 9 * hour + hour / 2)
        list += Zman("Plag hamincha", sunrise + 10 * hour + 3 * hour / 4)
        if (weekday == Calendar.FRIDAY) list += Zman("Candle lighting", sunset - candleMinutes * MINUTE)
        list += Zman("Shkiah", sunset)
        sun(day, lat, lon, TZEIS, false)?.let { list += Zman(if (weekday == Calendar.SATURDAY) "Shabbos ends" else "Tzeis", it) }
        return list.sortedBy { it.time }
    }

    /** The next zman after [now], looking into tomorrow once today's are over. */
    fun next(now: Long, lat: Double, lon: Double, magenAvraham: Boolean, candleMinutes: Int): Zman? =
        (forDay(now, lat, lon, magenAvraham, candleMinutes) + forDay(now + 24 * HOUR, lat, lon, magenAvraham, candleMinutes))
            // Misheyakir and Mincha gedola are left out of "next" to keep the clock calm.
            .filter { it.time > now && it.name != "Misheyakir" && it.name != "Mincha gedola" }
            .minByOrNull { it.time }

    /**
     * When the sun reaches [zenith] on the local day containing [day], rising or setting;
     * null when it doesn't (far north in summer).
     */
    fun sun(day: Long, lat: Double, lon: Double, zenith: Double, rising: Boolean): Long? {
        val local = Calendar.getInstance().apply { timeInMillis = day }
        val n = local.get(Calendar.DAY_OF_YEAR)
        val lngHour = lon / 15
        val t = n + ((if (rising) 6 else 18) - lngHour) / 24
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sinD(m) + 0.020 * sinD(2 * m) + 282.634, 360.0)
        var ra = norm(Math.toDegrees(atan(0.91764 * tanD(l))), 360.0)
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15
        val sinDec = 0.39782 * sinD(l)
        val cosDec = cos(asin(sinDec))
        val cosH = (cosD(zenith) - sinDec * sinD(lat)) / (cosDec * cosD(lat))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (rising) 360 - Math.toDegrees(acos(cosH)) else Math.toDegrees(acos(cosH))) / 15
        val localMean = h + ra - 0.06571 * t - 6.622
        val ut = norm(localMean - lngHour, 24.0)

        // UT hours on this date, then moved by a day if that lands on a different local date.
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
        var time = utc + (ut * HOUR).toLong()
        val result = Calendar.getInstance().apply { timeInMillis = time }
        val diff = result.get(Calendar.DAY_OF_YEAR) - n
        if (diff == 1 || diff < -300) time -= 24 * HOUR
        if (diff == -1 || diff > 300) time += 24 * HOUR
        return time
    }

    /**
     * "25 Tishrei", the Hebrew date for [now]. After [sunset] the Jewish day has already
     * turned over, so it's tomorrow's date.
     */
    fun hebrewDate(now: Long, sunset: Long?): String {
        val cal = HebrewCalendar().apply {
            timeInMillis = now
            if (sunset != null && now >= sunset) add(Calendar.DAY_OF_MONTH, 1)
        }
        val leap = cal.getActualMaximum(Calendar.DAY_OF_YEAR) > 380
        val month = when (cal.get(Calendar.MONTH)) {
            HebrewCalendar.TISHRI -> "Tishrei"
            HebrewCalendar.HESHVAN -> "Cheshvan"
            HebrewCalendar.KISLEV -> "Kislev"
            HebrewCalendar.TEVET -> "Teves"
            HebrewCalendar.SHEVAT -> "Shevat"
            HebrewCalendar.ADAR_1 -> "Adar I"
            HebrewCalendar.ADAR -> if (leap) "Adar II" else "Adar"
            HebrewCalendar.NISAN -> "Nissan"
            HebrewCalendar.IYAR -> "Iyar"
            HebrewCalendar.SIVAN -> "Sivan"
            HebrewCalendar.TAMUZ -> "Tammuz"
            HebrewCalendar.AV -> "Av"
            else -> "Elul"
        }
        return "${cal.get(Calendar.DAY_OF_MONTH)} $month"
    }

    private fun norm(v: Double, range: Double) = ((v % range) + range) % range
    private fun sinD(d: Double) = sin(Math.toRadians(d))
    private fun cosD(d: Double) = cos(Math.toRadians(d))
    private fun tanD(d: Double) = tan(Math.toRadians(d))
}
