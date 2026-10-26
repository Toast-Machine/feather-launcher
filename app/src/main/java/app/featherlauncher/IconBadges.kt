package app.featherlauncher

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.widget.ImageView

/**
 * Marks drawn on an app icon: a dot at the top corner for an app that was just installed and
 * hasn't been opened yet, and during Sleep time, gray with a moon at the bottom corner.
 */
object IconBadges {
    private val gray = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    /** [sizeDp] is the icon's size, which the marks scale with. */
    fun apply(icon: ImageView, asleep: Boolean, isNew: Boolean, sizeDp: Int) {
        icon.colorFilter = if (asleep) gray else null
        icon.imageAlpha = if (asleep) 150 else 255
        val size = Ui.dp(icon.context, sizeDp)
        val layers = listOfNotNull(
            if (isNew && !asleep) newDot(icon, size) else null,
            if (asleep) moon(icon, size) else null,
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

    private fun moon(icon: ImageView, size: Int): Group {
        val ctx = icon.context
        val glyphDrawable = ctx.getDrawable(R.drawable.ic_moon_fill)!!.mutate().apply { setTint(0xFF1B1C1F.toInt()) }
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
        // The dot is 40% of the icon, the moon 60% of the dot.
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
