package app.featherlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The time on a clock face, and nothing else: the card itself is the face, with twelve marks
 * following its rounded edge and no numbers, slim hands and a fine second hand. Solid, the card in
 * the accent's soft tone; Transparent, a glass card on the wallpaper. Under the marks, if they're
 * turned on in its settings, the date in both calendars and the parsha ([DateLines]): the marks
 * draw in above them to make room.
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

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val card = RectF()
    /** The card's shading, made again only when its size or color changes, not every second. */
    private var shading: RadialGradient? = null
    private var shadingFor = 0L

    init {
        isFocusable = true
        setWillNotDraw(false)
        // The dates reach past the card's padding, closer to its edges.
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

    /** Solid: the card in the accent's soft tone, with the cards' corners; transparent: a glass card. */
    fun setSolid(solid: Boolean) {
        this.solid = solid
        val gap = dp(CardWidget.GAP)
        setPadding(gap, gap, gap, gap)
        dates.solid = solid
        invalidate()
        face.invalidate()
    }

    /** Draws the hands again (they also move on their own each second while on screen), and the dates. */
    fun refresh() {
        invalidate()
        face.invalidate()
        dates.update()
    }

    private val showsDates get() = !dates.isEmpty

    /** The card's corners: the cards' (and so the icons'), never rounder than a circle. */
    private val corner get() = minOf(dp(WidgetStyle.radius).toFloat(), minOf(card.width(), card.height()) / 2f)

    /**
     * The face fills the card; with the dates, the card above them. The dates reach closer to the
     * card's edges than the marks, so they're cut short as little as can be.
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
        val across = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        face.measure(MeasureSpec.makeMeasureSpec(across, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(room.coerceAtLeast(0), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        face.layout(paddingLeft, paddingTop, paddingLeft + face.measuredWidth, paddingTop + face.measuredHeight)
        if (!showsDates) return dates.layout(0, 0, 0, 0)
        val side = dp(CardWidget.GAP + TEXT_EDGE)
        val under = face.bottom + dp(TEXT_GAP)
        dates.layout(side, under, side + dates.measuredWidth, under + dates.measuredHeight)
    }

    /** The card: the accent's soft tone, a touch lighter toward the middle; on the wallpaper, glass. */
    override fun onDraw(canvas: Canvas) {
        card.set(paddingLeft.toFloat(), paddingTop.toFloat(), (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
        if (card.isEmpty) return
        val corner = corner
        cardPaint.style = Paint.Style.FILL
        if (solid) {
            val tone = WidgetStyle.accentFill()
            val key = (((width * 31 + height) * 31 + face.height).toLong() shl 32) or (tone.toLong() and 0xFFFFFFFFL)
            if (key != shadingFor || shading == null) {
                val r = maxOf(face.width, face.height) / 2f
                val cx = face.left + face.width / 2f
                val cy = face.top + face.height / 2f * 0.85f
                shading = RadialGradient(cx, cy, (r * 1.1f).coerceAtLeast(1f), blend(tone, Color.WHITE, 0.06f), tone, Shader.TileMode.CLAMP)
                shadingFor = key
            }
            cardPaint.shader = shading
            canvas.drawRoundRect(card, corner, corner, cardPaint)
            cardPaint.shader = null
        } else {
            cardPaint.color = 0x24FFFFFF
            canvas.drawRoundRect(card, corner, corner, cardPaint)
            cardPaint.style = Paint.Style.STROKE
            cardPaint.strokeWidth = dp(1).toFloat()
            cardPaint.color = 0x40FFFFFF
            val e = cardPaint.strokeWidth / 2
            canvas.drawRoundRect(card.left + e, card.top + e, card.right - e, card.bottom - e, corner - e, corner - e, cardPaint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shading = null
    }

    private fun dp(value: Int) = Ui.dp(context, value)

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    /** The marks and hands, drawn to the face's size; the second hand moves only while it's on screen. */
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
        /** How far out each mark starts, from twelve on around. */
        private val outs = FloatArray(12)
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
            paint.clearShadowLayer()

            // Twelve marks, the quarters bolder; no numbers. Each points at the middle, from the
            // card's outline drawn in a little, so their outer ends run along the straight sides
            // and around the corners as the card does, in to a circle round the middle, so their
            // inner ends make a round dial whatever the card's shape.
            paint.style = Paint.Style.STROKE
            paint.color = withAlpha(hands, if (solid) 0.55f else 0.75f)
            val margin = r * MARGIN
            val halfX = width / 2f - margin
            val halfY = height / 2f - margin
            val inner = (corner - margin).coerceIn(0f, minOf(halfX, halfY))
            for (i in 0 until 12) {
                val angle = Math.toRadians(i * 30.0)
                outs[i] = reach(Math.sin(angle).toFloat(), -Math.cos(angle).toFloat(), halfX, halfY, inner)
            }
            // The quarters end on one circle; the others on a bigger one, so the shortest of them
            // is shorter than the shortest quarter, as the quarters stand out.
            val dial = minOf(halfX, halfY) - r * MARK
            var nearest = Float.MAX_VALUE
            for (i in 0 until 12) if (i % 3 != 0) nearest = minOf(nearest, outs[i])
            val minorDial = maxOf(dial, nearest - r * MARK * MINOR)
            for (i in 0 until 12) {
                val quarter = i % 3 == 0
                paint.strokeWidth = r * (if (quarter) 0.045f else 0.028f)
                val angle = Math.toRadians(i * 30.0)
                val dx = Math.sin(angle).toFloat()
                val dy = -Math.cos(angle).toFloat()
                val out = outs[i]
                val end = if (quarter) dial else minorDial
                canvas.drawLine(cx + dx * out, cy + dy * out, cx + dx * end, cy + dy * end, paint)
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

        /**
         * How far from the middle, along ([dx], [dy]), the edge of a rounded rectangle [halfX] and
         * [halfY] from the middle to its sides, with corners [corner], is.
         */
        private fun reach(dx: Float, dy: Float, halfX: Float, halfY: Float, corner: Float): Float {
            val x = abs(dx)
            val y = abs(dy)
            val straightX = halfX - corner
            val straightY = halfY - corner
            // Through a straight side, if it meets one.
            if (x > 0f) {
                val t = halfX / x
                if (t * y <= straightY + 0.001f) return t
            }
            if (y > 0f) {
                val t = halfY / y
                if (t * x <= straightX + 0.001f) return t
            }
            // Otherwise through the corner's curve, centered (straightX, straightY).
            val b = x * straightX + y * straightY
            val c = straightX * straightX + straightY * straightY - corner * corner
            return b + sqrt((b * b - c).coerceAtLeast(0f))
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

        private fun withAlpha(color: Int, alpha: Float) = (color and 0xFFFFFF) or ((alpha * 255).toInt() shl 24)
    }

    private companion object {
        /** How far in from the card's edge the marks start, as a share of the way from the face's middle to its nearest side. */
        const val MARGIN = 0.12f
        /** The shortest marks' length (the ones at the middle of the card's nearest sides), in the same share. */
        const val MARK = 0.14f
        /** The shortest of the marks between the quarters, as a share of the shortest quarter. */
        const val MINOR = 0.6f
        /** The room (dp) between the card's edges and the dates under the face, at the sides and below. */
        const val TEXT_EDGE = 8
        /** The room (dp) between the face's marks and the dates. */
        const val TEXT_GAP = 6
    }
}
