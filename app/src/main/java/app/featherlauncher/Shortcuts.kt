package app.featherlauncher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Process

/**
 * App shortcuts (a contact app's "New contact", a phone app's favorites): the app's own actions,
 * in its menu and for key shortcuts. Android only shares these with the phone's default home app.
 */
class Shortcuts(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    fun menuItems(entry: AppEntry): List<MenuItem> = list(entry).take(MAX).map { info ->
        MenuItem((info.shortLabel ?: info.longLabel ?: "").toString(), 0, drawable = icon(info)) {
            runCatching { launcherApps.startShortcut(info, null, null) }
        }
    }

    /** All of [entry]'s actions, the ones it always has first. */
    fun list(entry: AppEntry): List<ShortcutInfo> {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(entry.packageName)
            .setActivity(entry.component)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        val shortcuts = runCatching { launcherApps.getShortcuts(query, Process.myUserHandle()) }.getOrNull().orEmpty()
        return shortcuts
            .filter { it.isEnabled }
            .sortedWith(compareBy<ShortcutInfo>({ !it.isDeclaredInManifest }, { it.rank }))
    }

    fun icon(info: ShortcutInfo): Drawable? = runCatching {
        launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
    }.getOrNull()

    /** Opens the action [id] of the app [packageName]; false if it's gone or Android refuses. */
    fun start(packageName: String, id: String): Boolean = runCatching {
        launcherApps.startShortcut(packageName, id, null, null, Process.myUserHandle())
    }.isSuccess

    companion object {
        private const val MAX = 4
    }
}
