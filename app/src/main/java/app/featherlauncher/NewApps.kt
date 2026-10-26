package app.featherlauncher

import android.content.Context

/**
 * Apps installed since the launcher last looked and not opened yet. They get a dot on their
 * icon and on their category's tab until they're opened, however that happens.
 */
class NewApps private constructor(context: Context) {
    private val sp = context.getSharedPreferences("new_apps", Context.MODE_PRIVATE)

    /** Package names. */
    var packages: Set<String> = sp.getStringSet("new", emptySet())!!.toSet()
        private set

    fun isNew(entry: AppEntry) = entry.packageName in packages

    /** Compares the installed apps with the last list seen; ones not on it are new. */
    fun update(apps: List<AppEntry>) {
        if (apps.isEmpty()) return
        val installed = apps.map { it.packageName }.toSet()
        val known = sp.getStringSet("known", null)
        // The very first time, everything already on the phone counts as seen.
        val added = if (known == null) emptySet() else installed - known
        val next = (packages + added).intersect(installed)
        if (next == packages && known == installed) return
        packages = next
        sp.edit().putStringSet("known", installed).putStringSet("new", next).apply()
    }

    fun opened(pkg: String) {
        if (pkg !in packages) return
        packages = packages - pkg
        sp.edit().putStringSet("new", packages).apply()
    }

    companion object {
        @Volatile private var instance: NewApps? = null

        fun get(context: Context): NewApps =
            instance ?: synchronized(this) { instance ?: NewApps(context.applicationContext).also { instance = it } }
    }
}
