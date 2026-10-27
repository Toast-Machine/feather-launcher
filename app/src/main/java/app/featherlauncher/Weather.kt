package app.featherlauncher

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The weather where the clock is, from Open-Meteo (free, no account), kept for half an hour.
 * In Fahrenheit where that's what people use, Celsius everywhere else.
 */
object Weather {
    class Now(val temperature: Int, val high: Int, val low: Int, val description: String)

    private const val FRESH = 30 * 60_000L
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var cached: Triple<String, Long, Now>? = null
    @Volatile private var loading = false

    val fahrenheit get() = Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")

    /**
     * The weather at [place], or null until it's first loaded. When it's missing or more than
     * half an hour old, it's loaded in the background and [loaded] runs on the main thread.
     */
    fun at(place: ClockInfo.Place, loaded: () -> Unit): Now? {
        val key = "%.2f,%.2f,%b".format(Locale.US, place.lat, place.lon, fahrenheit)
        val known = cached?.takeIf { it.first == key }
        if ((known == null || System.currentTimeMillis() - known.second > FRESH) && !loading) {
            loading = true
            Thread {
                val now = runCatching { fetch(place) }.getOrNull()
                loading = false
                if (now != null) {
                    cached = Triple(key, System.currentTimeMillis(), now)
                    main.post(loaded)
                }
            }.start()
        }
        return known?.third
    }

    private fun fetch(place: ClockInfo.Place): Now {
        val unit = if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto$unit"
        val text = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 15_000
            inputStream.bufferedReader().use { it.readText() }
        }
        val o = JSONObject(text)
        val current = o.getJSONObject("current")
        val daily = o.getJSONObject("daily")
        return Now(
            Math.round(current.getDouble("temperature_2m")).toInt(),
            Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
            Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
            describe(current.getInt("weather_code")),
        )
    }

    /** The WMO weather codes Open-Meteo uses, in a word or two. */
    private fun describe(code: Int) = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorms"
        else -> ""
    }
}
