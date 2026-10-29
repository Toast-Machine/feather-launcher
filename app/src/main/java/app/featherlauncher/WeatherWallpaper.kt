package app.featherlauncher

import android.app.WallpaperManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * The Weather wallpaper: [WeatherScene] for the weather and time of day where the phone is,
 * set as an ordinary wallpaper on the home and lock screens (so it works on every phone,
 * even ones that can't show live wallpapers). Feather looks again whenever the screen turns
 * on, the home screen shows or the weather comes in, and sets a new picture only when the
 * scene has changed: a few times a day.
 */
object WeatherWallpaper {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    /** The scene being drawn right now, so two looks at once don't both set it. */
    @Volatile private var drawing: String? = null

    /** Turns the Weather wallpaper on and sets it now. */
    fun start(context: Context, done: (Boolean) -> Unit) {
        val prefs = Prefs(context)
        prefs.weatherWallpaper = true
        prefs.weatherWallpaperScene = null
        Shuffle.stop(context)
        // A wallpaper of its own replaces the light and dark pair.
        DarkMode.clearPair(context)
        refresh(context, done)
    }

    /** Another wallpaper was chosen. */
    fun stop(context: Context) {
        Prefs(context).weatherWallpaper = false
    }

    /** Sets the picture for the sky as it is now, if it's on and the scene changed. */
    fun refresh(context: Context, done: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.weatherWallpaper) return
        val place = ClockInfo(app).place()
        val sky = place?.let { Weather.at(it) { refresh(app) } }?.sky ?: Sky.CLEAR
        val time = timeOfDay(place)
        // The drawing's version goes in too, so a new look replaces the old one straight away.
        val scene = "3/$sky/$time"
        if (scene == prefs.weatherWallpaperScene || scene == drawing) return done?.invoke(true) ?: Unit
        drawing = scene
        io.execute {
            val manager = WallpaperManager.getInstance(app)
            val metrics = app.resources.displayMetrics
            val ok = runCatching {
                val picture = WeatherScene.draw(metrics.widthPixels, metrics.heightPixels, sky, time)
                manager.setBitmap(picture, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                picture.recycle()
            }.isSuccess
            main.post {
                drawing = null
                // Turned off while it was being drawn: the new wallpaper stays, unmarked.
                if (ok && prefs.weatherWallpaper) prefs.weatherWallpaperScene = scene
                done?.invoke(ok)
            }
        }
    }

    /**
     * Dawn and sunset for a while around sunrise and sunset where the phone is, day and night
     * between. Without a place, by the clock.
     */
    fun timeOfDay(place: ClockInfo.Place?): WeatherScene.Time {
        val now = System.currentTimeMillis()
        val rise = place?.let { Zmanim.sun(now, it, Zmanim.SUNRISE, true) }
        val set = place?.let { Zmanim.sun(now, it, Zmanim.SUNRISE, false) }
        if (rise == null || set == null) return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..6 -> WeatherScene.Time.DAWN
            in 7..17 -> WeatherScene.Time.DAY
            18 -> WeatherScene.Time.SUNSET
            else -> WeatherScene.Time.NIGHT
        }
        return when {
            now in rise - 50 * MINUTE until rise + 40 * MINUTE -> WeatherScene.Time.DAWN
            now in set - 40 * MINUTE until set + 30 * MINUTE -> WeatherScene.Time.SUNSET
            now in rise until set -> WeatherScene.Time.DAY
            else -> WeatherScene.Time.NIGHT
        }
    }

    private const val MINUTE = 60_000L
}
