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
 * The built-in clock: the time, the date (with the Hebrew date), and a line with the next
 * zman and the next calendar event. Its layout options live in [ClockOptions].
 */
@SuppressLint("ViewConstructor")
class ClockWidget(context: Context, val item: HomeItem) : LinearLayout(context) {
    private val time = TextClock(context)
    private val date = TextView(context)
    /** This week: the parsha, the Omer, Daf Yomi. */
    private val week = TextView(context)
    /** Up to two things coming up. */
    private val details = LinearLayout(context)
    private val glance = Glance(context)
    private var place: ClockInfo.Place? = null
    private var textSp = 24f

    /** Tapping something coming up: the day's zmanim, the event, the alarms. */
    var onTap: ((Glance.Item, ClockInfo.Place?) -> Unit)? = null

    private val tick = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, intent: android.content.Intent) = refresh()
    }

    init {
        orientation = VERTICAL
        isFocusable = true
        time.apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            includeFontPadding = false
            setShadowLayer(dp(8).toFloat(), 0f, 0f, 0x66000000)
            // Room for the soft shadow, which would otherwise be cut off square at the text's edges
            // (the margins below take the room back, so nothing moves).
            setPadding(dp(SHADOW_ROOM), dp(SHADOW_ROOM), dp(SHADOW_ROOM), dp(SHADOW_ROOM))
        }
        date.apply {
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            includeFontPadding = false
            setShadowLayer(dp(6).toFloat(), 0f, 0f, 0x66000000)
            setPadding(dp(SHADOW_ROOM), dp(SHADOW_ROOM), dp(SHADOW_ROOM), dp(SHADOW_ROOM))
        }
        week.apply {
            setTextColor(0xE6FFFFFF.toInt())
            includeFontPadding = false
            setShadowLayer(dp(6).toFloat(), 0f, 0f, 0x66000000)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        details.orientation = HORIZONTAL
        val room = -dp(SHADOW_ROOM)
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { setMargins(room, room, room, room) })
        addView(date, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(room, dp(6) + 2 * room, room, room)
        })
        addView(week, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        addView(details, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    /** A small icon and text on the details line. */
    private fun detail(icon: Int) = TextView(context).apply {
        setTextColor(0xE6FFFFFF.toInt())
        includeFontPadding = false
        setShadowLayer(dp(6).toFloat(), 0f, 0f, 0x66000000)
        compoundDrawablePadding = dp(5)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setCompoundDrawablesRelativeWithIntrinsicBounds(context.getDrawable(icon)?.mutate()?.apply {
            setTint(0xE6FFFFFF.toInt())
            setBounds(0, 0, dp(14), dp(14))
        }, null, null, null)
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

    /** Works out the date and details again; runs every minute. */
    fun refresh() {
        val lines = glance.lines(System.currentTimeMillis()) { if (isAttachedToWindow) refresh() }
        place = lines.place
        date.text = lines.date
        week.text = lines.week
        week.visibility = if (lines.week != null) VISIBLE else GONE
        details.removeAllViews()
        lines.coming.forEachIndexed { i, item ->
            details.addView(detail(item.icon).apply {
                text = item.text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp * 0.62f)
                setOnClickListener { onTap?.invoke(item, place) }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(14) })
        }
        details.visibility = if (lines.coming.isNotEmpty()) VISIBLE else GONE
    }

    fun apply(options: ClockOptions) {
        val (timeSp, dateSp) = when (options.size) {
            ClockOptions.Size.LARGE -> 84f to 24f
            ClockOptions.Size.MEDIUM -> 64f to 20f
            ClockOptions.Size.SMALL -> 46f to 17f
        }
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, timeSp)
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, dateSp)
        week.setTextSize(TypedValue.COMPLEX_UNIT_SP, dateSp * 0.62f)
        textSp = dateSp
        time.visibility = if (item.ref == AT_A_GLANCE) GONE else VISIBLE
        date.visibility = if (options.showDate) VISIBLE else GONE
        val horizontal = if (options.alignStart) Gravity.START else Gravity.CENTER_HORIZONTAL
        gravity = horizontal or Gravity.CENTER_VERTICAL
        setPadding(dp(if (options.alignStart) 20 else 0), 0, 0, 0)
        (time.layoutParams as LayoutParams).gravity = horizontal
        (date.layoutParams as LayoutParams).gravity = horizontal
        (week.layoutParams as LayoutParams).gravity = horizontal
        (details.layoutParams as LayoutParams).gravity = horizontal
        refresh()
        requestLayout()
    }

    val dateView: TextView get() = date

    companion object {
        /** The [HomeItem.ref] of the At a glance widget: the clock without the time. */
        const val AT_A_GLANCE = "glance"
    }
}

/** Padding around the clock's text so its shadow isn't clipped. */
private const val SHADOW_ROOM = 10

class ClockOptions(val size: Size, val alignStart: Boolean, val showDate: Boolean) {
    enum class Size(val label: String) { SMALL("Small"), MEDIUM("Medium"), LARGE("Large") }
}

