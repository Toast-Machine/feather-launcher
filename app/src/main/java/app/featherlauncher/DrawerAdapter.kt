package app.featherlauncher

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.FrameLayout

/** What tapping a contact (or one of its buttons) does. */
enum class ContactAction { OPEN, CALL, TEXT, EMAIL }

/**
 * The app list's rows: section titles, rows of icon tiles (grid), or one app per row (list).
 * Each app tile is focusable on its own, so the keypad moves tile to tile.
 */
class DrawerAdapter(
    private val context: Context,
    private val onContact: (Contacts.Contact, ContactAction) -> Unit = { _, _ -> },
    /** Calls a number typed into search. */
    private val onDial: (String) -> Unit = {},
    private val icon: (AppEntry) -> Bitmap?,
    private val digitFor: (AppEntry) -> Int?,
    /** Locked by an app limit right now: drawn gray with a lock. */
    private val isLocked: (AppEntry) -> Boolean,
    /** Installed and not opened yet: a dot on the icon. */
    private val isNew: (AppEntry) -> Boolean,
    /** Notifications showing: a red badge with the count. */
    private val badge: (AppEntry) -> Int = { 0 },
    private val onClick: (AppEntry, View) -> Unit,
    private val onLongClick: (AppEntry, View) -> Unit,
) : BaseAdapter() {
    sealed class Row {
        /** A section title, with an optional action on the right (like "Show more"). */
        class Title(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null) : Row()
        class Person(val contact: Contacts.Contact) : Row()
        /** A phone number typed into search, with a way to call it. */
        class Dial(val number: String) : Row()
        class Notice(val text: String, val action: String, val onAction: () -> Unit) : Row()
        class Tiles(val apps: List<AppEntry>) : Row()
        class Line(val app: AppEntry) : Row()
        /** Two equal-width search cells, sharing a vertical position in the result list. */
        class Columns(val left: Row?, val right: Row?, val first: Boolean = false, val last: Boolean = false) : Row()
        /** A setting, shortcut or file found by Smart Search. */
        class Result(val found: Found) : Row()
        /** The thin line between search sections. */
        object Divider : Row()
    }

    /** What's been typed in search; matching letters in names are picked out. */
    var query = ""
    var searchColumns = false
    private var hideContactActions: (() -> Unit)? = null

    var palette: Palette? = null
    /** The app being dragged: its tile stays in place but empty, as the gap it will drop into. */
    var dragging: AppEntry? = null
    var rows: List<Row> = emptyList()
        private set
    /** The app that OK/Enter opens: the first search result. */
    var primary: AppEntry? = null
        private set

    fun set(rows: List<Row>, primary: AppEntry?) {
        hideContactActions?.invoke()
        hideContactActions = null
        this.rows = rows
        this.primary = primary
        notifyDataSetChanged()
    }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 8
    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Title -> 0
        is Row.Columns -> 1 // Both kinds of horizontal containers can share the recycling pool.
        is Row.Tiles -> 1
        is Row.Line -> 2
        is Row.Person -> 3
        is Row.Notice -> 4
        is Row.Result -> 5
        Row.Divider -> 6
        is Row.Dial -> 7
    }
    // Rows themselves aren't selectable; the tiles inside them are.
    override fun areAllItemsEnabled() = false
    override fun isEnabled(position: Int) = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val p = palette ?: return View(context)
        return rowView(rows[position], p, convertView)
    }

    private fun rowView(row: Row, p: Palette, convertView: View? = null): View {
        return when (row) {
            is Row.Columns -> LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                listOf(row.left, row.right).forEachIndexed { index, cell ->
                    if (index == 1) addView(FrameLayout(context).apply {
                        setPadding(0, if (row.first) dp(Ui.SPACE_L) else 0, 0, if (row.last) dp(Ui.SPACE_L) else 0)
                        addView(View(context).apply { setBackgroundColor(0x1FFFFFFF) },
                            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
                    val view = if (cell == null) Space(context) else rowView(cell, p)
                    if (cell is Row.Title) {
                        // Match the result icons' inset and keep both headings on one baseline,
                        // including when only one heading has a Show more button.
                        view.setPaddingRelative(dp(Ui.SPACE_S), dp(Ui.SPACE_L), dp(Ui.SPACE_S), dp(Ui.SPACE_XS))
                        view.minimumHeight = dp(48)
                    }
                    addView(view,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            }
            is Row.Title -> LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(Ui.SPACE_L), dp(Ui.SPACE_L), dp(Ui.SPACE_S), dp(Ui.SPACE_XS))
                addView(TextView(context).apply {
                    text = row.text
                    style(TextStyle.Section, p.onWallSecondary)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (row.action != null) {
                    addView(TextView(context).apply {
                        text = row.action
                        style(TextStyle.Label, p.onWall)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(dp(Ui.SPACE_M), dp(6), dp(Ui.SPACE_M), dp(6))
                        background = Ui.pressable(context, Ui.RADIUS_PILL, focusColor = p.onWall)
                        isFocusable = true
                        setOnClickListener { row.onAction?.invoke() }
                    })
                }
            }
            is Row.Person -> person(row.contact, p)
            is Row.Dial -> dial(row.number, p)
            is Row.Notice -> LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(Ui.SPACE_L), dp(Ui.SPACE_S), dp(Ui.SPACE_S), dp(Ui.SPACE_S))
                addView(TextView(context).apply {
                    text = row.text
                    style(TextStyle.Secondary, p.onWallSecondary)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Components.pillButton(context, row.action, p.accent, p.onAccent) { row.onAction() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
            }
            is Row.Tiles -> {
                val view = convertView as? LinearLayout ?: LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                view.removeAllViews()
                for (i in 0 until COLS) {
                    val app = row.apps.getOrNull(i)
                    view.addView(if (app == null) Space(context) else tile(app, p),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                view
            }
            is Row.Line -> line(row.app, p)
            is Row.Result -> result(row.found, p)
            Row.Divider -> View(context).apply {
                setBackgroundColor(0x1FFFFFFF)
                layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            }.let { line ->
                // Inset to line up with the section titles and rows.
                android.widget.FrameLayout(context).apply {
                    setPadding(dp(Ui.SPACE_L), dp(Ui.SPACE_S), dp(Ui.SPACE_L), 0)
                    addView(line, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                }
            }
        }
    }

    private fun tile(app: AppEntry, p: Palette): View {
        val image = ImageView(context).apply {
            setImageBitmap(icon(app))
            IconBadges.apply(this, isLocked(app), isNew(app), 54, badge(app))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            // The notification badge hangs off the icon's corner.
            clipChildren = false
            clipToPadding = false
            foreground = Ui.pressable(context, Ui.RADIUS_L, focusColor = p.onWall)
            isFocusable = true
            alpha = if (app.key == dragging?.key) 0f else 1f
            tag = app
            addView(image, LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(TextView(context).apply {
                text = SmartSearch.highlight(app.label, query, p.onWall)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(p.onWall)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener { onClick(app, image) }
            setOnLongClickListener { onLongClick(app, it); true }
        }
    }

    private fun line(app: AppEntry, p: Palette): View {
        val image = ImageView(context).apply {
            setImageBitmap(icon(app))
            IconBadges.apply(this, isLocked(app), isNew(app), 40, badge(app))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (searchColumns) 64 else 56)
            clipChildren = false
            clipToPadding = false
            setPadding(dp(if (searchColumns) Ui.SPACE_S else Ui.SPACE_M), 0, dp(if (searchColumns) Ui.SPACE_S else Ui.SPACE_L), 0)
            foreground = Ui.pressable(context, Ui.RADIUS_L, focusColor = p.onWall)
            isFocusable = true
            alpha = if (app.key == dragging?.key) 0f else 1f
            tag = app
            addView(image, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(TextView(context).apply {
                text = SmartSearch.highlight(app.label, query, p.onWall)
                style(TextStyle.Body, p.onWall)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(if (searchColumns) Ui.SPACE_S else Ui.SPACE_L) })
            digitFor(app)?.let { d ->
                addView(TextView(context).apply {
                    text = d.toString()
                    style(TextStyle.Label, p.onWallSecondary)
                })
            }
            setOnClickListener { onClick(app, image) }
            setOnLongClickListener { onLongClick(app, it); true }
        }
    }

    /** A setting, shortcut or file: icon, name with matches picked out, and what it is. */
    private fun result(found: Found, p: Palette): View {
        val size = dp(44)
        val icon = ImageView(context).apply {
            when {
                found.bitmap != null -> {
                    setImageBitmap(found.bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                found.drawable != null -> setImageDrawable(found.drawable)
                else -> {
                    setImageResource(found.iconRes)
                    imageTintList = android.content.res.ColorStateList.valueOf(p.onWall)
                    scaleType = ImageView.ScaleType.CENTER
                }
            }
            if (found.drawable == null) {
                background = Ui.rounded(context, 0x1FFFFFFF, Ui.RADIUS_M)
                clipToOutline = true
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L), 0)
            foreground = Ui.pressable(context, Ui.RADIUS_L, focusColor = p.onWall)
            isFocusable = true
            addView(icon, LinearLayout.LayoutParams(size, size))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = SmartSearch.highlight(found.title, query, p.onWall)
                    style(TextStyle.Body, p.onWall)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (found.subtitle != null) {
                    addView(TextView(context).apply {
                        text = found.subtitle
                        style(TextStyle.Secondary, p.onWallSecondary)
                        maxLines = 1
                        setPadding(0, dp(4), 0, 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Ui.SPACE_L) })
            setOnClickListener { found.open() }
        }
    }

    /** The typed number in large type, like a dial pad, with a Call button. The Call key does the same. */
    private fun dial(number: String, p: Palette): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(72)
        setPadding(dp(Ui.SPACE_L), dp(Ui.SPACE_S), dp(Ui.SPACE_L), dp(Ui.SPACE_S))
        foreground = Ui.pressable(context, Ui.RADIUS_L, focusColor = p.onWall)
        isFocusable = true
        tag = number
        addView(TextView(context).apply {
            text = android.telephony.PhoneNumberUtils.formatNumber(number, java.util.Locale.getDefault().country) ?: number
            style(TextStyle.Title, p.onWall)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_L + 4), 0)
            background = Ui.rounded(context, CALL_GREEN, Ui.RADIUS_PILL)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_phone_call)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(Ui.SPACE_S) })
            addView(TextView(context).apply {
                text = "Call"
                style(TextStyle.Label, 0xFFFFFFFF.toInt())
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        setOnClickListener { onDial(number) }
    }

    /** A contact: picture or initial, name and number, and buttons to email (if they have an address), text and call. */
    private fun person(contact: Contacts.Contact, p: Palette): View {
        val size = dp(if (searchColumns) 32 else 44)
        val avatar: View = if (contact.photo != null) {
            ImageView(context).apply {
                setImageURI(contact.photo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.rounded(context, p.folderFill, Ui.RADIUS_M)
                clipToOutline = true
            }
        } else {
            TextView(context).apply {
                text = contact.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
                gravity = Gravity.CENTER
                style(TextStyle.Title, p.onWall)
                background = Ui.rounded(context, 0x1FFFFFFF, Ui.RADIUS_M)
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(Ui.SPACE_L), 0, dp(Ui.SPACE_S), 0)
            minimumHeight = dp(60)
            foreground = Ui.pressable(context, Ui.RADIUS_L, focusColor = p.onWall)
            isFocusable = true
            // The Call key calls whoever is highlighted.
            tag = contact
            addView(avatar, LinearLayout.LayoutParams(size, size))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = SmartSearch.highlight(contact.name, query, p.onWall)
                    style(TextStyle.Body, p.onWall)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = contact.number
                    style(TextStyle.Secondary, p.onWallSecondary)
                    setPadding(0, dp(4), 0, 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(Ui.SPACE_L) })
            // Call stays at the far right, where it's always been; the others line up before it.
            if (!searchColumns) {
                if (contact.email != null) addView(contactButton(R.drawable.ic_cat_mail, "Email ${contact.name}", p) { onContact(contact, ContactAction.EMAIL) })
                addView(contactButton(R.drawable.ic_message, "Text ${contact.name}", p) { onContact(contact, ContactAction.TEXT) })
                addView(contactButton(R.drawable.ic_cat_phone, "Call ${contact.name}", p) { onContact(contact, ContactAction.CALL) })
                setOnClickListener { onContact(contact, ContactAction.OPEN) }
            }
        }
        if (!searchColumns) return content
        // Actions sit over the faded details, leaving the avatar and card reachable.
        content.isFocusable = false
        content.tag = null
        content.foreground = null
        content.setPadding(dp(Ui.SPACE_S), 0, dp(Ui.SPACE_S), 0)
        val details = content.getChildAt(1)
        (details.layoutParams as LinearLayout.LayoutParams).marginStart = dp(Ui.SPACE_S)
        val toolbar = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
                val width = minOf(dp(48), available / childCount.coerceAtLeast(1))
                // Keep the 24dp glyphs while reducing their surrounding padding first.
                // Only scale the glyphs when even padding-free buttons cannot fit.
                val horizontalPadding = ((width - dp(24)) / 2).coerceAtLeast(0)
                for (i in 0 until childCount) {
                    val button = getChildAt(i) as ImageView
                    button.layoutParams.width = width
                    button.scaleType = ImageView.ScaleType.FIT_CENTER
                    if (button.paddingLeft != horizontalPadding || button.paddingTop != dp(12)) {
                        button.setPadding(horizontalPadding, dp(12), horizontalPadding, dp(12))
                    }
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPaddingRelative(0, 0, dp(Ui.SPACE_S), 0)
            addView(contactButton(R.drawable.ic_cat_phone, "Call ${contact.name}", p) { onContact(contact, ContactAction.CALL) })
            addView(contactButton(R.drawable.ic_message, "Text ${contact.name}", p) { onContact(contact, ContactAction.TEXT) })
            if (contact.email != null) addView(contactButton(R.drawable.ic_cat_mail, "Email ${contact.name}", p) { onContact(contact, ContactAction.EMAIL) })
            visibility = View.INVISIBLE
        }
        return object : FrameLayout(context) {
            private fun hide() { toolbar.visibility = View.INVISIBLE; details.alpha = 1f }
            private fun reveal() {
                if (toolbar.visibility == View.VISIBLE) return
                hideContactActions?.invoke()
                details.alpha = 0.28f
                toolbar.visibility = View.VISIBLE
                hideContactActions = { hide() }
            }
            override fun onFocusChanged(gain: Boolean, direction: Int, previous: android.graphics.Rect?) {
                super.onFocusChanged(gain, direction, previous)
                if (gain) reveal()
            }
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
            }
            override fun onDetachedFromWindow() {
                viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
                super.onDetachedFromWindow()
            }
            private val focusListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
                var child = focused
                while (child != null && child !== this) child = child.parent as? View
                if (child == null) hide() else reveal()
            }
            init {
                isFocusable = true
                tag = contact
                contentDescription = "${contact.name}, ${contact.number}"
                minimumHeight = dp(64)
                foreground = Ui.pressable(context, Ui.RADIUS_L, focusColor = p.onWall)
                addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), Gravity.CENTER_VERTICAL).apply {
                    marginStart = dp(Ui.SPACE_S + 32 + Ui.SPACE_S)
                })
                setOnClickListener {
                    if (toolbar.visibility == View.VISIBLE) onContact(contact, ContactAction.OPEN) else reveal()
                }
            }
        }
    }

    private fun contactButton(icon: Int, description: String, p: Palette, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(p.onWall)
        scaleType = ImageView.ScaleType.CENTER
        background = Ui.pressable(context, Ui.RADIUS_PILL, focusColor = p.onWall)
        isFocusable = true
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = Ui.dp(context, value)

    companion object {
        /** The usual green of a call button, so it reads as "call" at a glance. */
        private const val CALL_GREEN = 0xFF2E9E5B.toInt()

        const val COLS = 4
    }
}
