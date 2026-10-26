package app.featherlauncher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.CalendarContract

/** What the clock shows under the time: the dates, the next zman and the next event. */
class ClockInfo(private val context: Context) {
    class Place(val name: String, val lat: Double, val lon: Double)
    class Event(val id: Long, val title: String, val begin: Long)

    private val prefs = Prefs(context)

    /** The chosen city, else where the phone is (or last was); null if neither is known. */
    fun place(): Place? {
        prefs.zmanCity?.let { name -> CITIES.firstOrNull { it.name == name }?.let { return it } }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lm = context.getSystemService(LocationManager::class.java)
            val fix = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            if (fix != null) prefs.lastPlace = fix.latitude to fix.longitude
        }
        return prefs.lastPlace?.let { (lat, lon) -> Place("Current location", lat, lon) }
    }

    fun zmanim(day: Long, place: Place) = Zmanim.forDay(day, place.lat, place.lon, prefs.zmanMagenAvraham, prefs.candleMinutes)

    fun nextZman(now: Long, place: Place) = Zmanim.next(now, place.lat, place.lon, prefs.zmanMagenAvraham, prefs.candleMinutes)

    /** "Mon, October 26 · 25 Tishrei". */
    fun dateLine(now: Long, place: Place?): String {
        val date = android.text.format.DateFormat.format("EEE, MMMM d", now).toString()
        if (!prefs.clockHebrewDate) return date
        val sunset = place?.let { Zmanim.sun(now, it.lat, it.lon, 90.833, false) }
        return "$date · ${Zmanim.hebrewDate(now, sunset)}"
    }

    /** The next timed event in the coming day, if calendar access was given. */
    fun nextEvent(now: Long): Event? {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + DAY)
        }.build()
        return runCatching {
            context.contentResolver.query(uri,
                arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
                "${CalendarContract.Instances.ALL_DAY} = 0 AND ${CalendarContract.Instances.BEGIN} >= ? AND ${CalendarContract.Instances.VISIBLE} = 1",
                arrayOf(now.toString()), "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c -> if (c.moveToFirst()) Event(c.getLong(0), c.getString(1).orEmpty().ifEmpty { "Event" }, c.getLong(2)) else null }
        }.getOrNull()
    }

    companion object {
        private const val DAY = 24 * 60 * 60_000L

        /** Places to pick for zmanim when the phone's location isn't wanted. */
        val CITIES = listOf(
            Place("New York", 40.7128, -74.0060),
            Place("Brooklyn", 40.6782, -73.9442),
            Place("Monsey", 41.1112, -74.0685),
            Place("Lakewood", 40.0821, -74.2097),
            Place("Five Towns", 40.6290, -73.7271),
            Place("Teaneck", 40.8932, -74.0116),
            Place("Baltimore", 39.2904, -76.6122),
            Place("Miami", 25.7617, -80.1918),
            Place("Chicago", 41.8781, -87.6298),
            Place("Los Angeles", 34.0522, -118.2437),
            Place("Toronto", 43.6532, -79.3832),
            Place("London", 51.5074, -0.1278),
            Place("Jerusalem", 31.7683, 35.2137),
            Place("Bnei Brak", 32.0807, 34.8338),
        )
    }
}
