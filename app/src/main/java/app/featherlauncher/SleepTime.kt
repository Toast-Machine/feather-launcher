package app.featherlauncher

import android.content.Context
import java.util.Calendar

/**
 * Sleep time: during a nightly window (say 1:00 to 6:00 AM) the chosen apps are locked.
 * Opening one shows [SleepActivity] instead, where a short wait opens it for a few minutes,
 * after which it locks again and can't be reopened until a cooldown has passed.
 *
 * One shared instance ([get]) so the launcher, the settings page and the accessibility
 * service all see the same unlocks as soon as they happen.
 */
class SleepTime private constructor(context: Context) {
    private val sp = context.getSharedPreferences("sleep", Context.MODE_PRIVATE)
    private val state = context.getSharedPreferences("sleep_state", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(value) = sp.edit().putBoolean("enabled", value).apply()

    /** Minutes after midnight. The window can run past midnight (start later than end). */
    var start: Int
        get() = sp.getInt("start", 60)
        set(value) = sp.edit().putInt("start", value).apply()

    var end: Int
        get() = sp.getInt("end", 6 * 60)
        set(value) = sp.edit().putInt("end", value).apply()

    /** Nights it runs on, as [Calendar] days of the week, counted by the day the window starts. */
    var days: Set<Int>
        get() = sp.getStringSet("days", null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: ALL_DAYS
        set(value) = sp.edit().putStringSet("days", value.map { it.toString() }.toSet()).apply()

    /** Package names of the apps it locks. */
    var apps: Set<String>
        get() = sp.getStringSet("apps", emptySet())!!.toSet()
        set(value) = sp.edit().putStringSet("apps", value).apply()

    var waitSeconds: Int
        get() = sp.getInt("wait", 30)
        set(value) = sp.edit().putInt("wait", value).apply()

    var openMinutes: Int
        get() = sp.getInt("open", 5)
        set(value) = sp.edit().putInt("open", value).apply()

    /** Minutes after an app locks again before it can be opened again; 0 for none. */
    var cooldownMinutes: Int
        get() = sp.getInt("cooldown", 1)
        set(value) = sp.edit().putInt("cooldown", value).apply()

    /** Shown on the lock screen. */
    var message: String
        get() = sp.getString("message", null) ?: DEFAULT_MESSAGE
        set(value) = sp.edit().putString("message", value).apply()

    /** Whether the window is running at [now], ignoring which apps are chosen. */
    fun isNight(now: Long = System.currentTimeMillis()): Boolean = enabled && windowStart(now) != null

    fun isLocked(pkg: String, now: Long = System.currentTimeMillis()): Boolean =
        pkg in apps && isNight(now) && openUntil(pkg) <= now

    /** Until when [pkg] was opened from the lock screen (0 if never). */
    fun openUntil(pkg: String): Long = state.getLong("open_$pkg", 0)

    /** When [pkg] can be opened again from the lock screen. */
    fun readyAt(pkg: String): Long = openUntil(pkg) + cooldownMinutes * MINUTE

    fun open(pkg: String, now: Long = System.currentTimeMillis()) {
        state.edit().putLong("open_$pkg", now + openMinutes * MINUTE).apply()
        listeners.forEach { it() }
    }

    /** Called when an app is opened from the lock screen, so timers can be set for when it locks again. */
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** When the current window ends, or null outside it. */
    fun nightEnd(now: Long = System.currentTimeMillis()): Long? {
        val begin = windowStart(now) ?: return null
        return begin + length() * MINUTE
    }

    /**
     * The next moment anything about locking can change for [pkg] (null: any chosen app):
     * a window starting or ending, or an app's open time running out.
     */
    fun nextChange(now: Long = System.currentTimeMillis(), pkg: String? = null): Long? {
        if (!enabled) return null
        val times = ArrayList<Long>()
        nightEnd(now)?.let(times::add)
        nextStart(now)?.let(times::add)
        // One app's open time, or with no app given, every app's.
        (if (pkg != null) listOf(pkg) else apps).forEach { p -> openUntil(p).takeIf { it > now }?.let(times::add) }
        return times.minOrNull()
    }

    /** Start of the window [now] falls in, or null. */
    private fun windowStart(now: Long): Long? {
        val length = length()
        // The window that's running started today or, if it runs past midnight, yesterday.
        for (back in 0..1) {
            val begin = dayStart(now, -back) + start * MINUTE
            if (dayOf(begin) in days && now >= begin && now < begin + length * MINUTE) return begin
        }
        return null
    }

    private fun nextStart(now: Long): Long? =
        (0..7).map { dayStart(now, it) + start * MINUTE }.firstOrNull { it > now && dayOf(it) in days }

    private fun length(): Int = ((end - start) + 24 * 60) % (24 * 60)

    private fun dayStart(now: Long, plusDays: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, plusDays)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dayOf(time: Long) = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.DAY_OF_WEEK)

    companion object {
        const val MINUTE = 60_000L
        val ALL_DAYS = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()
        const val DEFAULT_MESSAGE = "It's late. Is this worth staying up for?"

        @Volatile private var instance: SleepTime? = null

        fun get(context: Context): SleepTime =
            instance ?: synchronized(this) { instance ?: SleepTime(context.applicationContext).also { instance = it } }

        /** "1:00 AM", in the phone's 12 or 24 hour style. */
        fun formatTime(context: Context, minutes: Int): String {
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, minutes / 60)
                set(Calendar.MINUTE, minutes % 60)
            }
            return android.text.format.DateFormat.getTimeFormat(context).format(c.time)
        }

        fun formatTime(context: Context, time: Long): String =
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(time))

        /** "Every day", "Weeknights", or the short day names. */
        fun formatDays(days: Set<Int>): String {
            val names = java.text.DateFormatSymbols.getInstance().shortWeekdays
            return when (days) {
                ALL_DAYS -> "Every day"
                emptySet<Int>() -> "Never"
                setOf(Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY) -> "Sun–Thu"
                else -> (Calendar.SUNDAY..Calendar.SATURDAY).filter { it in days }.joinToString(", ") { names[it] }
            }
        }

        fun formatDuration(seconds: Int): String = when {
            seconds == 0 -> "Off"
            seconds < 60 -> "$seconds sec"
            seconds % 60 == 0 -> "${seconds / 60} min"
            else -> "${seconds / 60} min ${seconds % 60} sec"
        }
    }
}
