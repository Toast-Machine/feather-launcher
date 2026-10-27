package app.featherlauncher

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Sees every notification as it arrives. Ones a filter matches are dismissed and kept in the
 * history; ones from apps locked by Sleep time are held back until it ends. What's left is
 * counted for the badges on app icons.
 */
class NotificationService : NotificationListenerService() {
    private var media: MediaGuard? = null

    override fun onListenerConnected() {
        media = MediaGuard(this).also { it.start() }
        // Ones already showing when access was given (or the service restarted) are filtered too.
        runCatching { activeNotifications }.getOrNull()?.forEach(::filter)
        recount()
    }

    override fun onListenerDisconnected() {
        media?.stop()
        media = null
        NotificationBadges.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        filter(sbn)
        recount()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = recount()

    // Someone turned an app's notification dots on or off.
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) = recount()

    private fun recount() = NotificationBadges.update(this, runCatching { activeNotifications }.getOrNull())

    private fun filter(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == packageName || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()

        val sleep = SleepTime.get(this)
        val filters = NotificationFilters.get(this)
        val now = System.currentTimeMillis()

        // Calls, music and other ongoing ones are left to MediaGuard during Sleep time.
        if (!sbn.isOngoing && filters.holdDuringSleep && sleep.isLocked(sbn.packageName, now)) {
            val until = sleep.nightEnd(now) ?: return
            // Snoozed, not dismissed: it comes back by itself when Sleep time ends.
            snoozeNotification(sbn.key, until - now)
            filters.remember(HiddenNotification(sbn.packageName, title, text, now, "Held until ${SleepTime.formatTime(this, until)}"))
            return
        }

        val night = sleep.isNight(now)
        val filter = filters.filters.firstOrNull { it.hides(sbn.packageName, "$title\n$text", night) } ?: return
        if (sbn.isClearable) {
            cancelNotification(sbn.key)
        } else {
            // Ones that can't be dismissed (an app's permanent notification) are snoozed instead;
            // when one comes back, it's filtered again.
            snoozeNotification(sbn.key, PERMANENT_SNOOZE)
        }
        // A permanent notification coming back every few hours would fill the history.
        if (filters.history.firstOrNull()?.let { it.app == sbn.packageName && it.title == title && it.text == text } != true) {
            filters.remember(HiddenNotification(sbn.packageName, title, text, now, describe(filter)))
        }
    }

    private fun describe(filter: NotificationFilter) = when {
        filter.words.isEmpty() -> "All from this app"
        filter.allowOnly -> "Didn't mention ${filter.words.joinToString(", ")}"
        else -> "Mentioned ${filter.words.joinToString(", ")}"
    }

    companion object {
        private const val PERMANENT_SNOOZE = 5 * 60 * 60 * 1000L

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, NotificationService::class.java)
            return context.getSystemService(android.app.NotificationManager::class.java).isNotificationListenerAccessGranted(me)
        }
    }
}
