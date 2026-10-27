package app.featherlauncher

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * How Feather's own widgets look, so they all match each other and the dock: type straight on
 * the wallpaper is white with a soft shadow; pills and cards sit on the frosted surface of the
 * search bar and tab bar: nearly solid and tinted from the wallpaper, so they look the same on
 * any part of it, unlike a see-through fill.
 */
object WidgetStyle {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val SOFT_WHITE = 0xD9FFFFFF.toInt()

    /** The home screen's colors, from the wallpaper; set by [HomeScreen]. */
    var palette: () -> Palette? = { null }

    /** The surface and its text colors, for pills and cards. */
    fun surface(): Int = palette()?.glass ?: 0xF5202124.toInt()
    fun onSurface(): Int = palette()?.onWall ?: WHITE
    fun onSurfaceSecondary(): Int = palette()?.onWallSecondary ?: SOFT_WHITE
    private fun hairline(): Int = palette()?.hairline ?: 0x14FFFFFF

    /** A rounded piece of the surface, [radius] in dp. */
    fun surfaceBackground(ctx: Context, radius: Int) = Ui.rounded(ctx, surface(), radius, hairline())

    val display: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    val title: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    val body: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    val label: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)

    /** White text with the soft shadow that keeps it readable on any wallpaper. */
    fun text(ctx: Context, sp: Float, face: Typeface, color: Int = WHITE) = TextView(ctx).apply {
        setTextColor(color)
        typeface = face
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setShadowLayer(Ui.dp(ctx, 6).toFloat(), 0f, Ui.dp(ctx, 1).toFloat(), 0x59000000)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** Text on a card, in the dock's text color. */
    fun cardText(ctx: Context, sp: Float, face: Typeface, secondary: Boolean = false) = TextView(ctx).apply {
        setTextColor(if (secondary) onSurfaceSecondary() else onSurface())
        typeface = face
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** A rounded pill with an icon and a few words, for something to tap. */
    fun pill(ctx: Context, icon: Drawable?, words: String, sp: Float) = TextView(ctx).apply {
        val dp = { v: Int -> Ui.dp(ctx, v) }
        text = words
        setTextColor(onSurface())
        typeface = label
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        background = surfaceBackground(ctx, Ui.RADIUS_PILL)
        foreground = Ui.pressable(ctx, Ui.RADIUS_PILL)
        val size = (sp * 1.2f * ctx.resources.displayMetrics.scaledDensity).toInt()
        icon?.mutate()?.apply {
            setTint(onSurface())
            setBounds(0, 0, size, size)
        }
        setCompoundDrawablesRelative(icon, null, null, null)
        compoundDrawablePadding = dp(6)
        setPadding(dp(10), dp(6), dp(12), dp(6))
    }

    fun icon(ctx: Context, res: Int): Drawable? = ctx.getDrawable(res)
}

/**
 * A row of pills that only shows the ones that fit, in order: on a small screen the last ones
 * are left out rather than squeezed. Centred, or from the start with [alignStart].
 */
class FitRow(context: Context) : ViewGroup(context) {
    var gap = Ui.dp(context, 8)
    var alignStart = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec).let { if (it == 0) Int.MAX_VALUE else it }
        var used = 0
        var height = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            val next = used + (if (used > 0) gap else 0) + child.measuredWidth
            // The first pill always shows, shortened if it must be; later ones only if they fit whole.
            if (used > 0 && next > available) {
                child.visibility = View.GONE
                continue
            }
            child.visibility = View.VISIBLE
            used = minOf(next, available)
            height = maxOf(height, child.measuredHeight)
        }
        setMeasuredDimension(if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) available else used, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val shown = (0 until childCount).map(::getChildAt).filter { it.visibility == View.VISIBLE }
        val total = shown.sumOf { it.measuredWidth } + gap * (shown.size - 1).coerceAtLeast(0)
        var x = if (alignStart) 0 else ((r - l) - total).coerceAtLeast(0) / 2
        for (child in shown) {
            val w = minOf(child.measuredWidth, r - l - x)
            child.layout(x, 0, x + w, child.measuredHeight)
            x += w + gap
        }
    }
}

/**
 * A column that leaves out its last rows when they don't fit, instead of letting them spill
 * over or get cut in half: on a small screen a card shows fewer rows, not broken ones.
 */
open class FitColumn(context: Context) : android.widget.LinearLayout(context) {
    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        for (i in 0 until childCount) getChildAt(i).let { if (it.getTag(R.id.fit_hidden) == true) it.visibility = View.VISIBLE }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) return
        val room = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        val width = MeasureSpec.makeMeasureSpec(measuredWidth - paddingLeft - paddingRight, MeasureSpec.AT_MOST)
        while (used(width) > room) {
            val last = (childCount - 1 downTo 1).map(::getChildAt).firstOrNull { it.visibility == View.VISIBLE } ?: break
            last.visibility = View.GONE
            last.setTag(R.id.fit_hidden, true)
        }
        // Checking the rows' own heights measured them loosely; measure them properly to lay out.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The rows' own heights, as they'd be with room to spare (the column would squeeze the last one to fit). */
    private fun used(width: Int) = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }.sumOf {
        val lp = it.layoutParams as MarginLayoutParams
        it.measure(width, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        it.measuredHeight + lp.topMargin + lp.bottomMargin
    }
}
