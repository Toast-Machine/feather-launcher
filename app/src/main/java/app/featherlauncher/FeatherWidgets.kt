package app.featherlauncher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.Calendar
import java.util.Date

/** Feather's own widgets: the clock, and the ones on cards. [ref] is the [HomeItem.ref] that says which; [about] says what it's for. */
enum class FeatherKind(val ref: String, val label: String, val w: Int, val h: Int, val about: String) {
    CLOCK("", "Clock", 4, 2, "The time, the date and what's coming up"),
    AGENDA("agenda", "Agenda", 4, 2, "Today's events"),
    WEATHER("weather", "Weather", 4, 2, "The weather now and over the next hours"),
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
 * A Feather widget on a card: the frosted surface of the search bar, rounded, with its text color, so it
 * reads the same on any part of the wallpaper. Works its contents out again every minute and whenever [refresh] is called.
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
        // The frosted surface, inset so cards side by side or one above another have a gap between them.
        background = android.graphics.drawable.InsetDrawable(WidgetStyle.surfaceBackground(context, Ui.RADIUS_XL), dp(GAP))
        foreground = android.graphics.drawable.InsetDrawable(Ui.focusRing(context, Ui.RADIUS_XL, WidgetStyle.onSurface()), dp(GAP))
        setPadding(dp(GAP + 16), dp(GAP + 14), dp(GAP + 16), dp(GAP + 14))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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

    /** Small type at the top of a card: "Today", "Zmanim". */
    protected fun heading(text: String, end: String? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(WidgetStyle.cardText(context, 13f, WidgetStyle.label, secondary = true).apply { this.text = text },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (end != null) addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, secondary = true).apply { this.text = end })
    }

    /** A line with something on the left and a time on the right. */
    protected fun row(left: String, right: String, strong: Boolean = false) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(7), 0, 0)
        val face = if (strong) WidgetStyle.title else WidgetStyle.body
        addView(WidgetStyle.cardText(context, 15f, face).apply { text = left },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
        addView(WidgetStyle.cardText(context, 15f, face, secondary = !strong).apply { text = right })
    }

    protected fun quiet(text: String) = WidgetStyle.cardText(context, 15f, WidgetStyle.body, secondary = true).apply {
        this.text = text
        maxLines = 2
        setPadding(0, dp(7), 0, 0)
    }

    protected fun time(t: Long, zone: java.util.TimeZone? = null): String =
        android.text.format.DateFormat.getTimeFormat(context).apply { zone?.let { timeZone = it } }.format(Date(t))

    protected fun dp(v: Int) = Ui.dp(context, v)

    companion object {
        /** Half the space between two cards. */
        private const val GAP = 5

        /** The view for a Feather widget that sits on a card, or null for the clock and At a glance. */
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

/** Today's events, then tomorrow's first one once today's are over. */
@SuppressLint("ViewConstructor")
class AgendaWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    var onEvent: ((ClockInfo.Event) -> Unit)? = null
    var onCalendar: (() -> Unit)? = null
    /** Without calendar access: where to allow it. */
    var onAllow: (() -> Unit)? = null

    override fun refresh() {
        content.removeAllViews()
        setOnClickListener { onCalendar?.invoke() }
        val now = System.currentTimeMillis()
        val info = ClockInfo(context)
        val midnight = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        content.addView(heading("Today", android.text.format.DateFormat.format("EEE, MMM d", now).toString()))
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            content.addView(quiet("Tap to allow calendar access and see your events here"))
            setOnClickListener { onAllow?.invoke() }
            return
        }
        val today = info.events(now, midnight, 4)
        today.forEach { e ->
            content.addView(row(e.title, if (e.allDay) "All day" else time(e.begin), strong = !e.allDay && e.begin - now < HOUR).apply {
                setOnClickListener { onEvent?.invoke(e) }
            })
        }
        if (today.isEmpty()) {
            content.addView(quiet("Nothing more today"))
            info.events(midnight, midnight + DAY, 1).firstOrNull()?.let { e ->
                content.addView(row("Tomorrow · ${e.title}", if (e.allDay) "All day" else time(e.begin)).apply {
                    setOnClickListener { onEvent?.invoke(e) }
                })
            }
        }
    }

    private companion object {
        const val HOUR = 60 * 60_000L
        const val DAY = 24 * HOUR
    }
}

/** The weather now, large, and the coming hours under it. */
@SuppressLint("ViewConstructor")
class WeatherWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    override fun refresh() {
        content.removeAllViews()
        val place = ClockInfo(context).place()
        if (place == null) {
            content.addView(heading("Weather"))
            content.addView(quiet("Choose a location in Settings, under Calendar and times."))
            return
        }
        val w = Weather.at(place) { if (isAttachedToWindow) refresh() }
        content.addView(heading(place.name, w?.let { "H ${it.high}°  L ${it.low}°" }))
        if (w == null) {
            content.addView(quiet("Loading…"))
            return
        }
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(ImageView(context).apply { setImageDrawable(WeatherIcon(w.sky, w.night, WidgetStyle.onSurface())) },
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
            addView(WidgetStyle.cardText(context, 40f, WidgetStyle.display).apply { text = "${w.temperature}°" })
            addView(WidgetStyle.cardText(context, 15f, WidgetStyle.body, secondary = true).apply { text = w.description },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        })
        // The next hours, evenly across the card.
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            w.hours.filterIndexed { i, _ -> i % 2 == 0 }.take(5).forEach { h ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(WidgetStyle.cardText(context, 12f, WidgetStyle.body, secondary = true).apply {
                        gravity = Gravity.CENTER
                        text = android.text.format.DateFormat.format(if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h a", h.time).toString()
                    })
                    addView(ImageView(context).apply { setImageDrawable(WeatherIcon(h.sky, h.night, WidgetStyle.onSurface())) },
                        LinearLayout.LayoutParams(dp(20), dp(20)).apply { topMargin = dp(4); bottomMargin = dp(4) })
                    addView(WidgetStyle.cardText(context, 14f, WidgetStyle.label).apply {
                        gravity = Gravity.CENTER
                        text = "${h.temperature}°"
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        })
    }
}

/** What's playing in any app, with play and pause, and skipping. Needs notification access. */
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
        if (!NotificationService.isEnabled(context)) {
            content.addView(heading("Now playing"))
            content.addView(quiet("Allow notification access to see what's playing."))
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
        content.gravity = Gravity.CENTER_VERTICAL
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The song's artwork, else the app's icon, else a note.
            val art = meta?.let {
                it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            }
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = Ui.rounded(context, (WidgetStyle.onSurface() and 0xFFFFFF) or 0x1A000000, 10)
                when {
                    art != null -> setImageBitmap(art)
                    c != null -> runCatching { setImageDrawable(context.packageManager.getApplicationIcon(c.packageName)) }
                    else -> {
                        setImageResource(R.drawable.ic_media_music)
                        imageTintList = android.content.res.ColorStateList.valueOf(WidgetStyle.onSurfaceSecondary())
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                    }
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(WidgetStyle.cardText(context, 15f, WidgetStyle.title).apply {
                    text = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Nothing playing"
                })
                val by = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    ?: c?.packageName?.let { pkg -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
                if (by != null) addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, secondary = true).apply {
                    text = by
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (c != null) {
                addView(button(R.drawable.ic_previous) { c.transportControls.skipToPrevious() })
                addView(button(if (playing) R.drawable.ic_pause else R.drawable.ic_play) {
                    if (playing) c.transportControls.pause() else c.transportControls.play()
                })
                addView(button(R.drawable.ic_next) { c.transportControls.skipToNext() })
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** A round button the keypad can reach too. */
    private fun button(icon: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(WidgetStyle.onSurface())
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = Ui.pressable(context, Ui.RADIUS_PILL)
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
    }
}

/** The time in another city, and how far ahead or behind it is. */
@SuppressLint("ViewConstructor")
class WorldClockWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    override fun refresh() {
        content.removeAllViews()
        content.gravity = Gravity.CENTER_VERTICAL
        val place = Prefs(context).widgetPlace(item.id)
        if (place == null) {
            content.addView(heading("World clock"))
            content.addView(quiet("Hold to choose a city"))
            return
        }
        val now = System.currentTimeMillis()
        content.addView(heading(place.name))
        content.addView(WidgetStyle.cardText(context, 30f, WidgetStyle.display).apply {
            text = time(now, place.zone)
            setPadding(0, dp(4), 0, dp(2))
        })
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
            hours % 1.0 == 0.0 -> " · ${if (hours > 0) "+" else ""}${hours.toInt()}h"
            else -> " · ${if (hours > 0) "+" else ""}$hours" + "h"
        }
        content.addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, secondary = true).apply { text = day + offset })
    }
}

/** Days (then hours) until something: the next Shabbos, the next Yom Tov, or a date you choose. */
@SuppressLint("ViewConstructor")
class CountdownWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    /** What it counts to, and what to call it. */
    class Target(val title: String, val time: Long)

    override fun refresh() {
        content.removeAllViews()
        content.gravity = Gravity.CENTER_VERTICAL
        val target = target(context, Prefs(context).countdown(item.id))
        if (target == null) {
            content.addView(heading("Countdown"))
            content.addView(quiet("Hold to choose what to count to"))
            return
        }
        val left = target.time - System.currentTimeMillis()
        val (number, unit) = when {
            left <= 0 -> "Now" to ""
            left >= 2 * DAY -> "${(left + DAY / 2) / DAY}" to " days"
            left >= 2 * HOUR -> "${left / HOUR}" to " hours"
            else -> "${(left / MINUTE).coerceAtLeast(1)}" to " min"
        }
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(WidgetStyle.cardText(context, 30f, WidgetStyle.display).apply { text = number })
            addView(WidgetStyle.cardText(context, 15f, WidgetStyle.label).apply { text = unit; setPadding(0, 0, 0, dp(4)) })
        })
        content.addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, secondary = true).apply {
            text = if (left <= 0) target.title else "until ${target.title}"
            setPadding(0, dp(2), 0, 0)
        })
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

/** The day's coming zmanim, the next one picked out. */
@SuppressLint("ViewConstructor")
class ZmanimWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    var onOpen: ((ClockInfo.Place) -> Unit)? = null

    override fun refresh() {
        content.removeAllViews()
        val info = ClockInfo(context)
        val place = info.place()
        val zmanim = Prefs(context).dailyZmanim
        content.addView(heading(if (zmanim) "Zmanim" else "Sunrise and sunset", place?.name))
        if (place == null) {
            content.addView(quiet("Choose a location in Settings, under Calendar and times."))
            return
        }
        setOnClickListener { onOpen?.invoke(place) }
        val now = System.currentTimeMillis()
        val coming = (info.zmanim(now, place) + info.zmanim(now + 86_400_000L, place)).filter { it.time > now }.take(5)
        coming.forEachIndexed { i, z -> content.addView(row(z.name, time(z.time, place.zone), strong = i == 0)) }
    }
}
