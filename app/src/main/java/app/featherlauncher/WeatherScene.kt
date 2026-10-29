package app.featherlauncher

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The Weather wallpaper's picture: just the sky as it is where the phone is, drawn to look
 * real rather than illustrated. A gradient for the time of day, a soft sun with its glare
 * or the moon and stars, clouds grown from smooth noise and lit from the sun's side, and
 * rain, snow, lightning or fog over them. The same weather and time always draw the same
 * picture.
 */
object WeatherScene {
    enum class Time { DAWN, DAY, SUNSET, NIGHT }

    /** A clear sky: its colors top to bottom, where the sun or moon is (fractions of the screen), its glow, and the lit and shaded sides of clouds. */
    private class Sky(val top: Int, val mid: Int, val low: Int, val sunX: Float, val sunY: Float, val glow: Int, val lit: Int, val shade: Int)

    private val skies = mapOf(
        Time.DAWN to Sky(0xFF34416F.toInt(), 0xFF8D8DB8.toInt(), 0xFFF2C3A6.toInt(), 0.55f, 1.0f, 0xFFFFD7B6.toInt(), 0xFFFFE6D6.toInt(), 0xFF76708F.toInt()),
        Time.DAY to Sky(0xFF2A5AA6.toInt(), 0xFF4C82CA.toInt(), 0xFF8AB5E5.toInt(), 0.08f, 0.2f, 0xFFFFF8EA.toInt(), 0xFFFFFFFF.toInt(), 0xFF8A98AE.toInt()),
        Time.SUNSET to Sky(0xFF28285A.toInt(), 0xFF8E4C86.toInt(), 0xFFF39A66.toInt(), 0.45f, 1.0f, 0xFFFFBD84.toInt(), 0xFFFFCFA8.toInt(), 0xFF64496C.toInt()),
        Time.NIGHT to Sky(0xFF070A1F.toInt(), 0xFF171C3A.toInt(), 0xFF2A3453.toInt(), 0.25f, 0.1f, 0xFFCDD6EE.toInt(), 0xFF7A84A2.toInt(), 0xFF171D31.toInt()),
    )
    /** The same times under a covered sky. */
    private val overcast = mapOf(
        Time.DAWN to intArrayOf(0xFF575A72.toInt(), 0xFF86849A.toInt(), 0xFFB2A4A6.toInt()),
        Time.DAY to intArrayOf(0xFF5F697B.toInt(), 0xFF7A8394.toInt(), 0xFF9AA2B0.toInt()),
        Time.SUNSET to intArrayOf(0xFF463F5C.toInt(), 0xFF776274.toInt(), 0xFFA6857A.toInt()),
        Time.NIGHT to intArrayOf(0xFF0E1119.toInt(), 0xFF191D29.toInt(), 0xFF262B38.toInt()),
    )

    /** How much of the sky clouds fill, how grey the sky turns, and how heavy their undersides are. */
    private class Cover(val clouds: Float, val grey: Float, val dark: Float)

    private fun cover(sky: app.featherlauncher.Sky) = when (sky) {
        app.featherlauncher.Sky.CLEAR -> Cover(0f, 0f, 0f)
        app.featherlauncher.Sky.PARTLY -> Cover(0.35f, 0.08f, 0.05f)
        app.featherlauncher.Sky.CLOUDY -> Cover(0.85f, 0.9f, 0.2f)
        app.featherlauncher.Sky.RAIN -> Cover(0.9f, 1f, 0.35f)
        app.featherlauncher.Sky.STORM -> Cover(0.95f, 1f, 0.55f)
        app.featherlauncher.Sky.SNOW -> Cover(0.8f, 0.85f, 0.1f)
        app.featherlauncher.Sky.FOG -> Cover(0f, 0.9f, 0f)
    }

    /** Draws [weather] at [time] into a new [w] by [h] picture. */
    fun draw(w: Int, h: Int, weather: app.featherlauncher.Sky, time: Time): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val seed = 7 + weather.ordinal * 13 + time.ordinal
        val r = Random(seed)
        val s = skies.getValue(time)
        val k = cover(weather)
        val grey = overcast.getValue(time)
        val night = time == Time.NIGHT
        var top = mix(s.top, grey[0], k.grey)
        var mid = mix(s.mid, grey[1], k.grey)
        var low = mix(s.low, grey[2], k.grey)
        if (weather == app.featherlauncher.Sky.STORM) {
            top = mix(top, Color.BLACK, 0.35f); mid = mix(mid, Color.BLACK, 0.3f); low = mix(low, Color.BLACK, 0.25f)
        }
        if (weather == app.featherlauncher.Sky.SNOW || weather == app.featherlauncher.Sky.FOG) {
            val lift = if (night) 0.05f else 0.22f
            top = mix(top, Color.WHITE, lift); mid = mix(mid, Color.WHITE, lift); low = mix(low, Color.WHITE, lift * 1.3f)
        }
        val wf = w.toFloat()
        val hf = h.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, hf, intArrayOf(top, mid, low), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, wf, hf, paint)
        paint.shader = null

        val sx = s.sunX * wf
        val sy = s.sunY * hf
        val vis = max(0f, 1 - k.grey * 1.15f)
        if (night && k.grey < 0.5f) stars(canvas, wf, hf, r, 1 - k.grey * 1.8f)
        if (!night) {
            val a = 0.15f + vis * 0.65f
            paint.shader = RadialGradient(sx, sy, hf * 0.8f, intArrayOf(alpha(s.glow, a), alpha(s.glow, a * 0.45f), alpha(s.glow, 0f)),
                floatArrayOf(0f, 0.15f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, wf, hf, paint)
            paint.shader = null
        }
        if (night && vis > 0.3f) moon(canvas, sx, sy, wf * 0.06f, vis)
        if (k.clouds > 0) clouds(canvas, w, h, seed, k, s, top, mid, night, sx, sy)
        if (time == Time.DAY && vis > 0.3f) sun(canvas, sx, sy, wf, hf, vis)
        if (weather == app.featherlauncher.Sky.FOG) fog(canvas, wf, hf, low, night)
        if (weather == app.featherlauncher.Sky.STORM) {
            paint.shader = RadialGradient(wf * 0.62f, hf * 0.22f, wf * 0.8f, 0x47D7D7FF, 0x00D7D7FF, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, wf, hf, paint)
            paint.shader = null
        }
        if (weather == app.featherlauncher.Sky.RAIN || weather == app.featherlauncher.Sky.STORM) {
            rain(canvas, wf, hf, r, if (weather == app.featherlauncher.Sky.STORM) 1.5f else 1f, night)
        }
        if (weather == app.featherlauncher.Sky.SNOW) snow(canvas, wf, hf, r)
        return bitmap
    }

    /**
     * Clouds, worked out a point at a time at a quarter of the size and scaled up smoothly:
     * they're soft, so nothing is lost. Each point's thickness comes from smooth noise; it's
     * lit by how little cloud lies between it and the light, so puffs are bright on the sun's
     * side and shaded underneath. They thin out toward the bottom, as looking up at the sky.
     */
    private fun clouds(canvas: Canvas, w: Int, h: Int, seed: Int, k: Cover, s: Sky, top: Int, mid: Int, night: Boolean, sx: Float, sy: Float) {
        val cw = (w + 3) / 4
        val ch = (h + 3) / 4
        val body = Noise(seed * 7 + 3)
        val warp = Noise(seed * 11 + 5)
        val lit = mix(s.lit, mid, k.grey * 0.3f)
        val shade = mix(mix(s.shade, mix(mid, top, 0.5f), k.grey * 0.5f), 0xFF10141E.toInt(), k.dark)
        val scale = 1.6f / cw
        val threshold = 0.32f + (1 - k.clouds) * 0.3f
        // Light from the sun's side and from above.
        var lx = sx / w - 0.5f
        var ly = min(sy / h - 0.5f, -0.3f)
        val length = hypot(lx, ly)
        lx /= length
        ly /= length
        val pixels = IntArray(cw * ch)
        for (j in 0 until ch) {
            val fade = ((1f - j.toFloat() / ch) / 0.6f).coerceIn(0f, 1f)
            for (i in 0 until cw) {
                val u = i * scale
                val v = j * scale * 1.8f
                val wx = (warp.fbm(u * 0.6f, v * 0.6f, 3) - 0.5f) * 0.6f
                val wy = (warp.fbm(u * 0.6f + 5.2f, v * 0.6f + 1.3f, 3) - 0.5f) * 0.6f
                val dens = body.fbm(u + wx, v + wy, 6)
                var a = ((dens - threshold) / 0.22f).coerceIn(0f, 1f)
                a = a * a * (3 - 2 * a) * fade
                if (a <= 0.003f) continue
                var occ = 0f
                for (step in 1..4) occ += body.fbm(u + wx + lx * 0.045f * step, v + wy + ly * 0.045f * step, 4)
                occ /= 4
                val l = (0.55f + (dens - occ) * 3.6f + (1 - a) * 0.22f).coerceIn(0f, 1f)
                val color = mix(shade, lit, l * l * (3 - 2 * l))
                pixels[j * cw + i] = alpha(color, a * if (night) 0.9f else 0.97f)
            }
        }
        val small = Bitmap.createBitmap(pixels, cw, ch, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(small, Rect(0, 0, cw, ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        small.recycle()
    }

    /** A bright, soft sun with faint rays, a halo and the glints a lens catches. */
    private fun sun(canvas: Canvas, sx: Float, sy: Float, w: Float, h: Float, vis: Float) {
        val r = w * 0.1f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(sx, sy, r * 3.5f,
            intArrayOf(alpha(Color.WHITE, vis), alpha(0xFFF5FAFF.toInt(), 0.75f * vis), alpha(0xFFE6F0FF.toInt(), 0.25f * vis), 0x00DCEBFF),
            floatArrayOf(0f, 0.2f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(sx, sy, r * 3.5f, paint)
        paint.shader = null
        paint.maskFilter = BlurMaskFilter(w / 60, BlurMaskFilter.Blur.NORMAL)
        for (i in 0 until 6) {
            val angle = i * (Math.PI / 3 + 0.2)
            val c = Math.cos(angle).toFloat()
            val sn = Math.sin(angle).toFloat()
            paint.shader = LinearGradient(sx, sy, sx + c * r * 2.2f, sy + sn * r * 2.2f, alpha(Color.WHITE, 0.25f * vis), 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawPath(Path().apply {
                moveTo(sx - sn * r * 0.35f, sy + c * r * 0.35f)
                lineTo(sx + c * r * 2.2f, sy + sn * r * 2.2f)
                lineTo(sx + sn * r * 0.35f, sy - c * r * 0.35f)
                close()
            }, paint)
        }
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w / 30
        paint.color = alpha(0xFFE6EEFF.toInt(), 0.1f * vis)
        paint.maskFilter = BlurMaskFilter(w / 70, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(sx, sy, w * 0.6f, paint)
        paint.style = Paint.Style.FILL
        paint.maskFilter = null
        val cx = w / 2
        val cy = h / 2
        listOf(floatArrayOf(0.7f, 0.07f, 0.07f), floatArrayOf(0.95f, 0.045f, 0.08f), floatArrayOf(1.2f, 0.03f, 0.1f), floatArrayOf(1.55f, 0.08f, 0.05f)).forEach { (t, rr, a) ->
            val px = sx + (cx - sx) * t * 1.3f
            val py = sy + (cy - sy) * t * 1.3f
            paint.shader = RadialGradient(px, py, w * rr, intArrayOf(alpha(0xFFCDDAFF.toInt(), a * vis), alpha(0xFFCDDAFF.toInt(), a * 0.8f * vis), 0x00CDDAFF),
                floatArrayOf(0f, 0.85f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(px, py, w * rr, paint)
        }
    }

    private fun stars(canvas: Canvas, w: Float, h: Float, r: Random, strength: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val u = w / 900
        repeat((900 * w * h / (360f * 780f)).roundToInt().coerceAtMost(2600)) {
            val x = r.next() * w
            val y = r.next() * h * 0.97f
            val b = r.next().pow(4)
            val a = (0.2f + b * 0.8f) * (1 - y / h * 0.4f) * strength
            paint.color = alpha(if (r.next() < 0.12f) 0xFFFFE4CD.toInt() else 0xFFECF1FF.toInt(), a)
            canvas.drawCircle(x, y, max(0.6f, (0.5f + b * 1.9f) * u), paint)
        }
    }

    /** A small full moon with faint grey seas and a gentle glow. */
    private fun moon(canvas: Canvas, x: Float, y: Float, r: Float, vis: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(x, y, r * 4.5f, intArrayOf(alpha(0xFFCDD6EE.toInt(), 0.22f * vis), 0x00CDD6EE),
            floatArrayOf(0.2f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r * 4.5f, paint)
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(x, y, r, Path.Direction.CW) })
        paint.shader = RadialGradient(x - r * 0.35f, y - r * 0.35f, r * 1.3f,
            intArrayOf(alpha(0xFFF4F5F7.toInt(), vis), alpha(0xFFDDE0E6.toInt(), vis), alpha(0xFFAFB4BE.toInt(), vis)),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r, paint)
        paint.shader = null
        paint.color = alpha(0xFF878C9B.toInt(), 0.3f * vis)
        paint.maskFilter = BlurMaskFilter(r * 0.12f, BlurMaskFilter.Blur.NORMAL)
        listOf(floatArrayOf(-0.25f, -0.2f, 0.34f), floatArrayOf(0.22f, 0.02f, 0.27f), floatArrayOf(0.05f, 0.38f, 0.2f),
            floatArrayOf(-0.38f, 0.25f, 0.15f), floatArrayOf(0.36f, -0.32f, 0.12f)).forEach { (dx, dy, rr) ->
            canvas.drawOval(RectF(x + dx * r - rr * r * 1.25f, y + dy * r - rr * r, x + dx * r + rr * r * 1.25f, y + dy * r + rr * r), paint)
        }
        canvas.restore()
    }

    /** Soft bands of mist, thicker toward the bottom. */
    private fun fog(canvas: Canvas, w: Float, h: Float, low: Int, night: Boolean) {
        val mist = mix(low, Color.WHITE, if (night) 0.1f else 0.45f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(w * 0.07f, BlurMaskFilter.Blur.NORMAL) }
        for (i in 0 until 7) {
            paint.color = alpha(mist, 0.14f + i * 0.045f)
            val y = h * (0.2f + i * 0.12f)
            canvas.drawRect(-w * 0.2f, y, w * 1.2f, y + h * 0.08f, paint)
        }
        paint.maskFilter = null
        paint.shader = LinearGradient(0f, h * 0.25f, 0f, h, alpha(mist, 0f), alpha(mist, 0.6f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    /** Long, faint, slanting streaks, starting below the cloud base and fading up into it. */
    private fun rain(canvas: Canvas, w: Float, h: Float, r: Random, amount: Float, night: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val u = w / 900
        repeat((500 * amount).roundToInt()) {
            val d = r.next().pow(1.5f)
            val x = r.next() * w * 1.1f
            val y = h * (0.12f + r.next() * 0.98f)
            val length = (40 + d * 90) * u
            val up = ((y / h - 0.12f) / 0.4f).coerceIn(0f, 1f)
            val a = (0.07f + d * 0.24f) * up * if (night) 0.75f else 1f
            val ex = x - length * 0.12f
            val ey = y + length
            paint.shader = LinearGradient(x, y, ex, ey, intArrayOf(0x00DCE6FA, alpha(0xFFDCE6FA.toInt(), a), 0x00DCE6FA), null, Shader.TileMode.CLAMP)
            paint.strokeWidth = max(0.6f, (0.7f + d * 1.7f) * u)
            canvas.drawLine(x, y, ex, ey, paint)
        }
    }

    /** A few near flakes, big and out of focus, and many far ones, fewer high up in the cloud. */
    private fun snow(canvas: Canvas, w: Float, h: Float, r: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val u = w / 900
        repeat(420) {
            val d = r.next().pow(3)
            val x = r.next() * w
            val y = h * (0.1f + r.next() * 0.95f)
            val up = ((y / h - 0.1f) / 0.4f).coerceIn(0f, 1f)
            val blur = (0.4f + d * 4) * u
            paint.maskFilter = if (blur > 0.5f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
            paint.color = alpha(Color.WHITE, (0.35f + (1 - d) * 0.35f) * up * if (d > 0.5f) 0.6f else 1f)
            canvas.drawCircle(x, y, (1.2f + d * 9) * u, paint)
        }
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

    /** Smooth value noise, added up in finer and fainter layers (fractal noise) for cloud shapes. */
    private class Noise(seed: Int) {
        private val perm = IntArray(512)
        private val values = FloatArray(256)

        init {
            val r = Random(seed)
            for (i in 0 until 256) { perm[i] = i; values[i] = r.next() }
            for (i in 255 downTo 1) {
                val j = (r.next() * (i + 1)).toInt()
                val t = perm[i]; perm[i] = perm[j]; perm[j] = t
            }
            for (i in 0 until 256) perm[i + 256] = perm[i]
        }

        private fun fade(t: Float) = t * t * t * (t * (t * 6 - 15) + 10)

        private fun at(x: Float, y: Float): Float {
            val xi = floor(x).toInt()
            val yi = floor(y).toInt()
            val u = fade(x - xi)
            val v = fade(y - yi)
            val xa = xi and 255
            val ya = yi and 255
            val a = values[perm[perm[xa] + ya]]
            val b = values[perm[perm[xa + 1] + ya]]
            val c = values[perm[perm[xa] + ya + 1]]
            val d = values[perm[perm[xa + 1] + ya + 1]]
            return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
        }

        fun fbm(x0: Float, y0: Float, octaves: Int): Float {
            var x = x0
            var y = y0
            var sum = 0f
            var amp = 0.5f
            var total = 0f
            repeat(octaves) {
                sum += amp * at(x, y)
                total += amp
                x = x * 2.03f + 17.1f
                y = y * 2.03f + 9.3f
                amp *= 0.5f
            }
            return sum / total
        }
    }
}
