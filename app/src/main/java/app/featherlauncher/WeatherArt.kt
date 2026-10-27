package app.featherlauncher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Weather widget's pictures: the sky behind it in the colors of the weather and the time
 * of day, and full-color drawings of the sun, the moon, clouds, rain, snow and lightning.
 */
object WeatherArt {
    /** The sky as a top-to-bottom gradient, rounded to [radius] px. */
    fun sky(sky: Sky, night: Boolean, radius: Float): GradientDrawable {
        val (top, bottom) = when (sky) {
            Sky.CLEAR -> if (night) 0xFF0E1A33 to 0xFF2A4470 else 0xFF2F7BE8 to 0xFF64B6F7
            Sky.PARTLY -> if (night) 0xFF17233D to 0xFF3A5478 else 0xFF4A86D4 to 0xFF8DBDE9
            Sky.CLOUDY -> if (night) 0xFF2E3644 to 0xFF4B5566 else 0xFF66768C to 0xFF97A4B6
            Sky.FOG -> if (night) 0xFF3A424C to 0xFF5A636E else 0xFF6F7E8C to 0xFF9EAAB5
            Sky.RAIN -> if (night) 0xFF1F2A3A to 0xFF3B4B60 else 0xFF44586F to 0xFF71859C
            Sky.SNOW -> if (night) 0xFF2B3B55 to 0xFF52688A else 0xFF5B7EA8 to 0xFF94B2D3
            Sky.STORM -> 0xFF1F2328 to 0xFF3D434C
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top.toInt(), bottom.toInt())).apply {
            cornerRadius = radius
        }
    }

    fun icon(sky: Sky, night: Boolean): Drawable = Picture(sky, night)

    /** A weather drawing on a 24 by 24 grid, in full color. */
    private class Picture(private val sky: Sky, private val night: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = minOf(b.width(), b.height()).toFloat()
            canvas.save()
            canvas.translate(b.left + (b.width() - s) / 2, b.top + (b.height() - s) / 2)
            canvas.scale(s / 24f, s / 24f)
            when (sky) {
                Sky.CLEAR -> if (night) moon(canvas, 12f, 12f, 8.5f) else sun(canvas, 12f, 12f, 7.4f)
                Sky.PARTLY -> {
                    if (night) moon(canvas, 9f, 8.5f, 5.8f) else sun(canvas, 9f, 8.5f, 5.4f)
                    cloud(canvas, 13.5f, 15f, 0.85f, WHITE, SHADE)
                }
                Sky.CLOUDY -> {
                    cloud(canvas, 9.5f, 10.5f, 0.6f, 0xFFDDE3EA.toInt(), 0xFFC4CDD8.toInt())
                    cloud(canvas, 13f, 14.5f, 0.95f, WHITE, SHADE)
                }
                Sky.FOG -> {
                    cloud(canvas, 12f, 10f, 0.85f, WHITE, SHADE)
                    line.color = 0xE6E3E8EE.toInt()
                    line.strokeWidth = 1.8f
                    canvas.drawLine(5f, 17.5f, 19f, 17.5f, line)
                    canvas.drawLine(7f, 20.5f, 17f, 20.5f, line)
                }
                Sky.RAIN -> {
                    cloud(canvas, 12f, 9.5f, 0.9f, 0xFFE9EEF3.toInt(), 0xFFC5CFDA.toInt())
                    line.color = 0xFF64B5F6.toInt()
                    line.strokeWidth = 1.8f
                    for (x in listOf(8f, 12f, 16f)) canvas.drawLine(x, 16.5f, x - 1.4f, 20.5f, line)
                }
                Sky.SNOW -> {
                    cloud(canvas, 12f, 9.5f, 0.9f, WHITE, SHADE)
                    paint.shader = null
                    paint.color = WHITE
                    for ((x, y) in listOf(8f to 17.5f, 12f to 20f, 16f to 17.5f, 10f to 21.5f, 14f to 22f)) canvas.drawCircle(x, y, 1.1f, paint)
                }
                Sky.STORM -> {
                    cloud(canvas, 12f, 9f, 0.9f, 0xFFB8C2CE.toInt(), 0xFF8E9AA8.toInt())
                    paint.shader = null
                    paint.color = 0xFFFFC93C.toInt()
                    canvas.drawPath(Path().apply {
                        moveTo(13.2f, 14.5f); lineTo(9.6f, 19.2f); lineTo(12f, 19.2f); lineTo(10.6f, 23f); lineTo(14.6f, 17.6f)
                        lineTo(12.2f, 17.6f); close()
                    }, paint)
                }
            }
            canvas.restore()
        }

        /** A warm sun with a soft glow around it. */
        private fun sun(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            paint.shader = RadialGradient(cx, cy, r * 1.6f, intArrayOf(0x66FFE27A, 0x00FFE27A), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * 1.6f, paint)
            paint.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.3f, intArrayOf(0xFFFFE680.toInt(), 0xFFFFB300.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
            paint.shader = null
        }

        /** A pale crescent moon. */
        private fun moon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val shape = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
            shape.op(Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.42f, r * 0.85f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
            paint.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xFFFFF6D5.toInt(), 0xFFF3DC8A.toInt(), Shader.TileMode.CLAMP)
            canvas.drawPath(shape, paint)
            paint.shader = null
        }

        /** A puffy cloud, lighter on top and a little shaded underneath. */
        private fun cloud(canvas: Canvas, cx: Float, cy: Float, k: Float, top: Int, bottom: Int) {
            val path = Path().apply {
                val left = cx - 8f * k
                val right = cx + 8f * k
                val base = cy + 4f * k
                addRoundRect(RectF(left, base - 5f * k, right, base), 2.5f * k, 2.5f * k, Path.Direction.CW)
                addCircle(cx - 3.2f * k, base - 4.6f * k, 3.4f * k, Path.Direction.CW)
                addCircle(cx + 1.6f * k, base - 6.2f * k, 4.6f * k, Path.Direction.CW)
                addCircle(cx + 5.4f * k, base - 3.6f * k, 2.6f * k, Path.Direction.CW)
            }
            paint.shader = LinearGradient(0f, cy - 6f * k, 0f, cy + 4f * k, top, bottom, Shader.TileMode.CLAMP)
            canvas.drawPath(path, paint)
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            line.alpha = alpha
        }

        override fun setColorFilter(filter: ColorFilter?) {
            paint.colorFilter = filter
            line.colorFilter = filter
        }

        @Deprecated("Platform Drawable API")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val SHADE = 0xFFDCE3EC.toInt()
}
