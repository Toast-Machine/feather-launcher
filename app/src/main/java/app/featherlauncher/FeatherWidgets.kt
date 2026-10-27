package app.featherlauncher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.InsetDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.Calendar
import java.util.Date

/** Feather's own widgets: the clock, and the ones on cards. [ref] is the [HomeItem.ref] that says which; [about] says what it's for. */
enum class FeatherKind(val ref: String, val label: String, val w: Int, val h: Int, val about: String) {
    CLOCK("", "Clock", 4, 2, "The time, the date and what's coming up"),
    AGENDA("agenda", "Agenda", 4, 2, "Today's events"),
    WEATHER("weather", "Weather", 4, 2, "The weather now, the chance of rain and the next hours"),
    MEDIA("media", "Now playing", 4, 1, "Play, pause and skip whatever's playing"),
    ZMANIM("zmanim", "Zmanim", 4, 2, "Today's coming zmanim"),
    ;

    /**
     * How many rows it takes when added, for cells [cellHeightDp] tall. On short screens
     * (keypad phones) Weather takes three, or there's no room for the days under it.
     */
    fun height(cellHeightDp: Float) = if (this == WEATHER && cellHeightDp < 100f) 3 else h

    companion object {
        fun of(item: HomeItem) = entries.firstOrNull { it.ref == item.ref } ?: CLOCK
    }
}

/**
 * A Feather widget on a card, in Android's own widget style ([WidgetStyle]). Works its
 * contents out again every minute and whenever [refresh] is called.
 */
@SuppressLint("ViewConstructor")
abstract class CardWidget(context: Context, val item: HomeItem) : FrameLayout(context) {
    /** Rows that don't fit are left out, the last ones first. */
    protected val content = FitColumn(context).apply { gravity = Gravity.CENTER_VERTICAL }

    private val tick = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) = refresh()
    }

    init {
        isFocusable = true
        setCard(WidgetStyle.cardBackground(context))
        foreground = InsetDrawable(Ui.focusRing(context, WidgetStyle.RADIUS, WidgetStyle.onCard()), dp(GAP))
        setPadding(dp(GAP + 18), dp(GAP + 16), dp(GAP + 18), dp(GAP + 16))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** The card behind everything, inset so cards next to each other have a gap between them. */
    protected fun setCard(drawable: android.graphics.drawable.Drawable) {
        // A new background brings its own padding; the card's stays.
        val (l, t, r, b) = listOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
        background = InsetDrawable(drawable, dp(GAP))
        setPadding(l, t, r, b)
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

    abstract fun refresh()

    /** A small icon in the accent color and a label, at the top of a card: "Zmanim · Monsey". */
    protected fun heading(icon: Int, text: String, end: String? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(WidgetStyle.accent())
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        addView(WidgetStyle.cardText(context, 14f, WidgetStyle.label, WidgetStyle.accent()).apply { this.text = text },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (end != null) addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply { this.text = end })
    }

    protected fun quiet(text: String, sp: Float = 15f) = WidgetStyle.cardText(context, sp, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply {
        this.text = text
        maxLines = 2
    }

    protected fun time(t: Long, zone: java.util.TimeZone? = null): String =
        android.text.format.DateFormat.getTimeFormat(context).apply { zone?.let { timeZone = it } }.format(Date(t))

    protected fun dp(v: Int) = Ui.dp(context, v)

    protected fun gapAbove(v: View, gap: Int) = v.also {
        it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(gap) }
    }

    companion object {
        /** Half the space between two cards. */
        const val GAP = 5

        /** The view for a Feather widget that sits on a card, or null for the clock. */
        fun create(context: Context, item: HomeItem): CardWidget? = when (FeatherKind.of(item)) {
            FeatherKind.AGENDA -> AgendaWidget(context, item)
            FeatherKind.WEATHER -> WeatherWidget(context, item)
            FeatherKind.MEDIA -> MediaWidget(context, item)
            FeatherKind.ZMANIM -> ZmanimWidget(context, item)
            else -> null
        }
    }
}

/**
 * Today, in two columns: on the left the date (the weekday, the day of the month large in the
 * accent color, and the Hebrew date with the Hebrew calendar); on the right the day's events,
 * each with its calendar's color, or tomorrow's first once today's are done.
 */
@SuppressLint("ViewConstructor")
class AgendaWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    var onEvent: ((ClockInfo.Event) -> Unit)? = null
    var onCalendar: (() -> Unit)? = null
    /** Without calendar access: where to allow it. */
    var onAllow: (() -> Unit)? = null

    override fun refresh() {
        content.removeAllViews()
        content.gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { onCalendar?.invoke() }
        val now = System.currentTimeMillis()
        val info = ClockInfo(context)
        val place = info.place()
        val sunset = place?.let { Zmanim.sun(now, it, Zmanim.SUNRISE, false) }
        val second = Prefs(context).secondCalendar.format(now, sunset)

        val events = FitColumn(context).apply { gravity = Gravity.CENTER_VERTICAL }
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(WidgetStyle.cardText(context, 13f, WidgetStyle.title, WidgetStyle.accent()).apply {
                    text = android.text.format.DateFormat.format("EEE", now).toString().uppercase()
                    letterSpacing = 0.08f
                })
                addView(WidgetStyle.cardText(context, 44f, WidgetStyle.display).apply {
                    text = android.text.format.DateFormat.format("d", now).toString()
                    setPadding(0, dp(2), 0, dp(2))
                })
                addView(WidgetStyle.cardText(context, 12f, WidgetStyle.label, WidgetStyle.onCardQuiet()).apply {
                    text = second ?: android.text.format.DateFormat.format("MMM", now).toString()
                })
            }, LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))
            // A thin line between the date and the events.
            addView(View(context).apply { setBackgroundColor((WidgetStyle.onCard() and 0xFFFFFF) or 0x1F000000) },
                LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    marginStart = dp(10); marginEnd = dp(14); topMargin = dp(6); bottomMargin = dp(6)
                })
            addView(events, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            events.addView(WidgetStyle.cardText(context, 15f, WidgetStyle.title).apply { text = "Your events" })
            events.addView(gapAbove(quiet("Tap to allow calendar access", 13f), 4))
            setOnClickListener { onAllow?.invoke() }
            return
        }
        val midnight = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = info.events(now, midnight, 4)
        today.forEachIndexed { i, e -> events.addView(gapAbove(event(e, null), if (i == 0) 0 else 10)) }
        if (today.isEmpty()) {
            events.addView(WidgetStyle.cardText(context, 15f, WidgetStyle.title).apply { text = "Nothing more today" })
            info.events(midnight, midnight + DAY, 1).firstOrNull()?.let { e -> events.addView(gapAbove(event(e, "Tomorrow"), 10)) }
                ?: events.addView(gapAbove(quiet("Enjoy the rest of your day", 13f), 4))
        }
    }

    /** One event: a bar in its calendar's color, then its name over its time. */
    private fun event(e: ClockInfo.Event, prefix: String?) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Ui.pressable(context, Ui.RADIUS_S)
        setOnClickListener { onEvent?.invoke(e) }
        addView(View(context).apply {
            background = Ui.rounded(context, if (e.color != 0) e.color or 0xFF000000.toInt() else WidgetStyle.accent(), 2)
        }, LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(10) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(WidgetStyle.cardText(context, 15f, WidgetStyle.label).apply { text = e.title })
            addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply {
                val hours = if (e.allDay) "All day" else "${time(e.begin)} – ${time(e.end)}"
                text = listOfNotNull(prefix, hours).joinToString(" · ")
                setPadding(0, dp(2), 0, 0)
            })
        })
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}

/**
 * The weather on a card that looks like the sky: blue on a clear day, deep blue at night,
 * gray for clouds, slate for rain. The temperature large with a picture of the weather, today's
 * high and low and chance of rain, and the next hours with theirs.
 */
@SuppressLint("ViewConstructor")
class WeatherWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    /** A short card (a small screen): the top on one line. */
    private var compact = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = h < dp(250)
        if (next != compact) {
            compact = next
            post { refresh() }
        }
    }

    override fun refresh() {
        content.removeAllViews()
        val place = ClockInfo(context).place()
        if (place == null) {
            content.addView(heading(R.drawable.ic_location, "Weather"))
            content.addView(gapAbove(quiet("Choose a location in Settings, under Calendar and times."), 10))
            return
        }
        val w = Weather.at(place) { if (isAttachedToWindow) refresh() }
        if (w == null) {
            content.addView(heading(R.drawable.ic_location, place.name))
            content.addView(gapAbove(quiet("Loading the weather…"), 10))
            return
        }
        setCard(WeatherArt.sky(w.sky, w.night, dp(WidgetStyle.RADIUS).toFloat()))
        val white = WidgetStyle.WHITE
        val soft = 0xD9FFFFFF.toInt()
        content.gravity = Gravity.CENTER_VERTICAL
        // In a short card everything up top fits on one line, leaving room for the days.
        if (compact) {
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply { setImageDrawable(WeatherArt.icon(w.sky, w.night)) },
                    LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
                addView(WidgetStyle.cardText(context, 34f, WidgetStyle.display, white).apply { text = "${w.temperature}°" })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(WidgetStyle.cardText(context, 14f, WidgetStyle.title, white).apply { text = "${place.name} · ${w.description}" })
                    addView(WidgetStyle.cardText(context, 13f, WidgetStyle.label, soft).apply {
                        text = listOfNotNull("↑${w.high}°  ↓${w.low}°", w.rainChance.takeIf { it >= 20 }?.let { "$it% rain" }).joinToString("  ·  ")
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            })
        } else
        // The place, the temperature and the weather on the left; its picture on the right.
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(WidgetStyle.cardText(context, 14f, WidgetStyle.label, white).apply { text = place.name })
                addView(WidgetStyle.cardText(context, 46f, WidgetStyle.display, white).apply {
                    text = "${w.temperature}°"
                    setPadding(0, dp(2), 0, dp(2))
                })
                addView(WidgetStyle.cardText(context, 14f, WidgetStyle.label, white).apply { text = w.description })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(ImageView(context).apply { setImageDrawable(WeatherArt.icon(w.sky, w.night)) },
                    LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.END })
                addView(WidgetStyle.cardText(context, 13f, WidgetStyle.label, white).apply {
                    text = "↑${w.high}°  ↓${w.low}°"
                    gravity = Gravity.END
                    setPadding(0, dp(4), 0, 0)
                })
                if (w.rainChance >= 20) addView(WidgetStyle.cardText(context, 13f, WidgetStyle.label, soft).apply {
                    text = "${w.rainChance}% rain"
                    gravity = Gravity.END
                    setPadding(0, dp(2), 0, 0)
                })
            })
        })
        // The next hours, then the next days, each evenly across the card. When only one fits,
        // the days stay: they say more.
        content.addView(FitColumn.dropFirst(gapAbove(forecast(w.hours.filterIndexed { i, _ -> i % 2 == 0 }.take(5).map { h ->
            Column(android.text.format.DateFormat.format(if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h a", h.time).toString(),
                h.sky, h.night, "${h.temperature}°", null, h.rain)
        }), 12), priority = 2))
        content.addView(FitColumn.dropFirst(gapAbove(forecast(w.days.take(5).mapIndexed { i, d ->
            Column(if (i == 0) "Today" else android.text.format.DateFormat.format("EEE", d.time).toString(),
                d.sky, false, "${d.high}°", "${d.low}°", d.rain)
        }), 12), priority = 1))
    }

    /** One time in a forecast row: a label, the weather's picture, the temperature (a high and a low for days), the chance of rain. */
    private class Column(val label: String, val sky: Sky, val night: Boolean, val temperature: String, val low: String?, val rain: Int)

    private fun forecast(columns: List<Column>) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        columns.forEach { c ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(WidgetStyle.cardText(context, 12f, WidgetStyle.label, SOFT).apply {
                    gravity = Gravity.CENTER
                    text = c.label
                })
                addView(ImageView(context).apply { setImageDrawable(WeatherArt.icon(c.sky, c.night)) },
                    LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(4); bottomMargin = dp(3) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    addView(WidgetStyle.cardText(context, 14f, WidgetStyle.title, WidgetStyle.WHITE).apply { text = c.temperature })
                    if (c.low != null) addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, SOFT).apply {
                        text = c.low
                        setPadding(dp(4), 0, 0, 0)
                    })
                })
                addView(WidgetStyle.cardText(context, 11f, WidgetStyle.label, RAIN).apply {
                    gravity = Gravity.CENTER
                    text = if (c.rain >= 20) "${c.rain}%" else " "
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private companion object {
        const val SOFT = 0xD9FFFFFF.toInt()
        /** A chance of rain, in the color of rain drops. */
        const val RAIN = 0xFFB3E0FF.toInt()
    }
}

/**
 * What's playing in any app, the way Android's own media player shows it: the card takes its
 * color from the artwork, the artwork fills its height, then the song and who it's by, and
 * buttons to go back, play or pause, and skip, as large as the card allows.
 */
@SuppressLint("ViewConstructor")
class MediaWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, NotificationService::class.java)
    private var controller: MediaController? = null
    private val changed = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }

    init {
        // Less room above and below than other cards: a 1-row card is short, and this one is all one row.
        setPadding(dp(GAP + 14), dp(GAP + 12), dp(GAP + 14), dp(GAP + 12))
    }

    override fun onAttachedToWindow() {
        runCatching { sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener) }
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        controller?.unregisterCallback(changed)
        controller = null
        super.onDetachedFromWindow()
    }

    override fun refresh() {
        content.removeAllViews()
        content.gravity = Gravity.CENTER_VERTICAL
        if (!NotificationService.isEnabled(context)) {
            setCard(WidgetStyle.cardBackground(context))
            content.addView(heading(R.drawable.ic_media_music, "Now playing"))
            content.addView(gapAbove(quiet("Allow notification access to see what's playing", 14f), 6))
            return
        }
        // The one playing, else the last one that was.
        val all = runCatching { sessions.getActiveSessions(listener) }.getOrDefault(emptyList())
        val c = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull { it.metadata != null }
        if (c?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(changed)
            controller = c
            c?.registerCallback(changed)
        }
        val meta = c?.metadata
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        val art = meta?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
        // With artwork, the card is a deep shade of its color, with white type; without, the usual card.
        val tint = art?.let(::shadeOf)
        setCard(if (tint != null) Ui.rounded(context, tint, WidgetStyle.RADIUS) else WidgetStyle.cardBackground(context))
        val main = if (tint != null) WidgetStyle.WHITE else WidgetStyle.onCard()
        val quiet = if (tint != null) 0xCCFFFFFF.toInt() else WidgetStyle.onCardQuiet()

        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Square(context, Int.MAX_VALUE).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = Ui.rounded(context, if (tint != null) 0x33FFFFFF else WidgetStyle.accentFill(), 16)
                when {
                    art != null -> setImageBitmap(art)
                    c != null -> {
                        runCatching { setImageDrawable(context.packageManager.getApplicationIcon(c.packageName)) }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                    }
                    else -> {
                        setImageResource(R.drawable.ic_media_music)
                        imageTintList = ColorStateList.valueOf(WidgetStyle.onAccentFill())
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(WidgetStyle.cardText(context, 16f, WidgetStyle.title, main).apply {
                    text = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Nothing playing"
                })
                val by = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    ?: c?.packageName?.let { pkg -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
                    ?: "Music and podcasts show here"
                addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, quiet).apply {
                    text = by
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            if (c != null) {
                val fill = if (tint != null) WidgetStyle.WHITE else WidgetStyle.accent()
                val onFill = tint ?: WidgetStyle.onAccent()
                addView(button(R.drawable.ic_previous, main, null) { c.transportControls.skipToPrevious() })
                addView(button(if (playing) R.drawable.ic_pause else R.drawable.ic_play, onFill, fill) {
                    if (playing) c.transportControls.pause() else c.transportControls.play()
                })
                addView(button(R.drawable.ic_next, main, null) { c.transportControls.skipToNext() })
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** A round button the keypad can reach too, as tall as the card allows up to 44dp; [fill] makes it solid. */
    private fun button(icon: Int, color: Int, fill: Int?, onClick: () -> Unit) = Square(context, dp(44)).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val inset = dp(if (fill != null) 11 else 9)
        setPadding(inset, inset, inset, inset)
        background = fill?.let { Ui.rounded(context, it, Ui.RADIUS_PILL) }
        foreground = Ui.pressable(context, Ui.RADIUS_PILL)
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(2)
        }
    }

    /** A deep, rich shade of the artwork's overall color, dark enough for white type. */
    private fun shadeOf(art: android.graphics.Bitmap): Int {
        val average = runCatching { android.graphics.Bitmap.createScaledBitmap(art, 1, 1, true).getPixel(0, 0) }.getOrDefault(0xFF333333.toInt())
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(average, hsv)
        hsv[1] = hsv[1].coerceIn(0.25f, 0.7f)
        hsv[2] = 0.34f
        return android.graphics.Color.HSVToColor(hsv)
    }

    /** An image as wide as it is tall: as tall as its row lets it be, up to [max] pixels. */
    private class Square(context: Context, private val max: Int) : ImageView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = minOf(MeasureSpec.getSize(heightMeasureSpec), max)
            setMeasuredDimension(size, size)
        }
    }
}

/** The day's coming zmanim: the next one in a highlighted row, then the ones after it. */
@SuppressLint("ViewConstructor")
class ZmanimWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    var onOpen: ((ClockInfo.Place) -> Unit)? = null

    override fun refresh() {
        content.removeAllViews()
        content.gravity = Gravity.TOP
        val info = ClockInfo(context)
        val place = info.place()
        val zmanim = Prefs(context).dailyZmanim
        content.addView(heading(R.drawable.ic_sunrise, if (zmanim) "Zmanim" else "Sunrise and sunset", place?.name))
        if (place == null) {
            content.addView(gapAbove(quiet("Choose a location in Settings, under Calendar and times."), 10))
            return
        }
        setOnClickListener { onOpen?.invoke(place) }
        val now = System.currentTimeMillis()
        val coming = (info.zmanim(now, place) + info.zmanim(now + 86_400_000L, place)).filter { it.time > now }.take(5)
        coming.forEachIndexed { i, z ->
            val next = i == 0
            content.addView(gapAbove(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // The next one stands out in a rounded row of the accent's soft fill.
                if (next) {
                    background = Ui.rounded(context, WidgetStyle.accentFill(), 14)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                } else {
                    setPadding(dp(12), dp(2), dp(12), dp(2))
                }
                val color = if (next) WidgetStyle.onAccentFill() else WidgetStyle.onCard()
                addView(WidgetStyle.cardText(context, 15f, if (next) WidgetStyle.title else WidgetStyle.body, color).apply { text = z.name },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
                addView(WidgetStyle.cardText(context, 15f, if (next) WidgetStyle.title else WidgetStyle.body,
                    if (next) color else WidgetStyle.onCardQuiet()).apply { text = time(z.time, place.zone) })
            }, if (next) 10 else 4))
        }
    }
}
