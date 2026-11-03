package app.featherlauncher

import android.icu.text.SimpleDateFormat
import android.icu.util.ULocale
import java.util.Date

/**
 * A calendar whose date shows next to the usual one, from the calendars Android knows. The
 * Hebrew and Islamic days start at sunset, so after it they show tomorrow's date.
 */
enum class SecondCalendar(val label: String, private val icu: String?, private val fromSunset: Boolean = false) {
    NONE("None", null),
    HEBREW("Hebrew", "hebrew", fromSunset = true),
    ISLAMIC("Islamic", "islamic-umalqura", fromSunset = true),
    CHINESE("Chinese", "chinese"),
    PERSIAN("Persian", "persian"),
    INDIAN("Indian national", "indian"),
    ;

    /**
     * "25 Tishrei" or "14 Rabiʻ II" for [now]; null for [NONE]. [sunset] is today's, if known.
     * [inHebrew] writes the Hebrew date in Hebrew: "כ״ה תשרי". [short] shortens the month's name
     * ("25 Tish.", "14 Rab. II"); in Hebrew, the names are short already.
     */
    fun format(now: Long, sunset: Long?, inHebrew: Boolean = false, short: Boolean = false): String? {
        val calendar = icu ?: return null
        val day = if (fromSunset && sunset != null && now >= sunset) now + DAY else now
        if (this == HEBREW && inHebrew) return HebrewText.date(JewishCalendar.day(day, java.util.TimeZone.getDefault()))
        if (this == HEBREW && short) return JewishCalendar.hebrew(JewishCalendar.day(day, java.util.TimeZone.getDefault())).let {
            "${it.day} ${JewishCalendar.shortMonthName(it.month, JewishCalendar.isLeap(it.year))}"
        }
        if (this == HEBREW) return Zmanim.hebrewDate(day, null)
        return SimpleDateFormat(if (short) "d MMM" else "d MMMM", ULocale("en@calendar=$calendar")).format(Date(day))
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
