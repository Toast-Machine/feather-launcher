package app.featherlauncher

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.slice.Slice
import android.app.slice.SliceItem
import android.app.slice.SliceManager
import android.app.slice.SliceSpec
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast

/**
 * A switch on the Quick settings widget, switched right there. The flashlight, Do not disturb and
 * the ringer Android lets any app switch. Wi-Fi, Bluetooth, mobile data, airplane mode and
 * battery saver it doesn't, so these press the Settings app's own switches ([SettingsSlices]),
 * which Android lets the home app use. Whether each is on is read from Android directly.
 */
enum class QuickTile(val label: String, val icon: Int) {
    WIFI("Wi-Fi", R.drawable.ic_set_wifi),
    BLUETOOTH("Bluetooth", R.drawable.ic_set_bluetooth),
    DATA("Mobile data", R.drawable.ic_set_network),
    FLASHLIGHT("Flashlight", R.drawable.ic_flashlight),
    DND("Do not disturb", R.drawable.ic_set_dnd),
    SOUND("Sound", R.drawable.ic_set_sound),
    AIRPLANE("Airplane mode", R.drawable.ic_set_airplane),
    SAVER("Battery saver", R.drawable.ic_set_battery),
    ;

    /** How it looks right now: on or off, and for the ringer, which mode it's in. */
    data class Look(val on: Boolean, val label: String, val icon: Int)

    /** Whether this phone has it at all: no flashlight tile without a flash, and so on. */
    fun available(context: Context): Boolean {
        val pm = context.packageManager
        return when (this) {
            WIFI -> pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
            BLUETOOTH -> pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
            FLASHLIGHT -> Torch.id(context) != null
            DATA -> pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
            DND, SOUND, AIRPLANE, SAVER -> true
        }
    }

    fun look(context: Context): Look = when (this) {
        WIFI -> plain(runCatching { context.getSystemService(WifiManager::class.java).isWifiEnabled }
            .getOrElse { global(context, "wifi_on") in listOf(1, 2) })
        // Its setting, not Android's Bluetooth object: that one can still say on just after it's turned off.
        BLUETOOTH -> plain(global(context, "bluetooth_on") != 0)
        FLASHLIGHT -> plain(Torch.isOn(context))
        DND -> plain(Quiet.isOn(context))
        SOUND -> when (context.getSystemService(AudioManager::class.java).ringerMode) {
            AudioManager.RINGER_MODE_VIBRATE -> Look(false, "Vibrate", R.drawable.ic_vibration)
            AudioManager.RINGER_MODE_SILENT -> Look(false, "Silent", R.drawable.ic_volume_off)
            else -> Look(true, "Ring", R.drawable.ic_set_sound)
        }
        // Each SIM has its own setting (mobile_data1, ...); "mobile_data" alone can be out of date.
        DATA -> plain(runCatching { context.getSystemService(TelephonyManager::class.java).isDataEnabled }
            .getOrElse { global(context, "mobile_data${SubscriptionManager.getDefaultDataSubscriptionId()}") == 1 })
        AIRPLANE -> plain(global(context, Settings.Global.AIRPLANE_MODE_ON) == 1)
        SAVER -> plain(context.getSystemService(PowerManager::class.java).isPowerSaveMode)
    }

    private fun plain(on: Boolean) = Look(on, label, icon)

    /**
     * Switches it. Should Settings' switch not be there (a phone without it, or Feather not
     * being the home app), opens Android's own place for it instead.
     */
    fun press(context: Context) {
        val on = !look(context).on
        when (this) {
            WIFI -> SettingsSlices.WIFI.press(context, on) { open(context, Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS)) }
            BLUETOOTH -> SettingsSlices.BLUETOOTH.press(context, on) { open(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            DATA -> SettingsSlices.DATA.press(context, on) { open(context, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) }
            AIRPLANE -> SettingsSlices.AIRPLANE.press(context, on) { open(context, Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
            SAVER -> SettingsSlices.SAVER.press(context, on) { open(context, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
            FLASHLIGHT -> Torch.toggle(context)
            DND -> Quiet.toggle(context)
            SOUND -> nextRingerMode(context)
        }
    }

    companion object {
        private fun global(context: Context, name: String) = Settings.Global.getInt(context.contentResolver, name, 0)

        /**
         * Ring, then Vibrate, then Silent, then Ring again. Silent needs Do not disturb access
         * (which notification access brings); without it, Vibrate goes back to Ring.
         */
        private fun nextRingerMode(context: Context) {
            val audio = context.getSystemService(AudioManager::class.java)
            when (audio.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> runCatching { audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
                AudioManager.RINGER_MODE_VIBRATE -> if (runCatching { audio.ringerMode = AudioManager.RINGER_MODE_SILENT }.isFailure) {
                    runCatching { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL }
                }
                else -> runCatching { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL }
            }
        }

        /** Opens the first of [intents] the phone has. */
        fun open(context: Context, vararg intents: Intent) {
            for (intent in intents) {
                try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                } catch (_: Exception) {
                }
            }
            Toast.makeText(context, "Nothing on this phone can open that", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * The Settings app's own switches, as "slices": small pieces of Settings that another app can
 * show and press. A switch pressed this way runs inside Settings, with its rights, so it can do
 * what no ordinary app may (turn Wi-Fi or Bluetooth on and off, say). Android lets the default
 * home app use any slice without asking, which is why Feather can.
 *
 * Each is pinned (kept up to date by Settings) only for the moment it's pressed: some, like the
 * Internet one, scan for networks the whole time they're pinned.
 */
enum class SettingsSlices(uri: String, private val pick: (List<SliceItem>) -> SliceItem?) {
    // The Internet panel's: its switches are mobile data's (named "mobile_toggle") and Wi-Fi's.
    WIFI("content://com.android.settings.slices/action/provider_model", { switches ->
        switches.firstOrNull { s -> s.slice.items.none { it.format == SliceItem.FORMAT_TEXT && it.text?.toString() == "mobile_toggle" } }
    }),
    BLUETOOTH("content://android.settings.slices/action/bluetooth", { it.firstOrNull() }),
    DATA("content://com.android.settings.slices/action/mobile_data", { it.firstOrNull() }),
    AIRPLANE("content://android.settings.slices/action/airplane_mode", { it.firstOrNull() }),
    SAVER("content://android.settings.slices/action/battery_saver", { it.firstOrNull() }),
    DND("content://com.android.settings.slices/action/zen_mode_toggle", { it.firstOrNull() }),
    ;

    private val uri: Uri = Uri.parse(uri)

    /**
     * Turns it [on] or off, in the background; [otherwise] runs (on the main thread) if Settings'
     * switch can't be had. Some switches only flip (Wi-Fi's), so a switch already as asked is left.
     */
    fun press(context: Context, on: Boolean, otherwise: () -> Unit) {
        val app = context.applicationContext
        io.execute {
            val done = runCatching {
                val slices = app.getSystemService(SliceManager::class.java)
                var switch = slices.bindSlice(uri, SPECS)?.let { pick(switches(it)) }
                if (switch == null) {
                    // Not loaded yet: pinned, Settings works it out, and it's there a moment later.
                    slices.pinSlice(uri, SPECS)
                    try {
                        for (attempt in 0 until 20) {
                            Thread.sleep(100)
                            switch = slices.bindSlice(uri, SPECS)?.let { pick(switches(it)) }
                            if (switch != null) break
                        }
                    } finally {
                        slices.unpinSlice(uri)
                    }
                }
                val found = switch ?: return@runCatching false
                val isOn = Slice.HINT_SELECTED in found.hints || Slice.HINT_SELECTED in found.slice.hints
                if (isOn != on || !found.action.isImmutable) {
                    found.action.send(app, 0, Intent().putExtra(Slice.EXTRA_TOGGLE_STATE, on))
                }
                true
            }.getOrDefault(false)
            if (!done) main.post(otherwise)
        }
    }

    companion object {
        private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())
        // What Settings builds its slices as (AndroidX's list), under its old and new names.
        private val SPECS = setOf(SliceSpec("androidx.slice.LIST", 1), SliceSpec("androidx.app.slice.LIST", 1))

        /** Every switch in [slice], in order. */
        private fun switches(slice: Slice): List<SliceItem> = slice.items.flatMap {
            when {
                it.format == SliceItem.FORMAT_ACTION && it.subType == Slice.SUBTYPE_TOGGLE -> listOf(it)
                it.format == SliceItem.FORMAT_SLICE || it.format == SliceItem.FORMAT_ACTION -> switches(it.slice)
                else -> emptyList()
            }
        }
    }
}

/** The flashlight: whether it's on (also when switched elsewhere, as from the shade), and switching it. */
object Torch {
    private var on = false
    private var watching = false
    private var found = false
    private var cameraId: String? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    /** The camera with the flash, or null for a phone without one. */
    fun id(context: Context): String? {
        if (found) return cameraId
        found = true
        val camera = context.getSystemService(CameraManager::class.java)
        cameraId = runCatching {
            camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull()
        return cameraId
    }

    fun isOn(context: Context): Boolean {
        watch(context)
        return on
    }

    fun toggle(context: Context) {
        watch(context)
        val id = id(context) ?: return Toast.makeText(context, "This phone has no flashlight", Toast.LENGTH_SHORT).show()
        runCatching { context.getSystemService(CameraManager::class.java).setTorchMode(id, !on) }
    }

    /** [listener] hears about the flashlight going on or off, until [unlisten]. */
    fun listen(context: Context, listener: () -> Unit) {
        watch(context)
        listeners += listener
    }

    fun unlisten(listener: () -> Unit) {
        listeners -= listener
    }

    private fun watch(context: Context) {
        if (watching) return
        watching = true
        id(context)
        // Told at once how it is now, then whenever it changes.
        context.getSystemService(CameraManager::class.java).registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id != cameraId) return
                on = enabled
                listeners.toList().forEach { it() }
            }

            override fun onTorchModeUnavailable(id: String) {
                if (id != cameraId) return
                on = false
                listeners.toList().forEach { it() }
            }
        }, Handler(Looper.getMainLooper()))
    }
}

/** Do not disturb: on whenever anything is held back, by Feather or by the phone's own switch. */
object Quiet {
    fun isOn(context: Context) = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let {
        it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /**
     * Switches it with Do not disturb access, which comes with notification access. Without it,
     * asks for notification access. If Android won't let it be switched (newer Androids let an app
     * turn off only what it turned on), presses Settings' own switch for it instead ([SettingsSlices]).
     */
    fun toggle(context: Context) {
        val was = isOn(context)
        val next = if (was) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
        val nm = context.getSystemService(NotificationManager::class.java)
        val asked = (nm.isNotificationPolicyAccessGranted && runCatching { nm.setInterruptionFilter(next) }.isSuccess) ||
            NotificationService.requestInterruptionFilter(next)
        if (!asked) {
            Toast.makeText(context, "Allow notification access to switch Do not disturb here", Toast.LENGTH_LONG).show()
            (context as? android.app.Activity)?.let(Setup.NOTIFICATIONS.start)
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (isOn(context) != was) return@postDelayed
            SettingsSlices.DND.press(context, !was) {
                QuickTile.open(context, Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS), Intent("android.settings.ZEN_MODE_SETTINGS"))
            }
        }, 1_000)
    }
}

/**
 * Quick settings on the home screen: a round button for each switch ([QuickTile]), filled in the
 * accent color while it's on, as in the shade. As many as fit, in the order set in its settings,
 * where any can be left out. On one row they're icons only; with room, each has its name under it.
 * For phones like Sonim's, which have no quick settings to pull down.
 */
@SuppressLint("ViewConstructor")
class QuickSettingsWidget(context: Context, item: HomeItem) : CardWidget(context, item) {
    override val everyMinute = false

    private val grid = Grid()

    /** How many tiles fit at the widget's size, for its settings to say (0 before it's first laid out). */
    val room get() = grid.room
    private val main = Handler(Looper.getMainLooper())
    private val update = Runnable { refresh() }
    private val late = Runnable { refresh() }
    private fun soon() {
        main.removeCallbacks(update)
        main.post(update)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = soon()
    }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = soon()
    }
    private val torch: () -> Unit = { soon() }

    init {
        // Less room around than other cards: the buttons are what the card is for.
        pad(dp(GAP + 10), dp(GAP + 10), dp(GAP + 10), dp(GAP + 10))
        content.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        // Every tile to start with (as in the widget list), until its settings say otherwise.
        setHidden(emptySet())
    }

    /** The tiles left out, by name; set from its settings. */
    fun setHidden(hidden: Set<String>) {
        val tiles = QuickTile.entries.filter { it.name !in hidden && it.available(context) }
        if (tiles == grid.tiles.map { it.tile }) return
        grid.removeAllViews()
        tiles.forEach { tile -> grid.addView(Tile(tile)) }
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        // Bluetooth's and mobile data's news needs permissions; their settings changing doesn't.
        // All of them, since each SIM has its own mobile data setting (mobile_data1, ...).
        context.contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, observer)
        Torch.listen(context, torch)
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(receiver) }
        context.contentResolver.unregisterContentObserver(observer)
        Torch.unlisten(torch)
        main.removeCallbacks(update)
        main.removeCallbacks(late)
        super.onDetachedFromWindow()
    }

    // Back from a panel or a settings page: what was switched there shows at once.
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) refresh()
    }

    override fun refresh() {
        setCard(WidgetStyle.cardBackground(context))
        grid.tiles.forEach { it.show(it.tile.look(context)) }
    }

    /** One switch: a circle with its icon, and its name under it when there's room. */
    private inner class Tile(val tile: QuickTile) : View(context) {
        var look = QuickTile.Look(false, tile.label, tile.icon)
        /** The size of the button with its keypad highlight, and whether the name shows, as [Grid] works them out. */
        var circle = 0
        var named = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        /** The button's outline: the icon shape from Settings, as the app icons have. */
        private var shape: IconShape? = null
        private var shapeSize = 0
        private val outline = android.graphics.Path()
        /** The keypad highlight, around the button in the same shape (it's drawn here, not as the view's foreground, so it leaves out the name). */
        private var ring: android.graphics.drawable.Drawable? = null
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = WidgetStyle.label
            textAlign = Paint.Align.CENTER
        }

        init {
            isFocusable = true
            // Something there, so the home screen doesn't add a rounded highlight of its own,
            // and no grey wash from Android over the focused tile: its ring is enough.
            foreground = android.graphics.drawable.ColorDrawable(0)
            defaultFocusHighlightEnabled = false
            setOnClickListener {
                tile.press(context)
                soon()
                // Some take a moment (a radio turning on or off), and not all say when they're done.
                main.postDelayed(late, 1_500)
                main.postDelayed(late, 4_000)
            }
        }

        private var icon: android.graphics.drawable.Drawable? = null

        fun show(next: QuickTile.Look) {
            if (next.icon != look.icon || icon == null) icon = context.getDrawable(next.icon)?.mutate()
            look = next
            contentDescription = "${tile.label}, ${if (next.on) "on" else if (tile == QuickTile.SOUND) next.label else "off"}"
            invalidate()
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val r = circle / 2f
            // Room between the button and its highlight, as around an app icon.
            val gap = maxOf(dp(3), (circle * 0.07f).toInt())
            val size = circle - 2 * gap
            val now = AppIconView.shape
            if (now != shape || size != shapeSize) {
                shape = now
                shapeSize = size
                outline.set(now.path(size.toFloat()))
                outline.offset(cx - size / 2f, r - size / 2f)
                ring = Ui.iconFocusRing(context, now)
            }
            fill.color = if (look.on) WidgetStyle.accent() else (WidgetStyle.onCard() and 0xFFFFFF) or 0x24000000
            canvas.drawPath(outline, fill)
            if (isPressed) {
                fill.color = if (look.on) 0x33000000 else (WidgetStyle.onCard() and 0xFFFFFF) or 0x1F000000
                canvas.drawPath(outline, fill)
            }
            ring?.let {
                it.state = drawableState
                it.setBounds((cx - r).toInt(), 0, (cx + r).toInt(), circle)
                it.draw(canvas)
            }
            icon?.let { icon ->
                val half = (size * 0.27f).toInt()
                icon.setBounds((cx - half).toInt(), (r - half).toInt(), (cx + half).toInt(), (r + half).toInt())
                icon.setTint(if (look.on) WidgetStyle.onAccent() else WidgetStyle.onCard())
                icon.draw(canvas)
            }
            if (!named) return
            text.color = WidgetStyle.onCard()
            // As large as fits, down to a size still easy to read, then cut short.
            var textSp = 12f
            do {
                text.textSize = sp(textSp)
                textSp -= 0.5f
            } while (text.measureText(look.label) > width && textSp >= 10f)
            val words = TextUtils.ellipsize(look.label, text, width.toFloat(), TextUtils.TruncateAt.END)
            canvas.drawText(words, 0, words.length, cx, circle + dp(LABEL_GAP) - text.ascent(), text)
        }
    }

    /**
     * The tiles, as many as fit: in rows of equal columns, the rows spread evenly down the card.
     * Names go under the circles only when that costs no tiles.
     */
    private inner class Grid : ViewGroup(context) {
        val tiles get() = (0 until childCount).map { getChildAt(it) as Tile }
        var room = 0
        private var columns = 1
        private var rows = 1

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            // Measured loosely ([FitColumn] checking what fits), it asks for nothing, so it's never left out.
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) return setMeasuredDimension(w, 0)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            val all = tiles
            if (all.isEmpty() || w <= 0 || h <= 0) return
            val name = dp(LABEL_GAP) + (sp(12f) * 1.3f).toInt()

            fun fit(named: Boolean): Pair<Int, Int> {
                val slot = if (named) dp(NAMED_SLOT) else dp(SMALLEST) + dp(GAP_X)
                val cols = ((w + dp(GAP_X)) / slot).coerceAtLeast(1)
                val rowsFit = ((h + dp(GAP_Y)) / (dp(SMALLEST) + (if (named) name else 0) + dp(GAP_Y))).coerceAtLeast(if (named) 0 else 1)
                return cols to rowsFit
            }
            val (namedCols, namedRows) = fit(true)
            val (plainCols, plainRows) = fit(false)
            val named = namedRows > 0 && minOf(all.size, namedCols * namedRows) >= minOf(all.size, plainCols * plainRows)
            columns = if (named) namedCols else plainCols
            room = columns * if (named) namedRows else plainRows
            val shown = minOf(all.size, room)
            // Fewer than a row's worth: spread across the card all the same.
            columns = minOf(columns, shown)
            rows = (shown + columns - 1) / columns
            val slotW = w / columns
            val rowH = (h - dp(GAP_Y) * (rows - 1)) / rows
            val circle = minOf(dp(LARGEST), rowH - (if (named) name else 0), if (named) slotW - dp(4) else slotW - dp(GAP_X))
                .coerceAtLeast(dp(24))
            all.forEachIndexed { i, tile ->
                tile.visibility = if (i < shown) View.VISIBLE else View.GONE
                if (i >= shown) return@forEachIndexed
                tile.circle = circle
                tile.named = named
                val tileW = if (named) slotW else circle
                tile.measure(MeasureSpec.makeMeasureSpec(tileW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(circle + if (named) name else 0, MeasureSpec.EXACTLY))
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val shown = tiles.filter { it.visibility == View.VISIBLE }
            if (shown.isEmpty()) return
            val slotW = (r - l) / columns
            val tileH = shown[0].measuredHeight
            // The room left over goes evenly above, between and below the rows.
            val spare = ((b - t) - tileH * rows).coerceAtLeast(0) / (rows + 1)
            shown.forEachIndexed { i, tile ->
                val row = i / columns
                val inRow = minOf(columns, shown.size - row * columns)
                // A last row that isn't full is centered under the ones above.
                val start = (columns - inRow) * slotW / 2
                val x = start + (i % columns) * slotW + (slotW - tile.measuredWidth) / 2
                val y = spare + row * (tileH + spare)
                tile.layout(x, y, x + tile.measuredWidth, y + tileH)
            }
        }
    }

    private fun sp(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private companion object {
        /** Button sizes with the room for their keypad highlight, dp. */
        const val SMALLEST = 44
        const val LARGEST = 62
        /** The least room across for a circle with its name under it, dp. */
        const val NAMED_SLOT = 68
        const val GAP_X = 2
        const val GAP_Y = 6
        const val LABEL_GAP = 1
    }
}
