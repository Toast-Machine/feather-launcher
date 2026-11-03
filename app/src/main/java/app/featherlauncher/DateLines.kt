package app.featherlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import java.util.TimeZone

/**
 * The day's date in both calendars on one line ("Tue, November 3 · 13 Cheshvan") and this week's
 * parsha, under the analog clock ([AnalogClockWidget]) and on the Date widget ([DateWidget]).
 *
 * Short of room, the months are shortened ("Tue, Nov. 3 · 13 Ches."), then the weekday goes,
 * then the words get smaller; the parsha follows the dates on their line when it fits there and
 * goes on a line of its own when it doesn't. Nothing is cut short unless even the smallest words
 * don't fit. The second date is the one chosen in Calendar and times, and the Jewish day turns
 * over at shkiah, as it does everywhere else.
 *
 * With [fill], the words are as large as the view's height allows (up to [largest] sp); without
 * it, the view is as tall as its lines at [largest] sp, or smaller ones if that's what fits across.
 */
class DateLines(context: Context) : View(context) {
    var showDates = true
        set(value) { field = value; update() }
    var showParsha = true
        set(value) { field = value; update() }
    /** On a card: the card's colors; otherwise white with a shadow, for the wallpaper. */
    var solid = true
        set(value) { field = value; paints(); invalidate() }
    var fill = false
        set(value) { field = value; requestLayout() }
    /** The largest the date's words get, in sp. */
    var largest = 13f
        set(value) { field = value; requestLayout() }

    /** Nothing to show: both off, or the parsha alone in a week of Yom Tov. */
    val isEmpty get() = dates.isEmpty() && parsha == null

    /** The date, longest way of writing it first. */
    private var dates: List<String> = emptyList()
    /** The parsha with "Parshas" before it, for a line of its own, and on its own, to follow the dates. */
    private var parsha: Pair<String, String>? = null

    private class Line(val text: String, val px: Float, val main: Boolean)
    private var lines: List<Line> = emptyList()

    private val mainPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { typeface = WidgetStyle.label }
    private val quietPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { typeface = WidgetStyle.body }

    private val tick = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) = update()
    }

    init {
        paints()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(tick, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIME_TICK)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        })
        update()
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(tick) }
        super.onDetachedFromWindow()
    }

    private fun paints() {
        val main = if (solid) WidgetStyle.onCard() else WidgetStyle.WHITE
        val quiet = if (solid) WidgetStyle.onCardQuiet() else WidgetStyle.SOFT_WHITE
        mainPaint.color = main
        quietPaint.color = quiet
        for (p in listOf(mainPaint, quietPaint)) {
            if (solid) p.clearShadowLayer() else p.setShadowLayer(Ui.dp(context, 6).toFloat(), 0f, Ui.dp(context, 1).toFloat(), 0x59000000)
        }
    }

    /** Works out what to show again (each minute, and when the settings change); lays out again only if it's changed. */
    fun update() {
        val now = System.currentTimeMillis()
        val prefs = Prefs(context)
        val place = ClockInfo(context).place()
        val sunset = place?.let { Zmanim.sun(now, it, Zmanim.SUNRISE, false) }
        val hebrew = prefs.secondCalendar == SecondCalendar.HEBREW && prefs.hebrewInHebrew
        val nextDates = if (!showDates) emptyList() else {
            val second = prefs.secondCalendar
            fun joined(civil: String, short: Boolean) = listOfNotNull(civil, second.format(now, sunset, hebrew, short)?.let { if (hebrew) HebrewText.isolate(it) else it }).joinToString(" · ")
            val month = android.text.format.DateFormat.format("MMMM", now).toString()
            val abbreviated = android.text.format.DateFormat.format("MMM", now).toString().let { if (it == month || it.endsWith(".")) it else "$it." }
            val day = android.text.format.DateFormat.format("d", now)
            val weekday = android.text.format.DateFormat.format("EEE", now)
            listOf(joined("$weekday, $month $day", false), joined("$weekday, $abbreviated $day", true), joined("$abbreviated $day", true)).distinct()
        }
        val nextParsha = if (!showParsha) null else {
            val zone = place?.zone ?: TimeZone.getDefault()
            val today = JewishCalendar.day(now, zone)
            val jewishDay = if (sunset != null && now >= sunset) today + 1 else today
            JewishCalendar.parshaOfWeek(jewishDay, place?.inIsrael == true)?.let {
                if (hebrew) HebrewText.parsha(it).let { he -> HebrewText.isolate("פרשת $he") to HebrewText.isolate(he) }
                else "Parshas $it" to it
            }
        }
        if (nextDates == dates && nextParsha == parsha) return
        dates = nextDates
        parsha = nextParsha
        contentDescription = listOfNotNull(dates.firstOrNull(), parsha?.first).joinToString(", ")
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val room = (width - paddingLeft - paddingRight).toFloat()
        val tall = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        val limited = MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
        var px = sp(largest)
        lines = plan(room, px)
        // Filling the view, the words get smaller until the lines fit its height too.
        if (fill && limited) while (px > sp(SMALLEST) && height(lines) > tall) {
            px -= sp(1f)
            lines = plan(room, px)
        }
        val wanted = height(lines).toInt() + paddingTop + paddingBottom
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wanted, MeasureSpec.getSize(heightMeasureSpec))
            else -> wanted
        }
        setMeasuredDimension(width, h)
    }

    /** The lines for [room] pixels across, the date's words at most [px] pixels. */
    private fun plan(room: Float, px: Float): List<Line> {
        if (room <= 0f) return emptyList()
        val out = ArrayList<Line>()
        // A smaller size only once the shortest way of writing it doesn't fit at the largest.
        val comfortable = minOf(px, sp(COMFORTABLE))
        val date = if (dates.isEmpty()) null else fit(dates, mainPaint, room, px, comfortable)
        val p = parsha
        if (date != null) {
            val together = if (p == null) null else "${date.first} · ${p.second}"
            if (together != null && size(together, mainPaint, room, date.second) >= date.second) {
                out += Line(together, date.second, true)
                return out
            }
            out += Line(date.first, date.second, true)
        }
        if (p != null) {
            // A touch smaller than the date, and quieter; just its name, if "Parshas" doesn't fit.
            val most = if (date != null) date.second * 0.92f else px
            val (text, size) = fit(listOf(p.first, p.second), quietPaint, room, most, minOf(most, comfortable))
            out += Line(text, size, date == null)
        }
        return out
    }

    /** The first of [options] that fits [room] at [comfortable] pixels or larger (up to [most]); else the last, as large as fits. */
    private fun fit(options: List<String>, paint: TextPaint, room: Float, most: Float, comfortable: Float): Pair<String, Float> {
        for (o in options) size(o, paint, room, most).let { if (it >= comfortable) return o to it }
        return options.last() to size(options.last(), paint, room, most).coerceAtLeast(sp(SMALLEST))
    }

    /** The largest [text] can be, up to [most] pixels, and still fit [room] pixels across. */
    private fun size(text: String, paint: TextPaint, room: Float, most: Float): Float {
        paint.textSize = most
        val w = paint.measureText(text)
        return if (w <= room) most else most * room / w * 0.98f
    }

    private fun height(lines: List<Line>) = lines.sumOf { (it.px * LINE).toDouble() }.toFloat() + if (lines.size > 1) sp(GAP) else 0f

    override fun onDraw(canvas: Canvas) {
        val room = (width - paddingLeft - paddingRight).toFloat()
        val tall = height - paddingTop - paddingBottom
        var y = paddingTop + ((tall - height(lines)) / 2f).coerceAtLeast(0f)
        lines.forEachIndexed { i, line ->
            val paint = if (line.main) mainPaint else quietPaint
            paint.textSize = line.px
            val text = TextUtils.ellipsize(line.text, paint, room, TextUtils.TruncateAt.END).toString()
            val fm = paint.fontMetrics
            // Each line in the middle of its own height.
            val baseline = y + (line.px * LINE - (fm.descent - fm.ascent)) / 2f - fm.ascent
            canvas.drawText(text, paddingLeft + (room - paint.measureText(text)) / 2f, baseline, paint)
            y += line.px * LINE + if (i == 0) sp(GAP) else 0f
        }
    }

    private fun sp(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private companion object {
        /** No words smaller than this (sp): past it, the line is cut short instead. */
        const val SMALLEST = 9f
        /** Words this size (sp) or larger are worth keeping the longer way of writing the date for. */
        const val COMFORTABLE = 12f
        /** A line's height, for its text size. */
        const val LINE = 1.25f
        /** The room between the lines (sp). */
        const val GAP = 1f
    }
}
