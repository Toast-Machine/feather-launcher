package app.featherlauncher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import kotlin.math.roundToInt

/** Spacing, radii and type used everywhere. Change a value here and every screen follows. */
object Ui {
    const val SPACE_XS = 4
    const val SPACE_S = 8
    const val SPACE_M = 12
    const val SPACE_L = 16
    const val SPACE_XL = 24

    const val RADIUS_S = 12
    const val RADIUS_M = 16
    const val RADIUS_L = 22
    const val RADIUS_XL = 28
    const val RADIUS_PILL = 999
    /** The dock and the app list's tab bar share one shape. */
    const val RADIUS_BAR = 26
    /** The dock's height: its icon box plus the even gap above and below. */
    const val DOCK_HEIGHT = 92
    /** Side and bottom margin of the dock and tab bar, so they line up across screens. */
    const val BAR_MARGIN = 16

    const val ROW_HEIGHT = 52
    const val ICON_ROW = 40
    const val ICON_GRID = 56

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(context: Context, value: Number): Int = (value.toFloat() * context.resources.displayMetrics.density).roundToInt()

    /**
     * A text field's cursor, selection handles and selected text in [color] (its text's), the same
     * in every field, instead of the theme's white, which can't be seen on a light field.
     */
    fun cursor(field: android.widget.TextView, color: Int) {
        val ctx = field.context
        field.textCursorDrawable = GradientDrawable().apply {
            setColor(color)
            setSize(dp(ctx, 2), 0)
            cornerRadius = dp(ctx, 1).toFloat()
        }
        field.highlightColor = (color and 0xFFFFFF) or 0x40000000
        field.textSelectHandle?.mutate()?.let { it.setTint(color); field.setTextSelectHandle(it) }
        field.textSelectHandleLeft?.mutate()?.let { it.setTint(color); field.setTextSelectHandleLeft(it) }
        field.textSelectHandleRight?.mutate()?.let { it.setTint(color); field.setTextSelectHandleRight(it) }
    }

    fun rounded(context: Context, color: Int, radiusDp: Int, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeColor != 0) setStroke(dp(context, 1), strokeColor)
        }

    /**
     * Where the keypad's highlight goes on a view: around this drawable's bounds, with corners of
     * [radiusDp] (the corners of what's highlighted, not of the ring). [FocusRing] draws the ring
     * itself, the same everywhere.
     */
    fun focusRing(@Suppress("UNUSED_PARAMETER") context: Context, radiusDp: Int): Drawable = FocusRing.Spot({ radiusDp })

    /** As [focusRing], for corners that change (a widget's follow the icons'). */
    fun focusRing(radiusDp: () -> Int): Drawable = FocusRing.Spot(radiusDp)

    /** For app icons: no box when tapped (the icon shrinks instead, see [pressScale]), only the focus ring. */
    fun focusHighlight(context: Context, radiusDp: Int): Drawable = focusRing(context, radiusDp)

    /** The highlight around an app icon, in the icon's own [shape], around this drawable's bounds. */
    fun iconFocusRing(@Suppress("UNUSED_PARAMETER") context: Context, shape: IconShape): Drawable = FocusRing.Spot({ 0 }, shape)

    /** Touch ripple, plus the focus ring when the keypad is on the view. */
    fun pressable(context: Context, radiusDp: Int, content: Drawable? = null): Drawable =
        android.graphics.drawable.LayerDrawable(arrayOf(
            TouchRipple(content, rounded(context, 0xFFFFFFFF.toInt(), radiusDp)),
            focusRing(context, radiusDp),
        ))

    /**
     * A full-width row's touch ripple and highlight: inset from the row's sides and a little from
     * its top and bottom, with rounded corners, so the ring (just outside it) stays on the screen.
     */
    class RowHighlight(context: Context) : android.graphics.drawable.InsetDrawable(
        pressable(context, ROW_RADIUS), dp(context, SPACE_S), dp(context, 3), dp(context, SPACE_S), dp(context, 3))

    fun rowHighlight(context: Context): Drawable = RowHighlight(context)

    /** A row's highlight's corners, inside a group or not. */
    const val ROW_RADIUS = 14

    /** A ripple for touches only. A plain ripple also fills the item while it's focused, on top of the focus ring. */
    private class TouchRipple(content: Drawable?, mask: Drawable) : RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask) {
        override fun onStateChange(stateSet: IntArray): Boolean = super.onStateChange(
            stateSet.filter { it != android.R.attr.state_focused && it != android.R.attr.state_hovered }.toIntArray(),
        )
    }

    /** The focus ring's color: the accent with its hue 120 degrees lower, at the same saturation and lightness. */
    fun ringColor(context: Context): Int {
        val prefs = Prefs(context)
        val accent = Palette.from(context, prefs.colorStyle, prefs.themeMode, prefs.accentColor).accent
        val (h, s, l) = Palette.rgbToHsl(accent)
        return Palette.hsl((h + 240f) % 360f, s, l)
    }

    /**
     * Makes [header] take [scrolled] while the page's content is scrolled under it, and [rest]
     * at the top, fading between them. Returns what to call as the page scrolls.
     */
    fun scrollTint(header: View, rest: Int, scrolled: Int): (Boolean) -> Unit {
        header.setBackgroundColor(rest)
        var under = false
        var fade: android.animation.ValueAnimator? = null
        return { now ->
            if (now != under) {
                under = now
                fade?.cancel()
                val from = (header.background as? android.graphics.drawable.ColorDrawable)?.color ?: rest
                fade = android.animation.ValueAnimator.ofArgb(from, if (now) scrolled else rest).apply {
                    duration = 150
                    addUpdateListener { header.setBackgroundColor(it.animatedValue as Int) }
                    start()
                }
            }
        }
    }
}

enum class TextStyle(val sp: Float, val bold: Boolean, val caps: Boolean = false, val spacing: Float = 0f) {
    Title(20f, true),
    Body(16f, false),
    Label(15f, true),
    Secondary(14f, false),
    Caption(12f, true),
    Section(14f, true),
}

fun TextView.style(style: TextStyle, color: Int) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sp)
    typeface = if (style.bold) Ui.medium else Typeface.DEFAULT
    isAllCaps = style.caps
    letterSpacing = style.spacing
    setTextColor(color)
    includeFontPadding = false
}

fun View.dp(value: Number) = Ui.dp(context, value)

/** Shrinks the view slightly while it's pressed, as tap feedback for icons. */
@android.annotation.SuppressLint("ClickableViewAccessibility")
fun View.pressScale() = setOnTouchListener { v, event ->
    when (event.actionMasked) {
        android.view.MotionEvent.ACTION_DOWN ->
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
            v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
    }
    false
}
