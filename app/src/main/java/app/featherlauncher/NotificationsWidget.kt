package app.featherlauncher

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sign

/**
 * The notifications showing now, in the order Android's own shade puts them. Kept up to date
 * by [NotificationService]; everything else reads it and listens for changes.
 */
object ActiveNotifications {
    @Volatile var list: List<StatusBarNotification> = emptyList()
        private set

    private val listeners = mutableSetOf<() -> Unit>()
    /** Whether [list] has come from the service since access was last taken away. */
    private var known = false

    /** Called on the main thread whenever [list] changes. */
    fun listen(listener: () -> Unit) = listeners.add(listener)
    fun unlisten(listener: () -> Unit) = listeners.remove(listener)

    fun update(service: NotificationListenerService, active: Collection<StatusBarNotification>) {
        val ranking = runCatching { service.currentRanking }.getOrNull()
        val r = NotificationListenerService.Ranking()
        val order = ranking?.orderedKeys?.withIndex()?.associate { it.value to it.index }.orEmpty()
        val next = active.filter { sbn ->
            val n = sbn.notification
            // Group summaries repeat their children; minimized ones aren't in the shade either.
            sbn.packageName != service.packageName && n.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                (ranking == null || !ranking.getRanking(sbn.key, r) || r.importance > NotificationManager.IMPORTANCE_MIN) &&
                // One drawn by its app alone (a timer) still has its buttons; it goes by its app's name.
                (Note.title(n).isNotEmpty() || Note.text(n).isNotEmpty() || !n.actions.isNullOrEmpty())
        }.sortedWith(compareBy<StatusBarNotification> { order[it.key] ?: Int.MAX_VALUE }.thenByDescending { it.postTime })
        // Rankings are updated often without anything new to show; a posted one is a new object.
        if (known && next.size == list.size && next.indices.all { next[it] === list[it] }) return
        known = true
        list = next
        changed()
    }

    /** Access was taken away: nothing is known anymore. */
    fun clear() {
        known = false
        list = emptyList()
        changed()
    }

    private fun changed() = listeners.toList().forEach { it() }
}

/** Something in a widget that the keypad's left or right sends away (a notification, cleared). */
interface SwipesAway {
    /** Returns true when it took the key. */
    fun swipeAway(direction: Int): Boolean
}

/** One notification, as the widget and the sheet show it. */
class Note(private val context: Context, val sbn: StatusBarNotification) {
    private val n = sbn.notification
    val key: String = sbn.key
    val title = title(n)
    val text = text(n)
    /** All of the text, for when it's opened up and the sheet: an inbox's lines, a conversation's messages. */
    val longText = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        ?: n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")?.takeIf { it.isNotBlank() }
        ?: messages()?.takeIf { it.isNotBlank() } ?: text
    val app: String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
    }.getOrDefault(sbn.packageName)
    val time = if (n.`when` > 0) n.`when` else sbn.postTime
    val actions: List<Notification.Action> = n.actions.orEmpty().filterNotNull()
    /** A music or video player's: its buttons are pictures (back, play, skip). */
    val media = n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
        n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true
    /** One with buttons, or more text than its line, which opens up in the widget to show them. */
    val expandable = actions.isNotEmpty() || longText != text
    /** Whether it can be cleared: not one an app keeps showing, such as music that's playing. */
    val clearable = sbn.isClearable

    /** A conversation's messages, a line each with who sent it. */
    @Suppress("DEPRECATION")
    private fun messages() = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.mapNotNull { m ->
        val words = (m as? android.os.Bundle)?.getCharSequence("text") ?: return@mapNotNull null
        m.getCharSequence("sender")?.let { "$it: $words" } ?: words.toString()
    }?.joinToString("\n")

    fun icon(): Drawable? = runCatching { context.packageManager.getApplicationIcon(sbn.packageName) }.getOrNull()
    /** The picture an app puts on it: album art, a contact's photo. */
    fun art(): Drawable? = runCatching { n.getLargeIcon()?.loadDrawable(context) }.getOrNull()
    fun actionIcon(a: Notification.Action): Drawable? = runCatching { a.getIcon()?.loadDrawable(context) }.getOrNull()

    /** "3:41 PM" today, "Oct 30" before; nothing for one whose app says its time doesn't matter (a timer). */
    fun whenText(): String = if (!n.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true)) "" else
        if (android.text.format.DateUtils.isToday(time)) android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(time))
        else android.text.format.DateFormat.format("MMM d", time).toString()

    /** Opens what it's about, as tapping it in the shade does, or else its app. */
    fun open() {
        val sent = n.contentIntent?.let { send(context, it) } == true
        if (!sent) context.packageManager.getLaunchIntentForPackage(sbn.packageName)?.let {
            runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        if (n.flags and Notification.FLAG_AUTO_CANCEL != 0) NotificationService.cancel(key)
    }

    /** Clears it, as swiping it away in the shade does. Returns false when it can't be. */
    fun clear(): Boolean = clearable && NotificationService.cancel(key)

    /** Presses one of its buttons. A reply needs typing, which its app is for. */
    fun press(a: Notification.Action) {
        if (!a.remoteInputs.isNullOrEmpty() || a.actionIntent == null) open() else send(context, a.actionIntent)
    }

    /** Whether [a] plays or pauses, the button that stands out on a player. */
    fun isPlayPause(a: Notification.Action) = a.title?.toString()?.let { Regex("play|pause", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true

    companion object {
        fun title(n: Notification) = (n.extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))?.toString()?.trim().orEmpty()

        fun text(n: Notification) = (n.extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim()?.replace('\n', ' ').orEmpty()

        /** Sends an app's [PendingIntent] as the launcher on screen, which may open its activities. */
        fun send(context: Context, intent: PendingIntent): Boolean = runCatching {
            val options = ActivityOptions.makeBasic().apply {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 34) setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()
            intent.send(context, 0, null, null, null, null, options)
        }.isSuccess
    }
}

/**
 * The notifications showing, a line each: its title and text, cut short with … when it's long
 * (on a narrow card, the text under the title). As many lines as fit, spaced evenly down the card.
 * A tap opens its app; one with buttons, or more text than fits, has a › and opens up instead,
 * filling the card with all of its text (scrolling, if it must) and its buttons. When they don't all fit,
 * the heading says how many more, and opens them all in a sheet. Swiping a line to the side,
 * or left or right on the keypad, clears it. Only on a card: a list of words on the wallpaper is hard to read.
 */
@SuppressLint("ViewConstructor")
class NotificationsWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    /** All of them, in a sheet. */
    var onMore: (() -> Unit)? = null
    /** After the card is made again, with new things in it to press. */
    var onRebuilt: (() -> Unit)? = null

    /** The one open to show its buttons, if any. */
    private var expanded: String? = null
    /** The only one there is, closed with its × : it stays a line until another comes or it goes. */
    private var dismissed: String? = null
    /** Where the keypad goes once the line it was on is cleared. */
    private var refocus: String? = null
    private val changed = { refresh() }
    /** While the card is being made again, when the highlight may briefly go elsewhere. */
    private var rebuilding = false
    /** The keypad highlight leaving the card closes the open one. */
    private val focusMoved = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
        if (!rebuilding && expanded != null && now != null && !isInside(now)) collapse()
    }

    private fun isInside(view: View): Boolean = generateSequence(view) { it.parent as? View }.any { it === this }

    init {
        // The lines' highlights reach a little past the card's room for its contents, into its
        // padding; drawn whole, not cut off at the sides.
        clipToPadding = false
        clipChildren = false
        content.clipChildren = false
    }

    /** Narrow: each line's text goes under its title, and the times are left out. */
    private var narrow = false
    /** Not wide enough for the times beside the words. */
    private var timeless = false
    /** So narrow the apps' icons are left out too. */
    private var iconless = false

    override fun sizeChanged(w: Int, h: Int): Boolean {
        val nextNarrow = w < dp(260)
        val nextTimeless = w < dp(320)
        val nextIconless = w < dp(180)
        if (nextNarrow == narrow && nextTimeless == timeless && nextIconless == iconless) return false
        narrow = nextNarrow
        timeless = nextTimeless
        iconless = nextIconless
        return true
    }

    override fun onAttachedToWindow() {
        ActiveNotifications.listen(changed)
        viewTreeObserver.addOnGlobalFocusChangeListener(focusMoved)
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        ActiveNotifications.unlisten(changed)
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusMoved)
        super.onDetachedFromWindow()
    }

    /** Another app, a system dialog, or the screen going off: the open one closes. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) collapse()
    }

    override fun refresh() {
        rebuilding = true
        try {
            rebuild()
        } finally {
            rebuilding = false
        }
    }

    private fun rebuild() {
        if (!NotificationService.isEnabled(context)) {
            expanded = null
            return prompt(R.drawable.ic_notification, "Notifications", "Allow notification access to see your notifications") {
                allow(Setup.NOTIFICATIONS)
            }
        }
        val active = ActiveNotifications.list
        if (active.none { it.key == expanded }) expanded = null
        // The only one there is starts off open, unless it was closed. A second one coming closes
        // it again, so the list shows both.
        val lone = active.singleOrNull()?.key
        if (dismissed != lone) dismissed = null
        if (lone == null && wasLone) expanded = null
        if (lone != null && dismissed == null) expanded = lone
        wasLone = lone != null
        // Opened up, a tap between its buttons does nothing, rather than opening the sheet over it.
        setOnClickListener { if (expanded == null) onMore?.invoke() }
        // Only what's on the card: an app updating a notification often changes none of it. The
        // day is there for the times, which say the date once it isn't today.
        if (same(listOf(narrow, timeless, iconless, expanded, TimeFormat.is24(context), JewishCalendar.day(System.currentTimeMillis(), java.util.TimeZone.getDefault()),
                active.map { sbn -> sbn.notification.let { n -> listOf(sbn.key, if (n.`when` > 0) n.`when` else sbn.postTime, Note.title(n), Note.text(n),
                    n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(), n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.toList(),
                    n.actions?.map { it.title?.toString() }, sbn.isClearable) } }))) return
        // The keypad stays on what it was on, if that's still here.
        val focused = findFocus()?.takeIf { !it.isInTouchMode }?.tag as? String
        val all = active.map { Note(context, it) }
        val open = all.firstOrNull { it.key == expanded }
        content.removeAllViews()
        content.gravity = Gravity.TOP
        content.spread = false
        setCard(WidgetStyle.cardBackground(context))
        if (open != null) {
            content.addView(FitColumn.dropFirst(expandedView(open), -1), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        } else if (all.isEmpty()) {
            // Nothing to show is calm, not a problem: a quiet line in the middle of the card.
            content.addView(heading(R.drawable.ic_notification, "Notifications"))
            content.addView(FitColumn.dropFirst(quiet("No notifications").apply { gravity = Gravity.CENTER }, -1),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            // The lines' highlights reach a little past the heading's edges; their contents line up with it.
            content.addView(FitColumn.dropFirst(lines(all), -1), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                marginStart = -dp(ROW_PAD); marginEnd = -dp(ROW_PAD)
            })
        }
        // One cleared with the keypad hands the highlight to the line that took its place.
        // A line that has opened up, being the only one left, hands it to its buttons.
        fun find(tag: String): View? = findViewWithTag<View>(tag)
            ?: if (tag == open?.key) findViewWithTag<View>("$tag#0") ?: findViewWithTag("$tag#open") else null
        (focused?.let(::find) ?: refocus?.let(::find))?.requestFocusFromTouch()
        refocus = null
        onRebuilt?.invoke()
    }

    /** Whether there was only one last time, open on its own. */
    private var wasLone = false

    /** The only one there is, open on its own: the card's usual look, not something to close. */
    private val resting get() = expanded != null && ActiveNotifications.list.singleOrNull()?.key == expanded

    /** Whether one is open to show its buttons, rather than the list. */
    val isExpanded get() = expanded != null && !resting

    /**
     * Back, or something else taking the user's attention: the open one closes again. Returns
     * false when none is open. The only one there is stays open; its close button closes it.
     */
    fun collapse(): Boolean = if (resting) false else close()

    /** The close button: the open one closes, even the only one there is. */
    private fun close(): Boolean {
        val key = expanded ?: return false
        if (resting) dismissed = key
        val keypad = findFocus()?.isInTouchMode == false
        expanded = null
        refresh()
        if (keypad) findViewWithTag<View>(key)?.requestFocusFromTouch()
        return true
    }

    private fun expand(note: Note, keypad: Boolean) {
        expanded = note.key
        dismissed = null
        refresh()
        // The keypad goes straight to its buttons.
        if (keypad) (findViewWithTag<View>("${note.key}#0") ?: findViewWithTag("${note.key}#open"))?.requestFocusFromTouch()
    }

    /**
     * The heading and a line for each notification that fits. The heading says how many there
     * are; when they don't all fit, it says how many more instead, and opens them all.
     */
    private fun lines(all: List<Note>): Lines {
        val count = WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply { text = all.size.toString() }
        val more = WidgetStyle.cardText(context, 13f, WidgetStyle.label, WidgetStyle.accent()).apply {
            tag = "more"
            gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelative(null, null, context.getDrawable(R.drawable.ic_chevron_right)?.mutate()?.apply {
                setTint(WidgetStyle.accent())
                setBounds(0, 0, dp(16), dp(16))
            }, null)
            compoundDrawablePadding = dp(2)
            background = Ui.pressable(context, Ui.RADIUS_PILL)
            setPadding(dp(8), dp(3), dp(2), dp(3))
            setOnClickListener { onMore?.invoke() }
        }
        val top = heading(R.drawable.ic_notification, "Notifications").apply {
            addView(count)
            addView(more)
        }
        val title = top.getChildAt(1) as TextView
        fun View.shows(visibility: Int) {
            if (this.visibility != visibility) this.visibility = visibility
        }
        return Lines(context, top, dp(ROW_PAD), dp(6)) { hidden, width ->
            if (more.text.toString() != "$hidden more") more.text = "$hidden more"
            val end = if (hidden > 0) more else count
            end.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            // On a narrow card, when they don't both fit: the title goes before how many more, the count before the title.
            val fits = dp(26) + title.paint.measureText(title.text.toString()) + end.measuredWidth <= width
            title.shows(if (fits || hidden == 0) View.VISIBLE else View.INVISIBLE)
            count.shows(if (hidden == 0 && fits) View.VISIBLE else View.GONE)
            more.shows(if (hidden > 0) View.VISIBLE else View.GONE)
        }.apply { all.forEach { addView(Row(it)) } }
    }

    /**
     * A line: the app's icon, then the title and its text, cut short with … when it's long, then
     * when. One with buttons, or text that doesn't fit, has a › after the time, and opens up.
     */
    private inner class Row(val note: Note) : LinearLayout(context), SwipesAway {
        // Narrow, the title has a line to itself and its text goes under it; otherwise the text follows it.
        private val words = if (narrow) WidgetStyle.cardText(context, 15f, WidgetStyle.title).apply { text = note.title.ifEmpty { note.app } }
            else WidgetStyle.cardText(context, 15f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply { text = styled(note.title.ifEmpty { note.app }, note.text) }
        private val below = if (narrow && note.text.isNotEmpty()) WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply {
            text = note.text
            setPadding(0, dp(2), 0, 0)
        } else null
        private val time = WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply {
            text = note.whenText()
            if (timeless) visibility = View.GONE
        }
        private val more = ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(WidgetStyle.onCardQuiet())
        }
        /** Whether a tap opens it up, rather than opening its app. */
        private var opens = note.expandable

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = note.key
            background = Ui.pressable(context, Ui.RADIUS_S)
            // Two lines of words need less room around them to look as roomy.
            val v = dp(if (below != null) ROW_V - 2 else ROW_V)
            setPadding(dp(ROW_PAD), v, dp(ROW_PAD), v)
            setOnClickListener { if (opens) expand(note, !isInTouchMode && isFocused) else note.open() }
            if (!iconless) addView(ImageView(context).apply { setImageDrawable(note.icon()) }, LayoutParams(dp(ICON), dp(ICON)).apply { marginEnd = dp(10) })
            addView(if (below == null) words else LinearLayout(context).apply {
                orientation = VERTICAL
                addView(words)
                addView(below)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            addView(more, LayoutParams(dp(18), dp(18)).apply { marginStart = dp(if (timeless) 4 else 8) })
        }

        // Swiping it to either side clears it, as in the shade. One that can't be cleared still
        // takes the swipe, so it stays put and doesn't turn the page either.
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var swiping = false
        private var velocity: VelocityTracker? = null

        // A tap still goes through the row's own onTouchEvent, which clicks it.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            velocity = (velocity ?: VelocityTracker.obtain()).also { it.addMovement(ev) }
            // Raw positions: the row itself moves under the finger.
            val dx = ev.rawX - downX
            val dy = ev.rawY - downY
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    swiping = false
                    // Sideways is the row's until the finger shows it's going up or down instead;
                    // otherwise the pages would take it first.
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (!swiping) {
                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        swiping = true
                        // No tap, press or hold once it's a swipe.
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                    } else if (abs(dy) > slop) {
                        parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val vx = velocity?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
                    velocity?.recycle()
                    velocity = null
                    if (swiping) {
                        swiping = false
                        if (!note.clearable) return true
                        val flung = abs(vx) > dp(FLING) && sign(vx) == sign(dx)
                        if (ev.actionMasked == MotionEvent.ACTION_UP && (abs(dx) > width / 3 || flung)) {
                            away(if (dx > 0) 1 else -1, keypad = false)
                        } else {
                            animate().translationX(0f).alpha(1f).setDuration(150).start()
                        }
                        return true
                    }
                }
            }
            if (swiping) {
                if (!note.clearable) return true
                translationX = dx
                alpha = 1f - (abs(dx) / width).coerceIn(0f, 0.8f)
                return true
            }
            return super.onTouchEvent(ev)
        }

        override fun swipeAway(direction: Int): Boolean {
            if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) return false
            // Left or right on one that can't be cleared does nothing, rather than leaving the widget.
            if (note.clearable) away(if (direction == View.FOCUS_RIGHT) 1 else -1, keypad = true)
            return true
        }

        /** Slides off to the [side] (1 right, -1 left) and clears it; it slides back if it can't be. */
        private fun away(side: Int, keypad: Boolean) {
            if (keypad) {
                // The highlight goes to the next line, or the one before when it was the last.
                val rows = (parent as ViewGroup).let { p -> (0 until p.childCount).map(p::getChildAt) }
                    .filter { it.visibility == View.VISIBLE && it.isClickable }
                val i = rows.indexOf(this)
                refocus = (rows.getOrNull(i + 1) ?: rows.getOrNull(i - 1))?.tag as? String
            }
            animate().translationX(side * width.toFloat()).alpha(0f).setDuration(180).withEndAction {
                if (!note.clear()) {
                    refocus = null
                    animate().translationX(0f).alpha(1f).setDuration(150).start()
                }
            }.start()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Whether the text fits beside the time and the ›'s room, worked out before laying out,
            // so the row isn't laid out twice. Visibility only changes when the answer does.
            val room = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - (if (iconless) 0 else dp(ICON + 10)) - dp(18 + if (timeless) 4 else 8) -
                (if (timeless) 0 else dp(8) + time.paint.measureText(time.text.toString()).toInt())
            opens = note.expandable || android.text.Layout.getDesiredWidth(words.text, words.paint) > room ||
                below != null && below.paint.measureText(note.text) > room
            // Its room stays when it has none, so the times line up.
            more.visibility = if (opens) View.VISIBLE else View.INVISIBLE
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** The title stands out; the text after it is quieter. */
    private fun styled(title: String, text: String?): CharSequence = SpannableStringBuilder().apply {
        append(title, ForegroundColorSpan(WidgetStyle.onCard()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(TypefaceSpan(WidgetStyle.title), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (!text.isNullOrEmpty()) append("  ").append(text)
    }

    /** One notification filling the card: its app's icon and its title on one line, then its text (which opens it), and its buttons. */
    private fun expandedView(note: Note) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageDrawable(note.icon()) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
            // The icon says which app; the title beside it (the app's name only when it has none),
            // leaving the rest of the card for the text.
            addView(WidgetStyle.cardText(context, 15f, WidgetStyle.title).apply {
                text = note.title.ifEmpty { note.app }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            if (!timeless) addView(WidgetStyle.cardText(context, 13f, WidgetStyle.body, WidgetStyle.onCardQuiet()).apply { text = note.whenText() })
            addView(iconButton(R.drawable.ic_close, WidgetStyle.onCard(), null, 32) { close() }.apply {
                tag = "${note.key}#close"
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(6) })
        })
        // All of its text (and its picture, if it has one), which open it; text that doesn't fit scrolls, by touch or keypad.
        var body: TextView? = null
        val open = LinearLayout(context)
        addView(open.apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "${note.key}#open"
            background = Ui.pressable(context, Ui.RADIUS_S)
            setPadding(dp(ROW_PAD), dp(4), dp(ROW_PAD), dp(4))
            setOnClickListener { note.open() }
            note.art()?.let { art ->
                // As tall as the room for it, up to its own size: on a short card a contact's photo shrinks rather than being cut off.
                addView(FitSquare(context, dp(ART)).apply {
                    setImageDrawable(art)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    background = Ui.rounded(context, WidgetStyle.accentFill(), 12)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(12) })
            }
            if (note.longText.isNotEmpty()) addView(ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(dp(8))
                addView(WidgetStyle.cardText(context, 15f, WidgetStyle.body, WidgetStyle.onCardQuiet()).also { body = it }.apply {
                    text = note.longText
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                    setLineSpacing(0f, 1.15f)
                    // A tap on the text opens it too; the scroll view only takes drags.
                    setOnClickListener { note.open() }
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(4); marginStart = -dp(ROW_PAD); marginEnd = -dp(ROW_PAD)
        })
        // Its buttons go onto two rows only if its text still has room for all of itself, or three lines.
        val textRoom = { width: Int ->
            val pad = open.paddingTop + open.paddingBottom
            open.measure(MeasureSpec.makeMeasureSpec(width + 2 * dp(ROW_PAD), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val lines = body?.let { 3 * it.lineHeight + pad } ?: open.measuredHeight
            minOf(open.measuredHeight, lines) + dp(4)
        }
        if (note.actions.isNotEmpty()) addView(ButtonRow(context, dp(6), dp(2), keep = textRoom).apply {
            note.actions.forEachIndexed { i, a ->
                val icon = if (note.media) note.actionIcon(a) else null
                val words = a.title?.toString().orEmpty()
                val button = if (icon != null || words.isBlank()) {
                    val main = note.isPlayPause(a)
                    iconButton(0, if (main) WidgetStyle.onAccent() else WidgetStyle.onCard(), if (main) WidgetStyle.accent() else null, BUTTON) { note.press(a) }.apply {
                        setImageDrawable((icon ?: note.actionIcon(a))?.mutate())
                        contentDescription = words
                    }.also { b -> b.layoutParams = ViewGroup.LayoutParams(dp(BUTTON), dp(BUTTON)) }
                } else {
                    pill(words) { note.press(a) }.also { b -> b.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
                }
                addView(button.apply { tag = "${note.key}#$i" })
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    /** A square picture as tall as its room, up to [max]: it shrinks to fit rather than being cut off. */
    private class FitSquare(context: Context, private val max: Int) : ImageView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val room = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) max else MeasureSpec.getSize(heightMeasureSpec)
            val size = minOf(max, room).coerceAtLeast(0)
            setMeasuredDimension(size, size)
        }
    }

    /** A round button, as on the Now playing card; [fill] makes it solid. */
    private fun iconButton(icon: Int, color: Int, fill: Int?, sizeDp: Int, onClick: () -> Unit) = ImageView(context).apply {
        if (icon != 0) setImageResource(icon)
        imageTintList = ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val inset = dp(sizeDp / 4)
        setPadding(inset, inset, inset, inset)
        background = fill?.let { Ui.rounded(context, it, Ui.RADIUS_PILL) }
        foreground = Ui.pressable(context, Ui.RADIUS_PILL)
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /**
     * A button with words on it, in the accent's soft fill, at least 36dp tall. Words that don't fit
     * get smaller, then go onto more lines ([ButtonText]).
     */
    private fun pill(words: String, onClick: () -> Unit) = ButtonText(context, 13f).apply {
        setTextColor(WidgetStyle.onAccentFill())
        typeface = WidgetStyle.label
        includeFontPadding = false
        text = words
        setPadding(dp(10), dp(4), dp(10), dp(4))
        minHeight = dp(36)
        background = Ui.rounded(context, WidgetStyle.accentFill(), Ui.RADIUS_PILL)
        foreground = Ui.pressable(context, Ui.RADIUS_PILL)
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /**
     * [top] (the heading, [inset] in from the sides, [gap] above the lines) and the lines, as many
     * as fit. [hidden] is told how many don't, and [top]'s width, before [top] is measured, so it can say so. When
     * some don't fit, the room left over is shared out between the lines, so the last sits on the
     * bottom of the card; when they all do, they keep to the top.
     */
    private class Lines(context: Context, private val top: View, private val inset: Int, private val gap: Int,
                        private val hidden: (Int, Int) -> Unit) : ViewGroup(context) {
        init {
            addView(top)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val rows = (1 until childCount).map(::getChildAt)
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val exactly = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            val loose = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            fun heading(left: Int): Int {
                hidden(left, width - 2 * inset)
                top.measure(MeasureSpec.makeMeasureSpec(width - 2 * inset, MeasureSpec.EXACTLY), loose)
                return top.measuredHeight + gap
            }
            rows.forEach { it.measure(exactly, loose) }
            val room = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
            var shown = rows.size
            var used = heading(0) + rows.sumOf { it.measuredHeight }
            if (used > room) {
                // The heading may be a little taller saying how many more.
                used = heading(1)
                shown = 0
                while (shown < rows.size && used + rows[shown].measuredHeight <= room) used += rows[shown++].measuredHeight
                heading(rows.size - shown)
                // Each line a little taller, its contents in the middle of it.
                val spare = room - used
                rows.take(shown).forEachIndexed { i, v ->
                    val extra = spare / shown + if (i < spare % shown) 1 else 0
                    v.measure(exactly, MeasureSpec.makeMeasureSpec(v.measuredHeight + extra, MeasureSpec.EXACTLY))
                }
            }
            rows.forEachIndexed { i, v ->
                val next = if (i < shown) View.VISIBLE else View.GONE
                if (v.visibility != next) v.visibility = next
            }
            setMeasuredDimension(width, if (shown == rows.size) minOf(used, room) else room)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            top.layout(inset, 0, inset + top.measuredWidth, top.measuredHeight)
            var y = top.measuredHeight + gap
            for (i in 1 until childCount) {
                val v = getChildAt(i)
                if (v.visibility != View.VISIBLE) continue
                v.layout(0, y, v.measuredWidth, y + v.measuredHeight)
                y += v.measuredHeight
            }
        }
    }

    private companion object {
        /** Room around a row's contents, inside its highlight. */
        const val ROW_PAD = 8
        /** Room above and below a row's contents, at the least. */
        const val ROW_V = 7
        /** A row's app icon. */
        const val ICON = 20
        /** The picture on one opened up (a contact's photo), at its largest. */
        const val ART = 44
        /** A round button on one opened up, at its largest. */
        const val BUTTON = 44
        /** How fast a swipe (dp a second) clears a row however short it is. */
        const val FLING = 1000
    }
}

/** Every notification showing, in a sheet: what the widget's heading opens when they don't all fit. */
object NotificationsSheet {
    fun show(ctx: Context, p: Palette, overlays: Overlays) {
        val dp = { v: Int -> Ui.dp(ctx, v) }
        val all = ActiveNotifications.list.map { Note(ctx, it) }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.SPACE_S), 0, dp(Ui.SPACE_S), dp(Ui.SPACE_M))
        }
        if (all.isEmpty()) column.addView(TextView(ctx).apply {
            text = "No notifications"
            style(TextStyle.Secondary, p.textSecondary)
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), dp(Ui.SPACE_S))
        })
        all.forEach { note ->
            column.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = dp(52)
                setPadding(dp(Ui.SPACE_L - Ui.SPACE_S), dp(10), dp(Ui.SPACE_L - Ui.SPACE_S), dp(10))
                background = Ui.pressable(ctx, Ui.RADIUS_M)
                isFocusable = true
                setOnClickListener {
                    overlays.dismiss()
                    note.open()
                }
                addView(ImageView(ctx).apply { setImageDrawable(note.icon()) }, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    marginEnd = dp(Ui.SPACE_M); topMargin = dp(2)
                })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(ctx).apply {
                        text = "${note.app} · ${note.whenText()}"
                        style(TextStyle.Secondary, p.textTertiary)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(ctx).apply {
                        text = note.title.ifEmpty { note.app }
                        style(TextStyle.Label, p.text)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    })
                    if (note.longText.isNotEmpty()) addView(TextView(ctx).apply {
                        text = note.longText
                        style(TextStyle.Secondary, p.textSecondary)
                        maxLines = 4
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    })
                    // Ones with buttons that matter show them here too.
                    if (note.expandable) addView(actions(ctx, p, overlays, note), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(Ui.SPACE_S) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        overlays.showSheet("Notifications", scroll, scrollable = scroll)
    }

    private fun actions(ctx: Context, p: Palette, overlays: Overlays, note: Note) = ButtonRow(ctx, Ui.dp(ctx, Ui.SPACE_S), Ui.dp(ctx, 2), fill = false).apply {
        val dp = { v: Int -> Ui.dp(ctx, v) }
        note.actions.forEach { a ->
            val icon = if (note.media) note.actionIcon(a) else null
            val words = a.title?.toString().orEmpty()
            val main = note.media && note.isPlayPause(a)
            val press = {
                overlays.dismiss()
                note.press(a)
            }
            if (icon != null || words.isBlank()) {
                addView(ImageView(ctx).apply {
                    setImageDrawable((icon ?: note.actionIcon(a))?.mutate())
                    imageTintList = ColorStateList.valueOf(if (main) p.onAccent else p.text)
                    contentDescription = words
                    val inset = dp(10)
                    setPadding(inset, inset, inset, inset)
                    background = Ui.rounded(ctx, if (main) p.accent else p.surfaceHigh, Ui.RADIUS_PILL)
                    foreground = Ui.pressable(ctx, Ui.RADIUS_PILL)
                    isFocusable = true
                    setOnClickListener { press() }
                }, ViewGroup.LayoutParams(dp(42), dp(42)))
            } else {
                addView(ButtonText(ctx, 14f).apply {
                    text = words
                    style(TextStyle.Label, p.text)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(Ui.SPACE_M), dp(4), dp(Ui.SPACE_M), dp(4))
                    minHeight = dp(38)
                    background = Ui.rounded(ctx, p.surfaceHigh, Ui.RADIUS_PILL)
                    foreground = Ui.pressable(ctx, Ui.RADIUS_PILL)
                    isFocusable = true
                    setOnClickListener { press() }
                }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }
}

/**
 * A notification's buttons in a row, in the middle: round ones their own size, ones with words
 * sharing the rest (alike, when that gives each enough room). Ones with words too wide for one row
 * go onto two first, when there's room: [keep] says how much of the height it's offered must be
 * left for the rest (the text above), given its width; without it (the sheet, which scrolls) there
 * always is. Only when there isn't (or a row is still too
 * wide, or they're a player's round buttons, which stay together on one row) does the room between
 * them shrink, down to [minGap], and then the buttons themselves, all by the same amount, their
 * words shrinking to fit ([ButtonText]).
 * With [fill] false (the sheet), they keep their own widths and sit at the start instead.
 */
private class ButtonRow(context: Context, private val gap: Int, private val minGap: Int, private val fill: Boolean = true,
                        private val keep: ((Int) -> Int)? = null) : ViewGroup(context) {
    /** Each row's buttons and the room between them, as last measured. */
    private var rows: List<Pair<List<View>, Int>> = emptyList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val shown = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val loose = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        // Each button's own width: a round one's size, or its words' with their padding.
        val round = shown.map { it.layoutParams.width >= 0 }
        val natural = shown.mapIndexed { i, v -> if (round[i]) v.layoutParams.width else { v.measure(loose, loose); v.measuredWidth } }
        val all = shown.indices.toList()
        fun wanted(row: List<Int>) = row.sumOf { natural[it] } + (row.size - 1).coerceAtLeast(0) * gap
        var height = 0
        if (shown.size >= 2 && !round.all { it } && wanted(all) > width) {
            // Too wide for one row: two rows before anything shrinks, split where they're most even, if there's room.
            val split = (1 until shown.size).minBy { maxOf(wanted(all.take(it)), wanted(all.drop(it))) }
            val two = listOf(all.take(split), all.drop(split)).map { row -> row.map(shown::get) to measureRow(row.map(shown::get), row.map(natural::get), width) }
            val tall = two.sumOf { it.second.second } + gap
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val room = when {
                keep == null -> Int.MAX_VALUE
                // Asked how tall it'd like to be, it doesn't know there's room, so it keeps to one row.
                MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED -> 0
                // Given its height, it fills it; the room for the rest was already left when it was offered it.
                MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY -> size
                else -> size - keep(width)
            }
            if (tall <= room) {
                rows = two.map { (row, m) -> row to m.first }
                height = tall
            }
        }
        if (height == 0) {
            val (spacing, tall) = measureRow(shown, natural, width)
            rows = listOf(shown to spacing)
            height = tall
        }
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    /** Measures [row] to fit [width], given each one's own width; returns the room between them and the row's height. */
    private fun measureRow(row: List<View>, natural: List<Int>, width: Int): Pair<Int, Int> {
        fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.EXACTLY)
        val loose = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val round = row.map { it.layoutParams.width >= 0 }
        val gaps = (row.size - 1).coerceAtLeast(0)
        val wanted = natural.sum()
        val spacing: Int
        val widths: List<Int> = when {
            wanted + gaps * gap <= width -> {
                spacing = gap
                // Ones with words fill the rest of the row: alike if each fits so, else each its own width and a share of what's left.
                val words = row.indices.filter { !round[it] }
                val rest = width - gaps * gap - row.indices.filter { round[it] }.sumOf { natural[it] }
                val even = if (words.isEmpty()) 0 else rest / words.size
                val alike = words.all { natural[it] <= even }
                val share = if (words.isEmpty()) 0 else (rest - words.sumOf { natural[it] }) / words.size
                if (!fill) natural else row.indices.map { i -> if (round[i]) natural[i] else if (alike) even else natural[i] + share }
            }
            wanted + gaps * minGap <= width -> {
                // Too wide: the room between them shrinks first...
                spacing = (width - wanted) / gaps
                natural
            }
            else -> {
                // ...then the buttons themselves, the widest first, so a short one ("Reply") keeps its words
                // and round ones of a size shrink alike: none wider than the most that lets them all fit.
                spacing = if (gaps == 0) 0 else minGap
                var left = (width - gaps * minGap).coerceAtLeast(0)
                val sorted = natural.sorted()
                var i = 0
                while (sorted[i] * (sorted.size - i) <= left) left -= sorted[i++]
                val most = left / (sorted.size - i)
                natural.map { minOf(it, most) }
            }
        }
        var height = 0
        row.forEachIndexed { i, v ->
            val w = widths[i]
            val tall = v.layoutParams.height
            if (round[i]) {
                // A round button stays round, its picture shrinking with it.
                val size = minOf(w, tall)
                val inset = size / 4
                if (v.paddingLeft != inset) v.setPadding(inset, inset, inset, inset)
                v.measure(exactly(size), exactly(size))
            } else {
                // One with words grows taller if they need more lines.
                v.measure(exactly(w), if (tall >= 0) exactly(tall) else loose)
            }
            height = maxOf(height, v.measuredHeight)
        }
        return spacing to height
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val heights = rows.map { (row, _) -> row.maxOfOrNull { it.measuredHeight } ?: 0 }
        // The rows in the middle of the room, one under the other.
        var y = ((b - t) - heights.sum() - gap * (rows.size - 1).coerceAtLeast(0)).coerceAtLeast(0) / 2
        rows.forEachIndexed { n, (row, spacing) ->
            val inOrder = if (rtl) row.reversed() else row
            val total = inOrder.sumOf { it.measuredWidth } + spacing * (inOrder.size - 1).coerceAtLeast(0)
            val free = ((r - l) - total).coerceAtLeast(0)
            var x = if (fill) free / 2 else if (rtl) free else 0
            for (v in inOrder) {
                val top = y + (heights[n] - v.measuredHeight) / 2
                v.layout(x, top, x + v.measuredWidth, top + v.measuredHeight)
                x += v.measuredWidth + spacing
            }
            y += heights[n] + gap
        }
    }
}

/**
 * A notification button's words, at [sp] on one line when they fit. When they don't, they get
 * smaller, down to [MIN_SP]; when even that is too wide, they go onto more lines (up to [LINES]),
 * as large as lets them, never breaking a word; the button grows taller to hold them. Only past
 * that are they cut short with ….
 */
private class ButtonText(context: Context, private val sp: Float) : TextView(context) {
    init {
        gravity = Gravity.CENTER
        breakStrategy = android.text.Layout.BREAK_STRATEGY_BALANCED
        hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        ellipsize = android.text.TextUtils.TruncateAt.END
        maxLines = 1
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private val measuring = android.text.TextPaint()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (size, lines) = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) sp to 1
            else fit(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        // Only when they change, since setting them asks for another layout.
        val px = px(size)
        if (textSize != px) setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px)
        if (maxLines != lines) maxLines = lines
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun px(sp: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    /** The size (sp) and lines the words take in [room] px. */
    private fun fit(room: Int): Pair<Float, Int> {
        val words = text?.toString().orEmpty()
        if (room <= 0 || words.isEmpty()) return sp to 1
        measuring.set(paint)
        val sizes = generateSequence(sp) { it - STEP }.takeWhile { it >= MIN_SP - 0.01f }.toList()
        // On one line, smaller...
        for (s in sizes) {
            measuring.textSize = px(s)
            if (measuring.measureText(words) <= room) return s to 1
        }
        // ...then on more lines, as large as lets each word fit whole.
        val parts = words.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (s in sizes) {
            measuring.textSize = px(s)
            if (parts.any { measuring.measureText(it) > room }) continue
            val lines = android.text.StaticLayout.Builder.obtain(words, 0, words.length, measuring, room)
                .setBreakStrategy(breakStrategy).setHyphenationFrequency(hyphenationFrequency)
                .setIncludePad(includeFontPadding).build().lineCount
            if (lines <= LINES) return s to lines
        }
        return MIN_SP to LINES
    }

    private companion object {
        /** The smallest the words get, in sp. */
        const val MIN_SP = 10f
        const val STEP = 0.5f
        /** The most lines the words go onto. */
        const val LINES = 3
    }
}
