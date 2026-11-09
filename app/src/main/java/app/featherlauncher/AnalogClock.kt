package app.featherlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The time on a clock face, and nothing else: the card itself is the face, with twelve marks
 * following its rounded edge and no numbers, slim hands and a fine second hand. Solid, a card like
 * the other widgets'; Transparent, a glass card on the wallpaper. With the date turned on in its
 * settings, the weekday at the top (with the parsha, when the second calendar is the Hebrew one)
 * and the date in both calendars at the bottom ([DateLines]), with the marks stopping at the edge of
 * the words and clear room, as wide as a quarter mark, round each letter.
 */
@SuppressLint("ViewConstructor")
class AnalogClockWidget(context: Context, val item: HomeItem) : FrameLayout(context) {
    private val face = Face(context)
    private val weekday = DateLines(context).apply {
        part = DateLines.Part.WEEKDAY
        showDates = false
        showParsha = false
    }
    private val dates = DateLines(context).apply {
        part = DateLines.Part.DATES
        showDates = false
        showParsha = false
    }
    private val lines get() = listOf(weekday, dates)
    var solid = false
        private set

    // Holding anywhere picks it up, as on the other widgets ([WholeHold]).
    private val hold = WholeHold(this)

    override fun dispatchTouchEvent(ev: android.view.MotionEvent) = hold.touch(ev) { super.dispatchTouchEvent(it) }

    override fun performLongClick() = hold.longClick { super.performLongClick() }

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val card = RectF()

    init {
        isFocusable = true
        setWillNotDraw(false)
        // The dates reach past the card's padding, closer to its edges.
        clipToPadding = false
        addView(face)
        addView(weekday)
        addView(dates)
        setSolid(false)
    }

    /** The weekday (and parsha) above the face and the dates under it, or neither. */
    fun setDates(on: Boolean) {
        for (l in lines) l.showDates = on
    }

    /** Solid: a card like the other widgets', with the cards' corners; transparent: a glass card. */
    fun setSolid(solid: Boolean) {
        this.solid = solid
        val gap = dp(CardWidget.GAP)
        setPadding(gap, gap, gap, gap)
        for (l in lines) l.solid = solid
        invalidate()
        face.invalidate()
    }

    /** Draws the hands again (they also move on their own each second while on screen), and the dates. */
    fun refresh() {
        invalidate()
        face.invalidate()
        for (l in lines) l.update()
    }

    /** The card's corners: the cards' (and so the icons'), never rounder than a circle. */
    private val corner get() = minOf(dp(WidgetStyle.radius).toFloat(), minOf(card.width(), card.height()) / 2f)

    /**
     * The face fills the card, the date or not: the weekday and the dates go over the ends of its
     * marks. They reach closer to the card's sides than the marks, so they're cut short as little
     * as can be.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) width else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val side = dp(CardWidget.GAP + TEXT_EDGE)
        for (l in lines) l.measure(MeasureSpec.makeMeasureSpec((width - 2 * side).coerceAtLeast(0), MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
        val room = height - paddingTop - paddingBottom
        val across = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        face.measure(MeasureSpec.makeMeasureSpec(across, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(room.coerceAtLeast(0), MeasureSpec.EXACTLY))
    }

    /**
     * How far in from the face's top the weekday's baseline is, and from its bottom the top of the
     * dates' capitals and numbers: the marks start there (and room round the letters further in),
     * so they stop at the edge of the words, whatever the letters reaching down or up past it. Null
     * without them, for the marks to start the card's padding in.
     */
    private var wordsTop: Float? = null
    private var wordsBottom: Float? = null

    /**
     * The weekday's letters the card's padding below its top, and the dates' the same above its
     * bottom: the same room as the marks have at the sides, as on the other widgets' cards.
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val side = dp(CardWidget.GAP + TEXT_EDGE)
        val pad = dp(PAD).toFloat()
        face.layout(paddingLeft, paddingTop, paddingLeft + face.measuredWidth, paddingTop + face.measuredHeight)
        wordsTop = null
        wordsBottom = null
        val top = weekday.letters()
        if (weekday.isEmpty || top == null) weekday.layout(0, 0, 0, 0) else {
            val y = (face.top + pad - top.first).toInt()
            weekday.layout(side, y, side + weekday.measuredWidth, y + weekday.measuredHeight)
            wordsTop = y + top.second - face.top
        }
        val bottom = dates.letters()
        if (dates.isEmpty || bottom == null) dates.layout(0, 0, 0, 0) else {
            val y = (face.bottom - pad - bottom.second).toInt()
            dates.layout(side, y, side + dates.measuredWidth, y + dates.measuredHeight)
            wordsBottom = face.bottom - (y + bottom.first)
        }
        face.invalidate()
    }

    /** The card: the cards' color, as on the other widgets; on the wallpaper, glass. */
    override fun onDraw(canvas: Canvas) {
        card.set(paddingLeft.toFloat(), paddingTop.toFloat(), (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
        if (card.isEmpty) return
        val corner = corner
        cardPaint.style = Paint.Style.FILL
        if (solid) {
            // Its whole color set each time, opacity too: the glass leaves the paint see-through.
            cardPaint.color = WidgetStyle.card()
            canvas.drawRoundRect(card, corner, corner, cardPaint)
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

    private fun dp(value: Int) = Ui.dp(context, value)

    /** The marks and hands, drawn to the face's size; the second hand moves only while it's on screen. */
    private inner class Face(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        /** Clears the words, and room round them, out of the marks and hands, for the card to show through. */
        private val cut = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeJoin = Paint.Join.ROUND
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        }
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
            // The marks' outline: the card's padding in from its sides, and from its top and
            // bottom, or the edge of the words there and the room round them. The hands turn
            // round its middle.
            val pad = dp(PAD).toFloat()
            val halfX = width / 2f - pad
            // Everything else is sized to that outline: the hands and marks as long and thick, for
            // the room inside it, whatever the card's padding. The room round the words is a
            // quarter mark's width, which is sized to the outline in turn, so it's worked out a
            // few times over until it settles.
            var r = 0f
            var marksTop = pad
            var halfY = 0f
            repeat(4) {
                val room = r * QUARTER
                marksTop = wordsTop?.plus(room) ?: pad
                halfY = (height - marksTop - (wordsBottom?.plus(room) ?: pad)) / 2f
                r = minOf(halfX, halfY) / OUTLINE
            }
            if (halfX <= 0f || halfY <= 0f) return
            val cx = width / 2f
            val cy = marksTop + halfY
            // The cards' text colors: the main one for the hands and the quarters, the quieter one for the other marks.
            val hands = if (solid) WidgetStyle.onCard() else WidgetStyle.WHITE
            val quiet = if (solid) WidgetStyle.onCardQuiet() else WidgetStyle.SOFT_WHITE
            val sweep = WidgetStyle.tertiary()
            paint.clearShadowLayer()
            // Drawn on their own layer, for the words' room to be cleared out of it after.
            val words = lines.filter { it.width > 0 && it.height > 0 && !it.isEmpty }
            val layer = if (words.isEmpty()) -1 else canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

            // Twelve marks, the quarters bolder and in the main color; no numbers. Each points at
            // the middle, from that outline, so their outer ends run along the straight sides and
            // around the corners as the card does, in to a circle round the middle, so their inner
            // ends make a round dial whatever the card's shape.
            paint.style = Paint.Style.STROKE
            val inner = (corner - pad).coerceIn(0f, minOf(halfX, halfY))
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
                paint.strokeWidth = r * (if (quarter) QUARTER else 0.028f)
                paint.color = if (quarter) hands else quiet
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
            paint.color = if (solid) WidgetStyle.card() else hands
            canvas.drawCircle(cx, cy, r * 0.018f, paint)

            // Round the words, the card shows through, as wide as a quarter mark: the card's own
            // color on a solid card, the glass and the wallpaper under it on a transparent one.
            if (layer >= 0) {
                cut.strokeWidth = 2 * r * QUARTER
                for (w in words) {
                    canvas.save()
                    canvas.translate((w.left - left).toFloat(), (w.top - top).toFloat())
                    w.cutOut(canvas, cut)
                    canvas.restore()
                }
                canvas.restoreToCount(layer)
            }

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
    }

    private companion object {
        /** The room (dp) inside the card, round the marks and above and below the words, as on the other widgets' cards. */
        const val PAD = 16
        /** The marks' outline, from the middle, as a share of the size the hands and marks are drawn to. */
        const val OUTLINE = 0.88f
        /** The quarter marks' width, as a share of the size the hands and marks are drawn to; the room round the words is as wide. */
        const val QUARTER = 0.045f
        /** The shortest marks' length (the ones at the middle of the card's nearest sides), as a share of the way from the face's middle to its nearest side. */
        const val MARK = 0.14f
        /** The shortest of the marks between the quarters, as a share of the shortest quarter. */
        const val MINOR = 0.6f
        /** The room (dp) between the card's sides and the weekday and dates. */
        const val TEXT_EDGE = 8
    }
}
