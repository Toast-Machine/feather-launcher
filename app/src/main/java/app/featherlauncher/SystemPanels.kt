package app.featherlauncher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

/**
 * Opens the notification shade or quick settings.
 *
 * Some phones (Sonim's) cut the pull-down shade out of the system: asking Android to expand it,
 * by any route, quietly does nothing. They list notifications on a screen of their own instead,
 * which is what their own launcher opens. Their quick settings are a page inside that launcher,
 * out of reach of other apps, so quick settings opens Android's internet panel there.
 */
object SystemPanels {
    /** Sonim's notification list. */
    private val NOTIFICATION_LIST = ComponentName("com.android.systemui", "com.android.systemui.NotificationManagerActivity")

    fun notifications(context: Context, service: AccessibilityService?) {
        if (!hasShade(context) && start(context, Intent().setComponent(NOTIFICATION_LIST))) return
        if (service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) != true) expand(context, "expandNotificationsPanel")
    }

    fun quickSettings(context: Context, service: AccessibilityService?) {
        if (!hasShade(context) && start(context, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))) return
        if (service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) != true) expand(context, "expandSettingsPanel")
    }

    /** Phones with a notification list of their own have no shade to pull down. */
    private fun hasShade(context: Context) =
        context.packageManager.resolveActivity(Intent().setComponent(NOTIFICATION_LIST), 0)?.activityInfo?.exported != true

    @SuppressLint("WrongConstant")
    private fun expand(context: Context, method: String) {
        runCatching {
            val statusBar = context.getSystemService("statusbar")
            statusBar.javaClass.getMethod(method).invoke(statusBar)
        }
    }

    private fun start(context: Context, intent: Intent) = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Toast.makeText(context, "Nothing on this phone can open that", Toast.LENGTH_SHORT).show()
        false
    }
}
