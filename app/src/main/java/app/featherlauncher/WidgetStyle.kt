package app.featherlauncher

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * How Feather's own widgets look on the wallpaper, so they all match: white type in a few
 * weights with a soft shadow, and small rounded pills with a dark see-through fill for the
 * things you can tap, which stay readable on light and dark wallpapers alike.
 */
object WidgetStyle {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val SOFT_WHITE = 0xD9FFFFFF.toInt()
    /** A pill's fill: dark and see-through. */
    private const val PILL = 0x33000000

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

    /** A rounded pill with an icon and a few words, for something to tap. */
    fun pill(ctx: Context, icon: Drawable?, words: String, sp: Float) = TextView(ctx).apply {
        val dp = { v: Int -> Ui.dp(ctx, v) }
        text = words
        setTextColor(WHITE)
        typeface = label
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(100).toFloat()
            setColor(PILL)
        }
        foreground = Ui.pressable(ctx, Ui.RADIUS_PILL)
        val size = (sp * 1.2f * ctx.resources.displayMetrics.scaledDensity).toInt()
        icon?.mutate()?.apply {
            setTint(WHITE)
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
        var hid = false
        while (used(width) > room) {
            val last = (childCount - 1 downTo 1).map(::getChildAt).firstOrNull { it.visibility == View.VISIBLE } ?: break
            last.visibility = View.GONE
            last.setTag(R.id.fit_hidden, true)
            hid = true
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        if (hid) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The rows' own heights, as they'd be with room to spare (the column would squeeze the last one to fit). */
    private fun used(width: Int) = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }.sumOf {
        val lp = it.layoutParams as MarginLayoutParams
        it.measure(width, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        it.measuredHeight + lp.topMargin + lp.bottomMargin
    }
}
