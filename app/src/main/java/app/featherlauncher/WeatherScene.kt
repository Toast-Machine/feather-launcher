package app.featherlauncher

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Weather wallpaper's picture: the sky as it is where the phone is, drawn rather than
 * photographed. A gradient for the time of day, the sun or the moon and stars, soft clouds
 * as thick as the weather, rain, snow, lightning or fog, and layered hills on the horizon.
 * The same weather and time always draw the same picture.
 */
object WeatherScene {
    enum class Time { DAWN, DAY, SUNSET, NIGHT }

    private class Sky(val top: Int, val mid: Int, val low: Int, val glow: Int, val sunX: Float, val sunY: Float, val sunR: Float, val hill: Int)

    private val skies = mapOf(
        Time.DAWN to Sky(0xFF34407E.toInt(), 0xFF9D86C4.toInt(), 0xFFFFC6A3.toInt(), 0xFFFFD9B0.toInt(), 0.30f, 0.78f, 0.06f, 0xFF4A3F6E.toInt()),
        Time.DAY to Sky(0xFF1F6FE0.toInt(), 0xFF4F9CF5.toInt(), 0xFFBFE0FF.toInt(), 0xFFFFF6D8.toInt(), 0.74f, 0.16f, 0.05f, 0xFF3D6F8F.toInt()),
        Time.SUNSET to Sky(0xFF26265E.toInt(), 0xFFB4507A.toInt(), 0xFFFFA25E.toInt(), 0xFFFFC27A.toInt(), 0.66f, 0.80f, 0.075f, 0xFF3A2240.toInt()),
        Time.NIGHT to Sky(0xFF03081B.toInt(), 0xFF0B1A40.toInt(), 0xFF1D3266.toInt(), 0xFFD9E4FF.toInt(), 0.72f, 0.17f, 0.045f, 0xFF0A1128.toInt()),
    )
    /** What the sky fades toward as clouds cover it. */
    private val grey = mapOf(Time.DAWN to 0xFF8A8AA0.toInt(), Time.DAY to 0xFF9AA6B4.toInt(), Time.SUNSET to 0xFF7A6A7C.toInt(), Time.NIGHT to 0xFF1A1F2C.toInt())
    /** How much of the sky is covered, 0 to 1. */
    private fun cover(sky: app.featherlauncher.Sky) = when (sky) {
        app.featherlauncher.Sky.CLEAR -> 0f
        app.featherlauncher.Sky.PARTLY -> 0.12f
        app.featherlauncher.Sky.CLOUDY -> 0.55f
        app.featherlauncher.Sky.RAIN -> 0.65f
        app.featherlauncher.Sky.STORM -> 0.82f
        app.featherlauncher.Sky.SNOW -> 0.5f
        app.featherlauncher.Sky.FOG -> 0.55f
    }

    /** Draws [weather] at [time] into a new [w] by [h] picture. */
    fun draw(w: Int, h: Int, weather: app.featherlauncher.Sky, time: Time): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val r = Random(1000 + weather.ordinal * 7 + time.ordinal)
        val s = skies.getValue(time)
        val cov = cover(weather)
        val g0 = grey.getValue(time)
        val night = time == Time.NIGHT
        val pale = weather == app.featherlauncher.Sky.SNOW || weather == app.featherlauncher.Sky.FOG
        val lift = if (pale) (if (night) 0.15f else 0.35f) else 0f
        val top = mix(mix(s.top, g0, cov), Color.WHITE, lift * 0.5f)
        val mid = mix(mix(s.mid, g0, cov), Color.WHITE, lift * 0.6f)
        val low = mix(mix(s.low, g0, cov * 0.9f), Color.WHITE, lift * 0.7f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val wf = w.toFloat()
        val hf = h.toFloat()
        // Lengths below are in a 400-wide picture's units, so any screen looks the same.
        val u = wf / 400f

        paint.shader = LinearGradient(0f, 0f, 0f, hf, intArrayOf(top, mid, low), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, wf, hf, paint)
        paint.shader = null

        if (night && cov < 0.3f) repeat((wf * hf / 2600f / u / u).roundToInt()) {
            val x = r.next() * wf
            val y = r.next() * hf * 0.7f
            val radius = (r.next() * 1.1f + 0.3f) * u
            paint.color = alpha(Color.WHITE, (0.25f + r.next() * 0.7f) * (1 - y / (hf * 0.75f)))
            canvas.drawCircle(x, y, radius, paint)
        }

        // The sun, or the moon, with its glow; hidden as clouds thicken.
        val sx = s.sunX * wf
        val sy = s.sunY * hf
        val sr = s.sunR * wf
        val vis = 1 - cov * 1.05f
        if (vis > 0) {
            paint.shader = RadialGradient(sx, sy, wf * (if (night) 0.35f else 0.75f),
                intArrayOf(alpha(s.glow, (if (night) 0.35f else 0.75f) * vis), alpha(s.glow, (if (night) 0.08f else 0.28f) * vis), alpha(s.glow, 0f)),
                floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, wf, hf, paint)
            paint.shader = null
            if (night) moon(canvas, sx, sy, sr, vis)
            else {
                paint.shader = RadialGradient(sx, sy, sr * 1.15f,
                    intArrayOf(alpha(0xFFFFFFFA.toInt(), vis), alpha(mix(Color.WHITE, s.glow, 0.5f), vis), alpha(s.glow, 0f)),
                    floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(sx, sy, sr * 1.15f, paint)
                paint.shader = null
            }
        }

        val clouds = when (weather) {
            app.featherlauncher.Sky.CLEAR -> 0
            app.featherlauncher.Sky.PARTLY -> 3
            app.featherlauncher.Sky.CLOUDY -> 7
            app.featherlauncher.Sky.RAIN -> 8
            app.featherlauncher.Sky.STORM -> 9
            app.featherlauncher.Sky.SNOW -> 6
            app.featherlauncher.Sky.FOG -> 3
        }
        val light = mix(mix(s.low, Color.WHITE, if (night) 0.15f else 0.75f), g0, cov * 0.4f)
        val shade = mix(mix(s.mid, g0, 0.55f), Color.BLACK, when (weather) {
            app.featherlauncher.Sky.STORM -> 0.45f
            app.featherlauncher.Sky.RAIN -> 0.25f
            else -> 0.08f
        })
        for (i in 0 until clouds) {
            val depth = i / max(1f, clouds - 1f)
            val cx = (r.next() * 1.3f - 0.15f) * wf
            val cy = (0.06f + depth * 0.42f + r.next() * 0.08f) * hf * (if (cov > 0.5f) 0.9f else 1f)
            val size = wf * (0.38f + r.next() * 0.35f) * (if (cov > 0.5f) 1.25f else 1f)
            cloud(canvas, cx, cy, size, light, shade, if (night) 0.6f else 0.95f, r, size * (0.012f + (1 - depth) * 0.03f))
        }

        // Hills, nearer ones darker, fading into the haze.
        val hillBase = mix(mix(s.hill, g0, cov * 0.6f), Color.WHITE, when (weather) {
            app.featherlauncher.Sky.SNOW -> 0.45f
            app.featherlauncher.Sky.FOG -> 0.35f
            else -> 0f
        })
        val fogBand = mix(low, Color.WHITE, 0.4f)
        for (layer in 0 until 3) {
            paint.color = mix(hillBase, low, 0.6f - layer * 0.25f)
            val y0 = hf * (0.78f + layer * 0.07f)
            val a = r.next() * 6
            val b = r.next() * 6
            val path = Path().apply {
                moveTo(0f, hf)
                var x = 0f
                while (x <= wf + 0.5f) {
                    lineTo(x, y0 - sin(x / wf * 3.1f + a) * hf * 0.035f - sin(x / wf * 7.3f + b) * hf * 0.012f)
                    x += wf / 60
                }
                lineTo(wf, hf)
                close()
            }
            canvas.drawPath(path, paint)
            if (weather == app.featherlauncher.Sky.FOG) {
                paint.shader = LinearGradient(0f, y0 - hf * 0.08f, 0f, y0 + hf * 0.06f,
                    intArrayOf(alpha(fogBand, 0f), alpha(fogBand, 0.6f), alpha(fogBand, 0f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, y0 - hf * 0.08f, wf, y0 + hf * 0.06f, paint)
                paint.shader = null
            }
        }

        if (weather == app.featherlauncher.Sky.FOG) {
            val veil = mix(low, Color.WHITE, if (night) 0.05f else 0.5f)
            paint.shader = LinearGradient(0f, hf * 0.3f, 0f, hf, alpha(veil, 0f), alpha(veil, 0.55f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, wf, hf, paint)
            paint.shader = null
        }

        if (weather == app.featherlauncher.Sky.RAIN || weather == app.featherlauncher.Sky.STORM) {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            repeat((wf * hf / (if (weather == app.featherlauncher.Sky.STORM) 700f else 1000f) / u / u).roundToInt()) {
                val x = r.next() * wf * 1.2f
                val y = r.next() * hf
                val length = (6 + r.next() * 14) * u
                val d = r.next()
                paint.color = alpha(0xFFDCE8FF.toInt(), 0.12f + d * 0.3f)
                paint.strokeWidth = (0.5f + d * 1.1f) * u
                canvas.drawLine(x, y, x - length * 0.25f, y + length, paint)
            }
            paint.style = Paint.Style.FILL
        }

        if (weather == app.featherlauncher.Sky.STORM) {
            val bx = wf * (0.25f + r.next() * 0.5f)
            val by = hf * 0.25f
            paint.shader = RadialGradient(bx, by, wf * 0.6f, alpha(0xFFEBE1FF.toInt(), 0.32f), alpha(0xFFEBE1FF.toInt(), 0f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, wf, hf, paint)
            paint.shader = null
            val bolt = Path().apply {
                var x = bx
                var y = by
                moveTo(x, y)
                repeat(7) {
                    x += (r.next() - 0.5f) * wf * 0.09f
                    y += hf * 0.045f
                    lineTo(x, y)
                }
            }
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = wf / 220
            paint.color = alpha(0xFFDCD2FF.toInt(), 0.9f)
            paint.maskFilter = BlurMaskFilter(wf / 60, BlurMaskFilter.Blur.NORMAL)
            canvas.drawPath(bolt, paint)
            paint.maskFilter = null
            paint.color = alpha(0xFFFAF8FF.toInt(), 0.9f)
            canvas.drawPath(bolt, paint)
            paint.style = Paint.Style.FILL
        }

        if (weather == app.featherlauncher.Sky.SNOW) repeat((wf * hf / 650f / u / u).roundToInt()) {
            val d = r.next()
            val x = r.next() * wf
            val y = r.next() * hf
            val radius = (0.6f + d * 2.2f) * u
            val blur = (1 - d) * 1.2f * u
            paint.maskFilter = if (blur > 0.3f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
            paint.color = alpha(Color.WHITE, 0.45f + d * 0.5f)
            canvas.drawCircle(x, y, radius, paint)
        }
        paint.maskFilter = null
        return bitmap
    }

    /** A crescent moon with a soft glow around it. */
    private fun moon(canvas: Canvas, x: Float, y: Float, radius: Float, vis: Float) {
        val size = ceil(radius * 6).toInt()
        val c = size / 2f
        val shape = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val sc = Canvas(shape)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFF8F6EC.toInt()
        sc.drawCircle(c, c, radius, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        sc.drawCircle(c + radius * 0.42f, c - radius * 0.28f, radius * 0.9f, p)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = alpha(0xFFE1E8FF.toInt(), 0.9f * vis)
            maskFilter = BlurMaskFilter(radius * 0.7f, BlurMaskFilter.Blur.NORMAL)
        }
        val offset = IntArray(2)
        val halo = shape.extractAlpha(glow, offset)
        canvas.drawBitmap(halo, x - c + offset[0], y - c + offset[1], glow)
        canvas.drawBitmap(shape, x - c, y - c, Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = (vis * 255).roundToInt() })
        halo.recycle()
        shape.recycle()
    }

    /** A soft cloud: puffs, highest in the middle, light on top and shaded underneath. */
    private fun cloud(canvas: Canvas, x: Float, y: Float, size: Float, light: Int, shade: Int, opacity: Float, r: Random, blur: Float) {
        val bw = ceil(size * 1.6f).toInt()
        val bh = ceil(size * 0.9f).toInt()
        val ox = size * 0.8f
        val oy = size * 0.55f
        val shape = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(shape)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            if (blur > 0.5f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        repeat(16) {
            val t = r.next()
            val hump = sin(t * PI).toFloat()
            val radius = size * (0.07f + hump * 0.13f + r.next() * 0.05f)
            val px = ox + (t - 0.5f) * size * 0.95f
            val py = oy - hump * size * 0.12f - r.next() * size * 0.06f + radius * 0.35f
            c.drawCircle(px, py, radius, p)
        }
        c.drawOval(ox - size * 0.46f, oy + size * 0.02f - size * 0.08f, ox + size * 0.46f, oy + size * 0.02f + size * 0.08f, p)
        p.maskFilter = null
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        p.shader = LinearGradient(0f, oy - size * 0.35f, 0f, oy + size * 0.12f,
            intArrayOf(light, mix(light, shade, 0.35f), shade), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
        p.shader = RadialGradient(ox - size * 0.15f, oy - size * 0.3f, size * 0.45f, 0x59FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
        canvas.drawBitmap(shape, x - ox, y - oy, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = (opacity * 255).roundToInt() })
        shape.recycle()
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).roundToInt().coerceIn(0, 255)
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    private fun alpha(color: Int, a: Float) = (color and 0xFFFFFF) or ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

    /** The same numbers every time for the same seed, so a scene never changes on its own. */
    private class Random(seed: Int) {
        private var s = seed.toLong() and 0xFFFFFFFFL
        fun next(): Float {
            s = (s * 1664525 + 1013904223) and 0xFFFFFFFFL
            return s / 4294967296f
        }
    }
}
