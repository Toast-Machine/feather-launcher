package app.featherlauncher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** What the sky is doing, in the few kinds that need their own picture. */
enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

/**
 * A small weather picture drawn in one color, in the same line weight as the app's icons:
 * the sun (or the moon at night), a cloud, and rain, snow or lightning under it.
 */
class WeatherIcon(private val sky: Sky, private val night: Boolean, color: Int) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        canvas.save()
        canvas.translate(b.left + (b.width() - s) / 2, b.top + (b.height() - s) / 2)
        // Drawn on a 24 by 24 grid, like the app's other icons.
        canvas.scale(s / 24f, s / 24f)
        stroke.strokeWidth = 2f
        when (sky) {
            Sky.CLEAR -> if (night) moon(canvas, 12f, 12f, 7f) else sun(canvas, 12f, 12f, 4.2f, rays = true)
            Sky.PARTLY -> {
                if (night) moon(canvas, 9f, 8.5f, 4.6f) else sun(canvas, 8.5f, 8.5f, 3.2f, rays = true)
                cloud(canvas, 13.5f, 15.5f, 0.82f)
            }
            Sky.CLOUDY -> cloud(canvas, 12f, 13f, 1f)
            Sky.FOG -> {
                cloud(canvas, 12f, 10.5f, 0.85f)
                canvas.drawLine(5f, 18.5f, 19f, 18.5f, stroke)
                canvas.drawLine(7f, 21.5f, 17f, 21.5f, stroke)
            }
            Sky.RAIN -> {
                cloud(canvas, 12f, 10f, 0.9f)
                for (x in listOf(8f, 12f, 16f)) canvas.drawLine(x, 17.5f, x - 1.2f, 21f, stroke)
            }
            Sky.SNOW -> {
                cloud(canvas, 12f, 10f, 0.9f)
                for ((x, y) in listOf(8f to 18f, 12f to 20.5f, 16f to 18f)) canvas.drawCircle(x, y, 1.15f, fill)
            }
            Sky.STORM -> {
                cloud(canvas, 12f, 10f, 0.9f)
                val bolt = Path().apply {
                    moveTo(12.8f, 15.5f); lineTo(10.2f, 19.2f); lineTo(12.6f, 19.2f); lineTo(11.2f, 22.5f)
                }
                canvas.drawPath(bolt, stroke)
            }
        }
        canvas.restore()
    }

    private fun sun(canvas: Canvas, cx: Float, cy: Float, r: Float, rays: Boolean) {
        canvas.drawCircle(cx, cy, r, stroke)
        if (!rays) return
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val inner = r + 2.4f
            val outer = r + 4.4f
            canvas.drawLine(cx + inner * cos(a).toFloat(), cy + inner * sin(a).toFloat(),
                cx + outer * cos(a).toFloat(), cy + outer * sin(a).toFloat(), stroke)
        }
    }

    /** A crescent: a circle with a smaller one taken out of its upper right. */
    private fun moon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val outer = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val bite = Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.45f, r * 0.82f, Path.Direction.CW) }
        outer.op(bite, Path.Op.DIFFERENCE)
        canvas.drawPath(outer, stroke)
    }

    /** A cloud centred on ([cx], [cy]), [k] times the full size: a flat bottom and two bumps. */
    private fun cloud(canvas: Canvas, cx: Float, cy: Float, k: Float) {
        val path = Path().apply {
            val left = cx - 8f * k
            val right = cx + 8f * k
            val bottom = cy + 4f * k
            moveTo(left + 3f * k, bottom)
            lineTo(right - 3f * k, bottom)
            arcTo(RectF(right - 6f * k, bottom - 6f * k, right, bottom), 90f, -180f)
            arcTo(RectF(cx - 4f * k, cy - 7f * k, cx + 4.5f * k, cy + 1.5f * k), -10f, -170f)
            arcTo(RectF(left, bottom - 6f * k, left + 6f * k, bottom), -90f, -180f)
            close()
        }
        canvas.drawPath(path, stroke)
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        stroke.colorFilter = filter
        fill.colorFilter = filter
    }

    @Deprecated("Platform Drawable API")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
