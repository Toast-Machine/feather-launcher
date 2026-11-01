package app.featherlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/** An app on the home screen or in the dock: icon, with its name underneath outside the dock. */
@SuppressLint("ViewConstructor")
class AppIconView(context: Context, val item: HomeItem) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val label = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isFocusable = true
        clipChildren = false
        clipToPadding = false
        // The keypad highlight is drawn on the icon only, an even square around it.
        icon.setPadding(dp(INSET), dp(INSET), dp(INSET), dp(INSET))
        icon.isDuplicateParentStateEnabled = true
        icon.background = Ui.focusHighlight(context, ICON_RADIUS + INSET)
        addView(icon, LayoutParams(dp(ICON + 2 * INSET), dp(ICON + 2 * INSET)))
        label.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(4), 0, dp(4), 0)
            height = dp(LABEL_HEIGHT)
        }
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        pressScale()
    }

    /** Icon size in dp: [ICON] on pages, [DOCK_ICON] in the dock. */
    private var size = ICON

    fun bind(bitmap: Bitmap?, name: String, showLabel: Boolean, locked: Boolean = false, isNew: Boolean = false,
             badge: Int = 0, iconDp: Int = ICON) {
        if (iconDp != size) {
            size = iconDp
            icon.layoutParams = LayoutParams(dp(size + 2 * INSET), dp(size + 2 * INSET))
            icon.background = Ui.focusHighlight(context, ICON_RADIUS * size / ICON + INSET)
        }
        icon.setImageBitmap(bitmap)
        IconBadges.apply(icon, locked, isNew, size + 2 * INSET, badge)
        label.text = name
        label.visibility = if (showLabel) VISIBLE else GONE
        contentDescription = name
    }

    /** The icon's bounds in this view, for the keypad highlight and drag image. */
    val iconView: ImageView get() = icon

    companion object {
        const val ICON = 56
        /**
         * Dock icons are larger, as in Smart Launcher: with the same padding around them,
         * smaller icons leave wide gaps between them and the dock looks spread out.
         */
        const val DOCK_ICON = 62
        const val INSET = 6
        /** Roughly the corner radius of a squircle icon at [ICON]. */
        const val ICON_RADIUS = 16
        /** Height of the icon with its highlight, which is also a dock cell's height. */
        const val BOX = ICON + 2 * INSET
        /** Fixed height of the name under the icon, so icons line up across rows. */
        const val LABEL_HEIGHT = 16
    }
}

/**
 * The built-in clock, always centred. On top, one small line about the day: the date, the
 * Hebrew date, the parsha and the like. Under it the time, as large as the widget
 * allows, and under that the weather and what's coming up. Transparent, straight on the
 * wallpaper in white, or Solid, on a card in the wallpaper's colors ([Prefs.widgetSolid]).
 */
@SuppressLint("ViewConstructor")
class ClockWidget(context: Context, val item: HomeItem) : FitColumn(context) {
    private val time = TextClock(context)
    /** "Sun, Nov 1 · 10 Cheshvan · Lech Lecha", over two lines if it must. */
    private val day = WidgetStyle.text(context, 15f, WidgetStyle.body)
    /** The weather and up to two things coming up. */
    private val pills = FitRow(context).apply { gap = Ui.dp(context, 14) }
    private val glance = Glance(context)
    private var place: ClockInfo.Place? = null
    private var solid = false
    /** The text sizes, in pixels, worked out from the widget's width (see [sizeFor]). */
    private var pillPx = 30f
    private var sizedFor = 0
    /** The time's size, before it's made smaller to fit. */
    private var timePx = 200f

    /** Tapping something coming up: the day's zmanim, the event, the alarms. */
    var onTap: ((Glance.Item, ClockInfo.Place?) -> Unit)? = null
    /** Tapping the weather. */
    var onWeather: (() -> Unit)? = null

    private val tick = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) = refresh()
    }

    init {
        isFocusable = true
        gravity = Gravity.CENTER
        // In a tight space the pills go first ([FitColumn]); the time never does.
        // The text's soft shadows reach past their own edges.
        clipChildren = false
        clipToPadding = false
        day.apply {
            maxLines = 2
            gravity = Gravity.CENTER
        }
        time.apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            typeface = WidgetStyle.display
            letterSpacing = -0.02f
            includeFontPadding = false
            // Room for the soft shadow, which would otherwise be cut off square at the text's edges
            // (the margins below take the room back, so nothing moves).
            setPadding(dp(SHADOW_ROOM), dp(SHADOW_ROOM), dp(SHADOW_ROOM), dp(SHADOW_ROOM))
        }
        addView(day, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val room = -dp(SHADOW_ROOM)
        addView(FitColumn.dropFirst(time, -1), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(room, room + dp(2), room, room)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        // The time does what the clock does (its alarms), so the keypad can reach it like the date and pills.
        time.setOnClickListener { performClick() }
        time.setOnLongClickListener { performLongClick() }
        // Its highlight hugs the digits, inside the room left for the shadow, so it stays within the clock.
        time.foreground = android.graphics.drawable.InsetDrawable(Ui.focusRing(context, Ui.RADIUS_L, WidgetStyle.WHITE), dp(SHADOW_ROOM))
        // Pulled up into the room under the digits (they have no descenders), so the space below the
        // time looks the same as the space above it.
        addView(pills, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = -dp(5) })
        setSolid(false)
    }

    /** The space last measured for, the time's scale that fitted it, and whether it's stacked, so the next pass changes nothing. */
    private var fitted: Fit? = null
    private class Fit(val width: Int, val height: Int, val scale: Float, val stacked: Boolean)

    /** The hours over the minutes, as Android's own tall clock has them. */
    private var stacked = false
    /** The stacked time's size, before it's made smaller to fit. */
    private var stackedPx = 200f

    /**
     * The time is as large as the widget lets it be: up to [timePx] for its width, then smaller
     * until everything fits its height. Only at the smallest are the pills left out. In a tall
     * widget, where the hours over the minutes can be much larger than both on one line, they're
     * stacked.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        sizeFor(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        fitted?.takeIf { it.width == widthMeasureSpec && it.height == heightMeasureSpec }?.let {
            stack(it.stacked)
            time.setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (it.stacked) stackedPx else timePx) * it.scale)
            return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        stack(false)
        val line = fit(widthMeasureSpec, heightMeasureSpec, timePx)
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            stack(true)
            val tall = fit(widthMeasureSpec, heightMeasureSpec, stackedPx)
            if (stackedPx * tall > timePx * line) {
                fitted = Fit(widthMeasureSpec, heightMeasureSpec, tall, true)
                return
            }
            stack(false)
            time.setTextSize(TypedValue.COMPLEX_UNIT_PX, timePx * line)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        fitted = Fit(widthMeasureSpec, heightMeasureSpec, line, false)
    }

    /** The largest scale of [size] at which everything fits; measures at it. */
    private fun fit(widthMeasureSpec: Int, heightMeasureSpec: Int, size: Float): Float {
        val height = MeasureSpec.getSize(heightMeasureSpec)
        var scale = 1f
        while (true) {
            time.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val shown = (0 until childCount).map(::getChildAt)
            val cut = shown.any { it.getTag(R.id.fit_hidden) == true && it.visibility == GONE }
            val used = paddingTop + paddingBottom + shown.filter { it.visibility != GONE }.sumOf {
                val lp = it.layoutParams as MarginLayoutParams
                it.measuredHeight + lp.topMargin + lp.bottomMargin
            }
            val fits = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || used <= height
            if (fits && !cut || scale <= 0.4f) return scale
            scale -= 0.05f
        }
    }

    private fun stack(on: Boolean) {
        if (on == stacked) return
        stacked = on
        time.format12Hour = if (on) "hh\nmm" else "h:mm"
        time.format24Hour = if (on) "HH\nmm" else "H:mm"
        // The lines closer than usual: digits have nothing above or below them to make room for.
        time.setLineSpacing(0f, if (on) 0.72f else 1f)
        time.gravity = Gravity.CENTER
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(tick, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIME_TICK)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        })
        refresh()
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(tick) }
        super.onDetachedFromWindow()
    }

    /** The text's color: the card's, or white on the wallpaper. */
    private val main get() = if (solid) WidgetStyle.onCard() else WidgetStyle.WHITE

    /** Solid: on a card with the cards' corners; transparent: straight on the wallpaper. */
    fun setSolid(solid: Boolean) {
        this.solid = solid
        background = if (solid) android.graphics.drawable.InsetDrawable(WidgetStyle.cardBackground(context), dp(CardWidget.GAP)) else null
        // The same room on every side of the card.
        val edge = dp(if (solid) CardWidget.GAP + 20 else 12)
        setPadding(edge, if (solid) edge else 0, edge, if (solid) edge else 0)
        time.setTextColor(if (solid) WidgetStyle.accent() else WidgetStyle.WHITE)
        listOf(time, day).forEach { shade(it) }
        sizedFor = 0
        fitted = null
        refresh()
        requestLayout()
    }

    /** The soft shadow on the wallpaper; none on a card. */
    private fun shade(text: TextView) {
        if (solid) text.setShadowLayer(0f, 0f, 0f, 0)
        else text.setShadowLayer(dp(if (text === time) 10 else 6).toFloat(), 0f, dp(1).toFloat(), 0x59000000)
    }

    /** Works out the date and details again; runs every minute. */
    fun refresh() {
        val lines = glance.lines(System.currentTimeMillis()) { if (isAttachedToWindow) refresh() }
        place = lines.place
        // Each part stays whole: a long line breaks only between them. All in one color, and in
        // regular weight, the one Hebrew has too (its font has no medium).
        day.text = (listOf(lines.date) + lines.detail?.split(" · ").orEmpty()).joinToString(" · ") { it.replace(' ', '\u00A0') }
        day.setTextColor(main)
        pills.removeAllViews()
        // The same as Up next, the two most pressing: the clock is Up next with the time.
        val coming = lines.coming.take(2)
        lines.weather?.let { w ->
            pills.addView(pill(WeatherArt.icon(w.sky, w.night), "${w.temperature}°  ${w.description}", tint = false).apply {
                setOnClickListener { onWeather?.invoke() }
                // With two things coming up and room for only some, the weather makes way.
                if (coming.size >= 2) FitColumn.dropFirst(this)
            })
        }
        coming.forEach { item ->
            pills.addView(pill(context.getDrawable(item.icon), item.text, tint = true).apply {
                setOnClickListener { onTap?.invoke(item, place) }
            })
        }
        pills.visibility = if (pills.childCount > 0) VISIBLE else GONE
        fitted = null
    }

    private fun pill(icon: android.graphics.drawable.Drawable?, words: String, tint: Boolean) =
        WidgetStyle.pill(context, icon?.mutate()?.apply { if (tint) setTint(main) }, words, pillPx).apply {
            setTextColor(main)
            // Regular, like the line about the day above the time.
            typeface = WidgetStyle.body
            shade(this)
        }

    /**
     * Sizes the text from the widget's [width] in pixels, so it takes the same share of the screen
     * on every phone, whatever its display size setting. The line about the day and the pills
     * step down from the time in fixed proportions.
     */
    private fun sizeFor(width: Int) {
        if (width <= 0 || width == sizedFor) return
        sizedFor = width
        fitted = null
        val unit = width / 100f
        // As wide as the widget: four digits ("12:59") fill it, edge to edge.
        val paint = android.graphics.Paint().apply {
            typeface = time.typeface
            letterSpacing = time.letterSpacing
            textSize = 100f
        }
        timePx = 100f * width / paint.measureText("00:00")
        stackedPx = 100f * width / paint.measureText("00") * 0.8f
        day.setTextSize(TypedValue.COMPLEX_UNIT_PX, 4.6f * unit)
        pillPx = 4.2f * unit
        for (i in 0 until pills.childCount) (pills.getChildAt(i) as? TextView)?.let { WidgetStyle.sizePill(it, pillPx) }
    }

    val dateView: TextView get() = day
}

/** Padding around the clock's time so its shadow isn't clipped. */
private const val SHADOW_ROOM = 12
