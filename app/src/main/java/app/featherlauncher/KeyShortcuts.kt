package app.featherlauncher

import android.content.Context
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/** How a key is pressed for a shortcut. */
enum class Press(val label: String, val verb: String) {
    SHORT("Press", "Press"),
    LONG("Hold", "Hold"),
    DOUBLE("Double press", "Double press"),
}

/** Something a shortcut can do. [needs] says what has to be picked to finish setting it up. */
enum class ActionType(val label: String, val icon: Int, val needs: Needs = Needs.NOTHING) {
    APP("Open an app", R.drawable.ic_app_list, Needs.APP),
    CALL("Call someone", R.drawable.ic_phone_call, Needs.CONTACT),
    FLASHLIGHT("Flashlight on or off", R.drawable.ic_flashlight),
    APP_LIST("App list", R.drawable.ic_app_list),
    NEXT_PAGE("Next home page", R.drawable.ic_chevron_right),
    PREVIOUS_PAGE("Previous home page", R.drawable.ic_chevron_left),
    SEARCH("Smart Search", R.drawable.ic_search),
    SWITCH_KEYBOARD("Switch keyboard", R.drawable.ic_keyboard),
    NOTIFICATIONS("Notifications", R.drawable.ic_notification),
    QUICK_SETTINGS("Quick settings", R.drawable.ic_settings),
    RECENTS("Recent apps", R.drawable.ic_history),
    RING_MODE("Ring or vibrate", R.drawable.ic_vibration),
    PLAY_PAUSE("Play or pause", R.drawable.ic_play),
    NEXT_TRACK("Next track", R.drawable.ic_next),
    LOCK("Lock the screen", R.drawable.ic_lock),
    SCREENSHOT("Take a screenshot", R.drawable.ic_screenshot),
    ;

    enum class Needs { NOTHING, APP, CONTACT }
}

/** An [ActionType] and what was picked for it: an app's key, or a phone number and name. */
data class KeyAction(val type: ActionType, val value: String? = null, val name: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("type", type.name).put("value", value).put("name", name)

    companion object {
        fun fromJson(o: JSONObject): KeyAction? = runCatching {
            KeyAction(ActionType.valueOf(o.getString("type")), o.optString("value").ifEmpty { null }, o.optString("name").ifEmpty { null })
        }.getOrNull()
    }
}

/** Where a shortcut works. A more specific one wins: one app over the home screen over all apps. */
enum class Scope(val label: String) { ALL("All apps"), HOME("Home screen"), APP("One app") }

/**
 * One shortcut: [press] on [keyCode] does [action] in the [scope] (with [app], a package name, for one app).
 * With [keyCode2] it's two keys pressed together instead, and [press] doesn't matter.
 */
data class KeyShortcut(
    val keyCode: Int,
    val press: Press,
    val action: KeyAction,
    val scope: Scope = Scope.ALL,
    val keyCode2: Int? = null,
    val app: String? = null,
) {
    val isCombo get() = keyCode2 != null

    /** Whether it works with [pkg] in front ([atHome]: the home screen itself). */
    fun appliesTo(pkg: String?, atHome: Boolean) = when (scope) {
        Scope.ALL -> true
        Scope.HOME -> atHome
        Scope.APP -> pkg != null && pkg == app
    }

    fun toJson(): JSONObject = JSONObject()
        .put("key", keyCode).put("key2", keyCode2 ?: 0).put("press", press.name).put("action", action.toJson())
        .put("scope", scope.name).put("app", app)

    companion object {
        fun fromJson(o: JSONObject): KeyShortcut? = runCatching {
            KeyShortcut(o.getInt("key"), Press.valueOf(o.getString("press")),
                KeyAction.fromJson(o.getJSONObject("action"))!!,
                runCatching { Scope.valueOf(o.getString("scope")) }.getOrDefault(Scope.ALL),
                o.optInt("key2").takeIf { it != 0 }, o.optString("app").ifEmpty { null })
        }.getOrNull()
    }
}

/** The saved shortcuts, shared by the settings page and the accessibility service. */
class KeyShortcuts private constructor(context: Context) {
    private val sp = context.getSharedPreferences("keys", Context.MODE_PRIVATE)

    var list: List<KeyShortcut> = read()
        private set

    private fun read(): List<KeyShortcut> {
        if (!sp.contains("shortcuts")) return DEFAULTS.also(::save).also { sp.edit().putInt("defaults", DEFAULTS_VERSION).apply() }
        val array = runCatching { JSONArray(sp.getString("shortcuts", "[]")) }.getOrDefault(JSONArray())
        val saved = (0 until array.length()).mapNotNull { KeyShortcut.fromJson(array.getJSONObject(it)) }
        if (sp.getInt("defaults", 1) >= DEFAULTS_VERSION) return saved
        // New ready-made ones, unless the same key and press already does something else.
        val added = saved + DEFAULTS.filter { d -> saved.none { sameTrigger(it, d) } && d !in saved }
        sp.edit().putInt("defaults", DEFAULTS_VERSION).apply()
        return added.also(::save)
    }

    private fun save(next: List<KeyShortcut>) {
        list = next
        sp.edit().putString("shortcuts", JSONArray(next.map { it.toJson() }).toString()).apply()
    }

    /** Adds or replaces [shortcut]; [replacing] is the one being edited, if any. A key and press have one action. */
    fun put(shortcut: KeyShortcut, replacing: KeyShortcut? = null) =
        save(list.filter { it != replacing && !sameTrigger(it, shortcut) } + shortcut)

    fun remove(shortcut: KeyShortcut) = save(list - shortcut)

    /** Adds shortcuts from a setup file, replacing any set off the same way. */
    fun import(shortcuts: List<KeyShortcut>) = shortcuts.forEach { put(it) }

    /**
     * Shortcuts for [keyCode] on its own (not combinations) that work with [pkg] in front,
     * one per kind of press, the most specific winning.
     */
    fun forKey(keyCode: Int, pkg: String?, atHome: Boolean): List<KeyShortcut> =
        list.filter { it.keyCode == keyCode && !it.isCombo && it.appliesTo(pkg, atHome) }
            .sortedByDescending { it.scope.ordinal }
            .distinctBy { it.press }

    /** The combination of [a] and [b], in either order, that works with [pkg] in front. */
    fun combo(a: Int, b: Int, pkg: String?, atHome: Boolean): KeyShortcut? =
        list.filter { it.isCombo && setOf(it.keyCode, it.keyCode2) == setOf(a, b) && it.appliesTo(pkg, atHome) }
            .maxByOrNull { it.scope.ordinal }

    /** Whether [shortcut] and [other] are set off the same way in the same place, so only one can stay. */
    fun sameTrigger(shortcut: KeyShortcut, other: KeyShortcut): Boolean {
        if (shortcut.scope != other.scope || shortcut.app != other.app) return false
        return if (shortcut.isCombo || other.isCombo) setOf(shortcut.keyCode, shortcut.keyCode2) == setOf(other.keyCode, other.keyCode2)
        else shortcut.keyCode == other.keyCode && shortcut.press == other.press
    }

    companion object {
        @Volatile private var instance: KeyShortcuts? = null

        fun get(context: Context): KeyShortcuts =
            instance ?: synchronized(this) { instance ?: KeyShortcuts(context.applicationContext).also { instance = it } }

        /**
         * Ready-made shortcuts: arrows held on the home screen open search (up) and the app list
         * (down), and flip pages (left, right). Changed or deleted like any other.
         */
        private val DEFAULTS = listOf(
            KeyShortcut(KeyEvent.KEYCODE_DPAD_DOWN, Press.LONG, KeyAction(ActionType.APP_LIST), scope = Scope.HOME),
            KeyShortcut(KeyEvent.KEYCODE_DPAD_UP, Press.LONG, KeyAction(ActionType.SEARCH), scope = Scope.HOME),
            KeyShortcut(KeyEvent.KEYCODE_DPAD_RIGHT, Press.LONG, KeyAction(ActionType.NEXT_PAGE), scope = Scope.HOME),
            KeyShortcut(KeyEvent.KEYCODE_DPAD_LEFT, Press.LONG, KeyAction(ActionType.PREVIOUS_PAGE), scope = Scope.HOME),
        )

        /** Bumped when ready-made shortcuts are added, so existing setups get the new ones too. */
        private const val DEFAULTS_VERSION = 2

        /** Keys a shortcut can't use: numbers have their own section, and Power must keep working. */
        fun canUse(keyCode: Int) = keyCode !in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 &&
            keyCode != KeyEvent.KEYCODE_POWER && keyCode != KeyEvent.KEYCODE_ENDCALL && keyCode != KeyEvent.KEYCODE_UNKNOWN

        /**
         * Keys whose normal press can be done for them when it isn't a shortcut, so holding or
         * double pressing them can be a shortcut in any app without losing the normal press.
         */
        fun canRepeatNormalPress(keyCode: Int) = keyCode in setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
        )

        /** Keys that type a character, which can still be typed into a text box after being held back. */
        fun typesInTextBoxes(keyCode: Int) = keyCode == KeyEvent.KEYCODE_STAR || keyCode == KeyEvent.KEYCODE_POUND

        /** "Star (*)", "Volume up", "Call": what the key is called on the settings page. */
        fun keyName(keyCode: Int): String = when (keyCode) {
            KeyEvent.KEYCODE_STAR -> "Star (*)"
            KeyEvent.KEYCODE_POUND -> "Pound (#)"
            KeyEvent.KEYCODE_CALL -> "Call"
            KeyEvent.KEYCODE_MENU -> "Menu"
            KeyEvent.KEYCODE_BACK -> "Back"
            KeyEvent.KEYCODE_HOME -> "Home"
            KeyEvent.KEYCODE_DPAD_CENTER -> "OK"
            KeyEvent.KEYCODE_DPAD_UP -> "Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
            KeyEvent.KEYCODE_VOLUME_UP -> "Volume up"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume down"
            KeyEvent.KEYCODE_CAMERA -> "Camera"
            KeyEvent.KEYCODE_SOFT_LEFT -> "Left soft key"
            KeyEvent.KEYCODE_SOFT_RIGHT -> "Right soft key"
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").lowercase()
                .split('_').joinToString(" ").replaceFirstChar { it.uppercase() }
        }

        /** "Hold Star (*)", or "Up + Down" for keys pressed together. */
        fun describe(shortcut: KeyShortcut) =
            if (shortcut.keyCode2 != null) "${keyName(shortcut.keyCode)} + ${keyName(shortcut.keyCode2)}"
            else "${shortcut.press.verb} ${keyName(shortcut.keyCode)}"

        /** "Open Chrome", "Call Mom", "Flashlight on or off". */
        fun describe(action: KeyAction, appLabel: (String) -> String?): String = when (action.type) {
            ActionType.APP -> action.value?.let(appLabel)?.let { "Open $it" } ?: "Open an app"
            ActionType.CALL -> action.name?.let { "Call $it" } ?: action.value?.let { "Call $it" } ?: "Call someone"
            else -> action.type.label
        }
    }
}
