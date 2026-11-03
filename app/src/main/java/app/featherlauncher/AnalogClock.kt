package app.featherlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import java.util.Calendar

/**
 * The time on a clock face, and nothing else: a soft round face with twelve marks and no numbers,
 * slim hands and a fine second hand. Solid, on a card with the face in the accent's soft tone;
 * Transparent, a glass face on the wallpaper. Under it, if they're turned on in its settings, the
 * date in both calendars and the parsha ([DateLines]): the face gets smaller to make room for them.
 */
@SuppressLint("ViewConstructor")
class AnalogClockWidget(context: Context, val item: HomeItem) : FrameLayout(context) {
    private val face = Face(context)
    private val dates = DateLines(context).apply {
        showDates = false
        showParsha = false
    }
    var solid = false
        private set

    // Holding anywhere picks it up, as on the other widgets ([WholeHold]).
    private val hold = WholeHold(this)

    override fun dispatchTouchEvent(ev: android.view.MotionEvent) = hold.touch(ev) { super.dispatchTouchEvent(it) }

    override fun performLongClick() = hold.longClick { super.performLongClick() }

    init {
        isFocusable = true
        // The dates reach past the face's padding, closer to the card's edges.
        clipToPadding = false
        addView(face)
        addView(dates)
        setSolid(false)
    }

    /** What shows under the face: the dates in both calendars, the parsha, both or neither. */
    fun setDates(dates: Boolean, parsha: Boolean) {
        this.dates.showDates = dates
        this.dates.showParsha = parsha
    }

    /** Solid: on a card with the cards' corners; transparent: straight on the wallpaper. */
    fun setSolid(solid: Boolean) {
        this.solid = solid
        background = if (solid) android.graphics.drawable.InsetDrawable(WidgetStyle.cardBackground(context), dp(CardWidget.GAP)) else null
        val edge = dp(CardWidget.GAP + EDGE)
        setPadding(edge, edge, edge, edge)
        dates.solid = solid
        face.invalidate()
    }

    /** Draws the hands again (they also move on their own each second while on screen), and the dates. */
    fun refresh() {
        face.invalidate()
        dates.update()
    }

    private val showsDates get() = !dates.isEmpty

    /**
     * The face as large as the room allows, in the middle; with the dates, as large as the room
     * above them allows, the two together in the middle. The dates reach closer to the card's
     * edges than the face, so they're cut short as little as can be.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) width else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        var room = height - paddingTop - paddingBottom
        if (showsDates) {
            val side = dp(CardWidget.GAP + TEXT_EDGE)
            dates.measure(MeasureSpec.makeMeasureSpec((width - 2 * side).coerceAtLeast(0), MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
            room = height - paddingTop - side - dates.measuredHeight - dp(TEXT_GAP)
        }
        val size = minOf(width - paddingLeft - paddingRight, room).coerceAtLeast(0)
        face.measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = (r - l - face.measuredWidth) / 2
        if (!showsDates) {
            dates.layout(0, 0, 0, 0)
            val top = (b - t - face.measuredHeight) / 2
            face.layout(left, top, left + face.measuredWidth, top + face.measuredHeight)
            return
        }
        val side = dp(CardWidget.GAP + TEXT_EDGE)
        val both = face.measuredHeight + dp(TEXT_GAP) + dates.measuredHeight
        val top = paddingTop + (b - t - paddingTop - side - both) / 2
        face.layout(left, top, left + face.measuredWidth, top + face.measuredHeight)
        val under = top + face.measuredHeight + dp(TEXT_GAP)
        dates.layout(side, under, side + dates.measuredWidth, under + dates.measuredHeight)
    }

    private fun dp(value: Int) = Ui.dp(context, value)

    /** The face itself, drawn to its size; the second hand moves only while it's on screen. */
    private inner class Face(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val calendar = Calendar.getInstance()
        private var showing = false
        /**
         * Each second: drawn again if it's on screen. On a page that isn't showing it only looks
         * again a second later, so its page comes into view with the time at most a second behind,
         * as it would be between ticks anyway.
         */
        private val second = object : Runnable {
            override fun run() {
                if (getGlobalVisibleRect(seen)) invalidate() else postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
            }
        }
        private val seen = android.graphics.Rect()
        /** The face's shading, made again only when its size or color changes, not every second. */
        private var shading: RadialGradient? = null
        private var shadingFor = 0L
        /** The minute [contentDescription] was last set for. */
        private var described = -1L

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            showing = isVisible
            if (isVisible) invalidate() else removeCallbacks(second)
        }

        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) / 2f
            if (r <= 0f) return
            val cx = width / 2f
            val cy = height / 2f
            val hands = if (solid) WidgetStyle.onAccentFill() else WidgetStyle.WHITE
            val sweep = WidgetStyle.tertiary()

            // The face: the accent's soft tone, a touch lighter at the middle; on the wallpaper, glass.
            paint.style = Paint.Style.FILL
            paint.clearShadowLayer()
            if (solid) {
                val tone = WidgetStyle.accentFill()
                val key = (width.toLong() shl 48) or (height.toLong() shl 32) or (tone.toLong() and 0xFFFFFFFFL)
                if (key != shadingFor || shading == null) {
                    shading = RadialGradient(cx, cy * 0.85f, r * 1.1f, blend(tone, Color.WHITE, 0.06f), tone, Shader.TileMode.CLAMP)
                    shadingFor = key
                }
                paint.shader = shading
            } else {
                paint.shader = null
                paint.color = 0x24FFFFFF
            }
            canvas.drawCircle(cx, cy, r, paint)
            paint.shader = null
            if (!solid) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = r * 0.012f
                paint.color = 0x40FFFFFF
                canvas.drawCircle(cx, cy, r - paint.strokeWidth / 2, paint)
            }

            // Twelve marks, the quarters a little longer and bolder; no numbers.
            paint.style = Paint.Style.STROKE
            paint.color = withAlpha(hands, if (solid) 0.55f else 0.75f)
            for (i in 0 until 12) {
                val quarter = i % 3 == 0
                paint.strokeWidth = r * (if (quarter) 0.045f else 0.028f)
                canvas.save()
                canvas.rotate(i * 30f, cx, cy)
                canvas.drawLine(cx, cy - r * 0.86f, cx, cy - r * (if (quarter) 0.70f else 0.76f), paint)
                canvas.restore()
            }

            calendar.timeInMillis = System.currentTimeMillis()
            val h = calendar.get(Calendar.HOUR)
            val m = calendar.get(Calendar.MINUTE)
            val s = calendar.get(Calendar.SECOND)
            // On the wallpaper, a soft shadow keeps the hands clear over any picture.
            if (!solid) paint.setShadowLayer(r * 0.04f, 0f, r * 0.015f, 0x40000000)
            paint.color = hands
            hand(canvas, cx, cy, (h + m / 60f) * 30f, r * 0.50f, r * 0.10f, r * 0.075f)
            hand(canvas, cx, cy, (m + s / 60f) * 6f, r * 0.78f, r * 0.10f, r * 0.05f)
            paint.color = sweep
            hand(canvas, cx, cy, s * 6f, r * 0.84f, r * 0.18f, r * 0.018f)
            paint.clearShadowLayer()

            // The middle: a dot in the second hand's color, with the face showing through.
            paint.style = Paint.Style.FILL
            paint.color = sweep
            canvas.drawCircle(cx, cy, r * 0.05f, paint)
            paint.color = if (solid) WidgetStyle.accentFill() else hands
            canvas.drawCircle(cx, cy, r * 0.018f, paint)

            // Said once a minute, not worked out every second.
            val minute = calendar.timeInMillis / 60_000L
            if (minute != described) {
                described = minute
                contentDescription = TimeFormat.format(context, calendar.timeInMillis)
            }
            // Ticks on the second while it's on screen.
            removeCallbacks(second)
            if (showing) postDelayed(second, 1000L - System.currentTimeMillis() % 1000L)
        }

        /** A hand at [degrees] from twelve, [length] out from the middle and [tail] behind it. */
        private fun hand(canvas: Canvas, cx: Float, cy: Float, degrees: Float, length: Float, tail: Float, width: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width
            canvas.save()
            canvas.rotate(degrees, cx, cy)
            canvas.drawLine(cx, cy + tail, cx, cy - length, paint)
            canvas.restore()
        }

        private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )

        private fun withAlpha(color: Int, alpha: Float) = (color and 0xFFFFFF) or ((alpha * 255).toInt() shl 24)
    }

    private companion object {
        /** The room (dp) between the clock's card and its face. */
        const val EDGE = 16
        /** The room (dp) between the card's edges and the dates under the face, at the sides and below. */
        const val TEXT_EDGE = 8
        /** The room (dp) between the face and the dates. */
        const val TEXT_GAP = 6
    }
}
