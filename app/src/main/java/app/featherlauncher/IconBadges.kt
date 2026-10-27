package app.featherlauncher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.widget.ImageView

/**
 * Marks drawn on an app icon: at the top corner, a red badge with the number of notifications
 * the app has showing, as on iOS, or a dot for an app that was just installed and hasn't been
 * opened yet; and while a limit has it locked, gray with a lock at the bottom corner.
 */
object IconBadges {
    private val gray = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    /** [sizeDp] is the icon's size, which the marks scale with; [count] is its notifications. */
    fun apply(icon: ImageView, locked: Boolean, isNew: Boolean, sizeDp: Int, count: Int = 0) {
        icon.colorFilter = if (locked) gray else null
        icon.imageAlpha = if (locked) 150 else 255
        val size = Ui.dp(icon.context, sizeDp)
        val layers = listOfNotNull(
            when {
                locked -> null
                count > 0 -> countBadge(icon, size, count)
                isNew -> newDot(icon, size)
                else -> null
            },
            if (locked) lock(icon, size) else null,
        )
        icon.foreground = if (layers.isEmpty()) null else LayerDrawable(layers.flatMap { it.layers }.toTypedArray()).apply {
            var i = 0
            layers.forEach { group -> group.place.forEach { it(this, i++) } }
        }
    }

    /** Drawables plus how to size and place each one in the combined layer list. */
    private class Group(val layers: List<Drawable>, val place: List<(LayerDrawable, Int) -> Unit>)

    /** A white dot sitting on the icon's top corner, where the rounded shape leaves room. */
    private fun newDot(icon: ImageView, size: Int): Group {
        val dot = (size * 0.28f).toInt()
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFF2F4F3.toInt())
            // A faint edge keeps it visible on white icons.
            setStroke(Ui.dp(icon.context, 1), 0x26000000)
        }
        return Group(listOf(circle), listOf { d, i ->
            d.setLayerSize(i, dot, dot)
            d.setLayerGravity(i, Gravity.TOP or Gravity.END)
        })
    }

    /**
     * A red pill with the count in white, growing sideways for longer numbers. As on iOS, it's
     * placed by the icon itself (not the view's padding around it) and hangs off its top corner,
     * so the view's parent must not clip it.
     */
    private fun countBadge(icon: ImageView, size: Int, count: Int): Group {
        val pad = icon.paddingTop
        val height = ((size - 2 * pad) * 0.4f).toInt()
        val overhang = (height * 0.28f).toInt()
        val badge = CountBadge(if (count > 99) "99+" else count.toString(), height, overhang)
        return Group(listOf(badge), listOf { d, i ->
            d.setLayerSize(i, badge.intrinsicWidth, height)
            d.setLayerGravity(i, Gravity.TOP or Gravity.END)
            d.setLayerInsetTop(i, pad)
            d.setLayerInsetEnd(i, icon.paddingEnd)
        })
    }

    /** Drawn [shift] up and out from its bounds, past the icon's corner. */
    private class CountBadge(private val text: String, height: Int, private val shift: Int) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = height * 0.64f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val width = maxOf(height, (ink.measureText(text) + height * 0.7f).toInt())
        private val h = height
        private val digits = Rect()

        override fun getIntrinsicWidth() = width
        override fun getIntrinsicHeight() = h

        override fun draw(canvas: Canvas) {
            val b = RectF(bounds).apply { offset(shift.toFloat(), -shift.toFloat()) }
            val r = b.height() / 2
            canvas.drawRoundRect(b, r, r, fill)
            // Centered on the digits' own height, not the font's ascent and descent.
            ink.getTextBounds(text, 0, text.length, digits)
            canvas.drawText(text, b.centerX(), b.centerY() - digits.exactCenterY(), ink)
        }

        override fun setAlpha(alpha: Int) {
            fill.alpha = alpha
            ink.alpha = alpha
        }

        override fun setColorFilter(filter: ColorFilter?) {
            fill.colorFilter = filter
            ink.colorFilter = filter
        }

        @Deprecated("Platform Drawable API")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun lock(icon: ImageView, size: Int): Group {
        val ctx = icon.context
        val glyphDrawable = ctx.getDrawable(R.drawable.ic_lock)!!.mutate().apply { setTint(0xFF1B1C1F.toInt()) }
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
        // The dot is 40% of the icon, the lock 60% of the dot.
        val dot = (size * 0.4f).toInt()
        val glyph = (dot * 0.6f).toInt()
        val inset = (dot - glyph) / 2
        return Group(listOf(circle, glyphDrawable), listOf(
            { d, i ->
                d.setLayerSize(i, dot, dot)
                d.setLayerGravity(i, Gravity.BOTTOM or Gravity.END)
            },
            { d, i ->
                d.setLayerSize(i, glyph, glyph)
                d.setLayerGravity(i, Gravity.BOTTOM or Gravity.END)
                d.setLayerInsetEnd(i, inset)
                d.setLayerInsetBottom(i, inset)
            },
        ))
    }
}
