package app.featherlauncher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process

/**
 * App shortcuts (a contact app's "New contact", a phone app's favorites).
 * Android only shares these with the phone's default home app.
 */
class Shortcuts(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    fun menuItems(entry: AppEntry): List<MenuItem> {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(entry.packageName)
            .setActivity(entry.component)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        val shortcuts = runCatching { launcherApps.getShortcuts(query, Process.myUserHandle()) }.getOrNull().orEmpty()
        return shortcuts
            .filter { it.isEnabled }
            .sortedWith(compareBy<ShortcutInfo>({ !it.isDeclaredInManifest }, { it.rank }))
            .take(MAX)
            .map { info ->
                val icon = runCatching {
                    launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
                }.getOrNull()
                MenuItem((info.shortLabel ?: info.longLabel ?: "").toString(), 0, drawable = icon) {
                    runCatching { launcherApps.startShortcut(info, null, null) }
                }
            }
    }

    companion object {
        private const val MAX = 4
    }
}
