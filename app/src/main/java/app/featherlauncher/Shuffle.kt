package app.featherlauncher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Photo Shuffle: the wallpaper changes by itself through a set of photos, either your own
 * (copied into Feather, so they stay even if the originals go) or one of the collections. It
 * moves on when the screen turns on, every hour, or every day, looked at whenever the screen
 * turns on or the home screen shows.
 */
object Shuffle {
    enum class Every(val label: String, val ms: Long) {
        WAKE("Each time the screen turns on", 0),
        HOUR("Every hour", 60 * 60_000L),
        DAY("Every day", 24 * 60 * 60_000L),
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    private fun folder(context: Context) = File(context.filesDir, "shuffle")

    /** Your own photos in the shuffle, in order. */
    fun photos(context: Context): List<File> = folder(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    /** Copies [uris] in as the shuffle's photos, replacing any before, and starts it. */
    fun startWithPhotos(context: Context, uris: List<Uri>, done: (Boolean) -> Unit) = io.execute {
        val dir = folder(context)
        dir.deleteRecursively()
        dir.mkdirs()
        val (w, h) = screen(context)
        uris.forEachIndexed { i, uri ->
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return@runCatching
                File(dir, "%03d.jpg".format(i)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                bitmap.recycle()
            }
        }
        val ok = photos(context).isNotEmpty()
        main.post {
            if (ok) start(context, null, done) else done(false)
        }
    }

    /** Starts shuffling: [collection] names one of the collections, or null for your own photos. */
    fun start(context: Context, collection: String?, done: (Boolean) -> Unit) {
        val prefs = Prefs(context)
        prefs.shuffleOn = true
        prefs.shuffleCollection = collection
        prefs.shuffleIndex = -1
        prefs.shuffleLast = 0
        // One wallpaper that changes by itself at a time, and none of the light and dark pair.
        WeatherWallpaper.stop(context)
        DarkMode.clearPair(context)
        next(context, force = true, done = done)
    }

    fun stop(context: Context) {
        Prefs(context).shuffleOn = false
    }

    /** Called when the screen turns on ([screenOn]) or the home screen shows: moves on if it's time. */
    fun next(context: Context, screenOn: Boolean = false, force: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.shuffleOn || busy) return
        val every = prefs.shuffleEvery
        val now = System.currentTimeMillis()
        val due = force || if (every == Every.WAKE) screenOn else now - prefs.shuffleLast >= every.ms
        if (!due) return
        val collection = prefs.shuffleCollection
        val ids = collection?.let { name -> WallpaperActivity.collection(name) }
        val count = ids?.size ?: photos(app).size
        if (count == 0) return
        val index = (prefs.shuffleIndex + 1) % count
        busy = true
        io.execute {
            val (w, h) = screen(app)
            val picture = if (ids != null) download("https://picsum.photos/id/${ids[index]}/$w/$h")
            else BitmapFactory.decodeFile(photos(app)[index].path)?.let { crop(it, w, h) }
            val ok = picture != null && runCatching {
                WallpaperManager.getInstance(app).setBitmap(picture, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            }.isSuccess
            main.post {
                busy = false
                // Offline (a collection's photo didn't come): it tries again next time.
                if (ok) {
                    prefs.shuffleIndex = index
                    prefs.shuffleLast = now
                }
                done?.invoke(ok)
            }
        }
    }

    /** [bitmap] scaled to cover [w] by [h], centered. */
    fun crop(bitmap: Bitmap, w: Int, h: Int): Bitmap {
        val scale = maxOf(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val sw = (w / scale).toInt()
        val sh = (h / scale).toInt()
        val left = (bitmap.width - sw) / 2
        val top = (bitmap.height - sh) / 2
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(bitmap, Rect(left, top, left + sw, top + sh), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun screen(context: Context): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun download(url: String): Bitmap? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            inputStream.use(BitmapFactory::decodeStream)
        }
    }.getOrNull()
}
