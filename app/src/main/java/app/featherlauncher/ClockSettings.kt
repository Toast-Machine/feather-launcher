package app.featherlauncher

import android.Manifest
import android.content.pm.PackageManager

/** What the clock shows under the time, and how zmanim are worked out. */
class ClockSettings(private val s: SettingsActivity) {
    private val prefs = s.prefs

    fun build(p: Palette) {
        val content = s.content
        val info = ClockInfo(s)
        val place = info.place()

        content.addView(s.heading(p, "On the clock"))
        content.addView(s.spacer())
        content.addView(Components.group(s, p, listOf(
            Components.toggleRow(s, p, R.drawable.ic_moon, "Hebrew date", prefs.clockHebrewDate) {
                prefs.clockHebrewDate = it
            },
            Components.toggleRow(s, p, R.drawable.ic_sunrise, "Next zman", prefs.clockZman) {
                prefs.clockZman = it
                s.build()
            },
            Components.toggleRow(s, p, R.drawable.ic_calendar, "Next event", prefs.clockEvent) {
                prefs.clockEvent = it
                s.build()
            },
        )))
        content.addView(Components.footnote(s, p, "Tap the zman on the clock for all of today's zmanim, or the event to open it."))

        if (prefs.clockEvent && !granted(Manifest.permission.READ_CALENDAR)) needs(p, Setup.items.first { it.title == "Calendar" })

        content.addView(s.heading(p, "Zmanim"))
        content.addView(s.spacer())
        val cities = ClockInfo.CITIES
        content.addView(Components.group(s, p, listOf(
            Components.row(s, p, R.drawable.ic_location, "Location", prefs.zmanCity ?: "Where you are", chevron = true, inset = Ui.SPACE_L) {
                Components.choiceSheet(s, p, s.overlays, "Location for zmanim",
                    listOf("Where you are" to null) + cities.map { it.name to null },
                    prefs.zmanCity?.let { name -> cities.indexOfFirst { it.name == name } + 1 } ?: 0) { i ->
                    prefs.zmanCity = if (i == 0) null else cities[i - 1].name
                    s.build()
                }
            },
            Components.row(s, p, R.drawable.ic_sunrise, "Sof zman Shema", if (prefs.zmanMagenAvraham) "Magen Avraham" else "Gra",
                chevron = true, inset = Ui.SPACE_L) {
                Components.choiceSheet(s, p, s.overlays, "Sof zman Shema and Tefila", listOf("Gra" to null, "Magen Avraham" to null),
                    if (prefs.zmanMagenAvraham) 1 else 0) {
                    prefs.zmanMagenAvraham = it == 1
                    s.build()
                }
            },
            Components.row(s, p, R.drawable.ic_sunset, "Candle lighting", "${prefs.candleMinutes} min before shkiah",
                chevron = true, inset = Ui.SPACE_L) {
                val minutes = listOf(18, 20, 22, 30, 40)
                Components.choiceSheet(s, p, s.overlays, "Candle lighting", minutes.map { "$it minutes before shkiah" to null },
                    minutes.indexOf(prefs.candleMinutes)) {
                    prefs.candleMinutes = minutes[it]
                    s.build()
                }
            },
        ) + listOfNotNull(place?.let {
            Components.row(s, p, R.drawable.ic_time, "Today's zmanim", chevron = true, inset = Ui.SPACE_L) {
                ZmanimSheet.show(s, p, s.overlays, it)
            }
        })))

        if (place == null) {
            if (prefs.zmanCity == null && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                needs(p, Setup.items.first { it.title == "Location" })
            } else {
                content.addView(Components.footnote(s, p, "The phone hasn't found where it is yet. Choose a city above, or try again later."))
            }
        } else {
            content.addView(Components.footnote(s, p, "Worked out from the sun's position for ${place.name}, to within about a minute."))
        }
    }

    /** A permission the clock needs, with the button to allow it. */
    private fun needs(p: Palette, item: Setup.Item) {
        s.content.addView(s.spacer())
        s.content.addView(Components.group(s, p, listOf(Setup.row(s, p, item))))
    }

    private fun granted(permission: String) = s.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
