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

    /** "25 Tishrei" or "14 Rabiʻ II" for [now]; null for [NONE]. [sunset] is today's, if known. */
    fun format(now: Long, sunset: Long?): String? {
        val calendar = icu ?: return null
        val day = if (fromSunset && sunset != null && now >= sunset) now + DAY else now
        if (this == HEBREW) return Zmanim.hebrewDate(day, null)
        return SimpleDateFormat("d MMMM", ULocale("en@calendar=$calendar")).format(Date(day))
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
