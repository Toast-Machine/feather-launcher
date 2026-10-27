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
import org.json.JSONObject
import java.util.Calendar
import java.util.Date

/** Feather's own widgets: the clock, and the ones on cards. [ref] is the [HomeItem.ref] that says which; [about] says what it's for. */
enum class FeatherKind(val ref: String, val label: String, val w: Int, val h: Int, val about: String) {
    CLOCK("", "Clock", 4, 2, "The time, the date and what's coming up"),
    AGENDA("agenda", "Agenda", 4, 2, "Today's events"),
    WEATHER("weather", "Weather", 4, 2, "The weather now, the chance of rain and the next hours"),
    MEDIA("media", "Now playing", 4, 1, "Play, pause and skip whatever's playing"),
    WORLD("world", "World clock", 2, 1, "The time in another city"),
    COUNTDOWN("countdown", "Countdown", 2, 1, "Days until Shabbos, Yom Tov or a date you choose"),
    ZMANIM("zmanim", "Zmanim", 4, 2, "Today's coming zmanim"),
    ;

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
            FeatherKind.WORLD -> WorldClockWidget(context, item)
            FeatherKind.COUNTDOWN -> CountdownWidget(context, item)
            FeatherKind.ZMANIM -> ZmanimWidget(context, item)
            else -> null
        }
    }
}

/**
 * Today, the way a calendar app's widget shows it: the date large, then each event with its
 * calendar's color, its time and its name; tomorrow's first one once today's are done.
 */
@SuppressLint("ViewConstructor")
class AgendaWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    var onEvent: ((ClockInfo.Event) -> Unit)? = null
    var onCalendar: (() -> Unit)? = null
    /** Without calendar access: where to allow it. */
    var onAllow: (() -> Unit)? = null

    override fun refresh() {
        content.removeAllViews()
        content.gravity = Gravity.TOP
        setOnClickListener { onCalendar?.invoke() }
        val now = System.currentTimeMillis()
        // The date: the day of the month large in the accent color, the weekday and month beside it.
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(WidgetStyle.cardText(context, 34f, WidgetStyle.display, WidgetStyle.accent()).apply {
                text = android.text.format.DateFormat.format("d", now).toString()
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(WidgetStyle.cardText(context, 15f, WidgetStyle.title).apply { text = android.text.format.DateFormat.format("EEEE", now).toString() })
                addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply {
                    text = android.text.format.DateFormat.format("MMMM", now).toString()
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        })
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            content.addView(gapAbove(quiet("Tap to allow calendar access and see your events here"), 14))
            setOnClickListener { onAllow?.invoke() }
            return
        }
        val info = ClockInfo(context)
        val midnight = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = info.events(now, midnight, 4)
        today.forEachIndexed { i, e -> content.addView(gapAbove(event(e, null), if (i == 0) 14 else 8)) }
        if (today.isEmpty()) {
            content.addView(gapAbove(quiet("No more events today"), 14))
            info.events(midnight, midnight + DAY, 1).firstOrNull()?.let { e -> content.addView(gapAbove(event(e, "Tomorrow"), 8)) }
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
        // The next hours, evenly across the card, with their chance of rain when there is one.
        content.addView(gapAbove(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            w.hours.filterIndexed { i, _ -> i % 2 == 0 }.take(5).forEach { h ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(WidgetStyle.cardText(context, 12f, WidgetStyle.body, soft).apply {
                        gravity = Gravity.CENTER
                        text = android.text.format.DateFormat.format(if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h a", h.time).toString()
                    })
                    addView(ImageView(context).apply { setImageDrawable(WeatherArt.icon(h.sky, h.night)) },
                        LinearLayout.LayoutParams(dp(26), dp(26)).apply { topMargin = dp(4); bottomMargin = dp(2) })
                    addView(WidgetStyle.cardText(context, 14f, WidgetStyle.label, white).apply {
                        gravity = Gravity.CENTER
                        text = "${h.temperature}°"
                    })
                    addView(WidgetStyle.cardText(context, 11f, WidgetStyle.label, 0xFFB3E0FF.toInt()).apply {
                        gravity = Gravity.CENTER
                        text = if (h.rain >= 20) "${h.rain}%" else " "
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }, 12))
    }
}

/** What's playing in any app: the artwork, the song, and buttons to go back, play or pause, and skip. */
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
            content.addView(heading(R.drawable.ic_media_music, "Now playing"))
            content.addView(gapAbove(quiet("Allow notification access to see what's playing"), 8))
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
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The artwork, else the app's icon, else a note.
            val art = meta?.let {
                it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            }
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = Ui.rounded(context, WidgetStyle.accentFill(), 16)
                when {
                    art != null -> setImageBitmap(art)
                    c != null -> {
                        runCatching { setImageDrawable(context.packageManager.getApplicationIcon(c.packageName)) }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                    }
                    else -> {
                        setImageResource(R.drawable.ic_media_music)
                        imageTintList = ColorStateList.valueOf(WidgetStyle.onAccentFill())
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setPadding(dp(14), dp(14), dp(14), dp(14))
                    }
                }
            }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(WidgetStyle.cardText(context, 16f, WidgetStyle.title).apply {
                    text = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Nothing playing"
                })
                val by = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    ?: c?.packageName?.let { pkg -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
                    ?: "Music and podcasts show here"
                addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply {
                    text = by
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (c != null) {
                addView(button(R.drawable.ic_previous, filled = false) { c.transportControls.skipToPrevious() })
                addView(button(if (playing) R.drawable.ic_pause else R.drawable.ic_play, filled = true) {
                    if (playing) c.transportControls.pause() else c.transportControls.play()
                })
                addView(button(R.drawable.ic_next, filled = false) { c.transportControls.skipToNext() })
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** A round button the keypad can reach too; play and pause is filled in the accent color. */
    private fun button(icon: Int, filled: Boolean, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(if (filled) WidgetStyle.onAccent() else WidgetStyle.onCard())
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = if (filled) Ui.rounded(context, WidgetStyle.accent(), Ui.RADIUS_PILL) else null
        foreground = Ui.pressable(context, Ui.RADIUS_PILL)
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) }
    }
}

/** The time in another city, large, with whether it's day or night there and how far ahead it is. */
@SuppressLint("ViewConstructor")
class WorldClockWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    override fun refresh() {
        content.removeAllViews()
        content.gravity = Gravity.CENTER_VERTICAL
        val place = Prefs(context).widgetPlace(item.id)
        if (place == null) {
            content.addView(heading(R.drawable.ic_location, "World clock"))
            content.addView(gapAbove(quiet("Hold to choose a city", 14f), 6))
            return
        }
        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance(place.zone).apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        content.addView(heading(if (hour in 6..18) R.drawable.ic_sunrise else R.drawable.ic_moon, place.name))
        content.addView(gapAbove(WidgetStyle.cardText(context, 32f, WidgetStyle.display).apply { text = time(now, place.zone) }, 6))
        val hours = (place.zone.getOffset(now) - java.util.TimeZone.getDefault().getOffset(now)) / 3_600_000.0
        val there = Calendar.getInstance(place.zone).apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
        val here = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
        val day = when {
            there == here -> "Today"
            there == here + 1 || there == 1 && here > 360 -> "Tomorrow"
            else -> "Yesterday"
        }
        val offset = when {
            hours == 0.0 -> ""
            hours % 1.0 == 0.0 -> " · ${if (hours > 0) "+" else ""}${hours.toInt()} hrs"
            else -> " · ${if (hours > 0) "+" else ""}$hours hrs"
        }
        content.addView(gapAbove(WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply { text = day + offset }, 4))
    }
}

/** Days (then hours) until something, the number large in the accent color: the next Shabbos, the next Yom Tov, or a date. */
@SuppressLint("ViewConstructor")
class CountdownWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    /** What it counts to, and what to call it. */
    class Target(val title: String, val time: Long)

    override fun refresh() {
        content.removeAllViews()
        content.gravity = Gravity.CENTER_VERTICAL
        val target = target(context, Prefs(context).countdown(item.id))
        if (target == null) {
            content.addView(heading(R.drawable.ic_hourglass, "Countdown"))
            content.addView(gapAbove(quiet("Hold to choose what to count to", 14f), 6))
            return
        }
        val left = target.time - System.currentTimeMillis()
        val (number, unit) = when {
            left <= 0 -> "Now" to ""
            left >= 2 * DAY -> "${(left + DAY / 2) / DAY}" to "days"
            left >= 2 * HOUR -> "${left / HOUR}" to "hours"
            else -> "${(left / MINUTE).coerceAtLeast(1)}" to "min"
        }
        content.addView(heading(R.drawable.ic_hourglass, target.title))
        content.addView(gapAbove(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(WidgetStyle.cardText(context, 38f, WidgetStyle.display, WidgetStyle.accent()).apply { text = number })
            addView(WidgetStyle.cardText(context, 16f, WidgetStyle.label).apply {
                text = unit
                setPadding(dp(6), 0, 0, dp(6))
            })
        }, 2))
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        /** Saved as "shabbos", "yomtov", or a title and a date. */
        fun target(context: Context, saved: JSONObject?): Target? {
            saved ?: return null
            val now = System.currentTimeMillis()
            return when (saved.optString("kind")) {
                "shabbos" -> nextLighting(context, now, yomTov = false)?.let { Target("Shabbos", it.second) }
                "yomtov" -> nextLighting(context, now, yomTov = true)?.let { (title, time) -> Target(title, time) }
                else -> Target(saved.optString("title", "the day"), saved.optLong("time"))
            }
        }

        /** Candle lighting for the next Shabbos, or for the next Yom Tov with its name. */
        private fun nextLighting(context: Context, now: Long, yomTov: Boolean): Pair<String, Long>? {
            val place = ClockInfo(context).place() ?: return null
            val minutes = Prefs(context).candleMinutes
            val today = JewishCalendar.day(now, place.zone)
            for (ahead in 0..400) {
                val erev = today + ahead
                val next = erev + 1
                val match = if (yomTov) JewishCalendar.isYomTov(next, place.inIsrael) && !JewishCalendar.isYomTov(erev, place.inIsrael)
                    else JewishCalendar.weekday(next) == Calendar.SATURDAY
                if (!match) continue
                // Noon on the eve, in the place's time zone, to find that day's shkiah.
                val noon = erev * 86_400_000L - place.zone.getOffset(erev * 86_400_000L) + 12 * HOUR
                val lighting = Zmanim.sun(noon, place, Zmanim.SUNRISE, false)?.minus(minutes * MINUTE) ?: continue
                if (lighting <= now) continue
                val name = JewishCalendar.holidays(next, place.inIsrael).firstOrNull() ?: "Shabbos"
                return name to lighting
            }
            return null
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
