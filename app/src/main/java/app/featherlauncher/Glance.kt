package app.featherlauncher

import android.app.AlarmManager
import android.content.Context
import java.util.Calendar
import java.util.Date

/**
 * At a glance: what the clock shows under the time. The date; a quieter line with the second
 * calendar's date, today's holiday, the parsha, the Omer and Daf Yomi; then the weather and up
 * to two things coming up, the most pressing first. Each part can be turned
 * off in Calendar and times, and each only shows when it matters. The Jewish parts are there
 * with the Hebrew calendar.
 */
class Glance(private val context: Context) {
    /** What tapping something does. */
    enum class Tap { ZMANIM, EVENT, ALARMS, CALLS, MESSAGES }

    /** One thing coming up; lower [rank] is more pressing. */
    class Item(val icon: Int, val text: String, val tap: Tap, val rank: Int, val event: ClockInfo.Event? = null)

    class Lines(val date: String, val detail: String?, val weather: Weather.Now?, val coming: List<Item>, val place: ClockInfo.Place?)

    private val info = ClockInfo(context)

    /** Everything for [now]. [weatherLoaded] runs when the weather arrives, to show it. */
    fun lines(now: Long, weatherLoaded: () -> Unit): Lines {
        val prefs = Prefs(context)
        val place = info.place()
        val jewish = prefs.secondCalendar == SecondCalendar.HEBREW
        val israel = place?.inIsrael == true
        val zone = place?.zone ?: java.util.TimeZone.getDefault()
        val today = JewishCalendar.day(now, zone)
        val sunset = place?.let { Zmanim.sun(now, it, Zmanim.SUNRISE, false) }
        // The Jewish day turns over at shkiah.
        val jewishDay = if (sunset != null && now >= sunset) today + 1 else today

        val date = android.text.format.DateFormat.format("EEEE, MMMM d", now).toString()
        val weather = if (prefs.glanceWeather && place != null) Weather.at(place, weatherLoaded) else null

        val week = mutableListOf<String>()
        prefs.secondCalendar.format(now, sunset)?.let { week += it }
        if (jewish && prefs.glanceHolidays) week += JewishCalendar.holidays(jewishDay, israel)
        if (jewish) {
            if (prefs.glanceParsha) JewishCalendar.parshaOfWeek(jewishDay, israel)?.let { week += "Parshas $it" }
            if (prefs.glanceOmer) JewishCalendar.omer(jewishDay)?.let { week += JewishCalendar.omerText(it) }
            if (prefs.glanceDaf) JewishCalendar.dafYomi(today)?.let { week += "Daf Yomi $it" }
        }

        val coming = ArrayList<Item>()
        val time = android.text.format.DateFormat.getTimeFormat(context)
        val placeTime = android.text.format.DateFormat.getTimeFormat(context).apply { timeZone = zone }

        if (prefs.clockEvent) info.nextEvent(now)?.let { e ->
            val minutes = (e.begin - now) / 60_000
            val text = if (minutes < 60) "${e.title} in ${minutes.coerceAtLeast(1)} min" else "${e.title} ${time.format(Date(e.begin))}"
            coming += Item(R.drawable.ic_calendar, text, Tap.EVENT, if (minutes < 60) 0 else 6, e)
        }

        // Candle lighting on Friday and Erev Yom Tov, until it's time.
        if (jewish && prefs.glanceCandles && place != null) {
            val weekday = JewishCalendar.weekday(today)
            val erevYomTov = JewishCalendar.isYomTov(today + 1, israel) && !JewishCalendar.isYomTov(today, israel)
            val lighting = Zmanim.sun(now, place, Zmanim.SUNRISE, false)?.minus(prefs.candleMinutes * 60_000L)
            if (lighting != null && now < lighting && weekday != Calendar.SATURDAY && (weekday == Calendar.FRIDAY || erevYomTov)) {
                coming += Item(R.drawable.ic_sunset, "Candle lighting ${placeTime.format(Date(lighting))}", Tap.ZMANIM,
                    if (lighting - now < 4 * HOUR) 1 else 5)
            }
        }

        // In the last hour before shkiah (the end of Mincha), it comes before messages and the like.
        val shkiahSoon = prefs.clockZman && sunset != null && now < sunset && sunset - now <= HOUR
        if (shkiahSoon) coming += Item(R.drawable.ic_sunset, "Shkiah ${placeTime.format(Date(sunset!!))}", Tap.ZMANIM, 2)

        if (prefs.glanceMissed) {
            val calls = NotificationBadges.missedCalls
            val texts = NotificationBadges.messages
            val parts = listOfNotNull(
                calls.takeIf { it > 0 }?.let { if (it == 1) "1 missed call" else "$it missed calls" },
                texts.takeIf { it > 0 }?.let { if (it == 1) "1 message" else "$it messages" },
            )
            if (parts.isNotEmpty()) coming += Item(if (calls > 0) R.drawable.ic_phone_call else R.drawable.ic_message,
                parts.joinToString(" · "), if (calls > 0) Tap.CALLS else Tap.MESSAGES, 3)
        }

        // Tonight's alarm, once it's evening.
        if (prefs.glanceAlarm) {
            val alarm = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
            val evening = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY) >= 18
            if (alarm != null && evening && alarm - now < 16 * HOUR) {
                coming += Item(R.drawable.ic_time, "Alarm ${time.format(Date(alarm))}", Tap.ALARMS, 4)
            }
        }

        if (jewish && prefs.glanceUpcoming) upcoming(jewishDay, israel)?.let { coming += Item(R.drawable.ic_calendar, it, Tap.ZMANIM, 7) }

        if (prefs.clockZman && place != null) info.nextZman(now, place)?.takeIf { !(shkiahSoon && it.name == "Shkiah") }?.let { z ->
            coming += Item(R.drawable.ic_time, "${z.name} ${placeTime.format(Date(z.time))}", Tap.ZMANIM, 8)
        }

        return Lines(date, week.joinToString(" · ").ifEmpty { null }, weather, coming.sortedBy { it.rank }.take(2), place)
    }

    /** The next holiday in the coming week that isn't today's: "Tomorrow: Purim", "Pesach in 3 days". */
    private fun upcoming(today: Long, israel: Boolean): String? {
        val current = JewishCalendar.holidays(today, israel).map(::family).toSet()
        for (ahead in 1..7) {
            val name = JewishCalendar.holidays(today + ahead, israel).map(::family)
                .firstOrNull { it !in current && notable(it, ahead) } ?: continue
            return if (ahead == 1) "Tomorrow: $name" else "$name in $ahead days"
        }
        return null
    }

    /** The holiday a day belongs to: "Chanukah · day 3" is Chanukah, Chol Hamoed is part of its Yom Tov. */
    private fun family(name: String) = when {
        name.startsWith("Chanukah") -> "Chanukah"
        name == "Chol Hamoed Pesach" || name == "Shvi'i shel Pesach" || name == "Acharon shel Pesach" -> "Pesach"
        name == "Chol Hamoed Sukkos" || name == "Hoshana Rabbah" -> "Sukkos"
        else -> name
    }

    /** Worth mentioning ahead: not Erev days, and Rosh Chodesh only the day before. */
    private fun notable(name: String, ahead: Int) = !name.startsWith("Erev ") && (!name.startsWith("Rosh Chodesh") || ahead == 1)

    private companion object {
        const val HOUR = 60 * 60_000L
    }
}
