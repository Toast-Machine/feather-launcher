package app.featherlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * Keeps locked apps quiet. When an app's open time runs out, or a limit that covers it
 * starts, its audio is stopped (not paused); if it starts again from earbuds or its
 * notification, it's stopped again straight away. Needs notification access to see media.
 */
class MediaGuard(private val context: Context) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, NotificationService::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val limits = AppLimits.get(context)
    private val watched = HashMap<MediaController, MediaController.Callback>()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }
    private val recheck = Runnable { enforceAll() }
    private val opened = { main.post { schedule() }; Unit }

    fun start() {
        runCatching { sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, main) }
        runCatching { watch(sessions.getActiveSessions(listener)) }
        limits.listeners += opened
        schedule()
    }

    fun stop() {
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        watched.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watched.clear()
        limits.listeners -= opened
        main.removeCallbacks(recheck)
    }

    private fun watch(controllers: List<MediaController>) {
        watched.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watched.clear()
        controllers.forEach { c ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = enforce(c)
            }
            c.registerCallback(callback, main)
            watched[c] = callback
            enforce(c)
        }
    }

    private fun enforceAll() {
        watched.keys.toList().forEach(::enforce)
        schedule()
    }

    /** Stops [c] if it's playing for an app that's locked right now. */
    private fun enforce(c: MediaController) {
        val state = c.playbackState?.state ?: return
        val playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_CONNECTING
        if (!playing || !limits.isLocked(c.packageName)) return
        // Stop where the app allows it (that ends the session); some apps (Chrome) only pause.
        val canStop = (c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_STOP != 0L
        if (canStop) c.transportControls.stop() else c.transportControls.pause()
        takeAudioFocus()
        tell(c.packageName)
    }

    /**
     * Takes audio focus for good and gives it straight back: players treat that as another app
     * starting, so they stop and don't resume on their own.
     */
    private fun takeAudioFocus() {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .build()
        audio.requestAudioFocus(request)
        audio.abandonAudioFocusRequest(request)
    }

    /** Looks again when a limit starts or an app's open time ends. */
    private fun schedule() {
        main.removeCallbacks(recheck)
        val now = System.currentTimeMillis()
        limits.nextChange(now)?.let { main.postDelayed(recheck, it - now + 500) }
    }

    /** A notification saying why the audio stopped, which opens the unlock screen. */
    private fun tell(pkg: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App limits", NotificationManager.IMPORTANCE_LOW))
        val label = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("This app")
        val limit = limits.lockFor(pkg) ?: return
        val until = limit.takeIf { !it.allDay }?.endsAt(System.currentTimeMillis())?.let { " until ${AppLimits.formatTime(context, it)}" } ?: ""
        val open = PendingIntent.getActivity(context, pkg.hashCode(), LimitActivity.intent(context, pkg),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(pkg.hashCode(), Notification.Builder(context, CHANNEL)
            .setSmallIcon(limit.strictness.icon)
            .setContentTitle("$label is locked$until")
            .setContentText(if (limit.strictness == Strictness.WAIT) "${limit.name} stopped its sound. Tap to open it for a few minutes."
                else "${limit.name} stopped its sound.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    companion object {
        private const val CHANNEL = "app_limits"
    }
}
